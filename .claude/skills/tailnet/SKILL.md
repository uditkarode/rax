---
name: tailnet
description: Rax's in-app Tailscale node and how Rax uses it. Covers why the app runs its own tsnet node, how app sockets reach the tailnet, when the node starts, sign-in, finding Rex servers on a tailnet, privacy settings, and the build traps. Use when working on :tailnet, on Tailscale hosts, sign-in, or discovery, or when a tailnet host cannot connect.
---

# The in-app Tailscale node

`tailnet/README.md` documents the module: its Kotlin API, how a tailnet socket is built, the
Android problems (interfaces, DNS, TLS roots, log directory) and their fixes, the build, and the
tests. Read it first. This skill adds the decisions around it.

## Why Rax runs its own node

The product requirement is that the user does not run a VPN on the whole phone to use Rax. So Rax
runs Tailscale's `tsnet` in userspace inside its own process, as its own device on the user's
tailnet, and only Rax's sockets go through it. A `VpnService` is not used, and the phone needs no
Tailscale app.

Consequences:

- Only connections made through `AppGraph.tailnetHttp` (`tailnet.clientBuilder(http)`) reach the
  tailnet. A host saved with the tailnet flag uses that client, and every other host uses the plain
  one. `Connections` picks the client per host.
- The node shows up as its own device in the user's Tailscale admin console, and an admin may have
  to approve it (`TailnetStatus.NeedsApproval`).
- Rex's built-in Tailscale admits only the Tailscale user who owns it, unless it was started with
  `--tailscale-allow-tailnet-members`. The user must sign Rax in with the same account.

## Lifecycle

- `AppGraph` starts the node when any saved host uses the tailnet. Signing in from Settings starts
  it too.
- A tailnet host connects only while the node is Running. While the node is down every attempt
  fails at once, and those failures would make the host look unreachable after the node comes up.
  So whenever the node's state changes, `Connections` stops each tailnet host that is not
  connected, and starts it again from its first attempt once the node runs. A connected host is
  left alone, because its connection can outlast a short drop of the node.
- Retry on a tailnet host, on Home or in a pane, starts the node again before it reconnects.
- Sign-in: when the node needs a login, its status carries the auth URL, and Rax opens it in a
  Custom Tab (`rememberTailnetSignIn`). Keys live in `noBackupFilesDir/tailnet`, so a backup
  restored on another phone does not clone this device.

## Finding Rex on the tailnet

`findRexServers` probes each online peer in two places:

- `https://<peer>/api/control`, where Rex's built-in Tailscale serves (HTTPS on 443 only, with a
  MagicDNS certificate);
- `http://<peer>:7297/api/control`, for a computer that runs Tailscale itself and starts Rex with
  `--extra-listen :7297`.

A control endpoint answers a plain GET with HTTP 426 (Upgrade Required), and that is the test. The
name Rax shows drops a `-rex` suffix or `rex-` prefix, because Rex names its device `<name>-rex` by
default.

## Privacy

`TS_NO_LOGS_NO_SUPPORT` is set and `logtail` is disabled, so the node uploads no logs to
Tailscale's log service. Its log goes to logcat only (tag `RaxTailnet`).

## Build traps

- gomobile's generated main package has no Go build info, so Tailscale would report its version to
  the control server as `x.y.z-ERR-BuildInfo`. `build-go.sh` sets the version stamps with
  `-ldflags -X`.
- Tailscale's `ts_omit_*` build tags were tried to make the library smaller. `tsnet` needs several
  of the omitted features (serve and ACME among them), and the size gain was about 3%, so the build
  uses no omit tags.
- Gradle rebuilds the Go library only when Go sources, `go.mod`, `go.sum` or the script change.
  When it misbehaves, delete `tailnet/libs/` and `tailnet/build/` and build again.
- The gomobile Java classes are called from Go through JNI by name. `consumer-rules.pro` keeps them,
  and R8 must not rename them.
