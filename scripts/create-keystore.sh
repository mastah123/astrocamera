#!/usr/bin/env bash
# Creates a release keystore and prints its Base64 value for GitHub Secrets.
# Usage: bash scripts/create-keystore.sh [output.jks] [alias]
set -euo pipefail

OUT="${1:-release.jks}"
ALIAS="${2:-astrocamera}"

keytool -genkeypair -v \
  -keystore "$OUT" -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 10000

echo
echo "Secrets to add (Settings > Secrets and variables > Actions):"
echo "  KEYSTORE_PASSWORD  = the keystore password you just entered"
echo "  KEY_ALIAS          = $ALIAS"
echo "  KEY_PASSWORD       = the key password you just entered"
echo "  KEYSTORE_BASE64    = (value below)"
echo
if base64 -w0 "$OUT" >/dev/null 2>&1; then base64 -w0 "$OUT"; else base64 "$OUT" | tr -d '\n'; fi
echo
echo
echo "Back up $OUT somewhere safe. It cannot be recovered, and it must not be committed."
