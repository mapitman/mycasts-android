#!/usr/bin/env bash
set -euo pipefail

# Generates a release signing keystore for use with .github/workflows/release.yml and
# scripts/set-release-secrets.sh. Run this once; keep the resulting file private and never commit
# it (already covered by .gitignore's *.jks/*.keystore).
# Usage: scripts/generate-release-keystore.sh [output-path] [alias]

OUTPUT_PATH="${1:-myfeeds-release.jks}"
KEY_ALIAS="${2:-myfeeds}"

if [[ -e "$OUTPUT_PATH" ]]; then
  echo "$OUTPUT_PATH already exists -- refusing to overwrite." >&2
  exit 1
fi

read -rp "Name for the certificate (e.g. your name or org, used only as the key's DN, not shown to users): " CERT_NAME
read -rsp "Keystore (store) password: " KEYSTORE_PASSWORD
echo
read -rsp "Confirm keystore password: " KEYSTORE_PASSWORD_CONFIRM
echo
if [[ "$KEYSTORE_PASSWORD" != "$KEYSTORE_PASSWORD_CONFIRM" ]]; then
  echo "Passwords didn't match." >&2
  exit 1
fi
read -rsp "Key password (press enter to reuse the keystore password): " KEY_PASSWORD
echo
KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"

keytool -genkeypair -v \
  -keystore "$OUTPUT_PATH" \
  -alias "$KEY_ALIAS" \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storetype PKCS12 \
  -storepass "$KEYSTORE_PASSWORD" \
  -keypass "$KEY_PASSWORD" \
  -dname "CN=$CERT_NAME"

echo
echo "Keystore written to $OUTPUT_PATH (alias: $KEY_ALIAS)."
echo "Next: scripts/set-release-secrets.sh $OUTPUT_PATH"
