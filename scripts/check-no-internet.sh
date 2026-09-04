#!/usr/bin/env bash
# PRD K6 / 4.3 gate: verify manifest contains zero INTERNET permission
set -e
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
if [[ ! -f "$APK" ]]; then
  echo "FAIL: APK not found at $APK" >&2; exit 1
fi
AAPT="${ANDROID_HOME:-$ANDROID_SDK_ROOT}/build-tools/34.0.0/aapt"
if [[ ! -x "$AAPT" ]]; then AAPT="aapt"; fi
echo "Checking $APK for INTERNET permission..."
if "$AAPT" dump permissions "$APK" 2>&1 | grep -qi "INTERNET"; then
  echo "FAIL: INTERNET permission found"; "$AAPT" dump permissions "$APK" | grep -i INTERNET; exit 1
else
  echo "PASS: no INTERNET permission"
fi
