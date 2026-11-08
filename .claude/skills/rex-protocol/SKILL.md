---
name: rex-protocol
description: How Rax talks to a Rex server. Covers the control and data WebSockets, API revisions, the event-driven state store, reconnect and resume, the data handshake and its reject reasons, pane ownership, and what to do when Rex updates. Use when changing the :rex module, debugging connection or stream problems, or adapting Rax to a new Rex release.
---

# Rex protocol, as Rax uses it

`docs/rex-protocol.md` is the full reference: every message, field and close code, captured from
Rex itself, with worked examples. `docs/rex-schema/` is a copy of what Rex reports about its own
API. This skill covers the decisions Rax makes on top of that, and the traps found the hard way.

## Two kinds of connection

- **Control**: one WebSocket per host on `/api/control`, subprotocol `rex.control.v1`, JSON text.
  The client sends `hello` (API revisions, client kind, name, version and label, and the reconnect
  token if it has one). The server answers `welcome` with the client ID, a new reconnect token, the
  negotiated revision and limits. Requests are `{type: request, id, method, payload}` and get
  `{type: response, id, payload | error, seq}`. Events are `{type: event, seq, payload: {type,
  payload}}`.
- **Data**: one binary WebSocket per open pane on `/api/data`, subprotocol `rex.data.terminal.v1`.
  It needs a ticket from `data.connect` on the control connection. A ticket is single use, valid
  60 s, and only for the same user.

A pane's own methods (`write`, `resize`, `title`, `pwd`, `process`, `program_status`, `format`) are
called as `<creator>.<method>`, where the creator is `com.superlogical.terminal`. Use
`RexClient.callBlock`.

## API revisions

`RexClient` sends `API_REVISION` and `API_MIN_REVISION`. If the server shares no revision with
Rax, it closes with 1008 and a reason that ends in "update the server" or "update the client".
Rax maps that to `Problem.Incompatible`, stops retrying until something changes, and Home tells the
user which side to update. When the welcome reports a server revision above Rax's, the host page
says Rex is newer. Before raising Rax's revision, read the schema diff (see "When Rex updates").

## RexHost is a single-writer store

- Every change to `HostState` goes through one inbox coroutine, in the order the server sent it.
  Do not update the state from anywhere else, or the UI can show a state the server never had.
- `gen` goes up when a connection starts and when it ends. Each async read carries the `gen` it
  started under, and a result from an older `gen` is dropped.
- Bootstrap: `session.bootstrap` with `event_scope: all`. Events that arrive before its reply are
  held, then replayed if their `seq` is above the `seq` of the reply, because the reply already
  includes everything up to it. A `desktop_notification` is not held: it changes no state, and the
  reply does not carry it.
- Views: `session.attach` once per session, then `session.view` when `session_view_changed`
  reports a higher revision. Reads of the same session are coalesced. When the server's
  `instance_id` changes, Rex restarted and can reuse session and pane IDs, so the views and pane
  facts Rax kept are dropped.
- Pane facts (title, directory, process, program status) are not in the view. Rax reads them once
  per pane, minimized panes included, then keeps them current from `block_event` events. Each event
  bumps a stamp for that fact, so a read that started before the event cannot overwrite the newer
  value. Title and directory events carry the whole fact, so such a read is dropped. Process and
  status events carry a part, so the read fills in the rest.
- An attach or a fact read that fails with `timeout`, `conflict` or a server error is tried again
  with backoff while the connection stays up. `SessionState.attach` records whether the current
  connection attached the session, or failed to for good.
- After a reconnect, every pane's facts are read again, also when the view did not change, because
  events from the time without a connection are lost.
- Alerts: `Alert.Waiting` fires when a pane's program status gains a `blocked` record, on the
  transition only. `Alert.Notification` comes from `desktop_notification` (OSC 9 and similar).

## Reconnect and resume

- The control connection retries with backoff, from 500 ms up to 30 s with jitter. It does not
  retry after `Incompatible` or HTTP 403. `Connections` calls `retryNow()` when the default network
  changes.
- Home shows a host as offline from its second failed attempt in a row. A single lost connection
  or unreachable attempt is common on a phone, so it shows as connecting. A TLS failure, a refusal,
  a server that is not Rex, or an incompatible one shows at once, because retrying will not fix it.
- A reconnect that sends the reconnect token resumes the same client ID if the server still holds
  it. The server keeps a dropped client for 60 s, and a resumed client gets its panes back.
- `stop()` closes with 1000, which ends the client on the server at once. `suspendConnection()`
  drops the socket without a close frame, so the server keeps the client and the next connect
  resumes it. Rax suspends 30 s after it goes to the background, unless background alerts are on.

## The data stream

1. Wait for the control connection, call `data.connect`, and open `/api/data`.
2. Send HELLO: `0x01` and the 16-byte ticket.
3. Read WELCOME (`0x81`, reconnect token, resume window, 64-bit nonce) or REJECT (`0x82`, reason).
4. Then come stream bytes: a GHOSTSNP snapshot of the screen, then program output.

Rex's own messages are mixed into the output as `ESC _ rex1;<nonce>;<json> ESC \` (resizes with
their owner, `child_exited`, and others). The nonce is an unsigned 64-bit number, printed in the
message as unsigned decimal, so format it with `Long.toUnsignedString`. Formatted as a signed
number, Rax missed Rex's messages for half of all nonces, and they reached the emulator as text. A
message with another nonce was printed by a program, and it stays in the output.

RESUME (`0x02`, token, bytes received after WELCOME) continues a dropped stream without a new
snapshot. Rex accepts only the exact count it sent. After a real network loss, bytes in flight are
lost, so expect reason `0x03` and start over with a new ticket and snapshot.

REJECT reasons that matter:

| Reason | Meaning | What Rax does |
|---|---|---|
| `0x01` | Ticket or token unknown, used or expired | New ticket |
| `0x02` | This client already has a data connection to this pane | Retry; see below |
| `0x03` | Stream unavailable, or RESUME counted too few bytes | New ticket and snapshot |
| `0x05` | Malformed HELLO, or RESUME counted too many bytes (this also ends the stream) | New ticket |

`0x02` is the one to understand:

- Rex allows one data connection per client and pane. A data socket that was never closed makes
  every later HELLO for that pane fail. Rax once leaked sockets this way and showed a blank
  terminal on reopen, so `TerminalStream` always ends with `close(1000)` in `finally`. A close with
  1000 also ends the stream on Rex.
- Right after the user leaves a pane and opens it again, the old close can still be in flight.
  Rax retries every 250 ms for this reason before it falls back to backoff.
- After a network change, Rex can believe the old socket is still alive until its keepalive gives
  up (pings every 120 s, 60 s to answer). RESUME is what replaces a dropped data connection.

Close code 4000 means the pane closed. `data.connect` failing with `not_found` or
`session_not_found` also means it is gone. Close code 1013 means Rex no longer has the stream, so
Rax starts over with a new ticket and does not send a RESUME.

## Ownership: who sizes a pane

A pane has one owner, and the owner's size applies. The phone takes the pane while it is on screen
(`session.own_block` with `mode: take`) and releases it when it leaves (`session.disown_block`), so
the desktop gets its own size back.

- When the view's revision moves past the revision at which Rax took the pane and the pane's
  `owner` is another client, another device took it. Rax stops sizing it and offers "Use here".
- A release must still reach Rex after the terminal screen is gone, because a client that resumes
  gets its panes back. A lost release would leave the desktop at the phone's size. Rax sends
  ownership changes from the app scope and waits up to 90 s for a connection that has attached the
  session.

## Input

- Keys and text go through the pane's `write` method as a UTF-8 string, one call in flight, with
  whatever was typed meanwhile joined into the next call. A call larger than the welcome's
  `max_message_bytes` would close the connection, so a large paste goes out in parts, in order.
- Writes, sizes and colours wait until the connection has attached the session
  (`HostState.canCall`). Before that, Rex refuses calls that name the session with `conflict`.
- Rex's own terminal answers terminal queries (DA, DSR and similar), so Rax makes its emulators
  with `repliesToProgram = false`, and they send only what the user types.
- Sizes go through `resize` with columns, rows and cell pixels. Only the newest pending size is
  sent, and it applies only while this client owns the pane.

## Rex's built-in Tailscale

`rex server tailscale on` makes Rex join the tailnet as its own device (`<name>-rex` by default).
It serves HTTPS on port 443 only, with a MagicDNS certificate, so tailnet hosts are
`https://<device>.<tailnet>.ts.net`. By default it admits only the Tailscale user who owns it. On a
LAN, Rex needs `--extra-listen :7297 --allow-anonymous`, and Rax uses plain `ws://`. Rax finds Rex
servers on the tailnet by probing `/api/control` on each peer: a WebSocket endpoint answers HTTP 426.

## When Rex updates

1. `tools/rex-probe/rex-schema.sh check` compares the installed Rex with `docs/rex-schema/`. Exit 0
   means no change, 1 means methods, events or schemas changed, and 2 means only the version or
   dependencies changed.
2. For exit 1, read the diff and update `:rex`. Then record the new state with
   `rex-schema.sh snapshot`.
3. Look at the libghostty version in `docs/rex-schema/deps.txt`. When it changes, the snapshot
   format can change too. Capture a new test snapshot for `:ghostty` (`ghostty/README.md`) and see
   the `terminal-rendering` skill.
4. Run `LiveServerTest` against the isolated server (see `testing-against-rex`).
