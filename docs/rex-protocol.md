# Rex wire protocol, as Rax uses it

This spec describes how a client talks to a Rex server: transport, authentication, the control
connection, the data model, terminal streaming, input, operations, attention events, and how to
notice protocol changes. It covers API revision 11.

The complete reference for every method and event of revision 11 is the server's own document,
kept in this repository as `docs/rex-schema/llms.txt` (prose) and `docs/rex-schema/api-help.json`
plus `docs/rex-schema/block-creators.json` (JSON Schemas). This spec selects what Rax needs, adds
what those documents leave out, and records what probing showed.

## 0. Versions and sources

Inspected on 2026-11-07:

| Item | Value |
| --- | --- |
| Rex Beta.app | 0.1.0 (1829), channel `preview`, `RexSourceCommit` b3dbc5483393f81e0195dd8160010ab2a7cda251 |
| Server binary | `rex 0.0.1793890641-gaf0c20`, commit af0c204859ccf584d9392e154d8d9b4e2124635f, built 2026-11-05T14:57:21Z, darwin_arm64, Go 1.27.3 |
| API revision | 11, server speaks 0 to 11 |
| libghostty (Go module in the binary) | `go.mitchellh.com/libghostty v0.0.0-20261021212106-df1957cbff56` |

The binary at `/Applications/Rex Beta.app/Contents/Helpers/Rex Server.rex/Contents/MacOS/rex` and the
one at `~/Library/Application Support/com.superlogical.rex/RexServer/Versions/Current/Rex Server.app/Contents/MacOS/rex`
are the same file (SHA-256 e5fb7eba…3404). The app bundle holds no schemas, docs or web client.
The server serves its own documentation, which is the primary source.

Sources, in order of weight:

1. Live probing of an isolated server (section 12). Facts with no tag were observed there or read
   from the server's own `/llms.txt` and `/api/help` and consistent with what was observed.
2. Server-served docs that probing did not exercise are tagged **(server doc)**.
3. Public docs at https://www.superlogical.com/rex/docs (fetched 2026-11-07) are tagged **(web doc)**.
4. A fact that came only from the existing client at `rax/core` is tagged **(existing client only)**.
5. **(unconfirmed)** marks what no source settled.

## 1. Transport

### 1.1 Endpoints

Every listener serves the same HTTP endpoints:

| Endpoint | Content |
| --- | --- |
| `GET /api/control` | Control connection: WebSocket, subprotocol `rex.control.v1`, JSON text messages |
| `GET /api/data` | Data connection: WebSocket, subprotocol `rex.data.terminal.v1` (terminal output) or `rex.data.bytes.v1` (file upload), binary messages |
| `GET /api/help` | JSON: every method with its input and output JSON Schema, examples and notes, plus block methods |
| `GET /llms.txt`, `GET /llms.html` | The server's protocol document, Markdown or HTML |

`GET /api/control` without an upgrade answers 426. An unknown path answers 404. A caller that the
listener refuses (for example a Tailscale user who is not allowed) gets 403 with a JSON body
`{"error": "..."}` on every request **(server doc)**.

### 1.2 Listeners and URLs

A server listens on a Unix socket and on any number of extra listeners (`--extra-listen`, repeatable):

| Listener | Flag | URL a phone uses | Caller identity |
| --- | --- | --- | --- |
| Plain TCP | `--extra-listen http://HOST:PORT --allow-anonymous` (`:PORT` binds every address) | `http://HOST:PORT` → `ws://HOST:PORT/api/control` | `anonymous`, full access |
| Built-in Tailscale | `--tailscale` at start, or `rex server tailscale on` at runtime | `https://NAME.TAILNET.ts.net` → `wss://NAME.TAILNET.ts.net/api/control` (port 443) | Tailscale login |
| Machine's tailscaled, tailnet IP | `--extra-listen tailnet://:PORT` | `http://MACHINE.TAILNET.ts.net:PORT` (plain HTTP, Tailscale encrypts) **(web doc)** | Tailscale login |
| Tailscale Serve | `--extra-listen tailnet-serve:///path.sock` plus `tailscale serve --https=443 unix:/path.sock` **(web doc)** | `https://MACHINE.TAILNET.ts.net` | Tailscale login |

Port 7297 is the conventional TCP port. A bare host name given to the `rex` CLI tries HTTPS on 443,
then HTTPS on 7297, then HTTP on 7297 **(web doc)**. Rax can apply the same order to a bare host.

A server refuses to start with a plain TCP listener unless `--allow-anonymous` is set:

```
Error: invalid --extra-listen "http://127.0.0.1:7298": rex server run: --extra-listen http://127.0.0.1:7298 is a TCP endpoint whose callers Rex cannot identify. Every connection would be admitted anonymously with full access. ...
```

### 1.3 WebSocket details

- HTTP/1.1 upgrade. The built-in Tailscale listener negotiates no ALPN (HTTP/1.1) over TLS 1.3, with a Let's Encrypt certificate for the `ts.net` name.
- The client must ask for the subprotocol. Without `rex.control.v1` the server closes with 1002.
- Compression: when the client offers `permessage-deflate`, the server answers
  `permessage-deflate; client_no_context_takeover; server_no_context_takeover`. It also works with no compression.
  Data connections are not compressed **(server doc)**. Send the hello uncompressed, because it can carry the reconnect token **(server doc)**.
- `Origin`: if the request has an `Origin` header, it must match the `Host` header, or the server answers 403.
  Without `Origin` the upgrade succeeds. OkHttp sends no `Origin` by default. `--websocket-origin-pattern` allows more origins.
- `Host` is not checked. `Host: 10.0.2.2:7297` against a listener bound to 127.0.0.1 is accepted.
- Message size: at most `limits.max_message_bytes` from the welcome (1 048 576 here, at least 65 536 on any server), measured after decompression.
  A larger request closes the connection with 1009. A result that would be larger fails with `too_large` and the connection stays open **(server doc)**.

## 2. Authentication and access

Rex has no tokens, passwords or pairing secrets. A connection is admitted by how it arrives, and an
admitted connection has full access to the server, including starting programs as its account.

| Principal `kind` | `transport` | When |
| --- | --- | --- |
| `peer` | `unix` | Unix socket. Carries `uid`, `gid`, `pid` |
| `anonymous` | `tcp` | Plain TCP with `--allow-anonymous`. Carries `remote_addr` |
| `tailscale` | `tailscale` | Built-in Tailscale or the machine's tailscaled. Carries `user_id`, `login_name`, `node`, `tags`, `path` |

Observed `server.whoami` over the user's built-in Tailscale address (read-only):

```json
{"principal": {"kind": "tailscale", "transport": "tailscale", "display": "alice@example.com",
  "remote_addr": "100.101.102.103:65457", "user_id": "userid:1234567890",
  "login_name": "alice@example.com", "node": "laptop", "path": ["tailscale"]},
 "account": "alice"}
```

### 2.1 Built-in Tailscale (server side)

- The server runs its own embedded Tailscale node (tsnet) and joins the tailnet as a separate device.
  The machine needs no Tailscale install **(web doc)**. The user's server keeps the node state in `~/Library/Application Support/rex/tsnet/`.
- Device name: `--tailscale-hostname`, default `rex`. On a Mac named `laptop`, the Mac app's server is named `laptop-rex`,
  so the app picks its own name **(how it picks: unconfirmed)**.
- Address: `https://NAME.TAILNET.ts.net`, HTTPS on port 443 only. Ports 80 and 7297 on that node did not answer.
  The tailnet needs MagicDNS and HTTPS certificates **(web doc)**.
- Sign-in: browser link (in `server.status.tailscale.auth_url` and the server log) or a one-time auth key
  (`--tailscale-auth-key-file`). The login persists. `--tailscale-control-url` supports Headscale.
- Who gets in: by default only the Tailscale user who owns the Rex device.
  `--tailscale-allow-tailnet-members` (or `allow_tailnet_members` in `server.tailscale.enable`) admits anyone the tailnet ACLs allow.
  A tagged device has no owner, so it needs that option to admit anyone **(web doc)**.
- Status is in `server.status.tailscale`: `state` (`disabled`, `starting`, `needs_login`, `needs_machine_approval`, `running`),
  `dns_name`, `https.state` (`pending`, `available`), `https.url`, `allow_tailnet_members`, `tags`, `account.login_name`.
  Changes arrive as `tailscale_status_changed` to connections that called `session.attach_all` or `session.bootstrap`.
- `rex server tailscale pair --json` prints `{"url": "https://laptop-rex.tail1234.ts.net"}`. The QR code holds only the address,
  not a credential **(server doc)**. The exact QR payload is **(unconfirmed)**, presumably that URL.

What the phone needs for Tailscale: the Tailscale app (or an embedded Tailscale) on the same tailnet,
signed in as the device owner, or any member when the server allows tailnet members. Then connect
to `wss://NAME.TAILNET.ts.net/api/control`.

### 2.2 Local-only methods

`server.stop` works only over the Unix socket; over TCP or Tailscale it fails with status 403 **(server doc)**.
`server.config_reload` is refused over a remote connection **(web doc)**.

## 3. Control connection

### 3.1 Hello and welcome

Within 5 seconds of the upgrade, the client sends one hello as a text message. Without it the
server drops the TCP connection with no close frame (observed after 5 s).

```json
{"type": "hello", "api_revision": 11,
 "client": {"kind": "app", "platform": "other", "name": "Rax", "version": "0.1.0", "label": "pixel-8"}}
```

| Field | Type | Meaning |
| --- | --- | --- |
| `type` | string, required | `hello` |
| `api_revision` | int | Newest revision the client speaks. Missing means 0 |
| `api_min_revision` | int | Oldest revision the client can use. Missing means 0 |
| `reconnect_token` | string | Token from the last welcome, to continue that client (3.6) |
| `client.kind` | string | `app`, `cli`, `agent`, `other` |
| `client.platform` | string | `macos`, `ios`, `linux`, `windows`, `freebsd`, `web`, `other`. The server stores `android` as `other` (observed) |
| `client.name`, `client.version`, `client.label` | string | Shown to people. Rex.app sends `{"kind":"app","platform":"macos","name":"Rex Beta","version":"0.1.0 (1829)","label":"Laptop"}` |

The doc asks for a hello under 512 bytes. A 600-byte hello was accepted.

The welcome is the server's first message:

```json
{"type":"welcome","protocol":"rex.control.v1","limits":{"max_message_bytes":1048576},
 "client":{"id":"client:01a1b1f9-1e38-7825-aec0-af79edad32b2","reconnect_token":"<22 base64url chars>"},
 "api_revision":11,"api_server_revision":11}
```

| Field | Meaning |
| --- | --- |
| `protocol` | `rex.control.v1`. Check it before sending more |
| `limits.max_message_bytes` | Largest message either way |
| `client.id` | This client's ID, stable across resumes |
| `client.reconnect_token` | Single-use token for the next hello. Do not log it |
| `client.resumed` | `true` when the hello's token continued an existing client. Omitted when false |
| `api_revision` | Revision used for every later message on this connection |
| `api_server_revision` | Newest revision the server speaks |
| `api_min_revision` | Oldest revision the server speaks. Omitted when 0 |
| `features` | Optional behaviors, by name. Not sent by this server |
| `carriers` | Other transports (for example QUIC) with `carrier`, `port`, `alpn`. Not sent by this server over TCP or Tailscale |

Negotiation: the server picks the newest revision both sides speak. A client sending `api_revision: 5`
got `"api_revision":5,"api_server_revision":11`, and the server translates messages to revision 5
shapes **(translation: server doc)**. With no common revision the server closes:

```
1008 "no common API revision: server speaks 0 to 11, client needs 12 to 13; update the server"
```

### 3.2 Requests and responses

```json
{"type": "request", "id": "r4", "method": "data.connect", "payload": {"session_id": "session:…", "block_id": "block:…", "data_ref_id": "terminal", "subprotocol": "rex.data.terminal.v1"}}
{"type":"response","id":"r4","seq":3,"payload":{"ticket":"sohpOedhHo5WnrtnDFmn-w","expires_in_ms":60000}}
```

- `id`: any non-empty string, unique among requests still waiting. Reusing a pending id closes the connection with 1002.
- `payload` of a request: a JSON object, or absent. The server ignores unknown fields **(server doc)**.
- A response carries `payload` on success or `error` on failure, and `seq` (3.4).
- Responses can arrive out of order. These methods run one at a time in arrival order per connection:
  `block.close`, `block.event`, `client.action`, `client.key`, `session.close_layer`, `session.close_window`,
  `session.detach_block`, `session.disown_block`, `session.float_block`, `session.focus_*`, `session.move_block`,
  `session.move_window`, `session.new_layer`, `session.new_split`, `session.new_window`, `session.own_block`,
  `session.set_layer_state`, `session.swap_blocks`, `session.zoom_block`. Every other method, including
  `com.superlogical.terminal.write`, can overtake them. To keep typing in order, send keys with `client.key`
  or `block.event`, or wait for each `write` response.
- The client may skip unknown message types from the server. The server skips unknown types from the client
  (observed: a `{"type":"something_new"}` was ignored).

Block methods are called as `<creator>.<method>` with the block's IDs and the method's own arguments under `args`:

```json
{"type":"request","id":"7","method":"com.superlogical.terminal.write",
 "payload":{"session_id":"session:…","block_id":"block:…","args":{"data":"ls\n"}}}
```

### 3.3 Errors

```json
{"type":"response","id":"r1","seq":0,"error":{"code":"method_not_found","message":"netapi: no handler for method \"no.such_method\"","status":404}}
```

| `code` | `status` | Seen when |
| --- | --- | --- |
| `bad_request` | 400 | Wrong argument type or missing field. Example: `"netapi: decoding args for \"session.create\": json: cannot unmarshal number into Go struct field CreateSessionInput.label of type apitypes.Label"` |
| `not_found` | 404 | Block, window, layer or split does not exist |
| `method_not_found` | 404 | Unknown method (server older than client) |
| `session_not_found` | 404 | Unknown session ID |
| `conflict` | 409 | Not attached (`"stream is not attached to session session:…"`), limit reached, state changed under the call |
| `not_supported` | 400 | **(server doc)** |
| `refused` | 403 | Another client declined a `client.perform` **(server doc)** |
| `client_unavailable` | 409 | **(server doc)** |
| `too_large` | 413 | Result above the message limit **(server doc)** |
| `revision_too_low` | 400 | Method needs a newer revision than the connection uses **(server doc)** |
| `timeout` | 504 | **(server doc)** |
| `internal` | 500 | Anything else, including 403 for local-only methods |

Branch on `code`, fall back to `status` for unknown codes. `message` wording changes between releases.
An all-zero session UUID is treated as missing (`"session_id is required"`).

### 3.4 Events

```json
{"type":"event","seq":2,"payload":{"type":"session_created","payload":{"session":{"session_id":"session:…","label":"rax-probe"}}}}
{"type":"event","seq":12,"payload":{"type":"session_event","payload":{"session_id":"session:…",
  "event":{"type":"active_window_changed","payload":{"window_id":"window:…"}}}}}
{"type":"event","seq":27,"payload":{"type":"session_event","payload":{"session_id":"session:…",
  "event":{"type":"block_event","payload":{"block_id":"block:…","creator_name":"com.superlogical.terminal","name":"bell","payload":{}}}}}}
```

Three levels: the message (`seq`), the event (`type`, `payload`), and for session events a
`session_event` wrapper; block events are a `block_event` inside that. Skip unknown event types and fields.

`seq` starts at 1 on each connection and goes up by one with no gaps. A response's `seq` is the last
event queued before the server started the request. To combine a result with events: apply the
result, drop held events with `seq` ≤ the response's `seq`, apply the rest. For views, compare
`revision`: a `session_view_changed` with `revision` ≤ the view's is already applied. These events
can arrive out of revision order **(server doc)**; zoom produced two in a row (9, 10).

Which events a connection gets:

| Attached with | Receives |
| --- | --- |
| no attachment | `keymap_changed`, `hosts_changed`, `client_call_requested`, `client_perform_requested` |
| `session.attach` (per session) | every event of that session, including `block_event`, and its `session_destroyed` |
| `session.attach_all` / `session.bootstrap`, `event_scope: "session"` | plus `session_created`, `session_destroyed`, `session_moved`, `tailscale_status_changed`, and every session's events except `block_event` |
| same, `event_scope: "all"` | plus `block_event` from every session and `client_joined`, `client_left`, `client_changed`, `client_performed`, `client_script_events_dropped` |

Observed: with `event_scope: "session"` and no `session.attach`, a bell and a program status report in another
session produced no events. With `"all"` they arrived. For a session list that shows "agent waiting" across all
sessions, Rax needs `event_scope: "all"`, or a `session.attach` to every session.

If more than 8 MiB of events queue up, the server closes with 1008 `event backlog exceeded` **(server doc)**.

### 3.5 Keepalive

The server sends WebSocket pings every 120 s, waits 60 s for each pong, and closes with 1001
`keepalive timeout` after 3 missed pongs. The client should ping on the same schedule to notice a
dead server **(server doc)**. Data connections use the same schedule **(server doc)**.

### 3.6 Reconnect and resume

- A connection that drops without a close frame keeps its client for 60 s **(60 s: server doc)**.
  Attachments, ID, owned blocks and scripts stay.
- Reconnect with the latest `reconnect_token` in the hello. Observed after a drop: welcome
  `{"client":{"id":"client:…6e56","reconnect_token":"…","resumed":true}}`, same ID, new token, attachments intact
  (the resumed client got the session's later events without a new `session.attach`).
- An unknown, expired or foreign token starts a new client with `resumed` absent **(server doc)**.
- Events during the gap are lost and `seq` restarts at 1. Read state again (`session.list`, `session.view`).
- Responses to in-flight requests are lost, but the server may have run them. Check state before repeating a create or a write.
- A close frame ends the client at once. So does a drop when the client had no attachment, no `attach_all` and no script **(server doc)**.
- Other clients of the session see `client_connection_changed` with `connection_state: "lost"` and later `"connected"`,
  or `client_disconnected` after 60 s.

### 3.7 Close codes (control)

| Code | Reason | Cause |
| --- | --- | --- |
| 1001 | `keepalive timeout` | Missed pongs **(server doc)** |
| 1002 | `protocol error` | No subprotocol, first message not a hello, non-JSON, missing `type`/`id`/`method`, duplicate pending `id` (all observed except non-JSON) |
| 1003 | `protocol error` | Binary message from the client |
| 1007 | `protocol error` | Invalid UTF-8 **(server doc)** |
| 1008 | `no common API revision: …` / `event backlog exceeded` | |
| 1009 | `read limited at …` / `event exceeds message limit` | **(server doc)** |
| 1006 (no frame) | | No hello within 5 s |

## 4. Data model

### 4.1 Objects

| Rex | Rax UI | ID form |
| --- | --- | --- |
| host | a server in Rax's own list | none (Rax stores URLs). `host_id` in `server.status` identifies the machine+account |
| session | session | `session:<uuid>` |
| window | tab | `window:<uuid>` |
| layer | tiled layout or floating pane group | `layer:<uuid>` |
| split | split node | `split:<uuid>` |
| block | pane (today a terminal) | `block:<uuid>` |
| client | a connected program | `client:<uuid>` |

IDs are UUIDv7, stable and not reused. Labels are user-chosen, trimmed, at most 128 characters,
no control characters. Missing labels get defaults: sessions `quiet-meadow` style, windows
`Window 89abcdef`. A new block without a label gets its flavor's short name (`shell`); clearing a block label gives `Block 89abcdef`.

Rex keeps a host list per server (`hosts.list`: `id`, `label`, `endpoints[]`, `icon`, `host_id`,
`added_from`, `updated_at`, `source`, plus `hash`), which Rex clients read from their "home" server.
Rax can offer these as suggestions. Endpoints use the `-S` forms, such as `studio.tail1234.ts.net` or `192.168.1.20:7297`.

### 4.2 Session list

`session.list` → `{"sessions":[{"session_id","label"}]}` in user order (creation order, changed by `session.move`).
`session.bootstrap` and `session.attach_all` return the same list.

### 4.3 Session view

`session.view` (or the `view` in `session.attach` / `session.bootstrap`) is the whole layout:

```json
{"revision": 10, "session_id": "session:…", "label": "rax-ops", "active_window_id": "window:W2",
 "windows": [{
   "window_id": "window:W2", "label": "renamed-two", "focused_block_id": "block:B2", "zoomed_block_id": "block:B3",
   "layers": [{
     "layer_id": "layer:…", "kind": "tiled", "bounds": [0, 0, 1, 1],
     "layout": {"rect": [0, 0, 1, 1], "split": {"split_id": "split:…", "direction": "horizontal", "ratio": 0.3,
                "before": {"rect": [0, 0, 0.3, 1], "block_id": "block:B2"},
                "after":  {"rect": [0.3, 0, 0.7, 1], "block_id": "block:B3"}}},
     "blocks": [{"block_id": "block:B2", "label": "shell", "creator_name": "com.superlogical.terminal",
                 "flavor": "com.superlogical.terminal.shell", "host_id": "…", "owner": "client:…"}, …]}]}]}
```

- `windows` order is tab order. `layers[0]` is the tiled layer; later ones are floating, back to front, with `bounds` in window fractions and optional `state: "minimized"`.
- A layout node has `rect` and either `block_id` or `split`. `direction: "horizontal"` puts `before` left of `after`; `"vertical"` puts it above. `ratio` is the `before` share.
- `zoomed_block_id`: draw that block over the whole window.
- `owner`: the client whose size and colors the terminal uses (5.6).
- A session can have no windows (after its last pane exits). `active_window_id` is then absent.
- Detached blocks are not in the view; `session.list_blocks` lists them with `placement: "detached"`.

### 4.4 Per-pane facts the view does not carry

| Fact | Read with | Kept current by block event |
| --- | --- | --- |
| Title | `com.superlogical.terminal.title` → `{"title","owner":{"pid","name"}}` | `title_changed` |
| Working directory | `….pwd` → `{"pwd":"file://laptop.local/Users/alice","owner"}` (file URL from OSC 7, else a path) | `pwd_changed` |
| Program | `….process` → `child`, `foreground`, `ancestors`, `last_exit`, `home` | `process_changed` |
| Agent / program status | `….program_status` → `records[]` | `program_status_changed`, `program_status_removed` |
| Size and owner | `….size` → `columns`, `rows`, `cell_width_px`, `cell_height_px`, `owner`, `desired[]` | `size_changed`, `block_owner_changed` (session event) |
| Modes | `….modes` → `screen` (`primary`/`alternate`), `modes[]` (`name`, `number`, `ansi`, `set`, `default`) | none |

Program name: use the first present of `foreground.script`, `foreground.argv0`, `foreground.name`.
On macOS the terminal's `child` is `/usr/bin/login` running as root and `foreground` is the shell
or the command, such as `{"name":"nvim","path":"/opt/homebrew/Cellar/neovim/0.12.6/bin/nvim","argv0":"nvim","cwd":"/Users/alice"}`.
`process_changed` is also sent each time a client starts receiving the terminal's output.

Programs in Rex terminals see `TERM_PROGRAM=rex`, and `TERM=xterm-rex` if `rex terminfo setup` ran, else
`xterm-256color` (observed `xterm-256color`).

### 4.5 Focus and the active window are shared

`active_window_id` and each window's `focused_block_id` belong to the session, not to a client.
`session.focus_window`, `session.focus_block`, `session.zoom_block` (with `block_id`) and
`session.new_split`/`new_window` with focus change what the desktop shows. To show a different tab
or pane on the phone without moving the desktop, keep the phone's selection local and pass
`block_id` explicitly to `client.key`, `block.event` and block methods. `client.key` without `block_id`
goes to the session's focused block.

## 5. Terminal streaming

### 5.1 Opening a data connection

1. The control connection must be attached with `session.attach` or `session.bootstrap`. With only
   `session.attach_all`, `data.connect` fails with `conflict` (observed).
2. Get a ticket: `data.connect` with `{"session_id","block_id","data_ref_id":"terminal","subprotocol":"rex.data.terminal.v1"}`
   → `{"ticket":"sohpOedhHo5WnrtnDFmn-w","expires_in_ms":60000}`. Or ask `session.bootstrap` for tickets for every visible block:
   `"data_tickets":[{"creator_name":"com.superlogical.terminal","data_ref_id":"terminal","subprotocol":"rex.data.terminal.v1"}]`
   returns `data_tickets[]` with `session_id`, `block_id`, `data_ref_id`, `subprotocol`, `ticket`, `expires_in_ms`.
   A ticket is 16 bytes in 22 base64url characters, single use, valid 60 s, usable only by the same user.
3. Open `GET /api/data` with subprotocol `rex.data.terminal.v1`.
4. Within 5 s send HELLO as one binary message.
5. Read one binary message: WELCOME or REJECT (then close with reason `data handshake rejected`).
6. Every later binary message is stream bytes, 1 to 65 536 bytes each. Message boundaries carry no meaning. Messages from the client after WELCOME are ignored.

A client can hold one data connection per block data source (REJECT 0x02 otherwise) **(server doc)**.

### 5.2 Handshake messages (little-endian)

| Message | Size | Layout |
| --- | --- | --- |
| HELLO | 17 | `0x01`, ticket (16 bytes, base64url-decoded without padding) |
| RESUME | 25 | `0x02`, reconnect token (16), stream bytes received so far (u64) |
| WELCOME | 32 | `0x81`, reconnect token (16), 3 zero bytes, resume window in ms (u32), channel nonce (u64, non-zero) |
| REJECT | 2 | `0x82`, reason |

The server ignores bytes after the fixed part of HELLO and RESUME **(server doc)**.

Captured pair (ticket `sohpOedhHo5WnrtnDFmn-w`):

```
HELLO   01 b2886939e7611e8e569ebb670c59a7fb
WELCOME 81 5c52f6e154cad62ae67db89e6a0251f0 000000 60ea0000 290349cdf2c89a37
           token                             pad    60000 ms nonce = 4006735763651232553
```

| REJECT reason | Meaning |
| --- | --- |
| 0x01 | Ticket or token unknown, expired, used, foreign, or its session is gone |
| 0x02 | This client already has a connection to this data source; RESUME replaces a dropped one |
| 0x03 | Stream unavailable: block closed, stream ended, or RESUME counted fewer bytes than were sent (observed) |
| 0x04 | Limit reached (not sent yet) **(server doc)** |
| 0x05 | Malformed or late handshake, or RESUME counted more bytes than were sent (observed) |
| 0x06 | Server failure |
| 0x07 | Ticket issued for another subprotocol |

A HELLO uses up its ticket even when refused.

### 5.3 Stream content

```
[GHOSTSNP snapshot][ESC _ rex1;<nonce>;{"type":"resize",…} ESC \][theme message, if any][raw PTY output with rex1 messages inserted]
```

After the snapshot, the bytes are the program's raw output (VT/xterm sequences, UTF-8), as a PTY
gives it, for example `\x1b]2;alice@laptop:~\x07\x1b]7;file://laptop.local/Users/alice\x1b\\…\x1b[?2004h`.
Feed them to the same terminal emulator that restored the snapshot.

### 5.4 GHOSTSNP snapshot

The snapshot is libghostty-vt's binary terminal snapshot (envelope `GHOSTSNP`, version 1). It holds
the screens, scrollback, colors, palette, modes, title, working directory and parser state.

Framing (observed and checked against captured streams):

```
offset 0  "GHOSTSNP"            8 bytes
offset 8  version               u16 LE (1)
then records until tag 6:
          tag                   u16 LE
          length                u32 LE (payload bytes)
          crc                   u32 LE, CRC-32C (Castagnoli) over the 6 bytes of tag and length plus the payload
          payload               length bytes
```

| Case | Tag sequence | Size |
| --- | --- | --- |
| Fresh shell, 80×24 | `1 2 3 7 5 4 6` | 1 157 bytes |
| 30 000 lines of scrollback, alternate screen on | `1 2 3 2 3 7 5 4 3×50 4 6` | 233 735 bytes over 4 WebSocket messages |

Tag names, from Ghostty's `src/terminal/snapshot/record.zig`: 1 TERMINAL, 2 SCREEN, 3 PAGE,
4 HISTORY, 5 READY, 6 FINISH, 7 CONTINUATION. A snapshot is TERMINAL, then a SCREEN with its PAGE
records for each screen, CONTINUATION, READY, a HISTORY with its PAGE records for each screen, and
FINISH. The payload layouts are in Ghostty's `src/terminal/snapshot/snapshot.ksy`. The first in-band
`resize` message starts at the byte after FINISH.

Decoding the payloads needs libghostty-vt at the version the server uses. Upstream calls snapshot v1
not binary-stable, and it has changed without a version bump (third-party reports). The server's
libghostty Go module version is in its build info (section 10). For Android:

- SagerNet/libghostty-android exposes GHOSTSNP restore (public PR "expose GHOSTSNP terminal snapshots").
- The existing client feeds the snapshot to `io.github.sagernet:libghostty-android:0.1.0-alpha02` **(existing client only)**.
- Rax's `:ghostty` decodes it with Ghostty's `snapshot.decode` at Ghostty `df246d0`, which reads the
  snapshots of Rex 0.0.1793890641 (libghostty `df1957c`). `GhosttySnapshotTest` restores one.

Fallback without a decoder: find the snapshot end by walking the records (or by scanning for
`ESC _ rex1;<nonce>;`), drop the snapshot, then draw the initial screen from
`com.superlogical.terminal.format` with `"format":"vt"` and the `extra_*` flags (cursor, modes, palette,
style, scrolling region, tab stops, charsets, keyboard, pwd). The two reads are not atomic, so output
between them can repeat or go missing **(unconfirmed in practice)**.

### 5.5 In-band Rex messages

Form: `ESC _ rex1;<nonce decimal>;<json> ESC \`. Accept only the WELCOME's nonce; others are program
output that looks the same. Skip unknown `type` values. Captured:

```
\x1b_rex1;4006735763651232553;{"type":"resize","cols":80,"rows":24,"cell_width_px":8,"cell_height_px":16,"owner":"client:01a1b1f9-1e38-7825-aec0-af79edad32b2"}\x1b\\
\x1b_rex1;4006735763651232553;{"type":"resize","cols":60,"rows":30,"cell_width_px":21,"cell_height_px":42,"owner":"client:01a1b1f9-…"}\x1b\\
\x1b_rex1;4006735763651232553;{"type":"theme","foreground":"#d6deeb","background":"#011627","palette":["1=#ef5350"]}\x1b\\
\x1b_rex1;4006735763651232553;{"type":"child_exited","exit_code":0}\x1b\\
```

| `type` | Fields | When |
| --- | --- | --- |
| `resize` | `cols`, `rows`, `cell_width_px`, `cell_height_px` (0 = unknown), `owner` (client ID or null) | Right after the snapshot, then on each size change. Not on an owner change alone |
| `theme` | `foreground`, `background`, `cursor` (`RRGGBB` or `#RRGGBB`), `palette[]` (`index=RRGGBB`), `scheme` (`light`/`dark`, optional). Each replaces the previous | After the first resize unless all colors are built-in, then on each change |
| `child_exited` | `exit_code` | Program exited; the stream stays open until the block closes |

`resize` arrives in stream order, so it is the exact point where the emulator must change size.

### 5.6 Size, owner, "take control" and "release"

One terminal has one size. The block's **owner** decides it.

- `com.superlogical.terminal.resize` `{"columns","rows","cell_width_px","cell_height_px"}` records the caller's wish.
  It applies at once only if the caller owns the block or no client does.
- `session.own_block` `{"session_id","block_ids":[…],"mode":"take"}` = take control. The caller becomes owner at once
  and its last requested size applies at once. `mode:"request"` takes only blocks with no owner and queues for the rest.
- `session.disown_block` = release. The block passes to the connected client in line that claimed it most recently
  (any `take` ahead of every `request`), and that client's last size applies.
- `session.create`, `new_window`, `new_split` make the caller owner of the blocks they create.
- Owner drops: block passes to the next in line (`reason:"lost"`). Owner resumes within 60 s: it gets the block back (`reason:"restore"`) and its size applies again.
- Owner detaches or leaves: `reason:"disconnect"`. A client that closes with a close frame leaves at once, so an
  ownerless block keeps its last size.

Observed sequence (A = desktop at 120×40, B = phone at 50×20):

| Step | Size | Owner | Events to A |
| --- | --- | --- | --- |
| B `resize` 50×20 | 120×40 | A | none; `size.desired` lists B first |
| B `own_block take` | 50×20 | B | `block_owner_changed` (take), `session_view_changed`, `size_changed` 50×20 |
| B `disown_block` | 120×40 | A | `block_owner_changed`, `session_view_changed`, `size_changed` 120×40 |
| B `own_block request` | 120×40 | A | none |
| B takes, then its network drops | 120×40 | A | `client_connection_changed` (lost), `block_owner_changed` (lost), `size_changed` |
| B resumes with its token | 50×20 | B | `client_connection_changed` (connected), `block_owner_changed` (restore), `size_changed` |

`size_changed` payload: `columns`, `rows`, `cell_width_px`, `cell_height_px`, `owner`, and `principal` of the caller when a resize call caused it.

On the user's live server, Rex.app owns the pane it shows and has requested 119×43 with 22×43 px cells.
When the phone takes a pane, the desktop pane changes to the phone's size. How Rex.app reacts
(takes it back on focus or not) is **(unconfirmed)**.

`com.superlogical.terminal.set_theme` follows the same owner rule.

### 5.7 Scrollback

The snapshot carries the scrollback (history records after READY). A terminal keeps about 64 MiB of
scrollback **(server doc)**. Each client scrolls in its own emulator; the server has no shared viewport.
For text: `com.superlogical.terminal.format` `{"format":"text"|"html"|"vt","trim","unwrap","max_bytes"}`
returns `content`. With `max_bytes`, a longer result returns `id`, `total`, `eof`, and the rest comes from
`….blob_read` `{"id","offset","max_bytes"}`. `format` covers the active screen; with the alternate screen on
it returned only that screen (173 bytes).

### 5.8 Resuming a data connection

- After a drop, the stream waits 60 s (WELCOME field). Open a new `/api/data` WebSocket and send RESUME
  with the latest WELCOME token and the exact count of stream bytes consumed (WELCOME excluded).
- Accepted: new WELCOME with a new token and the same nonce. The stream continues without a new snapshot.
  This worked with the control connection still up, and with both connections dropped and the control
  client resumed first.
- The server keeps only output produced after it noticed the drop. A count lower than what it already
  sent gets REJECT 0x03 (observed with 1 and 100 bytes fewer, and with bytes still unread in the socket).
  After a real network loss, bytes in flight are likely lost, so expect 0x03 and start over: new ticket,
  HELLO, new snapshot.
- A count higher than sent gets 0x05 and ends the stream (a later correct RESUME got 0x03).
- A RESUME also ends a stream whose socket Rex still holds open, as it does for a socket that dropped
  without a close. While that socket was open, a new HELLO got 0x02. A RESUME with its token then got
  WELCOME (exact count), 0x05 (too many) or 0x03 (too few, 0 bytes), and in each case the next HELLO got
  WELCOME (observed). The old socket got no close frame.
- Closing a data connection with status 1000 ends the stream.

### 5.9 Data close codes

| Code | Reason | Meaning |
| --- | --- | --- |
| 1000 | | Stream ended |
| 1001 | `keepalive timeout` / `data provider canceled` | Resume / server stopped the stream **(server doc)** |
| 1008 | `data provider unavailable` | Unknown `data_ref_id` or subprotocol **(server doc)** |
| 1011 | `data provider failed` | **(server doc)** |
| 1013 | `data reconnect required` | Start over with a new ticket **(server doc)** |
| 4000 | `block closed` | Block closed (observed after `exit`). Do not reconnect |
| 1002/1008/1011/1013 | `data handshake rejected` | After a REJECT (observed 1008) |

## 6. Input

Bytes flow only from server to client on the data connection. Input goes over the control connection.

| Method | Use | Ordered |
| --- | --- | --- |
| `client.key` `{"session_id","block_id","key":{…},"prefix":[…]}` → `{"handled":bool}` | Keys. Lua bindings in the config file see bound keys first | yes |
| `block.event` `{"session_id","block_id","event":{"type","payload"}}` → `{}` | `key`, `paste`, `mouse`, `wheel`, `focus` straight to the terminal | yes |
| `com.superlogical.terminal.write` `{"args":{"data"}}` → `{"bytes_written"}` | Raw bytes as typed | no |

Key object (same for `client.key.key` and `block.event` type `key`):

| Field | Type | Notes |
| --- | --- | --- |
| `key` | string | Physical key, W3C `code` in snake_case: `key_a`, `digit_1`, `enter`, `backspace`, `tab`, `escape`, `space`, `arrow_up`, `page_down`, `home`, `f5`, … or `unidentified` (full list in `api-help.json`) |
| `mods` | string | `+`-joined in order `shift`, `ctrl`, `alt`, `super`, `caps_lock`, `num_lock`, `shift_side`, `ctrl_side`, `alt_side`, `super_side`. Empty for none |
| `consumed_mods` | string | Modifiers used to produce `text` |
| `text` | string | Text the key types. Empty for arrows and similar |
| `unshifted_codepoint` | int | Code point without shift, 0 if none |
| `composing` | bool | Part of an unfinished IME composition |
| `action` | string | `press`, `release`, `repeat` |

Observed bytes delivered to a raw-mode program (cursor-key mode on, bracketed paste on):

| Input | Bytes |
| --- | --- |
| `key_a`, text `a`, cp 97 | `61` |
| `key_a`, mods `shift`, consumed `shift`, text `A` | `41` |
| `key_c`, mods `ctrl`, cp 99 | `03` |
| `enter` / `backspace` / `tab` / `escape` | `0d` / `7f` / `09` / `1b` |
| `arrow_up` | `1b5b41` |
| `arrow_left`, mods `alt` | `1b5b313b3344` (`ESC[1;3D`) |
| `key_b`, mods `alt`, text `b` | `1b62` |
| `f5` | `1b5b31357e` |
| `action: release` | no bytes |
| `unidentified`, text `é` | `c3a9` |

`unidentified` plus `text` types any text, which suits a phone keyboard. The terminal's own key
encoder (libghostty) handles modes such as application cursor keys and the Kitty keyboard protocol.

Paste (`block.event` type `paste`, `{"text":"…"}`):

| Mode | `"one\ntwo"` | `"a\r\nb"` | ESC in text |
| --- | --- | --- | --- |
| Bracketed paste on (2004) | `ESC[200~one\ntwo ESC[201~` | `ESC[200~a\r\nb ESC[201~` | replaced by a space |
| Off | `one\rtwo` | `a\r\rb` | replaced by a space |

Focus: `{"type":"focus","payload":{"action":"in"|"out"}}` → `ESC[I` / `ESC[O` when the program enabled focus events.

Mouse (`type: "mouse"`), fields observed:

| Field | Type | Notes |
| --- | --- | --- |
| `action` | string | `press`, `release`, `motion` (unknown values fail with `bad_request`) |
| `button` | string | `left`, `middle`, `right`, `four` (wheel up); `back` and missing produced no output **(full list unconfirmed)** |
| `x`, `y` | int | 0-based cell column and row |
| `pixel` | object `{x, y}` | Pixel position; with 8×16 cells `{40,32}` reported column 6, row 3 |
| `buttons` | object `{button: bool}` | Buttons held |
| `mods` | string | As for keys. `shift` turns the press into a selection with no report; `ctrl` adds 16 to the SGR button code |

With SGR mouse mode: left press at x=5,y=3 → `ESC[<0;6;4M`, release → `ESC[<0;6;4m`, motion → `ESC[<32;7;4M`,
right → `ESC[<2;…M`, middle → `ESC[<1;…M`, ctrl+left → `ESC[<16;…M`. With no mouse mode on, mouse events produce no bytes.
The terminal keeps button state across calls: send a release for every press. After a shift+press (a selection),
later left events in the same run produced no bytes.

Wheel (`type: "wheel"`): `delta_x`, `delta_y`, `delta_z` (numbers), `delta_mode` (`line` or `pixel`;
unknown fails), `x`, `y`. Positive `delta_y` scrolls down: `{"delta_y":1,"x":3,"y":3}` → `ESC[<65;4;4M`,
`delta_y:-3` → `ESC[<64;…M`, `delta_x:1` → `ESC[<67;…M`, `delta_y:48` with `pixel` → one step.

## 7. Operations

All need the connection attached to the session (3.4) except `session.list` and `session.create`.
Results are observed; each layout change returns the new view `revision` and sends `session_view_changed`.

| Operation | Method and arguments | Result | Events seen |
| --- | --- | --- | --- |
| Create session | `session.create` `{"label","initial_windows":[{"window_label","layout":{"block":{"flavor":"com.superlogical.terminal.shell","label","options":{"cwd","command":[…],"shell","login","interactive"}}}}]}` | `{"session_id","initial_windows":[{"window_id","block_ids","revision"}]}` | `session_created` (once windows are ready). Does not attach |
| Rename session | `session.set_label` `{"session_id","label"}` (empty restores the server's name) | `{"revision"}` | `session_label_changed`, `session_view_changed` |
| Close session | `session.destroy` `{"session_id"}` | `{}` | `client_disconnected` per client, `block_closed`, `session_destroyed` |
| New tab | `session.new_window` `{"session_id","window_label","layout","focus":false}` (focus defaults to true) | `{"window_id","block_ids","revision"}` | `block_created`, `block_owner_changed` (create), `window_created`, `session_view_changed`, then title/pwd/process events |
| Rename tab | `session.set_window_label` `{"session_id","window_id","label"}` | `{"revision"}` | `window_label_changed`, `session_view_changed` |
| Close tab | `session.close_window` `{"session_id","window_id"}` | `{"revision"}` | `window_closed`, `active_window_changed`, `session_view_changed`, `block_closed` |
| Switch desktop tab | `session.focus_window` `{"session_id","window_id"}` | `{"revision"}` | `active_window_changed`, `session_view_changed` |
| Split pane | `session.new_split` `{"session_id","anchor_id":"block:…","direction":"horizontal"\|"vertical","side":"before"\|"after","ratio","layout","focus"}` | `{"anchor_id","block_ids","split_ids","revision"}` | as new tab, without `window_created` |
| Rename pane | `session.set_block_label` `{"session_id","block_id","label"}` | `{"revision"}` | `session_view_changed` |
| Close pane | `block.close` `{"session_id","block_id","keep_last"}` | `{"block_id","revision"}` | `block_closed`, `session_view_changed`; `window_closed` if it was the last |
| Focus pane | `session.focus_block` `{"session_id","block_id"}` | `{"revision"}` | `session_view_changed` (and `active_window_changed` if the window changes) |
| Move border | `session.resize_pane` `{"session_id","direction","before_block_id","after_block_id","ratio"}` | `{"revision"}` | `session_view_changed` |
| Zoom | `session.zoom_block` `{"session_id","block_id","zoom":true}` | `{"block_id","zoomed","revision"}` | two `session_view_changed` |

A shell that exits closes its block (`child_exited`, `block_closed`, `window_closed`, `active_window_changed` with no
`window_id` if no window is left). The session stays, with no windows. The macOS terminal program is `login`,
and `exit 3` reported `exit_code: 0`.

Other useful calls: `client.set_label` `{"label"}`, `session.list_clients`, `session.describe_block`,
`server.status`, `server.version`, `block_creator.list`, `hosts.list`, `com.superlogical.terminal.clear` / `reset`.
Limits: 64 sessions, 64 windows per session, 64 panes per window, 1024 blocks (defaults, `conflict` when reached).

## 8. Attention and notifications

Terminal block events, from `block_creator.list`. All were observed except `selection_copied` and `url_clicked`:

| Event | Payload | Source in the terminal |
| --- | --- | --- |
| `bell` | `{}` (bells close together merge) | BEL |
| `desktop_notification` | `title`, `body` | OSC 9 (`title` empty) or OSC 777 `notify;Title;Body` |
| `program_status_changed` | `id` (`""` for root), `record`, `reason` (`report`) | OSC 7501 |
| `program_status_removed` | `ids[]`, `reason`: `clear`, `child_exited`, `reset`, `prompt`, `foreground`, `evicted` | |
| `progress_report` | `state`: `set`, `indeterminate`, `pause`, `error`, `remove`; `progress` 0–100 | OSC 9;4 |
| `child_exited` | `exit_code`, `error` | Program exit |
| `clipboard_written` | `text` (if small), `id`, `bytes` → `….clipboard_read` | OSC 52 |
| `title_changed`, `pwd_changed`, `process_changed`, `size_changed` | see 4.4 | |
| `selection_copied`, `url_clicked` | `text`/`id`/`bytes`; `url` | User actions in a client that renders mouse selection **(server doc)** |

Program status record (OSC 7501): `state` (`idle`, `working`, `done`, `blocked`, `error`), `kind` for blocked
(`permission`, `question`, `auth`), `progress`, `app`, `effective_app`, `title`, `msg`, `updated_at`, `owner {pid,name}`.
Records form a tree by `id` (`build/test`). Sequence example:
`ESC ] 7501 ; state=blocked:kind=question:app=claude-code:title=VGFzaw==:msg=TmVlZHMgaW5wdXQ= ESC \`
(title and msg base64) produced `{"state":"blocked","kind":"question","app":"claude-code","title":"Task","msg":"Needs input",…}`.
Protocol: https://www.superlogical.com/rex/docs/build/program-status.

Claude Code reports OSC 7501 by itself. The user's live pane (read-only) showed a root record
`{"state":"working","app":"claude-code","msg":"Checking test results and emulator state"}` and one
child record per subagent (`id` = agent ID, `title` = task). "Needs the user" is `state: "blocked"`.

To notice attention in sessions the phone is not viewing, use `event_scope: "all"` (3.4), and on connect
call `program_status` for each block to get current records, since events only report changes.

## 9. Worked examples (trimmed captures)

Captures were made against the isolated server with the scripts in `tools/rex-probe/`. Tokens are shortened.

### 9.1 Connect, bootstrap, create, attach, stream

```
→ {"type":"hello","api_revision":11,"client":{"kind":"other","platform":"other","name":"rax-probe","version":"0.0.1"}}
← {"type":"welcome","protocol":"rex.control.v1","limits":{"max_message_bytes":1048576},"client":{"id":"client:01a1b1f9-1e38-…","reconnect_token":"2PwO…"},"api_revision":11,"api_server_revision":11}
→ {"type":"request","id":"r1","method":"session.bootstrap","payload":{"event_scope":"all","data_tickets":[{"creator_name":"com.superlogical.terminal","data_ref_id":"terminal","subprotocol":"rex.data.terminal.v1"}]}}
← {"type":"event","seq":1,"payload":{"type":"session_event","payload":{"session_id":"session:01a1b1ed-…","event":{"type":"client_connected",…}}}}
← {"type":"response","id":"r1","seq":0,"payload":{"client_id":"client:01a1b1f9-…","server_status":{"pid":78888,"instance_id":"7d449e94-…","host_id":"a61adb43-…","session_count":1,"tailscale":{"state":"disabled","allow_tailnet_members":false},"api_revision":11,"config":{"generation":1,"hash":"e3b0c442…"}},"attach_all":{"sessions":[{"session_id":"session:01a1b1ed-…","label":"rax-probe"}]},"selected_session":{"session":{…},"view":{…}},"data_tickets":[{…,"ticket":"…","expires_in_ms":60000}]}}
→ {"type":"request","id":"r2","method":"session.create","payload":{"label":"rax-probe","initial_windows":[{"window_label":"main","layout":{"block":{"flavor":"com.superlogical.terminal.shell","label":"sh"}}}]}}
← {"type":"event","seq":2,"payload":{"type":"session_created","payload":{"session":{"session_id":"session:01a1b1f9-1e3d-…","label":"rax-probe"}}}}
← {"type":"response","id":"r2","seq":1,"payload":{"session_id":"session:01a1b1f9-1e3d-…","initial_windows":[{"window_id":"window:01a1b1f9-1e60-…","block_ids":["block:01a1b1f9-1e3d-7dda-…"],"revision":1}]}}
→ {"type":"request","id":"r3","method":"session.attach","payload":{"session_id":"session:01a1b1f9-1e3d-…"}}
← {"type":"event","seq":3,"payload":{"type":"session_event","payload":{"session_id":"session:01a1b1f9-1e3d-…","event":{"type":"client_connected","payload":{"client_id":"client:01a1b1f9-…","principal":{"kind":"anonymous","transport":"tcp","display":"anonymous","remote_addr":"127.0.0.1:…"},"connected_at":"…"}}}}}
← {"type":"response","id":"r3","seq":2,"payload":{"client_id":"client:01a1b1f9-…","view":{"revision":1,…,"blocks":[{…,"owner":"client:01a1b1f9-…"}]}}}
→ data.connect … ← {"ticket":"sohpOedhHo5WnrtnDFmn-w","expires_in_ms":60000}
data → 01 b2886939e7611e8e569ebb670c59a7fb
data ← 81 5c52…51f0 000000 60ea0000 290349cdf2c89a37
data ← 1315 bytes: 47484f5354534e50 0100 | 0100 99030000 4726742d 5000 1800 … (snapshot, 1157 bytes) | ESC_rex1;4006735763651232553;{"type":"resize","cols":80,"rows":24,…}ESC\ | shell output …
```

Response `r1` has `seq: 0` and event 1 arrived before it. Event 1 has a higher `seq`, so it is applied on top of
the result. Bootstrap without `attach_session_ids` attached the first existing session, which sent that event.

### 9.2 Phone sizes the pane and types

```
→ com.superlogical.terminal.resize {"args":{"columns":60,"rows":30,"cell_width_px":21,"cell_height_px":42}}   ← {}
→ com.superlogical.terminal.set_theme {"args":{"foreground":"#d6deeb","background":"#011627","palette":["1=#ef5350"]}}   ← {}
data ← …ESC_rex1;…;{"type":"resize","cols":60,"rows":30,"cell_width_px":21,"cell_height_px":42,"owner":"client:…"}ESC\
data ← …ESC_rex1;…;{"type":"theme","foreground":"#d6deeb","background":"#011627","palette":["1=#ef5350"]}ESC\
→ client.key {"session_id":"…","block_id":"…","key":{"key":"unidentified","mods":"","consumed_mods":"","text":"e","unshifted_codepoint":101,"composing":false,"action":"press"}}   ← {"handled":false}
  … one per character, then {"key":"enter",…,"unshifted_codepoint":13}
→ com.superlogical.terminal.write {"args":{"data":"exit\n"}}   ← {"bytes_written":5}
data ← …\x1b[?2004l\r\r\n\x1b]2;exit\x07\x1b]1;exit\x07ESC_rex1;…;{"type":"child_exited","exit_code":0}ESC\
data closed 4000 "block closed"
```

### 9.3 Errors and a refused revision

```
→ {"type":"request","id":"r1","method":"no.such_method"}
← {"type":"response","id":"r1","seq":0,"error":{"code":"method_not_found","message":"netapi: no handler for method \"no.such_method\"","status":404}}
→ session.view on a session not attached
← {"error":{"code":"conflict","message":"stream is not attached to session session:01a1b1f3-…","status":409}}
→ hello {"api_revision":13,"api_min_revision":12}
← close 1008 "no common API revision: server speaks 0 to 11, client needs 12 to 13; update the server"
```

### 9.4 Attention events after one command

Writing `printf '\a'; printf '\033]9;hello from osc9\a'; printf '\033]777;notify;Title;Body\a'; printf '\033]9;4;1;40\a'; printf '\033]52;c;Y29weQ==\a'; printf '\033]7501;state=blocked:kind=question:app=claude-code:title=VGFzaw==:msg=TmVlZHMgaW5wdXQ=\033\\'`:

```
#27 block_event bell {}
#28 block_event desktop_notification {"title": "", "body": "hello from osc9"}
#29 block_event desktop_notification {"title": "Title", "body": "Body"}
#30 block_event progress_report {"state": "set", "progress": 40}
#31 block_event program_status_changed {"id": "", "record": {"state": "blocked", "kind": "question", "app": "claude-code", "effective_app": "claude-code", "title": "Task", "msg": "Needs input", "updated_at": "2026-11-07T02:42:04.685979+09:00", "owner": {"pid": 83032, "name": "zsh"}}, "reason": "report"}
#32 block_event clipboard_written {"text": "copy", "id": 1, "bytes": 4}
```

### 9.5 Resume after a phone drop

```
(control and data connections aborted, no close frames)
→ hello {…,"reconnect_token":"22TF…"}
← welcome {"client":{"id":"client:01a1b1f1-b68a-…-6e56","reconnect_token":"QHBu…","resumed":true},…}
data → 02 <16-byte token from the last WELCOME> <u64 bytes received>
data ← 81 <new token> 000000 60ea0000 <same nonce>     (exact count; output produced while away follows)
data ← 82 03                                           (count lower than what the server had sent)
```

## 10. Detecting protocol changes

### 10.1 What Rex exposes

| Signal | Where | Changes when |
| --- | --- | --- |
| `api_server_revision`, `api_min_revision` | welcome; `server.status` | Any change to messages, fields, methods or events (a new revision per change) |
| `rex_version`, `commit`, `built_at`, `api_revision` | `server.version`; `rex version --json` | Every build |
| `features[]`, `carriers[]` | welcome | New optional behaviors |
| Method schemas | `GET /api/help`, `rex api list --json` (`methods[].input_schema`, `output_schema`, examples, notes) | Method changes |
| Block event and method schemas | `block_creator.list` (`creators[].api.events`, `.methods`, `flavors[].options_schema`) | Terminal event changes |
| Prose spec | `GET /llms.txt` ("It describes API revision 11") | Doc changes |
| libghostty version | `go version -m <rex binary>` → `dep go.mitchellh.com/libghostty v0.0.0-…-df1957cbff56` | Snapshot format risk |
| App build | `Info.plist` `CFBundleShortVersionString` 0.1.0, `CFBundleVersion` 1829, `RexSourceCommit` | App updates |

### 10.2 Snapshot kept in the repository

`tools/rex-probe/rex-schema.sh` writes `docs/rex-schema/`:

| File | Content |
| --- | --- |
| `version.json` | `server.version` without `built_at` |
| `revision.json` | `api_revision`, `api_min_revision` |
| `deps.txt` | Go module dependencies of the binary, including libghostty |
| `api-help.json` | `rex api list --json`, keys sorted |
| `block-creators.json` | `block_creator.list` without `host_id`, keys sorted |
| `llms.txt` | the server's prose spec |
| `index.txt` | one line per method, block method, block event and documented name, for a short diff |

```sh
# Record the installed Rex (starts and stops a temporary isolated server):
tools/rex-probe/rex-schema.sh snapshot
# After a Rex update:
tools/rex-probe/rex-schema.sh check     # exit 0 same, 1 API changed, 2 only version or deps changed
# Read a running server instead (read-only), such as the user's own:
REX_SCHEMA_SERVER="unix://$HOME/Library/Application%20Support/rex/server.sock" tools/rex-probe/rex-schema.sh check
```

`check` prints the `index.txt` and `revision.json` diffs and flags a libghostty change with
"re-test GHOSTSNP snapshot decoding". `REX_BIN` selects another binary, such as the app's Helpers copy.
The script needs `jq` and `curl`, and `go` for `deps.txt`. It was tested by removing a method and changing
the libghostty line in a copy of the snapshot.

The installed server can differ from the running one until the server restarts. Compare
`rex version --json` (installed CLI) with `rex api call server.version` (running server).

### 10.3 Runtime policy for Rax

1. Send `api_revision` = the revision Rax was built against (11) and `api_min_revision` = the oldest it implements.
2. Close 1008 with reason ending `update the server` or `update the client`: show that reason.
3. Welcome `api_revision` lower than Rax's: the server is older; disable features newer than that revision and expect `method_not_found`.
4. `api_server_revision` higher than Rax's: keep working (the server translates to Rax's revision) and show a non-blocking "Rex is newer than this app" notice once per server `rex_version`.
5. Store `server.version.rex_version` and `commit` per host. When they change, re-check that the GHOSTSNP decoder accepts the next snapshot; if it fails, fall back to `format` `vt` (5.4) and report the version.
6. Skip unknown message types, event types, fields, enum values, `features` and `carriers`.

## 11. Rax checklist

1. Connect `ws(s)://HOST/api/control`, subprotocol `rex.control.v1`, no `Origin`, hello within 5 s.
2. `session.bootstrap` with `event_scope:"all"`, `attach_session_ids:[]` for the session list; `session.attach` the opened session; `program_status` per block for current attention.
3. Show a pane: ticket, `/api/data`, HELLO, WELCOME, buffer until the GHOSTSNP FINISH record, decode, then feed live bytes, handling `rex1` messages with the WELCOME nonce.
4. Size: send `resize` with the phone's grid; call `own_block take` only when the user takes control, and `disown_block` to release.
5. Input: `client.key` with `block_id` for keys, `block.event` `paste`, `mouse`, `wheel`, `focus`.
6. Keep tab and pane selection local; avoid `focus_*` unless the user asks to move the desktop.
7. On drop: resume control with the token within 60 s, then RESUME data with the exact count; on 0x03 start over with a new ticket.

## 12. Isolated test server

`tools/rex-probe/isolated-server.sh` runs a second server with its own socket, state directory, runtime
directory and empty config, and an anonymous TCP listener on 127.0.0.1:7297:

```sh
tools/rex-probe/isolated-server.sh start     # foreground; runs:
#   env -u REX_SERVER -u REX_SESSION -u REX_BLOCK -u REX_CLIENT -u REX_CONFIG REX_RUNTIME_DIR=/tmp/rax-rex-probe/run \
#     rex server run --listen unix:///tmp/rax-rex-probe/rex.sock --extra-listen http://127.0.0.1:7297 \
#       --allow-anonymous --state-dir /tmp/rax-rex-probe/state --config /tmp/rax-rex-probe/init.lua --restore=false
tools/rex-probe/isolated-server.sh status    # REX_RUNTIME_DIR=/tmp/rax-rex-probe/run rex --autostart=false -S unix:///tmp/rax-rex-probe/rex.sock server status --json
tools/rex-probe/isolated-server.sh cli ls    # any rex command against the isolated server
tools/rex-probe/isolated-server.sh stop      # REX_RUNTIME_DIR=/tmp/rax-rex-probe/run rex --autostart=false -S unix:///tmp/rax-rex-probe/rex.sock server stop
```

`RAX_PROBE_DIR`, `RAX_PROBE_HOST`, `RAX_PROBE_PORT` and `REX_BIN` override the defaults. Every CLI call
passes `--autostart=false -S <isolated socket>` and unsets `REX_SERVER`, `REX_SESSION`, `REX_BLOCK` and
`REX_CLIENT`, so it cannot reach or start the user's server. `server stop` finds the server through
`runtime.json` below `REX_RUNTIME_DIR`; without that variable it looks in the user's state directory and fails
with `rex server is not running`. Programs in its terminals run as the user,
with the user's shell startup files.

Isolation checks done: its `host_id` (a61adb43-…) differs from the user's (a810fb9a-…); it reported
0 sessions and Tailscale `disabled`; its state appeared only under `/tmp/rax-rex-probe`; a listing of
`~/Library/Application Support/rex` before and after start differed only in the user's own tailscaled
log rotation.

Android emulator: the emulator reaches the host loopback as `10.0.2.2`, so use `http://10.0.2.2:7297`
(`ws://10.0.2.2:7297/api/control`). Checked from the running emulator (Android 17):
`adb shell 'printf "GET /api/help HTTP/1.0\r\n\r\n" | nc -w 3 10.0.2.2 7297'` → `HTTP/1.0 200 OK`.
Binding to 127.0.0.1 keeps the anonymous listener off the LAN. A physical phone on Wi-Fi needs
`RAX_PROBE_HOST=0.0.0.0` (anyone on that network then has full access) or Tailscale.

Android cleartext: `ws://` to 10.0.2.2 needs cleartext traffic allowed for that host in the app's network security config.

### Probe scripts

All in `tools/rex-probe/`, run with a Python that has `websockets` (tested with websockets 17.4):

| Script | Does |
| --- | --- |
| `rexwire.py` | Control and data client used by the others; logs frames to JSONL |
| `scenario.py URL [CAP]` | 9.1 and 9.2 end to end |
| `ops.py URL [CAP]` | Section 7 operations, attention events, errors |
| `ownership.py URL [CAP]` | Two clients, owner and size rules, phone drop and resume |
| `resume.py URL` | Data RESUME cases |
| `inputs.py URL`, `mouse.py URL`, `paste.py URL [--no-bracketed-paste]` | Bytes delivered per key, mouse, wheel, focus and paste input, through `keyecho.py` |
| `scope.py URL` | What an unattached connection can call and hear per `event_scope` |
| `bigsnap.py URL` | GHOSTSNP records with long scrollback and the alternate screen |
| `handshake.py URL` | Revisions, close codes, Origin, compression, HTTP endpoints |
| `stream.py URL LABEL OUT [SECONDS] [BLOCK]` | Save a block's stream to a file |
| `ghostsnp.py FILE` | Walk GHOSTSNP records, check CRCs, find the snapshot end |
| `welcome.py URL` | Read-only welcome, `server.whoami`, `server.version` |
| `isolated-server.sh`, `rex-schema.sh` | Section 12 and section 10 |

## 13. Open questions

1. Exact QR payload of `rex server tailscale pair`.
2. How Rex.app reacts when a phone takes ownership (gives up, or takes back on focus). Same for Rex for iOS.
3. How the Mac app names its Tailscale device (`laptop-rex` observed on a Mac named `laptop`).
4. Keepalive timings, the 60 s client resume window, the 8 MiB event backlog limit and the 512-byte hello limit come from the server doc and were not exercised (a 600-byte hello was accepted).
5. Mouse `button` names beyond `left`, `middle`, `right`, `four`.
6. `carriers` (QUIC) and `features`: defined in the welcome schema, not sent by this server on TCP or Tailscale. When a server sends a carrier, its use is undocumented beyond port and ALPN.
7. Whether a TCP data RESUME can succeed after a real network loss, given that any unread byte makes the server reject it (5.8).

## Sources

- Server-served docs: `GET /llms.txt`, `GET /api/help` (copies in `docs/rex-schema/`)
- https://www.superlogical.com/rex/docs/llms.txt (index), https://www.superlogical.com/rex/docs/linux/remote-access.md,
  https://www.superlogical.com/rex/docs/automate/server-api.md, https://www.superlogical.com/rex/docs/build/program-status.md
- https://www.bitdoze.com/superlogical-rex/ (architecture overview)
- GHOSTSNP background: https://github.com/ghostty-org/ghostty/discussions/11998,
  https://github.com/SagerNet/libghostty-android/pull/2, https://github.com/dagrigorev/Wisp/pull/62
