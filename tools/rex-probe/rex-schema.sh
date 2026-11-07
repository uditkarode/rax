#!/bin/bash
# Record the protocol surface of the installed Rex, or compare it with the recorded copy.
#
#   rex-schema.sh snapshot [DIR]   write the current surface to DIR (default: docs/rex-schema)
#   rex-schema.sh check [DIR]      compare the current surface with DIR
#
# check exits 0 when the API is unchanged, 1 when the API or event schemas
# changed, and 2 when only the version or the build dependencies changed.
#
# By default the script starts a temporary server from the installed rex binary
# with its own socket, state and empty config, reads it, and stops it. To read
# a running server instead (read-only), set REX_SCHEMA_SERVER to its endpoint,
# such as unix:///path/to/server.sock or http://127.0.0.1:7297.
#
# Environment: REX_BIN (rex binary), REX_SCHEMA_SERVER (see above).
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
REX="${REX_BIN:-$HOME/Library/Application Support/com.superlogical.rex/RexServer/Versions/Current/Rex Server.app/Contents/MacOS/rex}"
MODE="${1:-}"
DIR="${2:-$REPO/docs/rex-schema}"

case "$MODE" in snapshot|check) ;; *) echo "usage: $0 snapshot|check [DIR]" >&2; exit 2 ;; esac

TMP="$(mktemp -d /tmp/rax-schema.XXXXXX)"
SERVER_PID=""
cleanup() {
  if [ -n "$SERVER_PID" ]; then
    rexc server stop >/dev/null 2>&1 || kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

if [ -n "${REX_SCHEMA_SERVER:-}" ]; then
  ENDPOINT="$REX_SCHEMA_SERVER"
else
  ENDPOINT="unix://$TMP/rex.sock"
fi

# Do not fall back to the user's own server or start one by accident. "server stop"
# finds the temporary server's runtime.json below REX_RUNTIME_DIR.
rexc() {
  env -u REX_SERVER -u REX_SESSION -u REX_BLOCK -u REX_CLIENT REX_RUNTIME_DIR="$TMP/run" \
    "$REX" --autostart=false -S "$ENDPOINT" "$@"
}

http_get() {
  case "$ENDPOINT" in
    unix://*)
      local path="${ENDPOINT#unix://}"
      path="$(printf '%b' "${path//%/\\x}")"
      curl -sf --unix-socket "$path" "http://localhost$1" ;;
    *) curl -sf "${ENDPOINT%/}$1" ;;
  esac
}

if [ -z "${REX_SCHEMA_SERVER:-}" ]; then
  mkdir -p "$TMP/state" "$TMP/run"
  : > "$TMP/init.lua"
  env -u REX_SERVER -u REX_SESSION -u REX_BLOCK -u REX_CLIENT -u REX_CONFIG REX_RUNTIME_DIR="$TMP/run" \
    "$REX" server run --listen "$ENDPOINT" --state-dir "$TMP/state" --config "$TMP/init.lua" \
    --restore=false >"$TMP/server.log" 2>&1 &
  SERVER_PID=$!
  for _ in $(seq 1 50); do
    rexc server status >/dev/null 2>&1 && break
    sleep 0.2
  done
  rexc server status >/dev/null || { cat "$TMP/server.log" >&2; exit 3; }
fi

OUT="$TMP/out"
mkdir -p "$OUT"

# Version and revision. These change with every release.
rexc api call server.version | jq -S 'del(.built_at)' >"$OUT/version.json"
rexc api call server.status | jq -S '{api_revision, api_min_revision}' >"$OUT/revision.json"

# Go build dependencies of the binary. The libghostty line pins the GHOSTSNP snapshot format.
if command -v go >/dev/null 2>&1 && [ -z "${REX_SCHEMA_SERVER:-}" ]; then
  go version -m "$REX" | awk '$1 == "dep" { print $2, $3 }' >"$OUT/deps.txt"
fi

# Every method with its JSON Schemas, examples and notes.
rexc api list --json | jq -S . >"$OUT/api-help.json"
# Every block creator with its flavors, block methods and block event schemas.
rexc api call block_creator.list | jq -S 'del(.creators[].host_id)' >"$OUT/block-creators.json"
# The server's own prose description of the protocol.
http_get /llms.txt >"$OUT/llms.txt"

# One line per method and per event, for a diff that is quick to read.
{
  jq -r '.methods[] | "method \(.name)"' "$OUT/api-help.json"
  jq -r '.creators[] | .name as $c | (.api.methods // {} | keys[] | "block-method \($c).\(.)"),
                                     (.api.events // {} | keys[] | "block-event \($c).\(.)")' "$OUT/block-creators.json"
  grep -E '^### `[a-z_]+`$' "$OUT/llms.txt" | sed -E 's/^### `(.*)`$/doc \1/'
} | sort -u >"$OUT/index.txt"

if [ "$MODE" = snapshot ]; then
  mkdir -p "$DIR"
  cp "$OUT"/* "$DIR"/
  echo "wrote $(ls "$OUT" | wc -l | tr -d ' ') files to $DIR"
  jq -r '"rex \(.rex_version), commit \(.commit), API revision \(.api_revision)"' "$DIR/version.json"
  exit 0
fi

status=0
api_changed=0
for f in api-help.json block-creators.json llms.txt index.txt revision.json; do
  if ! diff -q "$DIR/$f" "$OUT/$f" >/dev/null 2>&1; then api_changed=1; fi
done
if [ "$api_changed" = 1 ]; then
  echo "API changed:"
  diff -u "$DIR/index.txt" "$OUT/index.txt" || true
  diff -u "$DIR/revision.json" "$OUT/revision.json" || true
  echo "(full diff: diff -ru $DIR <(new snapshot); run '$0 snapshot' to accept)"
  status=1
fi
if ! diff -q "$DIR/version.json" "$OUT/version.json" >/dev/null 2>&1 || \
   { [ -f "$OUT/deps.txt" ] && ! diff -q "$DIR/deps.txt" "$OUT/deps.txt" >/dev/null 2>&1; }; then
  echo "Version or dependencies changed:"
  diff -u "$DIR/version.json" "$OUT/version.json" || true
  if [ -f "$OUT/deps.txt" ]; then
    changed_deps="$(diff -u "$DIR/deps.txt" "$OUT/deps.txt" | grep -E '^[-+][^-+]' || true)"
    [ -n "$changed_deps" ] && echo "$changed_deps"
    if echo "$changed_deps" | grep -qi ghostty; then
      echo "libghostty changed: re-test GHOSTSNP snapshot decoding"
    fi
  fi
  [ "$status" = 0 ] && status=2
fi
[ "$status" = 0 ] && echo "No change."
exit "$status"
