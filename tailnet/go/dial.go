package libtailnet

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strconv"
	"time"
)

// A failed connection's error starts with one of these, so Kotlin can tell
// a timeout from a computer that cannot be reached.
const (
	errTimeout     = "timeout: "
	errUnreachable = "unreachable: "
)

// DialHandle is one outgoing connection attempt: a Dial through the node, or
// an SSH connection or a stream on one. Cancel can be called from another
// thread to abort it.
type DialHandle struct {
	ctx    context.Context
	cancel context.CancelFunc
}

func NewDialHandle() *DialHandle {
	ctx, cancel := context.WithCancel(context.Background())
	return &DialHandle{ctx: ctx, cancel: cancel}
}

// Cancel aborts the attempt in progress, or makes a later one fail at once.
func (h *DialHandle) Cancel() { h.cancel() }

// withTimeout returns the attempt's context, which also ends after
// timeoutMillis when that is above 0.
func (h *DialHandle) withTimeout(timeoutMillis int32) (context.Context, context.CancelFunc) {
	if timeoutMillis <= 0 {
		return h.ctx, func() {}
	}
	return context.WithTimeout(h.ctx, time.Duration(timeoutMillis)*time.Millisecond)
}

// dialNode connects to host:port through the node.
func dialNode(ctx context.Context, host string, port int32) (net.Conn, error) {
	n, err := runningNode()
	if err != nil {
		return nil, errors.New(errUnreachable + err.Error())
	}
	addr := net.JoinHostPort(host, strconv.Itoa(int(port)))
	conn, err := n.srv.Dial(ctx, "tcp", addr)
	if err != nil {
		return nil, connectError(addr, err)
	}
	return conn, nil
}

// connectError says that connecting to addr failed, and whether it timed out.
func connectError(addr string, err error) error {
	if errors.Is(err, context.DeadlineExceeded) || isTimeout(err) {
		return fmt.Errorf("%sconnecting to %s: %w", errTimeout, addr, err)
	}
	return fmt.Errorf("%sconnecting to %s: %w", errUnreachable, addr, err)
}

func isTimeout(err error) bool {
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}
