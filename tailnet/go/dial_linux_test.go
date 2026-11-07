package libtailnet

import (
	"io"
	"net"
	"os"
	"syscall"
	"testing"
	"time"
)

// spliced returns a TCP connection standing in for the tailnet peer and the
// app's end of a socketpair spliced to it.
func spliced(t *testing.T) (peer net.Conn, app *os.File) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	accepted := make(chan net.Conn, 1)
	go func() {
		c, _ := ln.Accept()
		accepted <- c
	}()
	remote, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	peer = <-accepted

	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_STREAM|syscall.SOCK_CLOEXEC, 0)
	if err != nil {
		t.Fatal(err)
	}
	f := os.NewFile(uintptr(fds[0]), "")
	local, err := net.FileConn(f)
	f.Close()
	if err != nil {
		t.Fatal(err)
	}
	go splice(remote, local.(*net.UnixConn))
	app = os.NewFile(uintptr(fds[1]), "app")
	t.Cleanup(func() {
		peer.Close()
		app.Close()
	})
	return peer, app
}

func readAll(t *testing.T, r io.Reader) string {
	t.Helper()
	done := make(chan string, 1)
	go func() {
		b, _ := io.ReadAll(r)
		done <- string(b)
	}()
	select {
	case s := <-done:
		return s
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for EOF")
		return ""
	}
}

func TestSpliceBothWays(t *testing.T) {
	peer, app := spliced(t)
	if _, err := app.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 4)
	if _, err := io.ReadFull(peer, buf); err != nil || string(buf) != "ping" {
		t.Fatalf("peer read %q, %v", buf, err)
	}
	if _, err := peer.Write([]byte("pong")); err != nil {
		t.Fatal(err)
	}
	if _, err := io.ReadFull(app, buf); err != nil || string(buf) != "pong" {
		t.Fatalf("app read %q, %v", buf, err)
	}
}

func TestSpliceAppCloseClosesPeer(t *testing.T) {
	peer, app := spliced(t)
	syscall.Shutdown(int(app.Fd()), syscall.SHUT_RDWR)
	app.Close()
	if got := readAll(t, peer); got != "" {
		t.Errorf("peer read %q after app close", got)
	}
}

func TestSpliceAppHalfClose(t *testing.T) {
	peer, app := spliced(t)
	app.Write([]byte("request"))
	if err := syscall.Shutdown(int(app.Fd()), syscall.SHUT_WR); err != nil {
		t.Fatal(err)
	}
	if got := readAll(t, peer); got != "request" {
		t.Fatalf("peer read %q", got)
	}
	// The other direction must stay open.
	peer.Write([]byte("response"))
	peer.Close()
	if got := readAll(t, app); got != "response" {
		t.Errorf("app read %q", got)
	}
}

func TestSplicePeerCloseIsEOF(t *testing.T) {
	peer, app := spliced(t)
	peer.Write([]byte("bye"))
	peer.Close()
	if got := readAll(t, app); got != "bye" {
		t.Errorf("app read %q", got)
	}
}
