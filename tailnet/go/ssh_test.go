package libtailnet

import (
	"bufio"
	"context"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"
)

func TestPasswordAnswersFillHiddenQuestions(t *testing.T) {
	got, err := passwordAnswers("pw", []string{"Password:"}, []bool{false})
	if err != nil || len(got) != 1 || got[0] != "pw" {
		t.Fatalf("got %q, %v", got, err)
	}
	if got, err := passwordAnswers("pw", nil, nil); err != nil || len(got) != 0 {
		t.Fatalf("empty round: got %q, %v", got, err)
	}
	if _, err := passwordAnswers("pw", []string{"Code:"}, []bool{true}); err == nil {
		t.Fatal("a question with echo was answered")
	}
}

func TestSocketFromStatus(t *testing.T) {
	path, err := socketFromStatus([]byte(`{"endpoint":"unix:///Users/me/Library/Application%20Support/rex/server.sock","pid":1}`))
	if err != nil || path != "/Users/me/Library/Application Support/rex/server.sock" {
		t.Fatalf("got %q, %v", path, err)
	}
	for _, bad := range []string{`{"endpoint":"npipe:////./pipe/rex"}`, `{}`, `not json`} {
		if _, err := socketFromStatus([]byte(bad)); err == nil || !strings.HasPrefix(err.Error(), sshErrRex) {
			t.Errorf("%s: got %v", bad, err)
		}
	}
}

func TestShellQuoteSurvivesQuotes(t *testing.T) {
	if got := shellQuote(`it's`); got != `'it'\''s'` {
		t.Fatalf("got %s", got)
	}
}

func TestCheckPrivateKey(t *testing.T) {
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	plain, err := ssh.MarshalPrivateKey(priv, "")
	if err != nil {
		t.Fatal(err)
	}
	locked, err := ssh.MarshalPrivateKeyWithPassphrase(priv, "", []byte("secret"))
	if err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		text, passphrase, want string
	}{
		{string(pem.EncodeToMemory(plain)), "", ""},
		{string(pem.EncodeToMemory(locked)), "", "passphrase"},
		{string(pem.EncodeToMemory(locked)), "wrong", "wrong_passphrase"},
		{string(pem.EncodeToMemory(locked)), "secret", ""},
		{"ssh-ed25519 AAAA not a private key", "", "invalid"},
	}
	for i, c := range cases {
		if got := CheckPrivateKey(c.text, c.passphrase); got != c.want {
			t.Errorf("case %d: got %q, want %q", i, got, c.want)
		}
	}
}

// keystoreSigner signs the way Android's keystore does with SHA256withECDSA.
type keystoreSigner struct{ key *ecdsa.PrivateKey }

func (s keystoreSigner) Sign(data []byte) ([]byte, error) {
	sum := sha256.Sum256(data)
	return ecdsa.SignASN1(rand.Reader, s.key, sum[:])
}

func newPhoneKey(t *testing.T) (*ecdsa.PrivateKey, []byte) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	pkix, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	return key, pkix
}

func TestPhoneSignerMakesSignaturesSSHAccepts(t *testing.T) {
	key, pkix := newPhoneKey(t)
	pub, err := phonePublicKey(pkix)
	if err != nil {
		t.Fatal(err)
	}
	s := &phoneSigner{pub: pub, sign: keystoreSigner{key}}
	data := []byte("session id and request")
	sig, err := s.Sign(rand.Reader, data)
	if err != nil {
		t.Fatal(err)
	}
	if err := pub.Verify(data, sig); err != nil {
		t.Fatalf("signature does not verify: %v", err)
	}
	line, err := PhoneKeyLine(pkix, "rax@phone")
	if err != nil || !strings.HasPrefix(line, "ecdsa-sha2-nistp256 AAAA") || !strings.HasSuffix(line, " rax@phone") {
		t.Fatalf("got %q, %v", line, err)
	}
}

// The live tests need an SSH server that runs commands with the rex CLI on
// the PATH and REX_SERVER set to a test server, which
// tools/rex-probe/test-sshd.sh runs. RAX_SSH_TEST is its host:port.
type liveSSH struct {
	host   string
	port   int32
	user   string
	key    string
	authed string
}

func live(t *testing.T, env string) liveSSH {
	t.Helper()
	addr := os.Getenv(env)
	if addr == "" {
		t.Skip(env + " is not set")
	}
	host, port, ok := strings.Cut(addr, ":")
	if !ok {
		t.Fatalf("%s=%q is not host:port", env, addr)
	}
	key, err := os.ReadFile(os.Getenv("RAX_SSH_TEST_KEY"))
	if err != nil {
		t.Fatalf("RAX_SSH_TEST_KEY: %v", err)
	}
	p, err := strconv.Atoi(port)
	if err != nil {
		t.Fatalf("%s=%q: %v", env, addr, err)
	}
	return liveSSH{host: host, port: int32(p), user: os.Getenv("RAX_SSH_TEST_USER"), key: string(key), authed: os.Getenv("RAX_SSH_TEST_AUTHORIZED_KEYS")}
}

func (l liveSSH) connect(t *testing.T, login *SSHLogin, signer SSHSigner) (*SSHConn, error) {
	t.Helper()
	if login.User == "" {
		login.User = l.user
	}
	return NewDialHandle().ConnectSSH(l.host, l.port, false, 20_000, login, signer)
}

// controlAnswers sends a plain GET to /api/control through a stream, which
// Rex answers with 426 because it wants a WebSocket.
func controlAnswers(t *testing.T, c *SSHConn) int {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	conn, err := c.open(ctx)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer conn.Close()
	conn.Write([]byte("GET /api/control HTTP/1.1\r\nHost: rex\r\nConnection: close\r\n\r\n"))
	resp, err := http.ReadResponse(bufio.NewReader(conn), nil)
	if err != nil {
		t.Fatalf("reading the answer: %v", err)
	}
	resp.Body.Close()
	return resp.StatusCode
}

func TestLiveSSHFindsRexAndOpensStreams(t *testing.T) {
	l := live(t, "RAX_SSH_TEST")
	c, err := l.connect(t, &SSHLogin{PrivateKey: l.key}, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if !strings.HasPrefix(c.HostKey(), "ssh-") && !strings.HasPrefix(c.HostKey(), "ecdsa-") {
		t.Errorf("host key %q", c.HostKey())
	}
	if c.socket == "" {
		t.Error("no socket")
	}
	for range 2 {
		if code := controlAnswers(t, c); code != http.StatusUpgradeRequired {
			t.Fatalf("got HTTP %d", code)
		}
	}

	// The same host key is accepted again, and a known socket skips the lookup.
	again, err := l.connect(t, &SSHLogin{PrivateKey: l.key, HostKey: c.HostKey(), Socket: c.socket}, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer again.Close()
	if code := controlAnswers(t, again); code != http.StatusUpgradeRequired {
		t.Fatalf("got HTTP %d", code)
	}
}

func TestLiveSSHRefusesAChangedHostKey(t *testing.T) {
	l := live(t, "RAX_SSH_TEST")
	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	other, _ := ssh.NewPublicKey(pub)
	_, err := l.connect(t, &SSHLogin{PrivateKey: l.key, HostKey: authorizedKey(other)}, nil)
	if err == nil || !strings.HasPrefix(err.Error(), sshErrHostKey+"ssh-") {
		t.Fatalf("got %v", err)
	}
}

func TestLiveSSHTellsAnUnknownKey(t *testing.T) {
	l := live(t, "RAX_SSH_TEST")
	_, pkix := newPhoneKey(t)
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	_, err := l.connect(t, &SSHLogin{PhoneKey: pkix}, keystoreSigner{key})
	if err == nil || !strings.HasPrefix(err.Error(), sshErrAuth) {
		t.Fatalf("got %v", err)
	}
}

func TestLiveSSHSignsInWithThePhoneKey(t *testing.T) {
	l := live(t, "RAX_SSH_TEST")
	if l.authed == "" {
		t.Skip("RAX_SSH_TEST_AUTHORIZED_KEYS is not set")
	}
	key, pkix := newPhoneKey(t)
	line, err := PhoneKeyLine(pkix, "rax-test")
	if err != nil {
		t.Fatal(err)
	}
	before, err := os.ReadFile(l.authed)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(l.authed, append(before, []byte(line+"\n")...), 0o600); err != nil {
		t.Fatal(err)
	}
	defer os.WriteFile(l.authed, before, 0o600)

	c, err := l.connect(t, &SSHLogin{PhoneKey: pkix}, keystoreSigner{key})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if code := controlAnswers(t, c); code != http.StatusUpgradeRequired {
		t.Fatalf("got HTTP %d", code)
	}
}

// RAX_SSH_TEST_NOFORWARD is an SSH server like RAX_SSH_TEST with
// AllowStreamLocalForwarding off.
func TestLiveSSHFallsBackToDialStdio(t *testing.T) {
	l := live(t, "RAX_SSH_TEST_NOFORWARD")
	c, err := l.connect(t, &SSHLogin{PrivateKey: l.key}, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	for range 2 {
		if code := controlAnswers(t, c); code != http.StatusUpgradeRequired {
			t.Fatalf("got HTTP %d", code)
		}
	}
}

func TestLiveSSHClosesWhenIdle(t *testing.T) {
	l := live(t, "RAX_SSH_TEST")
	if testing.Short() {
		t.Skip("waits for the idle timeout")
	}
	c, err := l.connect(t, &SSHLogin{PrivateKey: l.key}, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	controlAnswers(t, c)
	time.Sleep(sshIdleClose + 2*time.Second)
	if !c.Closed() {
		t.Fatal("still open with no stream")
	}
}

// serveSignIn runs an SSH server on a local port that accepts a password
// through the methods allowed in config and refuses every channel, so a
// client that signs in fails only when it asks Rex for its socket.
func serveSignIn(t *testing.T, config *ssh.ServerConfig) int32 {
	t.Helper()
	_, hostKey, _ := ed25519.GenerateKey(rand.Reader)
	signer, err := ssh.NewSignerFromKey(hostKey)
	if err != nil {
		t.Fatal(err)
	}
	config.AddHostKey(signer)
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func() {
				_, chans, reqs, err := ssh.NewServerConn(c, config)
				if err != nil {
					c.Close()
					return
				}
				go ssh.DiscardRequests(reqs)
				for ch := range chans {
					ch.Reject(ssh.Prohibited, "no channels here")
				}
			}()
		}
	}()
	return int32(l.Addr().(*net.TCPAddr).Port)
}

func TestPasswordSignsInWithEitherMethod(t *testing.T) {
	check := func(pw string) bool { return pw == "secret" }
	servers := map[string]*ssh.ServerConfig{
		"password": {PasswordCallback: func(_ ssh.ConnMetadata, pw []byte) (*ssh.Permissions, error) {
			if check(string(pw)) {
				return nil, nil
			}
			return nil, errors.New("wrong")
		}},
		// macOS asks for the password this way.
		"keyboard-interactive": {KeyboardInteractiveCallback: func(_ ssh.ConnMetadata, ask ssh.KeyboardInteractiveChallenge) (*ssh.Permissions, error) {
			answers, err := ask("", "", []string{"Password:"}, []bool{false})
			if err == nil && len(answers) == 1 && check(answers[0]) {
				return nil, nil
			}
			return nil, errors.New("wrong")
		}},
	}
	for name, config := range servers {
		port := serveSignIn(t, config)
		login := func(pw string) error {
			_, err := NewDialHandle().ConnectSSH("127.0.0.1", port, false, 10_000, &SSHLogin{User: "me", Password: pw}, nil)
			return err
		}
		// Signed in, so the failure comes from asking Rex for its socket.
		if err := login("secret"); err == nil || strings.HasPrefix(err.Error(), sshErrAuth) {
			t.Errorf("%s: right password: %v", name, err)
		}
		if err := login("nope"); err == nil || !strings.HasPrefix(err.Error(), sshErrAuth) {
			t.Errorf("%s: wrong password: %v", name, err)
		}
	}
}
