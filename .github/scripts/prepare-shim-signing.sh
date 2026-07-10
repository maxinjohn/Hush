#!/usr/bin/env bash
set -euo pipefail

KEYSTORE_B64="${KEYSTORE_B64:-}"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-}"
KEY_ALIAS="${KEY_ALIAS:-}"
KEY_PASSWORD="${KEY_PASSWORD:-}"

for name in KEYSTORE_B64 KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
  if [ -z "${!name:-}" ]; then
    echo "Missing required signing secret: ${name}"
    exit 1
  fi
done

KEYSTORE_PATH="${RUNNER_TEMP:-/tmp}/hush-waze-shim.keystore"
printf '%s' "$KEYSTORE_B64" | tr -d '[:space:]' | base64 -d > "$KEYSTORE_PATH"
chmod 600 "$KEYSTORE_PATH"

if [ -z "${GITHUB_ENV:-}" ]; then
  echo "GITHUB_ENV is required to configure Gradle signing."
  exit 1
fi

{
  echo "HUSH_SHIM_KEYSTORE=$KEYSTORE_PATH"
  echo "HUSH_SHIM_STORE_PASSWORD=$KEYSTORE_PASSWORD"
  echo "HUSH_SHIM_KEY_ALIAS=$KEY_ALIAS"
  echo "HUSH_SHIM_KEY_PASSWORD=$KEY_PASSWORD"
} >> "$GITHUB_ENV"
