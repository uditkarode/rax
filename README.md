# Rax

Rax is an Android app for [Rex](https://www.superlogical.com/rex), the terminal multiplexer for
Mac. It shows the sessions and tabs running in Rex on your computers, and opens any of them as a
live terminal on your phone.

<p>
  <img src="docs/screenshots/home.webp" width="260" alt="Sessions on a computer, with a tab that waits for an answer at the top">
  <img src="docs/screenshots/terminal.webp" width="260" alt="Neovim in a pane">
  <img src="docs/screenshots/keyboard.webp" width="260" alt="Answering a prompt with the keyboard and the extra key row">
</p>

- See every session and tab on each computer you add, and what runs in each one.
- Open a pane as a full terminal. A key row above the keyboard gives you Esc, Ctrl, Alt, Tab and
  the arrows.
- Programs that wait for you, such as a coding agent asking for permission, show at the top. With
  background alerts on, they also come as notifications.
- Split, rename and close panes, tabs and sessions.
- Rax can run its own Tailscale node, so it reaches your computers over Tailscale while the rest of
  your phone stays off it. You don't need the Tailscale app.
- Rex's color themes, light and dark, and 10 terminal fonts.

## Install

Download the APK from the newest release on the
[Releases page](https://github.com/uditkarode/rax/releases). Rax needs Android 13 or later.

## Connect to your computer

### On the same network

Start Rex so the phone can reach it:

```
rex server start --extra-listen :7297 --allow-anonymous
```

In Rax, add a host with the computer's address, for example `192.168.1.20:7297`. Anyone who can
reach this port can use your Rex, so do this only on a network you trust.

### Over Tailscale

Turn on Tailscale in Rex:

```
rex server tailscale on
```

In Rax, open Settings, then Tailscale, and sign in with the same Tailscale account. Rax lists the
Rex servers it finds, and you tap one to add it.

## Credits

Rax draws the terminal with [libghostty-android](https://github.com/SagerNet/libghostty-android)
and [Ghostty](https://ghostty.org), connects over [Tailscale](https://github.com/tailscale/tailscale),
and shows program logos from [Simple Icons](https://simpleicons.org). The color themes come from
Rex. Settings, then Acknowledgements, lists every license.
