#!/usr/bin/env bash
# Escribe app/src/{dev,prod}/google-services.json desde GS_DEV y GS_PROD (base64). Si un secreto llega vacío (las PRs de
# Dependabot no reciben los secretos de Actions) escribe uno de PRUEBA: compila y basta contra los emuladores, pero no
# habla con ningún proyecto real. El release no usa el de prueba: falla si faltan los secretos.
# Uso: GS_DEV=... GS_PROD=... ci-google-services.sh [--require]
set -euo pipefail
require="${1:-}"

placeholder() {
  local package="$1"
  cat <<JSON
{
  "project_info": {
    "project_number": "000000000000",
    "project_id": "violin-app-dev-f0b55",
    "storage_bucket": "violin-app-dev-f0b55.firebasestorage.app"
  },
  "client": [
    {
      "client_info": {
        "mobilesdk_app_id": "1:000000000000:android:0000000000000000000000",
        "android_client_info": { "package_name": "$package" }
      },
      "oauth_client": [],
      "api_key": [{ "current_key": "placeholder-not-a-real-key" }],
      "services": { "appinvite_service": { "other_platform_oauth_client": [] } }
    }
  ],
  "configuration_version": "1"
}
JSON
}

write() {
  local value="$1" flavor="$2" package="$3" out="app/src/$2/google-services.json"
  mkdir -p "app/src/$flavor"
  if [ -n "$value" ]; then
    printf '%s' "$value" | base64 -d > "$out"
  elif [ "$require" = "--require" ]; then
    echo "::error::Falta el secreto de google-services ($flavor)."
    exit 1
  else
    echo "::notice title=google-services de prueba::Sin secreto para $flavor (p. ej. PR de Dependabot); se usa un fichero de prueba."
    placeholder "$package" > "$out"
  fi
}

write "${GS_DEV:-}" dev com.violinstudio.dev
write "${GS_PROD:-}" prod com.violinstudio
