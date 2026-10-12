#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
if [[ $# -lt 2 ]]; then
  printf '%s\n' 'Usage: bash compose.sh sqlite|mysql|sqlite-tls|mysql-tls <compose command> [args...]' >&2
  exit 2
fi
mode="$1"
shift
args=(--env-file .env -f compose.yaml)
case "$mode" in
  sqlite) ;;
  mysql) args+=(-f compose.mysql.yaml) ;;
  sqlite-tls) args+=(-f compose.tls.yaml) ;;
  mysql-tls) args+=(-f compose.mysql.yaml -f compose.tls.yaml) ;;
  *) printf 'Unknown mode: %s\n' "$mode" >&2; exit 2 ;;
esac
if [[ ! -f .env ]]; then
  printf '%s\n' 'Missing .env. Run bash init-env.sh and set your model parameters.' >&2
  exit 1
fi
exec docker compose "${args[@]}" "$@"
