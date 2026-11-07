#!/bin/sh
# Start or stop a Rex server that shares no state with the user's own server.
#
#   isolated-server.sh start    run in the foreground (Ctrl-C or "stop" ends it)
#   isolated-server.sh stop     stop it through its own Unix socket
#   isolated-server.sh status   print its status as JSON
#
# Environment:
#   REX_BIN          rex binary (default: the server bundle installed by Rex.app)
#   RAX_PROBE_DIR    state, socket and config directory (default /tmp/rax-rex-probe)
#   RAX_PROBE_HOST   TCP listen address (default 127.0.0.1, which the Android
#                    emulator reaches as 10.0.2.2)
#   RAX_PROBE_PORT   TCP port (default 7297)
#
# The TCP listener is anonymous: every connection has full access to this
# server, so keep RAX_PROBE_HOST on 127.0.0.1 unless the network is yours.
set -eu

REX="${REX_BIN:-$HOME/Library/Application Support/com.superlogical.rex/RexServer/Versions/Current/Rex Server.app/Contents/MacOS/rex}"
BASE="${RAX_PROBE_DIR:-/tmp/rax-rex-probe}"
HOST="${RAX_PROBE_HOST:-127.0.0.1}"
PORT="${RAX_PROBE_PORT:-7297}"
SOCK="unix://$BASE/rex.sock"

# Commands must not fall back to the user's server or start a new one. "server stop"
# finds the server's runtime.json below REX_RUNTIME_DIR, so the CLI needs it too.
cli() {
  env -u REX_SERVER -u REX_SESSION -u REX_BLOCK -u REX_CLIENT REX_RUNTIME_DIR="$BASE/run" \
    "$REX" --autostart=false -S "$SOCK" "$@"
}

case "${1:-}" in
start)
  mkdir -p "$BASE/state" "$BASE/run"
  chmod 700 "$BASE"
  : > "$BASE/init.lua"
  exec env -u REX_SERVER -u REX_SESSION -u REX_BLOCK -u REX_CLIENT -u REX_CONFIG \
    REX_RUNTIME_DIR="$BASE/run" \
    "$REX" server run \
      --listen "$SOCK" \
      --extra-listen "http://$HOST:$PORT" \
      --allow-anonymous \
      --state-dir "$BASE/state" \
      --config "$BASE/init.lua" \
      --restore=false
  ;;
stop)
  cli server stop
  ;;
status)
  cli server status --json
  ;;
cli)
  shift
  cli "$@"
  ;;
*)
  echo "usage: $0 start|stop|status|cli ARGS..." >&2
  exit 2
  ;;
esac
