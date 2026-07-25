#!/usr/bin/env bash
set -euo pipefail

# Uploads the four release-signing secrets used by .github/workflows/release.yml to this repo
# via the gh CLI, so you don't have to click through the GitHub Settings UI four times.
# Usage: scripts/set-release-secrets.sh /path/to/myfeeds-release.jks

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /path/to/keystore.jks" >&2
  exit 1
fi

KEYSTORE_PATH="$1"
if [[ ! -f "$KEYSTORE_PATH" ]]; then
  echo "Keystore file not found: $KEYSTORE_PATH" >&2
  exit 1
fi

read -rp "Key alias [myfeeds]: " KEY_ALIAS
KEY_ALIAS="${KEY_ALIAS:-myfeeds}"
read -rsp "Keystore (store) password: " KEYSTORE_PASSWORD
echo
read -rsp "Key password (press enter to reuse the keystore password): " KEY_PASSWORD
echo
KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"

if [[ -z "$KEYSTORE_PASSWORD" ]]; then
  echo "Keystore password can't be empty." >&2
  exit 1
fi

base64 -w0 "$KEYSTORE_PATH" | gh secret set RELEASE_KEYSTORE_BASE64
gh secret set RELEASE_KEYSTORE_PASSWORD --body "$KEYSTORE_PASSWORD"
gh secret set RELEASE_KEY_ALIAS --body "$KEY_ALIAS"
gh secret set RELEASE_KEY_PASSWORD --body "$KEY_PASSWORD"

echo "Set RELEASE_KEYSTORE_BASE64, RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD."
