#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=scripts/local-lab-common.sh
. "$SCRIPT_DIR/local-lab-common.sh"

clean=0
case "${1:-}" in
  "")
    ;;
  --clean)
    clean=1
    ;;
  *)
    printf 'usage: %s [--clean]\n' "$0" >&2
    exit 2
    ;;
esac

frontend_running=0
backend_running=0
if pid_file_is_live "$FRONTEND_PID_FILE" "vite"; then
  frontend_running=1
fi
if pid_file_is_live "$BACKEND_PID_FILE" "arl-server"; then
  backend_running=1
fi

if [ "$frontend_running" -eq 0 ] && [ "$backend_running" -eq 0 ]; then
  printf 'Local lab is not running.\n'
else
  stop_pid_file "$FRONTEND_PID_FILE" "vite" "frontend"
  stop_pid_file "$BACKEND_PID_FILE" "arl-server" "backend"
  printf 'Local lab stopped.\n'
fi

if [ "$clean" -eq 1 ]; then
  rm -rf "$RUN_DIR"
  printf 'Removed local run logs and PID files.\n'
fi
