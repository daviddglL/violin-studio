#!/usr/bin/env bash
# Falla si git rastrea ficheros con secretos. Uso: secrets-guard.sh [directorio-del-repo]
set -euo pipefail
repo="${1:-.}"
found="$(git -C "$repo" ls-files \
  | grep -E '(^|/)google-services\.json$|\.jks$|\.keystore$|(^|/)\.env(rc)?($|\.)|(^|/)\.secret(\.|$)' \
  | grep -vE '(^|/)\.env\.example$' || true)"
if [ -n "$found" ]; then
  echo "Secretos rastreados por git (quítalos con git rm --cached):"
  echo "$found"
  exit 1
fi
echo "secrets-guard: ok"
