#!/usr/bin/env bash
# Re-sign a release APK with v1+v2+v3 (matches CI ilharp/sign-android-release behavior).
# Local Gradle builds often omit v1 JAR signing; some phones reject those APKs.
set -euo pipefail

APK_PATH="${1:-}"
if [ "$APK_PATH" = "--check" ]; then
  APK_PATH=""
fi
if [ -n "$APK_PATH" ] && [ ! -f "$APK_PATH" ]; then
  echo "Usage: $0 <path-to-release.apk>"
  echo "       $0 --check"
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL_PROPS="$ROOT_DIR/local.properties"
KEYSTORE="$ROOT_DIR/app/keystore/release.keystore"

read_prop() {
  local key="$1"
  if [ -f "$LOCAL_PROPS" ]; then
    local value
    value="$(grep -E "^${key}=" "$LOCAL_PROPS" | tail -n 1 | cut -d= -f2- | tr -d '\r')"
    if [ -n "$value" ]; then
      printf '%s' "$value"
      return 0
    fi
  fi
  if [ -n "${!key:-}" ]; then
    printf '%s' "${!key}"
    return 0
  fi
  return 1
}

STORE_PASS="$(read_prop STORE_PASSWORD || read_prop KEYSTORE_PASSWORD || true)"
KEY_ALIAS="$(read_prop KEY_ALIAS || true)"
KEY_PASS="$(read_prop KEY_PASSWORD || true)"

if [ ! -f "$KEYSTORE" ]; then
  echo "Missing keystore: $KEYSTORE"
  exit 1
fi
if [ -z "$STORE_PASS" ] || [ -z "$KEY_ALIAS" ] || [ -z "$KEY_PASS" ]; then
  echo "Missing signing credentials. Set STORE_PASSWORD (or KEYSTORE_PASSWORD), KEY_ALIAS, and KEY_PASSWORD in local.properties or the environment."
  exit 1
fi

resolve_android_sdk() {
  if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
    printf '%s' "$ANDROID_HOME"
    return 0
  fi
  if [ -f "$LOCAL_PROPS" ]; then
    local sdk_dir
    sdk_dir="$(grep -E '^sdk\.dir=' "$LOCAL_PROPS" | cut -d= -f2- | sed 's/\\:/:/g' | tr -d '\r')"
    if [ -n "$sdk_dir" ] && [ -d "$sdk_dir" ]; then
      printf '%s' "$sdk_dir"
      return 0
    fi
  fi
  if [ -d "$HOME/Library/Android/sdk" ]; then
    printf '%s' "$HOME/Library/Android/sdk"
    return 0
  fi
  return 1
}

SDK_ROOT="$(resolve_android_sdk)" || {
  echo "Android SDK not found. Set sdk.dir in local.properties or ANDROID_HOME."
  exit 1
}

APKSIGNER=""
for dir in "$SDK_ROOT/build-tools"/*; do
  [ -x "$dir/apksigner" ] || continue
  APKSIGNER="$dir/apksigner"
done
if [ -z "$APKSIGNER" ]; then
  echo "apksigner not found under $SDK_ROOT/build-tools"
  exit 1
fi

if ! keytool -list -keystore "$KEYSTORE" -storepass "$STORE_PASS" -alias "$KEY_ALIAS" >/dev/null 2>&1; then
  echo "Keystore validation failed. Check STORE_PASSWORD/KEYSTORE_PASSWORD and KEY_ALIAS in local.properties."
  exit 1
fi

if [ -z "$APK_PATH" ]; then
  echo "Release signing credentials are valid (alias: $KEY_ALIAS)."
  exit 0
fi

ZIPALIGN=""
for dir in "$SDK_ROOT/build-tools"/*; do
  [ -x "$dir/zipalign" ] || continue
  ZIPALIGN="$dir/zipalign"
done
if [ -z "$ZIPALIGN" ]; then
  echo "zipalign not found under $SDK_ROOT/build-tools"
  exit 1
fi

# Match CI (ilharp/sign-android-release): zipalign to a temp file, then sign to output.
# In-place apksigner on a Gradle-signed APK leaves broken v1 JAR signatures.
ALIGNED_APK="${APK_PATH%.apk}-aligned-temp.apk"
SIGNED_APK="${APK_PATH%.apk}-signed-temp.apk"
cleanup() {
  rm -f "$ALIGNED_APK" "$SIGNED_APK"
}
trap cleanup EXIT

echo "Aligning $(basename "$APK_PATH")..."
"$ZIPALIGN" -f -p 4 "$APK_PATH" "$ALIGNED_APK"

echo "Re-signing $(basename "$APK_PATH") (zipalign + apksigner, same as CI)..."
"$APKSIGNER" sign \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --min-sdk-version 26 \
  --ks "$KEYSTORE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:${STORE_PASS}" \
  --key-pass "pass:${KEY_PASS}" \
  --out "$SIGNED_APK" \
  "$ALIGNED_APK"

mv -f "$SIGNED_APK" "$APK_PATH"

echo "Verifying signature..."
"$APKSIGNER" verify --verbose "$APK_PATH"
echo "Done: $APK_PATH"
