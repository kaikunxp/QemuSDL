#!/usr/bin/env bash
# Generates a persistent CI/debug signing keystore at .github/signing/ci-debug.keystore
# (or downloads the reference keystore with --from-intent-launcher).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="$REPO_ROOT/.github/signing"
OUT_FILE="$OUT_DIR/ci-debug.keystore"

mkdir -p "$OUT_DIR"

if [[ "${1:-}" == "--from-intent-launcher" ]]; then
  echo "Downloading ci-debug.keystore from kaikunxp/Intent-launcher..."
  curl -fsSL "https://raw.githubusercontent.com/kaikunxp/Intent-launcher/main/.github/signing/ci-debug.keystore" -o "$OUT_FILE"
else
  if [[ -f "$OUT_FILE" ]]; then
    echo "Keystore already exists at $OUT_FILE"
  else
    echo "Generating new persistent QemuSDL CI signing keystore at $OUT_FILE..."
    keytool -genkeypair \
      -keystore "$OUT_FILE" \
      -storetype PKCS12 \
      -storepass android \
      -alias androiddebugkey \
      -keypass android \
      -keyalg RSA \
      -keysize 2048 \
      -validity 10000 \
      -dname "CN=QemuSDL CI Debug,O=QemuSDL,C=US"
  fi
fi

keytool -list -keystore "$OUT_FILE" -storepass android | grep -E "Alias name:|Certificate fingerprint"
echo "Ready! Commit .github/signing/ci-debug.keystore to keep CI APK signatures consistent."
