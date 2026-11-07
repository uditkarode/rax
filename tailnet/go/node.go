package libtailnet

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"sync"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/envknob"
	"tailscale.com/hostinfo"
	"tailscale.com/ipn"
	"tailscale.com/logtail"
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
	// errMsg is the last critical error from the backend. It is cleared
	// when the node reaches Running.
	errMsg string
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
// for the next login.
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
		return err
	}
	return n.login(ctx)
}

func (n *node) login(ctx context.Context) error {
	n.mu.Lock()
	n.loginRequested = true
	n.mu.Unlock()
	// Logout deletes the login profile and its prefs, so set the prefs again
	// the way tsnet sets them at start.
	prefs := ipn.NewPrefs()
	prefs.Hostname = n.hostname
	prefs.WantRunning = true
	if err := n.lc.Start(ctx, ipn.Options{UpdatePrefs: prefs}); err != nil {
		return err
	}
	return n.lc.StartLoginInteractive(ctx)
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
		w, err := n.lc.WatchIPNBus(n.ctx, ipn.NotifyInitialState|ipn.NotifyPeerChanges)
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
		n.mu.Lock()
		if msg.ErrMessage != nil {
			n.errMsg = *msg.ErrMessage
			logWarnf("backend error: %s", n.errMsg)
		}
		if msg.State != nil && *msg.State != n.state {
			logInfof("state %v -> %v", n.state, *msg.State)
			n.state = *msg.State
			if n.state == ipn.Running {
				n.errMsg = ""
				n.loginRequested = false
			}
		}
		// The node can drop back to NeedsLogin later, for example when its
		// key expires. Request a URL so the app can show it.
		startLogin := n.state == ipn.NeedsLogin && !n.loginRequested
		if startLogin {
			n.loginRequested = true
		}
		n.mu.Unlock()

		if startLogin {
			go func() {
				ctx, cancel := context.WithTimeout(n.ctx, 30*time.Second)
				defer cancel()
				if err := n.lc.StartLoginInteractive(ctx); err != nil && n.ctx.Err() == nil {
					logWarnf("starting login: %v", err)
				}
			}()
		}
		select {
		case n.dirty <- struct{}{}:
		default:
		}
	}
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
	errMsg := n.errMsg
	n.mu.Unlock()
	b, err := json.Marshal(buildStatus(st, errMsg))
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
