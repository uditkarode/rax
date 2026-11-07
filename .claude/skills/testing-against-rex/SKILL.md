---
name: testing-against-rex
description: How to test Rax against a real Rex without touching the user's own Rex server. Covers the isolated test server, the live protocol test, the probe scripts, running the app on an emulator (host address, Android 17 local network permission, adb on this Mac), driving the UI from scripts, and reproducing network problems. Use before running anything that talks to Rex or before testing the app on an emulator.
---

# Testing against Rex

## Safety first

The user's own Rex server (socket `~/Library/Application Support/rex/server.sock`) runs their live
work, including the terminal an agent runs in. Against it, only read-only calls are allowed. Do not
stop, restart or reconfigure it, and do not create, close, rename or type into any session, tab or
pane on it. The `rex` CLI talks to it by default, and so does any script that does not get an
explicit address. Everything below uses the isolated server.

## The isolated server

`tools/rex-probe/isolated-server.sh` runs the installed Rex binary as a second server:

- `start` runs it in the foreground, so run it in the background. It has its own socket, state and
  config in `/tmp/rax-rex-probe`, starts with no sessions, and listens for anonymous TCP clients on
  `127.0.0.1:7297`.
- `stop` stops it, and `cli ...` runs the `rex` CLI against it, for example
  `isolated-server.sh cli session new demo -- /bin/zsh -c 'cat sample.txt; exec /bin/zsh -f'`.
- The script unsets `REX_SERVER`, `REX_SESSION` and the other `REX_*` variables, because an agent
  running inside Rex inherits them, and they would point the CLI at the user's server.
- Before you send a signal to a Rex process, check its command line. The isolated server's command
  line contains `/tmp/rax-rex-probe`.

## Protocol tests

- `RAX_REX_URL=http://127.0.0.1:7297 ./gradlew :rex:test` also runs `LiveServerTest`, the protocol
  client against the isolated server. Without the variable, that test is skipped.
- `tools/rex-probe/*.py` are small Python probes (handshake, resume, ownership, input, mouse,
  paste, snapshots). They need the `websockets` package in a virtual environment. `rexwire.py` holds
  the shared framing code.

## Running the app on an emulator

- The emulator reaches the Mac at `10.0.2.2`, so the test host's address is `10.0.2.2:7297`.
- Android 17 (API 37) makes local network access a runtime permission
  (`ACCESS_LOCAL_NETWORK`, in the Nearby devices group). Without it, connections to LAN addresses
  time out, and they are not refused. Rax asks for it the first time a LAN host is saved.
- On this Mac, adb needs `ADB_LIBUSB=1 ADB_MDNS=0` in its environment, or the adb server fails to
  start.
- Install the debug build (`de.uditkaro.rax.debug`). It sits next to a release build, with its own
  data.
- To drive the UI from a script, dump the view tree with `adb shell uiautomator dump`, find a node
  by its text or content description, and tap the centre of its bounds with `adb shell input tap`.
  Take screenshots with `adb exec-out screencap -p`.
- To check fonts, print a line of TUI symbols (⏺ ⎿ ⏵ ⏸ ✻ ⎇, braille, box drawing, blocks) in a
  session and look at it in each font.

## Reproducing network problems

- No network: `adb shell cmd connectivity airplane-mode enable`, and `disable` afterwards.
- A slow network: `adb emu network delay <ms>`.
- A server that stops answering, with its sockets left open: `kill -STOP` on the isolated
  server's process, then `kill -CONT`. Check the process first, as described above.
- A pane opened again right after it was left: go back and open it again within a second, which
  makes Rex reject the new data connection with reason `0x02` for a moment.
- A force quit: `adb shell am force-stop de.uditkaro.rax.debug`, then open the same pane again.
