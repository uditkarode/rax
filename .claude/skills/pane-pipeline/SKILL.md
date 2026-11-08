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
- Ownership state (`wanted`, `owned`, `ownedSince`, `syncing`) changes on the main thread only.

## Starting a stream

1. `Event.Loading(bytes)` arrives while the snapshot downloads. A pane with long scrollback can
   send tens of megabytes, and `GhosttyTerminalSession.restore` decodes a snapshot only in one
   piece, so the whole snapshot must arrive first.
2. `Event.Started(snapshot)`: `GhosttyTerminalSession.restore` decodes it. It returns null when
   `:ghostty` cannot read Rex's snapshot format (see `terminal-rendering`). Rax then makes
   an empty emulator and asks Rex for the screen as escape sequences: the pane's `format` method
   with `format: vt`, all the `extra_*` flags, and a size limit.
3. Status becomes Live. Every later `Started` (a stream that could not resume) builds a new
   emulator from the new snapshot.

## Input and size

- The emulator's transport sends typing through `PaneInput.send`. Anything it sends while Rax
  feeds it output is a reply to a terminal query, and Rax drops it (a `ThreadLocal` flag), because
  Rex's own terminal already replied.
- When the terminal's grid changes, the session calls `sendResize`. Rax sends the size, and if the pane is
  visible and not owned elsewhere, it asks to own the pane.
- Rax sends the active terminal colours and whether the theme is light or dark with `set_theme`
  when a pane opens, on each new data connection, and when the theme changes. Like the size, Rex
  applies them while the phone owns the pane, and answers programs that ask for the background
  colour or the colour scheme with them. Programs such as Codex and Neovim ask once at start, so
  one started before the phone owned the pane keeps the colours it got then.

## Ownership timing

- `want(blockId)` sets the pane the phone should own and starts `syncOwnership` in the app scope,
  once. The loop runs until `wanted == owned`: it releases first, then takes, waiting up to 90 s for
  the connection each time. It runs in the app scope so a release still goes out after the screen
  is gone.
- The view revision is read before a take. A later view where the pane's `owner` is another client
  means another device took it, and the status becomes `ElsewhereOwned`.
- On `ON_START` the screen calls `onVisible` (take, if Live), and on `ON_STOP` it calls `onHidden`
  (release). `onCleared` releases, cancels the stream (which closes the socket with 1000), and
  closes the emulator.

## What the terminal area shows

`PaneView` is derived from the stream status, whether a screen exists, the host's `Link` (the same
`hostLink` function Home uses), the snapshot bytes so far, and a stall timer. The rule is that the
area is never blank without a reason on screen:

- A host problem comes first: `Failed` with Home's message, such as "Can't reach Rex at this
  address", and Retry when retrying can help. A Tailscale sign-in or local network permission
  problem gets a message without Retry.
- The host is still connecting: `Loading` ("Connecting to <host>…"), or `Reconnecting` over the
  last screen if there is one.
- The host is online: `Live` once the stream has started. With no screen yet it shows `Loading`,
  with the size so far once it passes 512 KB. With a screen it shows `Reconnecting`.
- If nothing arrives for 20 s while the host is online, the state is `Failed` ("Couldn't open this
  pane." or "Lost the connection to this pane.") with Retry. Time spent waiting for the host itself
  does not count. The stream keeps trying underneath and turns Live by itself if it recovers.
- Retry reconnects the host (and starts the tailnet node for tailnet hosts) and restarts the
  stream with `show(target, restart = true)`.
- `ElsewhereOwned`, `Closed` and `Unavailable` are messages with one action: Use here, Back, Back.

The screen also shows `Loading` while the terminal font loads, because a session can be ready
before its font.

## Switching panes

`show(target)` with a different pane closes the old emulator first, so the old pane's screen never
shows while the new one loads. When the shown pane closes (status `Closed`), the ViewModel moves to
another pane of the tab, or to another tab.

## `:ghostty` rules

- A session can be shown by one `GhosttyTerminal` at a time. Attaching it to a second one throws
  "session is already attached to another host".
- `close()` frees the native terminal later, on the main thread.
- `TerminalPreview` in the settings makes its own sessions, apart from any pane.
