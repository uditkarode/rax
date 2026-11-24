#!/bin/sh
# Run throwaway OpenSSH servers whose commands reach the isolated Rex server, to test Rax's SSH
# transport without touching the user's own Rex or the Mac's Remote Login.
#
#   test-sshd.sh start     run both servers in the foreground (Ctrl-C ends them)
#   test-sshd.sh env       print the variables for the Go live tests in tailnet/go
#
# The servers listen on 127.0.0.1, which the Android emulator reaches as 10.0.2.2:
#   2222  forwards Rex's socket, as most servers do
#   2223  refuses socket forwarding, so Rax falls back to rex __internal dial-stdio
#
# They admit the current user with the keys in $DIR/authorized_keys, which starts with a client
# key made here. Add a phone's key (Rax shows it in the host editor) to sign in from the emulator.
# Commands run with the rex CLI on the PATH and REX_SERVER set to the isolated server, so start
# isolated-server.sh first.
#
# Environment: RAX_SSHD_DIR (default /tmp/rax-sshd), RAX_PROBE_DIR and REX_BIN as in
# isolated-server.sh.
set -eu

REX="${REX_BIN:-$HOME/Library/Application Support/com.superlogical.rex/RexServer/Versions/Current/Rex Server.app/Contents/MacOS/rex}"
DIR="${RAX_SSHD_DIR:-/tmp/rax-sshd}"
PROBE="${RAX_PROBE_DIR:-/tmp/rax-rex-probe}"

setup() {
  mkdir -p "$DIR/bin"
  chmod 700 "$DIR"
  [ -f "$DIR/host_ed25519" ] || ssh-keygen -q -t ed25519 -N '' -f "$DIR/host_ed25519" -C rax-test-host
  [ -f "$DIR/client_ed25519" ] || ssh-keygen -q -t ed25519 -N '' -f "$DIR/client_ed25519" -C rax-test-client
  [ -f "$DIR/authorized_keys" ] || cp "$DIR/client_ed25519.pub" "$DIR/authorized_keys"
  chmod 600 "$DIR/authorized_keys"
  ln -sf "$REX" "$DIR/bin/rex"
  for port in 2222 2223; do
    forward=yes
    [ "$port" = 2223 ] && forward=no
    cat > "$DIR/sshd_$port.conf" <<EOF
Port $port
ListenAddress 127.0.0.1
HostKey $DIR/host_ed25519
AuthorizedKeysFile $DIR/authorized_keys
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
StrictModes no
PidFile $DIR/sshd_$port.pid
AllowStreamLocalForwarding $forward
SetEnv PATH=$DIR/bin:/usr/bin:/bin:/usr/sbin:/sbin REX_SERVER=unix://$PROBE/rex.sock REX_RUNTIME_DIR=$PROBE/run REX_AUTOSTART=0
EOF
  done
}

case "${1:-}" in
start)
  setup
  /usr/sbin/sshd -D -e -f "$DIR/sshd_2223.conf" &
  trap 'kill $! 2>/dev/null' EXIT INT TERM
  /usr/sbin/sshd -D -e -f "$DIR/sshd_2222.conf"
  ;;
env)
  echo "RAX_SSH_TEST=127.0.0.1:2222"
  echo "RAX_SSH_TEST_NOFORWARD=127.0.0.1:2223"
  echo "RAX_SSH_TEST_USER=$(id -un)"
  echo "RAX_SSH_TEST_KEY=$DIR/client_ed25519"
  echo "RAX_SSH_TEST_AUTHORIZED_KEYS=$DIR/authorized_keys"
  ;;
*)
  echo "usage: $0 start|env" >&2
  exit 2
  ;;
esac
