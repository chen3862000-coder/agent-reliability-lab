#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=scripts/local-lab-common.sh
. "$SCRIPT_DIR/local-lab-common.sh"

if pid_file_is_live "$BACKEND_PID_FILE" "arl-server"; then
  backend_state="running (PID $(pid_from_file "$BACKEND_PID_FILE"))"
else
  backend_state="stopped"
fi

if pid_file_is_live "$FRONTEND_PID_FILE" "vite"; then
  frontend_state="running (PID $(pid_from_file "$FRONTEND_PID_FILE"))"
else
  frontend_state="stopped"
fi

if curl -fsS --max-time 2 "$BACKEND_URL/health" >/dev/null 2>&1; then
  health_state="ok"
else
  health_state="unavailable"
fi

printf 'Local lab status\n'
printf '  Backend:  %s\n' "$backend_state"
printf '  Frontend: %s\n' "$frontend_state"
printf '  Health:   %s\n' "$health_state"
printf '  UI:       %s\n' "$FRONTEND_URL"
printf '  API:      %s\n' "$BACKEND_URL"
printf '  Logs:     %s and %s\n' "$BACKEND_LOG" "$FRONTEND_LOG"
