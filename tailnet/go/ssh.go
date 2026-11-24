package libtailnet

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/x509"
	"encoding/asn1"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/ssh"
)

// SSHLogin says who to sign in as, how, and what Rax knows about the server.
// Exactly one of Password, PrivateKey and PhoneKey is set.
type SSHLogin struct {
	User     string
	Password string
	// PrivateKey is a private key in PEM or OpenSSH form, and Passphrase the
	// passphrase that protects it, if any.
	PrivateKey string
	Passphrase string
	// PhoneKey is the public half of the phone's own key, in PKIX DER form.
	// The SSHSigner passed with it signs with the private half.
	PhoneKey []byte
	// HostKey is the server's host key in authorized_keys form from an
	// earlier connection. Empty means the server is new, and the key it shows
	// is accepted.
	HostKey string
	// Socket is the path of Rex's socket on the server. Empty means asking Rex
	// on the server, as rex -S ssh://... does.
	Socket string
}

// SSHSigner is implemented in Kotlin with the phone's key in Android's
// keystore, which never hands out the private key.
type SSHSigner interface {
	// Sign returns an ASN.1 DER ECDSA signature of data, hashed with SHA-256.
	Sign(data []byte) ([]byte, error)
}

// Error messages start with one of these, errTimeout or errUnreachable, so
// Kotlin can tell the user why.
const (
	sshErrAuth    = "auth: "
	sshErrHostKey = "hostkey: "
	sshErrNoRex   = "norex: "
	sshErrRex     = "rex: "
	sshErrSSH     = "ssh: "
)

const (
	// Like rex -S ssh://..., which runs ssh with ServerAliveInterval=15 and
	// ServerAliveCountMax=3.
	sshKeepaliveInterval = 15 * time.Second
	sshKeepaliveMissed   = 3
	// An SSH connection with no stream to Rex closes after this long, like
	// the ControlPersist=10 of rex -S ssh://...
	sshIdleClose = 10 * time.Second
)

// rexOnServer is a shell loop that runs the first rex it finds on the server
// as "$rex" ARGS. rex -S ssh://... looks on the PATH and in ~/.local/bin.
// The path inside Rex.app is for a Mac, whose rex is on the PATH only in
// shells that Rex opens, and not in the shell an SSH command runs in.
func rexOnServer(args string) string {
	return `for rex in rex ~/.local/bin/rex "$HOME/Library/Application Support/com.superlogical.rex/RexServer/Versions/Current/Rex Server.app/Contents/MacOS/rex"; do
  if command -v "$rex" >/dev/null 2>&1; then ` + args + `
  fi
done
exit 127`
}

// rexStatusCommand starts Rex on the server unless REX_AUTOSTART turns that
// off there, and prints its status as JSON, as rex -S ssh://... does.
var rexStatusCommand = shellCommand(rexOnServer(
	`case "${REX_AUTOSTART-}" in ""|1|t|T|true|TRUE|True) exec "$rex" server start --json;; *) exec "$rex" server status --json;; esac`))

// shellCommand runs script with sh, whatever the user's login shell is.
func shellCommand(script string) string { return "sh -c " + shellQuote(script) }

func shellQuote(s string) string { return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'" }

// SSHConn is a signed-in SSH connection to a computer that runs Rex. Each
// stream to Rex is a channel of it.
type SSHConn struct {
	client  *ssh.Client
	hostKey string
	socket  string

	mu sync.Mutex
	// streams counts open streams to Rex. With none, idle closes the
	// connection after sshIdleClose.
	streams int
	idle    *time.Timer
	// stdio is set once the server refused to forward Rex's socket, so each
	// stream runs rex __internal dial-stdio instead.
	stdio bool

	closed    chan struct{}
	closeOnce sync.Once
}

// ConnectSSH connects to port on addrs and signs in. addrs is a name when
// viaTailnet is set, which dials through the node, and otherwise addresses
// separated by spaces, tried in order. Unless login.Socket is set, it then
// asks Rex on the server where its socket is.
func (h *DialHandle) ConnectSSH(addrs string, port int32, viaTailnet bool, timeoutMillis int32, login *SSHLogin, signer SSHSigner) (*SSHConn, error) {
	ctx, cancel := h.withTimeout(timeoutMillis)
	defer cancel()
	raw, err := sshDial(ctx, addrs, port, viaTailnet)
	if err != nil {
		return nil, err
	}
	auth, err := sshAuth(login, signer)
	if err != nil {
		raw.Close()
		return nil, err
	}
	c, err := sshHandshake(ctx, raw, login, auth)
	if err != nil {
		return nil, err
	}
	c.socket = login.Socket
	if c.socket == "" {
		if c.socket, err = c.findSocket(ctx); err != nil {
			c.Close()
			return nil, err
		}
	}
	go c.keepalive()
	c.mu.Lock()
	c.startIdle()
	c.mu.Unlock()
	return c, nil
}

func sshAuth(login *SSHLogin, signer SSHSigner) ([]ssh.AuthMethod, error) {
	switch {
	case login.Password != "":
		pw := login.Password
		// Many servers, macOS among them, ask for the password through
		// keyboard-interactive instead of the password method.
		return []ssh.AuthMethod{
			ssh.Password(pw),
			ssh.KeyboardInteractive(func(_, _ string, questions []string, echos []bool) ([]string, error) {
				return passwordAnswers(pw, questions, echos)
			}),
		}, nil
	case login.PrivateKey != "":
		s, err := parsePrivateKey(login.PrivateKey, login.Passphrase)
		if err != nil {
			return nil, errors.New(sshErrAuth + err.Error())
		}
		return []ssh.AuthMethod{ssh.PublicKeys(s)}, nil
	case len(login.PhoneKey) > 0:
		if signer == nil {
			return nil, errors.New(sshErrAuth + "no signer for the phone's key")
		}
		pub, err := phonePublicKey(login.PhoneKey)
		if err != nil {
			return nil, errors.New(sshErrAuth + err.Error())
		}
		return []ssh.AuthMethod{ssh.PublicKeys(&phoneSigner{pub: pub, sign: signer})}, nil
	}
	return nil, errors.New(sshErrAuth + "no way to sign in")
}

// passwordAnswers answers a keyboard-interactive round that asks only for
// hidden input, such as "Password:", with the password. An empty round, which
// some servers send before the real one, gets no answers.
func passwordAnswers(password string, questions []string, echos []bool) ([]string, error) {
	answers := make([]string, len(questions))
	for i := range questions {
		if echos[i] {
			return nil, fmt.Errorf("the server asked %q, and Rax can answer only a password", questions[i])
		}
		answers[i] = password
	}
	return answers, nil
}

var (
	errNeedsPassphrase = errors.New("the key needs its passphrase")
	errWrongPassphrase = errors.New("the passphrase is wrong")
)

func parsePrivateKey(text, passphrase string) (ssh.Signer, error) {
	s, err := ssh.ParsePrivateKey([]byte(text))
	var missing *ssh.PassphraseMissingError
	if errors.As(err, &missing) {
		if passphrase == "" {
			return nil, errNeedsPassphrase
		}
		s, err = ssh.ParsePrivateKeyWithPassphrase([]byte(text), []byte(passphrase))
		if errors.Is(err, x509.IncorrectPasswordError) {
			return nil, errWrongPassphrase
		}
	}
	if err != nil {
		return nil, fmt.Errorf("the key cannot be read: %w", err)
	}
	return s, nil
}

// CheckPrivateKey returns "" when text is a private key that passphrase
// opens, "passphrase" when the key needs a passphrase and none was given,
// "wrong_passphrase", or "invalid".
func CheckPrivateKey(text, passphrase string) string {
	_, err := parsePrivateKey(text, passphrase)
	switch {
	case err == nil:
		return ""
	case errors.Is(err, errNeedsPassphrase):
		return "passphrase"
	case errors.Is(err, errWrongPassphrase):
		return "wrong_passphrase"
	}
	return "invalid"
}

func phonePublicKey(pkix []byte) (ssh.PublicKey, error) {
	key, err := x509.ParsePKIXPublicKey(pkix)
	if err != nil {
		return nil, fmt.Errorf("the phone's key cannot be read: %w", err)
	}
	ec, ok := key.(*ecdsa.PublicKey)
	if !ok {
		return nil, fmt.Errorf("the phone's key is a %T, not an ECDSA key", key)
	}
	return ssh.NewPublicKey(ec)
}

// PhoneKeyLine returns the line for ~/.ssh/authorized_keys that lets in
// the phone's key, given in PKIX DER form.
func PhoneKeyLine(pkix []byte, comment string) (string, error) {
	pub, err := phonePublicKey(pkix)
	if err != nil {
		return "", err
	}
	line := authorizedKey(pub)
	if comment != "" {
		line += " " + comment
	}
	return line, nil
}

// phoneSigner signs through Kotlin with a P-256 key in Android's keystore.
type phoneSigner struct {
	pub  ssh.PublicKey
	sign SSHSigner
}

func (s *phoneSigner) PublicKey() ssh.PublicKey { return s.pub }

func (s *phoneSigner) Sign(_ io.Reader, data []byte) (*ssh.Signature, error) {
	der, err := s.sign.Sign(data)
	if err != nil {
		return nil, err
	}
	var sig struct{ R, S *big.Int }
	if rest, err := asn1.Unmarshal(der, &sig); err != nil || len(rest) > 0 {
		return nil, errors.New("the keystore returned a signature that cannot be read")
	}
	// An SSH ECDSA signature is the two numbers as mpints (RFC 5656 3.1.2).
	return &ssh.Signature{Format: s.pub.Type(), Blob: ssh.Marshal(sig)}, nil
}

func sshDial(ctx context.Context, addrs string, port int32, viaTailnet bool) (net.Conn, error) {
	if viaTailnet {
		return dialNode(ctx, addrs, port)
	}
	var d net.Dialer
	err := errors.New(errUnreachable + "no address")
	for _, a := range strings.Fields(addrs) {
		addr := net.JoinHostPort(a, strconv.Itoa(int(port)))
		conn, derr := d.DialContext(ctx, "tcp", addr)
		if derr == nil {
			return conn, nil
		}
		err = connectError(addr, derr)
	}
	return nil, err
}

func sshHandshake(ctx context.Context, raw net.Conn, login *SSHLogin, auth []ssh.AuthMethod) (*SSHConn, error) {
	// A saved key that cannot be read stays nil, and counts as changed.
	var known ssh.PublicKey
	if login.HostKey != "" {
		known, _, _, _, _ = ssh.ParseAuthorizedKey([]byte(login.HostKey))
	}
	var seen string
	changed := false
	config := &ssh.ClientConfig{
		User: login.User,
		Auth: auth,
		HostKeyCallback: func(_ string, _ net.Addr, key ssh.PublicKey) error {
			seen = authorizedKey(key)
			if login.HostKey != "" && (known == nil || !bytes.Equal(known.Marshal(), key.Marshal())) {
				changed = true
				return errors.New("the host key changed")
			}
			return nil
		},
	}
	if known != nil {
		// Ask for a key of the type Rax saved, so a server with several host
		// keys shows the same one each time.
		config.HostKeyAlgorithms = hostKeyAlgorithms(known.Type())
	}

	// The handshake has no context, so a deadline and a watcher bound it.
	if dl, ok := ctx.Deadline(); ok {
		raw.SetDeadline(dl)
	}
	stop := context.AfterFunc(ctx, func() { raw.Close() })
	sc, chans, reqs, err := ssh.NewClientConn(raw, "", config)
	stopped := stop()
	if err == nil && !stopped {
		sc.Close()
		err = ctx.Err()
	}
	if err != nil {
		raw.Close()
		switch {
		case changed:
			return nil, errors.New(sshErrHostKey + seen)
		case strings.Contains(err.Error(), "unable to authenticate"):
			return nil, errors.New(sshErrAuth + err.Error())
		case ctx.Err() != nil || isTimeout(err):
			return nil, errors.New(errTimeout + err.Error())
		}
		return nil, errors.New(sshErrSSH + err.Error())
	}
	raw.SetDeadline(time.Time{})
	return &SSHConn{
		client:  ssh.NewClient(sc, chans, reqs),
		hostKey: seen,
		closed:  make(chan struct{}),
	}, nil
}

func authorizedKey(key ssh.PublicKey) string {
	return strings.TrimSpace(string(ssh.MarshalAuthorizedKey(key)))
}

// hostKeyAlgorithms lists the signature algorithms of a host key type.
func hostKeyAlgorithms(keyType string) []string {
	if keyType == ssh.KeyAlgoRSA {
		return []string{ssh.KeyAlgoRSASHA512, ssh.KeyAlgoRSASHA256, ssh.KeyAlgoRSA}
	}
	return []string{keyType}
}

// findSocket runs Rex on the server, which starts it if needed, and returns
// the path of its socket from the status it prints.
func (c *SSHConn) findSocket(ctx context.Context) (string, error) {
	s, err := c.client.NewSession()
	if err != nil {
		return "", errors.New(sshErrSSH + err.Error())
	}
	defer s.Close()
	var out, errOut bytes.Buffer
	s.Stdout = &out
	s.Stderr = &errOut
	done := make(chan error, 1)
	go func() { done <- s.Run(rexStatusCommand) }()
	select {
	case err = <-done:
	case <-ctx.Done():
		return "", errors.New(errTimeout + "waiting for Rex on the server")
	}
	if err != nil {
		var exit *ssh.ExitError
		if errors.As(err, &exit) && exit.ExitStatus() == 127 {
			return "", errors.New(sshErrNoRex + "rex was not found on the server")
		}
		msg := strings.TrimSpace(errOut.String())
		if msg == "" {
			msg = err.Error()
		}
		return "", errors.New(sshErrRex + msg)
	}
	return socketFromStatus(out.Bytes())
}

// socketFromStatus reads the path of the server's socket from the JSON that
// rex server start --json and rex server status --json print.
func socketFromStatus(status []byte) (string, error) {
	var st struct {
		Endpoint string `json:"endpoint"`
	}
	if err := json.Unmarshal(status, &st); err != nil {
		return "", fmt.Errorf("%sthe server's status cannot be read: %w", sshErrRex, err)
	}
	u, err := url.Parse(st.Endpoint)
	if err != nil || u.Scheme != "unix" || u.Path == "" {
		return "", fmt.Errorf("%sthe server listens on %q, which is not a Unix socket", sshErrRex, st.Endpoint)
	}
	return u.Path, nil
}

// open opens a stream to Rex's socket on the server.
func (c *SSHConn) open(ctx context.Context) (net.Conn, error) {
	type result struct {
		conn net.Conn
		err  error
	}
	c.mu.Lock()
	stdio := c.stdio
	c.mu.Unlock()
	got := make(chan result, 1)
	go func() {
		if !stdio {
			conn, err := c.client.Dial("unix", c.socket)
			var refused *ssh.OpenChannelError
			if !errors.As(err, &refused) {
				got <- result{conn, err}
				return
			}
			// OpenSSH refuses a forward that its settings forbid the same way as
			// one to a socket that is gone. rex -S ssh://... then runs
			// rex __internal dial-stdio on the server for each connection.
			c.mu.Lock()
			c.stdio = true
			c.mu.Unlock()
		}
		conn, err := c.dialStdio()
		got <- result{conn, err}
	}()
	select {
	case r := <-got:
		if r.err != nil {
			return nil, errors.New(sshErrSSH + r.err.Error())
		}
		c.mu.Lock()
		c.streams++
		if c.idle != nil {
			c.idle.Stop()
			c.idle = nil
		}
		c.mu.Unlock()
		return &stream{Conn: r.conn, onClose: c.streamClosed}, nil
	case <-ctx.Done():
		// A channel that does not open in time is on a connection that went
		// away without a word, so it ends, and the next stream signs in again.
		c.Close()
		go func() {
			if r := <-got; r.conn != nil {
				r.conn.Close()
			}
		}()
		return nil, errors.New(errTimeout + "opening a stream to Rex")
	}
}

func (c *SSHConn) dialStdio() (net.Conn, error) {
	s, err := c.client.NewSession()
	if err != nil {
		return nil, err
	}
	stdin, err := s.StdinPipe()
	if err != nil {
		s.Close()
		return nil, err
	}
	stdout, err := s.StdoutPipe()
	if err != nil {
		s.Close()
		return nil, err
	}
	cmd := shellCommand(rexOnServer(`exec "$rex" -S ` + shellQuote("unix://"+(&url.URL{Path: c.socket}).EscapedPath()) + ` __internal dial-stdio`))
	if err := s.Start(cmd); err != nil {
		s.Close()
		return nil, err
	}
	return &stdioConn{s: s, stdin: stdin, stdout: stdout}, nil
}

func (c *SSHConn) streamClosed() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.streams--
	c.startIdle()
}

// startIdle closes the connection after sshIdleClose when no stream is open.
// c.mu must be held.
func (c *SSHConn) startIdle() {
	if c.streams > 0 || c.idle != nil {
		return
	}
	var t *time.Timer
	t = time.AfterFunc(sshIdleClose, func() {
		c.mu.Lock()
		idle := c.idle == t && c.streams == 0
		c.mu.Unlock()
		if idle {
			c.Close()
		}
	})
	c.idle = t
}

// keepalive closes the connection when the server stops answering.
func (c *SSHConn) keepalive() {
	t := time.NewTicker(sshKeepaliveInterval)
	defer t.Stop()
	missed := 0
	for {
		select {
		case <-c.closed:
			return
		case <-t.C:
		}
		answered := make(chan bool, 1)
		go func() {
			_, _, err := c.client.SendRequest("keepalive@openssh.com", true, nil)
			answered <- err == nil
		}()
		ok := false
		select {
		case ok = <-answered:
		case <-time.After(sshKeepaliveInterval):
		case <-c.closed:
			return
		}
		if ok {
			missed = 0
		} else if missed++; missed >= sshKeepaliveMissed {
			c.Close()
			return
		}
	}
}

// HostKey returns the key the server showed, in authorized_keys form.
func (c *SSHConn) HostKey() string { return c.hostKey }

// Closed reports whether the connection ended. A closed connection opens no
// more streams.
func (c *SSHConn) Closed() bool {
	select {
	case <-c.closed:
		return true
	default:
		return false
	}
}

// Close ends the connection and every stream on it.
func (c *SSHConn) Close() {
	c.closeOnce.Do(func() {
		close(c.closed)
		c.client.Close()
	})
}

// stream is one connection to Rex, which tells its SSHConn when it closes.
type stream struct {
	net.Conn
	once    sync.Once
	onClose func()
}

func (s *stream) Close() error {
	err := s.Conn.Close()
	s.once.Do(s.onClose)
	return err
}

func (s *stream) CloseWrite() error {
	if cw, ok := s.Conn.(interface{ CloseWrite() error }); ok {
		return cw.CloseWrite()
	}
	return errors.New("cannot close only the write side")
}

// stdioConn is a stream to Rex through the input and output of a command on
// the server.
type stdioConn struct {
	s      *ssh.Session
	stdin  io.WriteCloser
	stdout io.Reader
}

func (c *stdioConn) Read(b []byte) (int, error)  { return c.stdout.Read(b) }
func (c *stdioConn) Write(b []byte) (int, error) { return c.stdin.Write(b) }
func (c *stdioConn) CloseWrite() error           { return c.stdin.Close() }
func (c *stdioConn) Close() error                { return c.s.Close() }

func (c *stdioConn) LocalAddr() net.Addr              { return stdioAddr{} }
func (c *stdioConn) RemoteAddr() net.Addr             { return stdioAddr{} }
func (c *stdioConn) SetDeadline(time.Time) error      { return nil }
func (c *stdioConn) SetReadDeadline(time.Time) error  { return nil }
func (c *stdioConn) SetWriteDeadline(time.Time) error { return nil }

type stdioAddr struct{}

func (stdioAddr) Network() string { return "ssh" }
func (stdioAddr) String() string  { return "rex dial-stdio" }
