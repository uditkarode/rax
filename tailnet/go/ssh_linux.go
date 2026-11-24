package libtailnet

// Dial opens a stream to Rex on the server and returns a file descriptor of
// a Unix stream socket that carries it, as DialHandle.Dial does for the
// tailnet. h can cancel it from another thread.
func (c *SSHConn) Dial(h *DialHandle, timeoutMillis int32) (int32, error) {
	ctx, cancel := h.withTimeout(timeoutMillis)
	defer cancel()
	conn, err := c.open(ctx)
	if err != nil {
		return -1, err
	}
	return handOff(conn)
}
