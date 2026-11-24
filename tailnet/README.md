# :tailnet

Rax's own Tailscale node, and its SSH client. The node runs Tailscale's `tsnet` in
userspace inside the app process, so Rax reaches computers on the user's tailnet
without the Tailscale app and without a `VpnService`. Both are in the one Go
library of the app, because an app can load only one gomobile library.

## Design

- `go/` is a Go module (package `libtailnet`) that wraps `tsnet`. gomobile binds it
  to Java as `de.uditkaro.rax.tailnet.libtailnet`. Only the Kotlin wrapper calls it.
- `src/main/kotlin/` is the Kotlin API:
  - `Tailnet.get(context)`: the node of this process. The first call must come
    before the app starts other threads, see below.
  - `start()`, `retry()`, `login()`, `logout()`, `stop()`: suspend functions.
    `retry()` starts the node again if it is in `Error`, which it does not
    leave by itself. A `logout()` that cannot reach Tailscale throws, and the
    node keeps its keys and stays signed in.
  - `status: StateFlow<TailnetStatus>`: `Stopped`, `Starting`, `NeedsLogin(authUrl)`,
    `NeedsApproval`, `Running(tailnetName, dnsName, addresses)`, `Error(message)`.
  - `peers: StateFlow<List<TailnetPeer>>`: MagicDNS name, host name, OS, online state
    and addresses of each device. Mullvad exit nodes are left out.
  - `dns`, `socketFactory`, and `clientBuilder(base)`, which returns
    `base.newBuilder()` with both set and with no proxy.
  - SSH (`Ssh.kt`): `SshAttempt.connect` signs in and finds Rex on the computer,
    and `SshAttempt.open` opens a stream to Rex on an `SshConnection`.
    `sshClientBuilder(base, open)` returns `base.newBuilder()` with each connection
    made as such a stream. A failure that SSH explains is an `SshFailure` from
    `:rex`, with a reason. `SshKeys` makes the authorized_keys line
    of the phone's key, checks a pasted private key, and computes fingerprints.
- Connections: OkHttp asks `dns` for the address and then connects a socket from
  `socketFactory`. The socket asks Go to dial the address with `tsnet`. Go makes a
  Unix socketpair, copies data between one end and the tailnet connection, and
  returns the other end's file descriptor. Kotlin adopts the descriptor and
  exposes it as a `java.net.Socket` with a custom `SocketImpl` (`FdSocket`). `SO_TIMEOUT` uses
  `poll`, and `close()` also aborts a dial in progress. Half-closes pass through.
  OkHttp and Conscrypt layer TLS on this socket with the URL's host name, so
  certificate checks are normal. The node opens no listening ports.
- `dns` resolves names like a `tsnet` dial does: MagicDNS names (also short names)
  and the tailnet's DNS records first, then the system resolver.
- SSH (`go/ssh.go`, with `golang.org/x/crypto/ssh`) works the way `rex -S ssh://...`
  does (`docs/rex-protocol.md` 2.2): it runs `rex server start --json` on the
  computer to find Rex's socket, then forwards that socket per stream, or runs
  `rex __internal dial-stdio` per stream where the server refuses to forward.
  Each stream reaches Kotlin as an `FdSocket`, like a tailnet connection. The TCP
  connection goes through the node for a host on the tailnet, and to addresses
  that Kotlin looked up otherwise. The phone's key stays in Android's keystore,
  so Go asks Kotlin to sign (`SSHSigner`). A connection sends a keepalive every
  15 s, ends after 3 go unanswered, and closes 10 s after its last stream.
- Login: the node requests a login URL by itself when it needs one (first start,
  after `logout()`, or after key expiry). `status` shows the URL as `NeedsLogin`.
  Keys are in `noBackupFilesDir/tailnet`, so the user logs in once and a backup
  restored on another phone does not copy this device's identity.

## Android problems and their fixes

- Go's `net.Interfaces()` fails because apps cannot dump netlink routes. Kotlin
  lists the interfaces with `java.net.NetworkInterface` and Go registers them with
  `netmon.RegisterInterfaceGetter`. A default network callback sends the interface
  name and gateway to Go and makes netmon re-check the network.
- DNS for control and DERP traffic: gomobile builds with cgo, and Go uses the cgo
  resolver on Android. It goes through bionic's `getaddrinfo` to the system
  resolver, so `/etc/resolv.conf` is not needed.
- TLS roots: Go reads `/system/etc/security/cacerts`. On Android 14 and later the
  current roots are in `/apex/com.android.conscrypt/cacerts`, so Go uses that
  directory when it exists (`SSL_CERT_DIR`). Tailscale also has built-in Let's
  Encrypt roots for its own servers.
- Apps have no `HOME` and no writable temp directory, and Tailscale code that
  looks for a log directory panics without one. `TS_LOGS_DIR` and `HOME` are set
  to the state directory.
- Environment: a gomobile library starts with an empty Go environment, so Go
  sets its variables itself (`TS_LOGS_DIR`, `HOME`, `TS_NO_LOGS_NO_SUPPORT`,
  `SSL_CERT_DIR`), and each of those calls also sets the C environment. Adding a
  C variable can move the environment array while another thread reads it, so
  `Tailnet.get` creates all of them first, and Go's calls then only replace
  values. `AppGraph` makes the `Tailnet` before anything that starts a thread.
- Logging goes to logcat with tag `RaxTailnet`. By default only INFO and above
  appear, which is a few lines per start and stop. For the node's full log on
  Android 11 and later:

  ```
  adb shell setprop log.tag.RaxTailnet VERBOSE
  ```

  DEBUG shows tsnet's messages and Go's `log` package. VERBOSE also shows the
  Tailscale backend.

## Build

Requirements: Go (the version in `go/go.mod`), an Android NDK (the version is
`ndk` in `gradle/libs.versions.toml`), and JDK 21.

Gradle builds the Go part when needed: `buildGoAar` runs `build-go.sh` when a Go
file, `go.mod`, `go.sum` or the script changes, and it is up to date otherwise.
`extractGoAar` unpacks the classes and native libraries for this module.

```
./gradlew :tailnet:assemble
```

To build only the AAR (`libs/tailnet-go.aar`, not in git):

```
tailnet/build-go.sh
```

The script installs gomobile and gobind at the versions pinned in `go/go.mod`
into `build/gobin` and builds for arm64-v8a and x86_64 with 16 KB page alignment.

To update Tailscale: `cd go && go get tailscale.com@<version> && go mod tidy`.

## Tests

- JVM: `./gradlew :tailnet:testDebugUnitTest`
- Device or emulator: `./gradlew :tailnet:connectedDebugAndroidTest`. It tests the
  socket over local socketpairs (timeouts, close, half-close, TLS on top, OkHttp)
  and starts the real node up to a login URL. It does not log in.
- Go on the host: `cd go && go test ./...`. The SSH tests that need a server run
  against throwaway OpenSSH servers and the isolated Rex server:

  ```
  tools/rex-probe/isolated-server.sh start   # in one terminal
  tools/rex-probe/test-sshd.sh start         # in another
  cd tailnet/go && env $(../../tools/rex-probe/test-sshd.sh env | xargs) go test ./...
  ```

  The socketpair tests need Linux. To run them on a device:

  ```
  cd go
  CC=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android33-clang \
    GOOS=android GOARCH=arm64 CGO_ENABLED=1 go test -c -o /tmp/libtailnet.test .
  adb push /tmp/libtailnet.test /data/local/tmp/
  adb shell /data/local/tmp/libtailnet.test -test.v
  ```
