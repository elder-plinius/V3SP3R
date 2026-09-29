#!/usr/bin/env bash
# Push Flippy Android signing secrets to GitHub repo secrets (requires gh auth).
# Usage: ./flippy-secrets-setup.sh [owner/repo]
set -euo pipefail

REPO="${1:-dimase260/Flipper-ai-brain}"
PROPS="$HOME/Flippy-by-DiMase/local.properties"
KEYSTORE="$HOME/keystore/flippy-release.jks"

get_prop() { grep -E "^$1=" "$PROPS" | head -1 | cut -d= -f2-; }

STORE_PASS="$(get_prop RELEASE_STORE_PASSWORD)"
ALIAS="$(get_prop RELEASE_KEY_ALIAS)"
KEY_PASS="$(get_prop RELEASE_KEY_PASSWORD)"
B64="$(base64 -w0 "$KEYSTORE")"

echo "Setting secrets on $REPO ..."
gh secret set ANDROID_KEYSTORE_BASE64 --repo "$REPO" --body "$B64"
gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$REPO" --body "$STORE_PASS"
gh secret set ANDROID_KEY_ALIAS        --repo "$REPO" --body "$ALIAS"
gh secret set ANDROID_KEY_PASSWORD     --repo "$REPO" --body "$KEY_PASS"
echo "Done. Verify: gh secret list --repo $REPO"
