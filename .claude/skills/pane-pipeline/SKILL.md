---
name: pane-pipeline
description: How an open pane goes from Rex's data stream to the screen and back. Covers TerminalViewModel threading, snapshot restore and its fallback, input, ownership timing, pane switching, and the loading, live, reconnecting and failed states. Use when working on the terminal screen or when a terminal is blank, stuck, shows the wrong pane, or fights the desktop over size.
---

# From data stream to screen

The chain is `TerminalStream` (`:rex`) → `TerminalViewModel` → `GhosttyTerminalSession` (the
`:ghostty` emulator) → the `GhosttyTerminal` composable, which draws it. The key bar sends keys
through the composable's `GhosttyTerminalState`. One `TerminalViewModel` belongs to one terminal
screen. It shows one pane at a time and can switch panes.

## Threads

- The stream is collected on `terminalThread`, a single-thread view of `Dispatchers.Default`.
  Snapshot decoding, creating the emulator and `feedOutput` all happen there, in stream order. Do
  not feed an emulator from any other thread.
- `_session` is swapped on the main thread, and the old session is closed after the swap. If the
  coroutine is cancelled before the swap, because the user picked another pane, the new session is
  closed instead, so no native emulator leaks.
- Ownership state (`wanted`, `owned`, `ownedSince`, `ownedBy`, `syncing`) changes on the main
  thread only.
- OkHttp's reader thread stops reading the data socket while 16 MiB of output wait unread, so a
  terminal that falls far behind slows the socket down. That thread also reads the pongs that keep
  the socket open, so a smaller limit would let a slow restore close the socket.

## Starting a stream

1. `Event.Loading(bytes)` arrives while the snapshot downloads. A pane with long scrollback can
   send tens of megabytes, and `GhosttyTerminalSession.restore` decodes a snapshot only in one
   piece, so the whole snapshot must arrive first.
2. `Event.Started(snapshot)`: `GhosttyTerminalSession.restore` decodes it. It returns null when
   `:ghostty` cannot read Rex's snapshot format (see `terminal-rendering`). Rax then asks Rex
   for the pane's `size` and for the screen as escape sequences: the pane's `format` method with
   `format: vt` and the `extra_*` flags except the palette, which comes from the phone's theme.
   A long screen comes in parts, read with `blob_read`. Rax reads the size again after the
   screen, and reads the screen again if the pane was resized in between. Rax makes the emulator
   at Rex's size, so the rows and the cursor land where they were, and feeds it the screen before
   it shows. Output that arrived before Rex drew the screen is already in it, so Rax drops it.
   The size call times a round trip, and Rex's drawing reaches the phone's clock about one round
   trip after the `format` call goes out. The reply itself comes later when the screen is long.
   A read whose answer was lost, or that came before a new connection attached the session, goes
   out again once Rex takes calls. If Rex refuses to send the screen, the status becomes `Failed`
   and the stream ends, so the pane shows "Couldn't open this pane" with Retry, and not a blank
   emulator.
3. Status becomes Live. Every later `Started` (a stream that could not resume) builds a new
   emulator from the new snapshot.

## Input and size

- Typing waits up to 15 s for the connection. Input that waited longer, or that Rex refused, is
  dropped, and Rax says so in a toast. A write whose answer was lost can have reached the pane,
  so it does not count as dropped.
- The emulator's transport sends typing through `PaneInput.send`. Rax makes its emulators with
  `repliesToProgram = false`, so they send only what the user types: Rex's own terminal answers
  terminal queries, and it sends the size (mode 2048) and colour scheme (mode 2031) reports
  itself. The phone sends no focus reports, and it denies clipboard reads without asking.
- When the terminal's grid changes, the session calls `sendResize`. Rax sends the size, and if the pane is
  visible and not owned elsewhere, it asks to own the pane. The view also resizes each session that
  attaches to it, so a new emulator tells Rex the phone's size even when the grid stays the same.
- The keyboard, with the key bar or the text box above it, covers the bottom of the terminal and
  changes no size, so the program sees no resize and does not redraw when the keyboard opens or
  closes. `TerminalScreen` keeps the dock's space under the bar and gives the terminal the covered
  height as `contentPadding`. In `SCROLL_UNDER` mode the rows move up until the last row with
  content is above the padding, but no further than keeps the visible cursor in sight. In a short
  window the whole screen first moves up by the top bar's height.
- The last grid sent to Rex is kept for the whole process. A new emulator is resized to it on
  `terminalThread` before it shows, so rewrapping a long scrollback stays off the main thread, also
  for the first pane a screen opens.
- Rax sends the active terminal colours and whether the theme is light or dark with `set_theme`
  when a pane opens, on each new data connection, and when the theme changes. Like the size, Rex
  applies them while the phone owns the pane, and answers programs that ask for the background
  colour or the colour scheme with them. Programs such as Codex and Neovim ask once at start, so
  one started before the phone owned the pane keeps the colours it got then.

## Ownership timing

- `want(blockId)` sets the pane the phone should own and starts `syncOwnership` in the app scope,
  once. The loop runs until `wanted == owned`: it releases first, then takes, waiting up to 90 s each
  time for a connection that has attached the session (`HostState.canCall`). It runs in the app scope so a release still goes out after the screen
  is gone. A take or release whose answer was lost (the connection dropped, or Rex did not answer
  in time) goes out again, because Rex may or may not have run it.
- The view revision is read before a take. A later view where the pane's `owner` is another client
  means another device took it, and the pane shows `ElsewhereOwned`. That is a flag apart from the
  stream status, so a reconnecting stream does not clear it. Only Use here or another pane does.
- A control connection that comes back as a new client owns no panes, so Rax forgets `owned` and
  takes the pane again when the new stream starts.
- On `ON_START` the screen calls `onVisible` (take, if Live), and on `ON_STOP` it calls `onHidden`
  (release). A stream that resumes takes the pane again if the screen is visible. After 30 s out of
  sight, the stream ends, so the phone does not download output nobody sees. `onVisible` starts it
  again with `show(target, restart = true)`, and the last screen shows as Reconnecting until the
  new snapshot arrives. `onCleared`
  releases, cancels the stream (which closes the socket with 1000), and closes the emulator.
- Retry on the same pane keeps it owned, so its size does not go to the desktop and back. Keys that
  the old `PaneInput` still has, such as the rest of a paste, go out before keys typed after it.

## What the terminal area shows

`PaneView` is derived from the stream status, whether a screen exists, the host's `Link` (the same
`hostLink` function Home uses), the snapshot bytes so far, and a stall timer. The rule is that the
area is never blank without a reason on screen:

- A host problem comes first: `Failed` with Home's message, such as "Can't reach Rex at this
  address", and Retry when retrying can help. A Tailscale sign-in or local network permission
  problem gets Home's message with Sign in or Allow.
- The host is still connecting: `Loading` ("Connecting to <host>…"), or `Reconnecting` over the
  last screen if there is one.
- The host is online: `Live` once the stream has started. With no screen yet it shows `Loading`,
  with the size so far once it passes 512 KB. With a screen it shows `Reconnecting`.
- If nothing arrives for 20 s while the host is online, the state is `Failed` ("Couldn't open this
  pane" or "Lost the connection to this pane") with Retry. The 20 s start when a live
  stream drops, and time spent waiting for the host itself does not count. The stream keeps
  trying underneath and turns Live by itself if it recovers.
- Retry reconnects the host (and starts the tailnet node for tailnet hosts) and restarts the
  stream with `show(target, restart = true)`.
- `ElsewhereOwned`, `Closed` and `Unavailable` are messages with one action: Use here, Back, Back.

The screen also shows `Loading` while the terminal font loads, because a session can be ready
before its font.

`Loading` shows its spinner and text only after 400 ms, so a pane that opens quickly, as a pane
switch usually does, shows a blank terminal for a moment and no spinner that flashes by.

## Switching panes

`show(target)` with a different pane closes the old emulator first, so the old pane's screen never
shows while the new one loads. When the shown pane closes (status `Closed`), the ViewModel moves to
another pane of the tab, or to another tab. It picks from the view it has, without the closed
pane, because the view can still list that pane for a moment.

Split and New tab run through `openPane`, one at a time. A spinner takes the place of the actions
menu until Rex answers, and the screen then shows the new pane.

A notification for a pane of the session that is open already switches that screen to the pane,
so it keeps its emulator. Any other notification opens the pane on top of Home.

## `:ghostty` rules

- A session can be shown by one `GhosttyTerminal` at a time. Attaching it to a second one throws
  "session is already attached to another host".
- `close()` frees the native terminal later, on the main thread.
- `TerminalPreview` in the settings makes its own sessions, apart from any pane.
