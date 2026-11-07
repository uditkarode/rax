#!/usr/bin/env bash
# Builds the Go part of :tailnet into an AAR with gomobile.
#
# Usage: build-go.sh [output.aar]   (default: libs/tailnet-go.aar)
#
# Environment: ANDROID_HOME (default ~/Library/Android/sdk), ANDROID_NDK_HOME
# (default: newest NDK in $ANDROID_HOME/ndk), GO (default: go on PATH, then
# Homebrew's go), JAVA_HOME (javac for gomobile's Java glue).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-$here/libs/tailnet-go.aar}"

: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/*/ 2>/dev/null | sort -V | tail -n 1)"
  ANDROID_NDK_HOME="${ANDROID_NDK_HOME%/}"
fi
if [[ ! -d "$ANDROID_NDK_HOME" ]]; then
  echo "build-go.sh: no Android NDK found. Install one with sdkmanager or set ANDROID_NDK_HOME." >&2
  exit 1
fi
export ANDROID_HOME ANDROID_NDK_HOME

if [[ -z "${GO:-}" ]]; then
  GO="$(command -v go || true)"
  for candidate in /opt/homebrew/bin/go /usr/local/go/bin/go; do
    [[ -n "$GO" ]] && break
    [[ -x "$candidate" ]] && GO="$candidate"
  done
fi
if [[ -z "$GO" ]]; then
  echo "build-go.sh: go not found. Install Go or set GO." >&2
  exit 1
fi
export PATH="$(dirname "$GO"):$PATH"
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

cd "$here/go"

# gomobile calls gobind from PATH; install both at the versions in go.mod.
bin="$here/build/gobin"
GOBIN="$bin" "$GO" install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind

# gomobile's generated main package carries no build info, so Tailscale would
# report its version to the control server as "x.y.z-ERR-BuildInfo".
tsversion="$("$GO" list -m -f '{{.Version}}' tailscale.com)"
tsversion="${tsversion#v}"

mkdir -p "$(dirname "$out")"
PATH="$bin:$PATH" gomobile bind \
  -target=android/arm64,android/amd64 \
  -androidapi=33 \
  -javapkg=de.uditkaro.rax.tailnet \
  -trimpath \
  -ldflags="-s -w -X tailscale.com/version.longStamp=$tsversion -X tailscale.com/version.shortStamp=$tsversion" \
  -o "$out" \
  .
