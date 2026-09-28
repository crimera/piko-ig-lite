#!/usr/bin/env bash
# Patch Instagram with the piko-ig-lite bundle.
set -euo pipefail

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$ROOT_DIR"

PATCHER_MAX_HEAP_MB="${PATCHER_MAX_HEAP_MB:-4096}"
PATCHER_JAR="${PATCHER_JAR:-../piko/morphe-desktop-1.11.0-all.jar}"
DEFAULT_APK="./apks/448.0.0.52.84.apk"
OUTPUT_APK="${OUTPUT_APK:-$HOME/Downloads/piko-ig-lite-patched.apk}"

if [[ ! "$PATCHER_MAX_HEAP_MB" =~ ^[1-9][0-9]*$ ]]; then
  echo "PATCHER_MAX_HEAP_MB must be a positive integer: $PATCHER_MAX_HEAP_MB" >&2
  exit 1
fi

VER=$(sed -n 's/^version *= *//p' gradle.properties | head -1)
MPP="patches/build/libs/patches-${VER}.mpp"
if [[ ! -f "$MPP" ]]; then
  echo "Missing patch bundle: $MPP" >&2
  echo "Build it first with: ./gradlew :patches:build" >&2
  exit 1
fi
if [[ ! -f "$PATCHER_JAR" ]]; then
  echo "Patcher jar not found: $PATCHER_JAR (override with PATCHER_JAR=...)" >&2
  exit 1
fi

APK="$DEFAULT_APK"
FLAGS=()
for arg in "$@"; do
  case "$arg" in
    *.apk|*.apkm|*.apks) APK="$arg" ;;
    --*) FLAGS+=("$arg") ;;
    *) FLAGS+=("-e" "$arg") ;;
  esac
done

echo "Patcher JVM heap limit: ${PATCHER_MAX_HEAP_MB} MB"
java "-Xmx${PATCHER_MAX_HEAP_MB}m" -jar "$PATCHER_JAR" patch \
  -p "$MPP" \
  --keystore Morphe.keystore \
  --striplibs=arm64-v8a \
  --force \
  -o "$OUTPUT_APK" \
  ${FLAGS[@]+"${FLAGS[@]}"} \
  -- \
  "$APK"

echo "Patched APK: $OUTPUT_APK"
