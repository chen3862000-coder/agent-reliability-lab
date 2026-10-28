#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=scripts/local-lab-common.sh
. "$SCRIPT_DIR/local-lab-common.sh"

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

require_command java
require_command node
require_command npm
require_command curl

if [ -n "${ARL_PORT:-}" ] && [ "$ARL_PORT" != "$BACKEND_PORT" ]; then
  die "the native cluster uses API port $BACKEND_PORT; unset ARL_PORT or change the Vite proxy"
fi

if [ ! -f "$VITE_JS" ]; then
  die "frontend dependencies are missing; run: make frontend-install"
fi

mkdir -p "$RUN_DIR"
if [ -n "${ARL_DB_PATH:-}" ]; then
  mkdir -p "$(dirname -- "$ARL_DB_PATH")"
fi

if pid_file_is_live "$BACKEND_PID_FILE" "arl-server"; then
  die "backend is already running; run: make lab-down"
fi
if pid_file_is_live "$FRONTEND_PID_FILE" "vite"; then
  die "frontend is already running; run: make lab-down"
fi
if port_in_use "$BACKEND_PORT"; then
  die "port $BACKEND_PORT is already in use by another process"
fi
if port_in_use "$FRONTEND_PORT"; then
  die "port $FRONTEND_PORT is already in use by another process"
fi

started_backend=0
started_frontend=0
success=0
cleanup_failed_start() {
  status=$?
  if [ "$success" -eq 0 ]; then
    if [ "$started_frontend" -eq 1 ]; then
      stop_pid_file "$FRONTEND_PID_FILE" "vite" "frontend" || true
    fi
    if [ "$started_backend" -eq 1 ]; then
      stop_pid_file "$BACKEND_PID_FILE" "arl-server" "backend" || true
    fi
    printf 'Startup failed; see %s and %s.\n' "$BACKEND_LOG" "$FRONTEND_LOG" >&2
  fi
  exit "$status"
}
trap cleanup_failed_start EXIT

printf 'Building backend...\n'
cd "$ROOT_DIR"
./mvnw -q -DskipTests -pl arl-server -am package

printf 'Starting backend...\n'
nohup java -jar "$JAR" >"$BACKEND_LOG" 2>&1 &
backend_pid=$!
printf '%s\n' "$backend_pid" >"$BACKEND_PID_FILE"
started_backend=1

printf 'Starting frontend...\n'
cd "$ROOT_DIR/frontend"
nohup node "$VITE_JS" --host 127.0.0.1 --port "$FRONTEND_PORT" --strictPort \
  >"$FRONTEND_LOG" 2>&1 &
frontend_pid=$!
printf '%s\n' "$frontend_pid" >"$FRONTEND_PID_FILE"
started_frontend=1
cd "$ROOT_DIR"

if ! wait_for_url "$BACKEND_URL/health" "$backend_pid" 60; then
  tail -n 20 "$BACKEND_LOG" >&2 || true
  die "backend did not become healthy"
fi
if ! wait_for_url "$FRONTEND_URL/" "$frontend_pid" 30; then
  tail -n 20 "$FRONTEND_LOG" >&2 || true
  die "frontend did not become ready"
fi

success=1
printf '\nLab cluster is running.\n'
printf '  UI:   %s\n' "$FRONTEND_URL"
printf '  API:  %s\n' "$BACKEND_URL"
printf '  Logs: %s and %s\n' "$BACKEND_LOG" "$FRONTEND_LOG"
printf '  Stop: make lab-down\n'
