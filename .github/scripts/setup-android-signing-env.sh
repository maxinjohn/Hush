#!/usr/bin/env bash
set -euo pipefail

PREFERRED_BUILD_TOOLS_VERSION="${PREFERRED_BUILD_TOOLS_VERSION:-35.0.0}"

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

find_installed_build_tools_version() {
  local sdk_root="$1"
  local version dir

  if [ -x "$sdk_root/build-tools/$PREFERRED_BUILD_TOOLS_VERSION/apksigner" ]; then
    printf '%s' "$PREFERRED_BUILD_TOOLS_VERSION"
    return 0
  fi

  version=""
  for dir in "$sdk_root/build-tools"/*; do
    [ -d "$dir" ] || continue
    [ -x "$dir/apksigner" ] || continue
    version="$(basename "$dir")"
  done

  if [ -n "$version" ]; then
    printf '%s' "$version"
    return 0
  fi

  return 1
}

install_build_tools_version() {
  local sdk_root="$1"
  local version="$2"
  local sdkmanager="" candidate

  for candidate in \
    "$sdk_root/cmdline-tools/latest/bin/sdkmanager" \
    "$sdk_root/cmdline-tools/16.0/bin/sdkmanager" \
    "$sdk_root/tools/bin/sdkmanager"
  do
    if [ -x "$candidate" ]; then
      sdkmanager="$candidate"
      break
    fi
  done

  if [ -z "$sdkmanager" ]; then
    echo "sdkmanager not found under $sdk_root"
    return 1
  fi

  echo "Installing build-tools;$version"
  yes | "$sdkmanager" "build-tools;$version"
}

SDK_ROOT="$(resolve_android_sdk)" || {
  echo "Android SDK not found. Run the Gradle build step first or set ANDROID_HOME."
  exit 1
}

BUILD_TOOLS_VERSION="$(find_installed_build_tools_version "$SDK_ROOT" || true)"
if [ -z "${BUILD_TOOLS_VERSION:-}" ]; then
  install_build_tools_version "$SDK_ROOT" "$PREFERRED_BUILD_TOOLS_VERSION"
  BUILD_TOOLS_VERSION="$PREFERRED_BUILD_TOOLS_VERSION"
fi

BT_DIR="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION"
if [ ! -x "$BT_DIR/apksigner" ]; then
  echo "apksigner not found in $BT_DIR"
  exit 1
fi

if [ -n "${GITHUB_ENV:-}" ]; then
  {
    echo "ANDROID_HOME=$SDK_ROOT"
    echo "ANDROID_SDK_ROOT=$SDK_ROOT"
    echo "BUILD_TOOLS_VERSION=$BUILD_TOOLS_VERSION"
  } >> "$GITHUB_ENV"
fi

echo "Using Android SDK: $SDK_ROOT"
echo "Using build-tools: $BUILD_TOOLS_VERSION"
echo "build_tools_version=$BUILD_TOOLS_VERSION"
