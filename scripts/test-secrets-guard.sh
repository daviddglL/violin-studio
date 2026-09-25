#!/usr/bin/env bash
# Prueba secrets-guard.sh en repos temporales.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
guard="$here/secrets-guard.sh"
fail=0

new_repo() {
  local dir
  dir="$(mktemp -d)"
  git -C "$dir" init -q
  git -C "$dir" config user.email t@t
  git -C "$dir" config user.name t
  echo "$dir"
}

expect() { # expect <exit esperado> <descripción> <ficheros...>
  local want="$1" desc="$2"; shift 2
  local repo; repo="$(new_repo)"
  for f in "$@"; do mkdir -p "$repo/$(dirname "$f")"; echo x > "$repo/$f"; git -C "$repo" add -f "$f"; done
  set +e; bash "$guard" "$repo" >/dev/null 2>&1; local got=$?; set -e
  if [ "$got" -ne "$want" ]; then echo "FALLO: $desc (exit $got, esperado $want)"; fail=1; else echo "ok: $desc"; fi
  rm -rf "$repo"
}

expect 0 "repo limpio" README.md .env.example
expect 1 "google-services.json" app/src/dev/google-services.json
expect 1 "keystore .jks" keystore/upload.jks
expect 1 "keystore .keystore" debug.keystore
expect 1 ".env" .env
expect 1 ".env.production" functions/.env.violin-app-795ee
exit $fail
