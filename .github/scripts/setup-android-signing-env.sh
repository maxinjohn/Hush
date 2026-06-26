#!/usr/bin/env bash
set -euo pipefail

BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-35.0.0}"

resolve_android_sdk() {
  if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
    printf '%s' "$ANDROID_HOME"
    return 0
  fi
  if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT" ]; then
    printf '%s' "$ANDROID_SDK_ROOT"
    return 0
  fi
  if [ -f local.properties ]; then
    local sdk_dir
    sdk_dir="$(grep -E '^sdk\.dir=' local.properties | cut -d= -f2- | sed 's/\\:/:/g' | tr -d '\r')"
    if [ -n "$sdk_dir" ] && [ -d "$sdk_dir" ]; then
      printf '%s' "$sdk_dir"
      return 0
    fi
  fi
  for candidate in /usr/local/lib/android/sdk "$HOME/Android/Sdk"; do
    if [ -d "$candidate" ]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  return 1
}

SDK_ROOT="$(resolve_android_sdk)" || {
  echo "Android SDK not found. Run the Gradle build step first or set ANDROID_HOME."
  exit 1
}

if [ -n "${GITHUB_ENV:-}" ]; then
  {
    echo "ANDROID_HOME=$SDK_ROOT"
    echo "ANDROID_SDK_ROOT=$SDK_ROOT"
  } >> "$GITHUB_ENV"
fi

echo "Using Android SDK: $SDK_ROOT"

BT_DIR="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION"
if [ ! -d "$BT_DIR" ]; then
  echo "Installing build-tools;$BUILD_TOOLS_VERSION"
  SDKMANAGER=""
  for candidate in \
    "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" \
    "$SDK_ROOT/cmdline-tools/16.0/bin/sdkmanager" \
    "$SDK_ROOT/tools/bin/sdkmanager"
  do
    if [ -x "$candidate" ]; then
      SDKMANAGER="$candidate"
      break
    fi
  done
  if [ -z "$SDKMANAGER" ]; then
    echo "sdkmanager not found under $SDK_ROOT"
    exit 1
  fi
  yes | "$SDKMANAGER" "build-tools;$BUILD_TOOLS_VERSION"
fi

if [ ! -x "$BT_DIR/apksigner" ]; then
  echo "apksigner not found in $BT_DIR"
  exit 1
fi

echo "Android build-tools $BUILD_TOOLS_VERSION ready for release signing."
