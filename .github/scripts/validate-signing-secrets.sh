#!/usr/bin/env bash
set -euo pipefail

KEYSTORE_B64="${KEYSTORE_B64:-}"
KEY_ALIAS="${KEY_ALIAS:-}"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-}"
KEY_PASSWORD="${KEY_PASSWORD:-}"

for name in KEYSTORE_B64 KEY_ALIAS KEYSTORE_PASSWORD KEY_PASSWORD; do
  if [ -z "${!name:-}" ]; then
    echo "Missing required signing secret: ${name}"
    echo "Configure repository secrets: KEYSTORE (base64), KEY_ALIAS, KEYSTORE_PASSWORD, KEY_PASSWORD"
    exit 1
  fi
done

TMP_KEYSTORE="$(mktemp)"
trap 'rm -f "$TMP_KEYSTORE"' EXIT

if ! printf '%s' "$KEYSTORE_B64" | tr -d '[:space:]' | base64 -d > "$TMP_KEYSTORE" 2>/dev/null; then
  echo "KEYSTORE secret is not valid base64."
  echo "Encode your keystore with: base64 -w0 release.keystore (Linux) or base64 -i release.keystore (macOS)"
  exit 1
fi

if [ ! -s "$TMP_KEYSTORE" ]; then
  echo "KEYSTORE decoded to an empty file. Check the base64 value in repository secrets."
  exit 1
fi

if ! keytool -list -keystore "$TMP_KEYSTORE" -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS" >/dev/null 2>&1; then
  echo "Keystore validation failed. KEYSTORE, KEYSTORE_PASSWORD, or KEY_ALIAS does not match."
  exit 1
fi

echo "Release signing secrets validated."
