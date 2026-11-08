package libtailnet

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"slices"
	"strings"
	"sync"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/envknob"
	"tailscale.com/hostinfo"
	"tailscale.com/ipn"
	"tailscale.com/logtail"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
)

var errNotStarted = errors.New("the tailnet node is not started")

var (
	// lifecycle serializes Start, Stop, Login and Logout.
	lifecycle sync.Mutex

	// curMu guards cur. It is also held while a status is delivered to
	// Kotlin, so that no status from a node arrives after Stop returns.
	curMu sync.Mutex
	cur   *node
)

type node struct {
	srv      *tsnet.Server
	lc       *local.Client
	hostname string
	ctx      context.Context
	cancel   context.CancelFunc
	dirty    chan struct{}

	mu    sync.Mutex
	state ipn.State
	// errMsg is the last error from the backend. It is cleared when the
	// backend moves on: a new state, a login URL, or a finished login.
	errMsg string
	// loggingErr is set when the tailnet requires network flow logs. The
	// backend then stops for good, so it stays until the node runs again
	// or logs out.
	loggingErr string
	// loginRequested is set while an interactive login is in progress, so
	// that the bus watcher does not start a second one.
	loginRequested bool
	lastStatus     string
}

func current() *node {
	curMu.Lock()
	defer curMu.Unlock()
	return cur
}

// Start starts the node with its state in dir. It returns when the node is
// initialized; login and connection progress arrive through Host.OnStatus.
// Calling Start on a started node does nothing.
//
// Start sets TS_LOGS_DIR, HOME and TS_NO_LOGS_NO_SUPPORT. Adding a variable
// to the C environment can move it while other threads read it, so the
// caller must create these variables before the app starts other threads.
func Start(dir, hostname, deviceModel, osVersion string) error {
	lifecycle.Lock()
	defer lifecycle.Unlock()
	if host == nil {
		return errors.New("libtailnet.Init was not called")
	}
	if current() != nil {
		return nil
	}

	hostinfo.SetApp("rax")
	hostinfo.SetDeviceModel(deviceModel)
	hostinfo.SetOSVersion(osVersion)
	// Android apps have no HOME and cannot write to the default temp dir, so
	// Tailscale code that looks for a log or cache directory (such as
	// logpolicy.LogsDir, which panics if it finds none) must be pointed here.
	os.Setenv("TS_LOGS_DIR", dir)
	if os.Getenv("HOME") == "" {
		os.Setenv("HOME", dir)
	}
	// The node's logs stay on the phone; nothing is uploaded to Tailscale's log service.
	envknob.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")
	logtail.Disable()

	srv := &tsnet.Server{
		Dir:      dir,
		Hostname: hostname,
		Logf:     logVerbosef,
		UserLogf: logDebugf,
	}
	if err := srv.Start(); err != nil {
		srv.Close()
		return err
	}
	lc, err := srv.LocalClient()
	if err != nil {
		srv.Close()
		return err
	}
	ctx, cancel := context.WithCancel(context.Background())
	n := &node{
		srv:      srv,
		lc:       lc,
		hostname: hostname,
		ctx:      ctx,
		cancel:   cancel,
		dirty:    make(chan struct{}, 1),
		state:    ipn.NoState,
		// tsnet starts an interactive login itself if the node needs one.
		loginRequested: true,
	}
	curMu.Lock()
	cur = n
	curMu.Unlock()

	go n.watch()
	go n.publishLoop()
	logInfof("node started, hostname %q", hostname)
	return nil
}

// Stop shuts the node down. Its state stays on disk.
func Stop() {
	lifecycle.Lock()
	defer lifecycle.Unlock()
	curMu.Lock()
	n := cur
	cur = nil
	curMu.Unlock()
	if n == nil {
		return
	}
	n.cancel()
	n.srv.Close()
	logInfof("node stopped")
}

// Login requests a new login URL. It is needed only if the last URL expired
// or was not used; the node requests one by itself whenever it needs login.
func Login() error {
	lifecycle.Lock()
	defer lifecycle.Unlock()
	n := current()
	if n == nil {
		return errNotStarted
	}
	ctx, cancel := context.WithTimeout(n.ctx, 30*time.Second)
	defer cancel()
	return n.login(ctx)
}

// Logout logs the node out and deletes its keys, then requests a login URL
// for the next login. If the control server cannot be told, the node keeps
// its keys and runs on, and Logout returns the error.
func Logout() error {
	lifecycle.Lock()
	defer lifecycle.Unlock()
	n := current()
	if n == nil {
		return errNotStarted
	}
	ctx, cancel := context.WithTimeout(n.ctx, 30*time.Second)
	defer cancel()
	n.mu.Lock()
	n.loginRequested = true
	n.mu.Unlock()
	if err := n.lc.Logout(ctx); err != nil {
		n.resume()
		return err
	}
	n.mu.Lock()
	n.errMsg = ""
	n.loggingErr = ""
	n.mu.Unlock()
	return n.login(ctx)
}

// resume runs the node again with its keys after a failed logout. The
// backend has saved the prefs as logged out and stopped, but it deletes the
// keys only after the control server takes the logout. Only a new control
// client uses the saved keys again, so this starts the backend again.
func (n *node) resume() {
	ctx, cancel := context.WithTimeout(n.ctx, 30*time.Second)
	defer cancel()
	if err := n.lc.Start(ctx, ipn.Options{UpdatePrefs: n.prefs()}); err != nil {
		logWarnf("starting again after a failed logout: %v", err)
	}
	n.mu.Lock()
	n.loginRequested = false
	n.mu.Unlock()
}

func (n *node) login(ctx context.Context) error {
	n.mu.Lock()
	n.loginRequested = true
	n.mu.Unlock()
	// Logout deletes the login profile and its prefs, so set them again.
	if err := n.lc.Start(ctx, ipn.Options{UpdatePrefs: n.prefs()}); err != nil {
		return err
	}
	return n.lc.StartLoginInteractive(ctx)
}

// prefs returns the prefs the way tsnet sets them at start.
func (n *node) prefs() *ipn.Prefs {
	prefs := ipn.NewPrefs()
	prefs.Hostname = n.hostname
	prefs.WantRunning = true
	return prefs
}

func (n *node) running() bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.state == ipn.Running
}

func runningNode() (*node, error) {
	n := current()
	if n == nil {
		return nil, errNotStarted
	}
	if !n.running() {
		return nil, errors.New("the tailnet is not connected")
	}
	return n, nil
}

// watch follows the IPN bus for the node's lifetime.
func (n *node) watch() {
	for n.ctx.Err() == nil {
		w, err := n.lc.WatchIPNBus(n.ctx, ipn.NotifyInitialState|ipn.NotifyPeerPatches)
		if err != nil {
			if n.ctx.Err() == nil {
				logWarnf("watching the IPN bus: %v", err)
			}
			select {
			case <-n.ctx.Done():
			case <-time.After(time.Second):
			}
			continue
		}
		n.readBus(w)
		w.Close()
	}
}

func (n *node) readBus(w *local.IPNBusWatcher) {
	for {
		msg, err := w.Next()
		if err != nil {
			return
		}
		changed, startLogin := n.apply(&msg)
		if startLogin {
			go func() {
				ctx, cancel := context.WithTimeout(n.ctx, 30*time.Second)
				defer cancel()
				if err := n.lc.StartLoginInteractive(ctx); err != nil && n.ctx.Err() == nil {
					logWarnf("starting login: %v", err)
				}
			}()
		}
		if changed {
			select {
			case n.dirty <- struct{}{}:
			default:
			}
		}
	}
}

// busFellBehind starts the message that the IPN bus sends before it closes a
// watch whose reader fell behind. watch then watches again, so it is not an
// error of the node.
const busFellBehind = "IPN bus consumer fell behind"

// requiresLogging is in the message that the backend sends, before it stops,
// when the tailnet uses network flow logs. Those need the log upload that
// TS_NO_LOGS_NO_SUPPORT turns off.
const requiresLogging = "requires logging"

// apply records a bus message. It reports whether the status can have
// changed, and whether the node needs a login that nobody requested yet.
func (n *node) apply(msg *ipn.Notify) (changed, startLogin bool) {
	n.mu.Lock()
	defer n.mu.Unlock()
	stateChanged := msg.State != nil && *msg.State != n.state
	if stateChanged || msg.BrowseToURL != nil || msg.LoginFinished != nil {
		n.errMsg = ""
	}
	if msg.ErrMessage != nil && !strings.HasPrefix(*msg.ErrMessage, busFellBehind) {
		logWarnf("backend error: %s", *msg.ErrMessage)
		if strings.Contains(*msg.ErrMessage, requiresLogging) {
			n.loggingErr = *msg.ErrMessage
		} else {
			n.errMsg = *msg.ErrMessage
		}
	}
	if stateChanged {
		logInfof("state %v -> %v", n.state, *msg.State)
		n.state = *msg.State
		if n.state == ipn.Running {
			n.loggingErr = ""
			n.loginRequested = false
		}
	}
	// The node can drop back to NeedsLogin later, for example when its key
	// expires. Request a URL so the app can show it.
	if n.state == ipn.NeedsLogin && !n.loginRequested {
		n.loginRequested = true
		startLogin = true
	}
	return affectsStatus(msg), startLogin
}

// affectsStatus reports whether msg can change what buildStatus returns.
// Patches that only move a peer's endpoints, DERP home or last-seen time
// change nothing in it.
func affectsStatus(msg *ipn.Notify) bool {
	if msg.State != nil || msg.ErrMessage != nil || msg.BrowseToURL != nil || msg.LoginFinished != nil ||
		msg.SelfChange != nil || len(msg.PeersChanged) > 0 || len(msg.PeersRemoved) > 0 {
		return true
	}
	return slices.ContainsFunc(msg.PeerChangedPatch, func(p *tailcfg.PeerChange) bool { return p.Online != nil })
}

// publishLoop sends the status to Kotlin after each change, at most a few
// times per second.
func (n *node) publishLoop() {
	for {
		select {
		case <-n.ctx.Done():
			return
		case <-n.dirty:
		}
		select {
		case <-n.ctx.Done():
			return
		case <-time.After(250 * time.Millisecond):
		}
		select {
		case <-n.dirty:
		default:
		}
		n.publish()
	}
}

func (n *node) publish() {
	ctx, cancel := context.WithTimeout(n.ctx, 10*time.Second)
	defer cancel()
	st, err := n.lc.Status(ctx)
	if err != nil {
		if n.ctx.Err() == nil {
			logWarnf("reading status: %v", err)
		}
		return
	}
	n.mu.Lock()
	errMsg, loggingErr := n.errMsg, n.loggingErr
	n.mu.Unlock()
	b, err := json.Marshal(buildStatus(st, errMsg, loggingErr))
	if err != nil {
		logErrorf("encoding status: %v", err)
		return
	}

	curMu.Lock()
	defer curMu.Unlock()
	if cur != n || string(b) == n.lastStatus {
		return
	}
	n.lastStatus = string(b)
	host.OnStatus(string(b))
}
