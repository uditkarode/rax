# Rax

Rax is an Android app for [Rex](https://www.superlogical.com/rex). It shows the sessions, tabs and
panes that run in Rex on your computer, and opens each pane as a live terminal on the phone.

- Sessions on every computer you add, with what runs in each tab and where.
- A full terminal for each pane, rendered by Ghostty, with a key row for the keys a phone keyboard lacks.
- Programs that wait for you, such as a coding agent that asks for permission, stand out in the list.
  With background alerts on, they also arrive as notifications.
- Split, rename and close panes, tabs and sessions.
- Rax's own Tailscale node: Rax reaches computers on your tailnet without the Tailscale app and
  without a VPN on the phone.
- All of Rex's color themes, light and dark, and 10 terminal fonts. You can add your own font files.

Rax needs Android 13 or later.

## Connect to a computer

### On the same network

Start Rex with a port the phone can reach:

```
rex server start --extra-listen :7297 --allow-anonymous
```

In Rax, add a host with the computer's address, for example `192.168.1.20:7297`. Anyone who can
reach that port can use this Rex, so do this only on a network you trust. On Android 17, Rax asks
for local network access the first time.

### Over Tailscale

Turn on Tailscale in Rex on the computer:

```
rex server tailscale on
```

In Rax, open Settings, then Tailscale, and sign in with the same Tailscale account. Rax lists the
Rex servers it finds on the tailnet; tap one to add it. Rex admits only the Tailscale user who owns
it unless you started it with `--tailscale-allow-tailnet-members`.

## Build

Requirements:

- JDK 21
- Android SDK with platform 37
- Android NDK `30.0.16248370`
- Go 1.27.3 or later, for the Tailscale node in `tailnet/`

```
./gradlew :app:assembleDebug
```

The first build also builds the Go library with gomobile; see [tailnet/README.md](tailnet/README.md).

To sign release builds, put a `keystore.properties` at the repository root:

```
storeFile=release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without it, `assembleRelease` signs with the debug key.

## Tests

```
./gradlew :rex:test :app:testDebugUnitTest
```

`LiveServerTest` also runs the protocol client against a real Rex. Start a throwaway server and
point the test at it:

```
tools/rex-probe/isolated-server.sh start
RAX_REX_URL=http://127.0.0.1:7297 ./gradlew :rex:test
tools/rex-probe/isolated-server.sh stop
```

The isolated server has its own socket, state and config in `/tmp/rax-rex-probe`, so it leaves your
own Rex alone.

## When Rex changes

[docs/rex-protocol.md](docs/rex-protocol.md) describes the protocol Rax speaks, and
[docs/rex-schema/](docs/rex-schema) is a copy of what Rex reports about its own API. After you update
Rex, compare the two:

```
tools/rex-probe/rex-schema.sh check
```

| Exit code | Meaning |
|---|---|
| 0 | The API is the same. |
| 1 | Methods, events or their schemas changed. Read the diff and update `rex/`. |
| 2 | Only the version or build dependencies changed. |

Once Rax handles the changes, record the new state with `tools/rex-probe/rex-schema.sh snapshot`.

The app also checks at run time. When a computer's Rex speaks a newer API revision than Rax, its
host page says so. When the two have no revision in common, Home tells you which one to update.

## Credits

- [libghostty-android](https://github.com/SagerNet/libghostty-android) and [Ghostty](https://ghostty.org), MIT.
- [Tailscale](https://github.com/tailscale/tailscale), BSD-3-Clause.
- Color themes from Rex.
- Google Sans Flex for the interface, and the terminal fonts listed in
  `app/src/main/assets/fonts/fonts.json`, each under its own license (mostly SIL OFL 1.1).
  Symbols Nerd Font fills in icon glyphs, and JuliaMono fills in other symbols the terminal font lacks.

The Acknowledgements page in Settings shows every license in full.
