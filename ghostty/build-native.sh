#!/usr/bin/env bash
# Builds the native part of :ghostty with zig.
#
# Usage:
#   build-native.sh source            Check out Ghostty at GHOSTTY_COMMIT into build/ghostty-source
#                                     and apply patches/*.patch.
#   build-native.sh android <out>     The JNI library for arm64-v8a and x86_64, as <out>/<abi>/*.so.
#   build-native.sh kotlin <out>      The Kotlin mirrors of Ghostty's input types.
#   build-native.sh host <out>        The JNI library for this computer, for the JVM unit tests.
#   build-native.sh zig-version       Print the version of the zig this script uses.
#
# Environment: ANDROID_NDK_HOME (android only), ZIG (default: zig on PATH, then Homebrew's zig).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
source_dir="$here/build/ghostty-source"
# zig reuses local cache entries only while the global cache that built them is still there, so
# both live in one directory.
cache="$here/build/zig-cache"

if [[ -z "${ZIG:-}" ]]; then
  ZIG="$(command -v zig || true)"
  for candidate in /opt/homebrew/bin/zig /usr/local/bin/zig; do
    [[ -n "$ZIG" ]] && break
    [[ -x "$candidate" ]] && ZIG="$candidate"
  done
fi

require_zig() {
  if [[ -z "$ZIG" ]]; then
    echo "build-native.sh: zig not found. Install the version in native/build.zig.zon or set ZIG." >&2
    exit 1
  fi
}

zig_build() {
  require_zig
  (cd "$here/native" && ZIG_GLOBAL_CACHE_DIR="$cache" "$ZIG" build "$@" --cache-dir "$cache")
}

checkout() {
  local commit
  commit="$(tr -d '[:space:]' < "$here/GHOSTTY_COMMIT")"
  if [[ ! -d "$source_dir/.git" ]]; then
    rm -rf "$source_dir"
    mkdir -p "$source_dir"
    git -C "$source_dir" init -q
  fi
  if ! git -C "$source_dir" cat-file -e "$commit^{commit}" 2>/dev/null; then
    git -C "$source_dir" fetch -q --depth 1 https://github.com/ghostty-org/ghostty.git "$commit"
  fi
  git -C "$source_dir" checkout -qf "$commit"
  git -C "$source_dir" clean -qfd
  for patch in "$here"/patches/*.patch; do
    git -C "$source_dir" apply "$patch"
  done
}

command="${1:-}"
out="${2:-}"
case "$command" in
  source)
    checkout
    ;;
  android)
    if [[ ! -d "${ANDROID_NDK_HOME:-}" ]]; then
      echo "build-native.sh: set ANDROID_NDK_HOME to the NDK in gradle/libs.versions.toml." >&2
      exit 1
    fi
    rm -rf "$out"
    for pair in arm64-v8a:aarch64-linux-android.33 x86_64:x86_64-linux-android.33; do
      abi="${pair%%:*}"
      prefix="$here/build/zig-out/$abi"
      rm -rf "$prefix"
      zig_build "-Dtarget=${pair#*:}" -Doptimize=ReleaseSmall --prefix "$prefix"
      mkdir -p "$out/$abi"
      cp "$prefix"/lib/*.so "$out/$abi/"
    done
    ;;
  kotlin)
    prefix="$here/build/zig-out/kotlin"
    rm -rf "$prefix" "$out"
    zig_build gen-kotlin --prefix "$prefix"
    mkdir -p "$out"
    cp -R "$prefix/kotlin/." "$out/"
    ;;
  zig-version)
    require_zig
    "$ZIG" version
    ;;
  host)
    prefix="$here/build/zig-out/host"
    rm -rf "$prefix" "$out"
    zig_build -Doptimize=ReleaseSafe --prefix "$prefix"
    mkdir -p "$out"
    find "$prefix" -name '*ghostty_jni*' \( -name '*.so' -o -name '*.dylib' \) -exec cp {} "$out/" \;
    ;;
  *)
    sed -n '2,12p' "$0" >&2
    exit 1
    ;;
esac
