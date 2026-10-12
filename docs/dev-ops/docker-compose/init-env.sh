#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
if [[ -e .env ]]; then
  printf '%s\n' '.env already exists; keep its keys. Edit it instead of regenerating.' >&2
  exit 1
fi
command -v openssl >/dev/null || { printf '%s\n' 'Install openssl first.' >&2; exit 1; }
umask 077
temporary_env="$(mktemp .env.tmp.XXXXXX)"
trap 'rm -f -- "$temporary_env"' EXIT
while IFS= read -r line || [[ -n "$line" ]]; do
  case "$line" in
    SSH_DEFAULT_SECRET_KEY=|LOCAL_FILE_STORAGE_SIGNING_SECRET=|MYSQL_PASSWORD=|MYSQL_ROOT_PASSWORD=)
      printf '%s=%s\n' "${line%%=*}" "$(openssl rand -hex 32)" ;;
    *) printf '%s\n' "$line" ;;
  esac
done < .env.example > "$temporary_env"
# A hard link prevents overwriting .env if another process created it meanwhile.
ln -- "$temporary_env" .env
printf '%s\n' 'Created private .env. Fill AI_MODEL_BASE_URL, AI_MODEL_API_KEY and AI_MODEL_NAME before starting.'
