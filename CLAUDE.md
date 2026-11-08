# Rax

Rax is an Android app for [Rex](https://www.superlogical.com/rex), a terminal multiplexer for macOS
by Superlogical. It lists the sessions, tabs and panes on each computer the user adds, opens a pane
as a live terminal, and notifies the user when a program in a pane waits for them. Package
`de.uditkaro.rax`, Android 10 and later, Kotlin and Jetpack Compose.

## Clean room

Rax is a clean-room implementation. An older, unlicensed Android client for Rex (RexPocket) exists,
and a fork of it is at `../rax` on the author's machine. Do not read it, copy from it, or use it as
a reference for code, text or design. Facts about Rex come from Rex itself: its documentation, its
CLI help, the installed app, and probing a test server. `docs/rex-protocol.md` tags the one fact
that came from that client.

## Do not touch the user's own Rex server

The user's Rex server (socket `~/Library/Application Support/rex/server.sock`) runs their live
work, including the terminal an agent runs in. Only read-only calls are allowed against it. Do not
stop, restart or reconfigure it, and do not create, close, rename or type into any session, tab or
pane on it. Anything that changes state runs against the isolated test server
(`tools/rex-probe/isolated-server.sh`). See the `testing-against-rex` skill.

## Modules

- `:rex`: the Rex protocol client in plain Kotlin, with no Android code. `RexClient` is the control
  connection, `RexHost` keeps one server's state current from its events, `TerminalStream` is one
  pane's data connection, and `StreamParser` splits that stream into snapshot, output and Rex's
  in-band messages.
- `:tailnet`: Rax's own Tailscale node, Go `tsnet` bound with gomobile. Rax reaches tailnet hosts
  without the Tailscale app and without a VPN. `tailnet/README.md` describes it in detail.
- `:ghostty`: the terminal emulator and its Compose UI. It is libghostty-cmp with Rax's changes,
  over Ghostty's libghostty-vt built with Zig. `ghostty/README.md` describes it.
- `:app`: the Compose UI, `Connections` (one `RexHost` per saved host), notifications (`Alerts`,
  `AlertService`), the terminal screen, themes and fonts. `AppGraph` holds the app-wide objects.

## How it works

Each saved host gets a `RexHost` with one control WebSocket. `RexHost` bootstraps the session list,
attaches every session, reads each pane's facts (title, directory, process, program status) and
then keeps all of it current from events, on one coroutine, so the UI always shows a state the
server had. Home is built from these states. Opening a pane starts a `TerminalStream`: a ticket
from the control connection, a binary data WebSocket, a GHOSTSNP snapshot of the screen, then live
output. `:ghostty` restores the snapshot into an emulator and draws it. While the pane is
on screen the phone owns it, so its size applies, and it releases the pane when it leaves.
Connections stay up while Rax is in the foreground, and in the background only while background
alerts are on, which runs a foreground service.

## Build and test

JDK 21, the Android SDK with platform 37, the NDK in `gradle/libs.versions.toml`, Go at the
version in `tailnet/go/go.mod`, and Zig at `minimum_zig_version` in
`ghostty/native/build.zig.zon`. The first build also builds the Go library and Ghostty.

```
./gradlew :app:assembleDebug
./gradlew :rex:test :ghostty:testDebugUnitTest :app:testDebugUnitTest
```

The debug build has the application ID `de.uditkaro.rax.debug`, so it installs next to a release
build.

`LiveServerTest` in `:rex` runs only when `RAX_REX_URL` points at a Rex, which must be the isolated
test server (`RAX_REX_URL=http://127.0.0.1:7297 ./gradlew :rex:test`).

A release build signs with the key named in `keystore.properties` at the repository root:

```
storeFile=release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without the file, `assembleRelease` signs with the debug key. CI writes it from repository secrets,
see `release-and-ci`.

After Rex updates, run `tools/rex-probe/rex-schema.sh check` to see whether its API changed, see
`rex-protocol`.

## README

The README is for people who might use Rax: what it is, screenshots, how to install and connect,
and credits. Build, test, release and protocol details go here or in the skills.

## Commits

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/):
`type(scope): summary`, for example `fix(terminal): show a spinner while a pane loads`. The scope
is optional. Use the types `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci` and
`chore`. Every push to `main` publishes a preview release, so commit only code that builds.

## Skills

Load the matching skill before working in one of these areas:

- `rex-protocol`: the control and data connections, events, resume, ownership, reject reasons, and
  what to do when Rex updates.
- `pane-pipeline`: how an open pane goes from the data stream to the screen and back, and the
  loading, live, reconnecting and failed states.
- `terminal-rendering`: how `:ghostty` draws, font fallback and glyph coverage, themes, and
  snapshot compatibility.
- `tailnet`: the in-app Tailscale node and how hosts on a tailnet are found and reached.
- `testing-against-rex`: the isolated Rex server, the emulator, and how to reproduce network
  problems.
- `release-and-ci`: signing, versions, R8 settings, native libraries and the preview workflow.
- `ui-design`: the design rules and the reasons behind them.
