# :ghostty

The terminal emulator and its Compose UI. It is
[libghostty-cmp](https://github.com/xchacha20-poly1305/libghostty-cmp) 0.4.1 (commit `26749f1`,
MIT, see `LICENSE`), Kotlin bindings for Ghostty's libghostty-vt, with these changes:

- Android only, in one module. The core and Compose libraries are merged, the desktop and web
  targets are removed, the `expect`/`actual` pairs are plain Android code, and the strings are
  Android resources in English.
- `GhosttyTerminalSession.restore` restores a session from a GHOSTSNP snapshot with Ghostty's
  `snapshot.decode` (`Session.restore` in `native/src/Session.zig`).
- `GhosttyTerminalState.sendKey` takes modifiers and the key's text, for keys such as Shift+Tab
  and Ctrl+C.
- `build-native.sh` builds the native part, in place of the upstream Gradle plugin, for arm64-v8a
  and x86_64 at API level 29.
- The unit tests run on the JVM against a build of the JNI library for the computer that runs
  them.

## Build

The build needs Zig at `minimum_zig_version` in `native/build.zig.zon`. Gradle runs
`build-native.sh` in these tasks:

- `ghosttySource` checks out Ghostty at the commit in `GHOSTTY_COMMIT` into
  `build/ghostty-source` and applies `patches/*.patch`.
- `ghosttyJni` builds `libghostty_jni.so` for the app, and `ghosttyKotlin` generates `GhosttyKey`
  and `GhosttyMods` from Ghostty's input types.
- `ghosttyHostJni` builds the library for the unit tests.

## Update Ghostty

1. Put the new commit in `GHOSTTY_COMMIT` and make sure that the patches still apply.
2. Run `./gradlew :ghostty:testDebugUnitTest`. `GhosttySnapshotTest` restores
   `src/test/resources/rex-snapshot.ghostsnp`, a snapshot that Rex sent.
3. When Rex's libghostty changes (`docs/rex-schema/deps.txt`), capture a new snapshot: run
   `src/test/resources/rex-snapshot.sh` in a session on the isolated server, save the pane's
   stream with `tools/rex-probe/stream.py`, and keep the bytes up to the end that
   `tools/rex-probe/ghostsnp.py` prints.

## Take upstream changes

Upstream's `library/src/{commonMain,androidMain}` and `library-compose/src/{commonMain,androidMain}`
are `src/main/kotlin` here, `native` is `native`, and `patches/ghostty` is `patches`.
