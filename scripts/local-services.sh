#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.local.yml"
COMMAND="${1:-up}"

cd "${ROOT_DIR}"

case "${COMMAND}" in
  up)
    shift || true
    docker compose -f "${COMPOSE_FILE}" up -d "$@"
    ;;
  down)
    shift || true
    docker compose -f "${COMPOSE_FILE}" down "$@"
    ;;
  logs)
    shift || true
    docker compose -f "${COMPOSE_FILE}" logs -f "$@"
    ;;
  ps)
    shift || true
    docker compose -f "${COMPOSE_FILE}" ps "$@"
    ;;
  restart)
    shift || true
    docker compose -f "${COMPOSE_FILE}" restart "$@"
    ;;
  *)
    echo "Usage: $0 {up|down|logs|ps|restart} [service...]" >&2
    exit 2
    ;;
esac
