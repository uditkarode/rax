package libtailnet

import (
	"cmp"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

// Lookup resolves name the way a dial through the node would: MagicDNS
// names and DNS records of the tailnet first, then the system resolver. It
// returns the addresses separated by spaces, IPv4 first.
func Lookup(name string) (string, error) {
	n, err := runningNode()
	if err != nil {
		return "", err
	}
	if ip, err := netip.ParseAddr(name); err == nil {
		return ip.String(), nil
	}
	ctx, cancel := context.WithTimeout(n.ctx, 15*time.Second)
	defer cancel()
	dialer := n.srv.Sys().Dialer.Get()
	hostPort := net.JoinHostPort(name, "0")
	var ips []string
	var firstErr error
	for _, network := range []string{"tcp4", "tcp6"} {
		ipp, _, err := dialer.UserDialPlan(ctx, network, hostPort)
		if err != nil {
			firstErr = cmp.Or(firstErr, err)
			continue
		}
		ips = append(ips, ipp.Addr().String())
	}
	if len(ips) == 0 {
		return "", firstErr
	}
	return strings.Join(ips, " "), nil
}

// DialHandle is one outgoing connection attempt. Cancel can be called from
// another thread to abort Dial.
type DialHandle struct {
	ctx    context.Context
	cancel context.CancelFunc
}

func NewDialHandle() *DialHandle {
	ctx, cancel := context.WithCancel(context.Background())
	return &DialHandle{ctx: ctx, cancel: cancel}
}

// Cancel aborts a Dial in progress, or makes a later Dial fail at once.
func (h *DialHandle) Cancel() { h.cancel() }

// Dial connects to host:port through the node and returns a file descriptor
// of a Unix stream socket that carries the connection. The caller owns the
// descriptor; closing it closes the connection. timeoutMillis <= 0 means no
// timeout. An error whose message starts with "timeout:" is a timeout.
func (h *DialHandle) Dial(host string, port int32, timeoutMillis int32) (int32, error) {
	n, err := runningNode()
	if err != nil {
		return -1, err
	}
	ctx := h.ctx
	if timeoutMillis > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, time.Duration(timeoutMillis)*time.Millisecond)
		defer cancel()
	}
	addr := net.JoinHostPort(host, strconv.Itoa(int(port)))
	remote, err := n.srv.Dial(ctx, "tcp", addr)
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) || isTimeout(err) {
			return -1, fmt.Errorf("timeout: connecting to %s: %w", addr, err)
		}
		return -1, fmt.Errorf("connecting to %s: %w", addr, err)
	}

	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_STREAM|syscall.SOCK_CLOEXEC, 0)
	if err != nil {
		remote.Close()
		return -1, fmt.Errorf("socketpair: %w", err)
	}
	f := os.NewFile(uintptr(fds[0]), "tailnet-conn")
	c, err := net.FileConn(f) // dups the fd
	f.Close()
	if err != nil {
		syscall.Close(fds[1])
		remote.Close()
		return -1, err
	}
	go splice(remote, c.(*net.UnixConn))
	return int32(fds[1]), nil
}

func isTimeout(err error) bool {
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}

// splice copies between the tailnet connection and the Go end of the
// socketpair until both directions are done. Half-closes pass through.
func splice(remote net.Conn, local *net.UnixConn) {
	var closeOnce sync.Once
	closeBoth := func() {
		closeOnce.Do(func() {
			remote.Close()
			local.Close()
		})
	}
	var wg sync.WaitGroup
	wg.Go(func() {
		if _, err := io.Copy(local, remote); err != nil {
			closeBoth()
			return
		}
		local.CloseWrite()
	})
	wg.Go(func() {
		_, err := io.Copy(remote, local)
		if err != nil || peerClosed(local) {
			closeBoth()
			return
		}
		cw, ok := remote.(interface{ CloseWrite() error })
		if !ok || cw.CloseWrite() != nil {
			closeBoth()
		}
	})
	wg.Wait()
	closeBoth()
}

// peerClosed reports whether the app closed its end of the socketpair, as
// opposed to only shutting down its output. Both look like EOF to a read;
// only a full close (or shutdown of both directions) sets POLLHUP.
func peerClosed(c *net.UnixConn) bool {
	rc, err := c.SyscallConn()
	if err != nil {
		return true
	}
	hup := true
	rc.Control(func(fd uintptr) {
		fds := []unix.PollFd{{Fd: int32(fd), Events: unix.POLLIN}}
		if _, err := unix.Poll(fds, 0); err == nil {
			hup = fds[0].Revents&unix.POLLHUP != 0
		}
	})
	return hup
}
