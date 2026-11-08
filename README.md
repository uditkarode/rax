# Rax

Rax is an Android client for [Rex](https://www.superlogical.com/rex), the terminal multiplexer.  

<p>
  <img src="docs/screenshots/home.webp" width="260" alt="Sessions on a computer, with Claude Code waiting for permission at the top">
  <img src="docs/screenshots/terminal.webp" width="260" alt="Claude Code asking for permission in a pane">
  <img src="docs/screenshots/keyboard.webp" width="260" alt="Typing to Codex with the keyboard and the extra key row">
</p>
  
Rax can run an in-app Tailscale node, so you don't need Tailscale running on the whole phone!

## Install

Download the APK from the newest release on the
[Releases page](https://github.com/uditkarode/rax/releases).  
  
Rax needs Android 10 or later. Future updates can use the in-app updater.

## Connect to your computer

### On the same network

Start Rex:

```bash
rex server start --extra-listen :7297 --allow-anonymous
# or from the GUI
```

In Rax, add a host with the computer's address, for example `192.168.1.20:7297`. Anyone who can
reach this port can access Rex, so do this only on a network you trust.

### Over Tailscale

Turn on Tailscale in Rex:

```bash
rex server tailscale on
# or from the GUI
```

In Rax, open Settings → Tailscale, and sign in to Tailscale. You'll see listed the
Rex servers it finds on your tailnet.

If the Tailscale app already runs on your phone, turn on "Use the Tailscale app" there instead. Rax
then reaches your tailnet through it. Add the computer as a host with its MagicDNS name, for
example `studio-rex.tail1234.ts.net`.

## Credits

The idea for Rax comes from [RexPocket](https://github.com/leizhiyuan/rexpocket), an earlier
Android client for Rex.

Rax draws the terminal with [libghostty-cmp](https://github.com/xchacha20-poly1305/libghostty-cmp)
and [Ghostty](https://ghostty.org), connects over [Tailscale](https://github.com/tailscale/tailscale),
and shows program logos from [Simple Icons](https://simpleicons.org) and
[Lobe Icons](https://github.com/lobehub/lobe-icons). The color themes come from
Rex. Settings → Acknowledgements lists licenses for everything used.
