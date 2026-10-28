#!/usr/bin/env bash

# Shared state and process helpers for the native local lab scripts.
# This file is sourced by start/stop/status scripts; it does not start anything itself.

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
RUN_DIR="${ARL_RUN_DIR:-$ROOT_DIR/.arl-run}"

BACKEND_PID_FILE="$RUN_DIR/backend.pid"
FRONTEND_PID_FILE="$RUN_DIR/frontend.pid"
BACKEND_LOG="$RUN_DIR/backend.log"
FRONTEND_LOG="$RUN_DIR/frontend.log"

BACKEND_PORT=8080
FRONTEND_PORT=5173
BACKEND_URL="http://127.0.0.1:$BACKEND_PORT"
FRONTEND_URL="http://127.0.0.1:$FRONTEND_PORT"
JAR="$ROOT_DIR/arl-server/target/arl-server-0.1.0-SNAPSHOT.jar"
VITE_JS="$ROOT_DIR/frontend/node_modules/vite/bin/vite.js"

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

pid_from_file() {
  if [ -f "$1" ]; then
    cat "$1" 2>/dev/null || true
  fi
}

pid_alive() {
  pid=$1
  case "$pid" in
    ''|*[!0-9]*) return 1 ;;
  esac
  kill -0 "$pid" 2>/dev/null
}

command_matches() {
  pid=$1
  pattern=$2
  command=$(ps -p "$pid" -o command= 2>/dev/null || true)
  case "$command" in
    *"$pattern"*) return 0 ;;
    *) return 1 ;;
  esac
}

pid_file_is_live() {
  pid_file=$1
  pattern=$2
  pid=$(pid_from_file "$pid_file")
  if pid_alive "$pid" && command_matches "$pid" "$pattern"; then
    return 0
  fi
  if [ -f "$pid_file" ]; then
    rm -f "$pid_file"
  fi
  return 1
}

port_in_use() {
  port=$1
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
  elif command -v nc >/dev/null 2>&1; then
    nc -z 127.0.0.1 "$port" >/dev/null 2>&1
  else
    return 1
  fi
}

wait_for_url() {
  url=$1
  pid=$2
  attempts=$3
  count=0
  while [ "$count" -lt "$attempts" ]; do
    if curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then
      return 0
    fi
    if ! pid_alive "$pid"; then
      return 1
    fi
    count=$((count + 1))
    sleep 1
  done
  return 1
}

stop_pid_file() {
  pid_file=$1
  pattern=$2
  label=$3
  pid=$(pid_from_file "$pid_file")
  if [ -z "$pid" ]; then
    return 0
  fi
  if ! pid_alive "$pid"; then
    rm -f "$pid_file"
    return 0
  fi
  if ! command_matches "$pid" "$pattern"; then
    printf 'warning: PID %s does not match %s; leaving it alone and removing stale PID file\n' \
      "$pid" "$label" >&2
    rm -f "$pid_file"
    return 0
  fi
  printf 'Stopping %s (PID %s)...\n' "$label" "$pid"
  kill "$pid" 2>/dev/null || true
  count=0
  while pid_alive "$pid" && [ "$count" -lt 50 ]; do
    count=$((count + 1))
    sleep 0.1
  done
  if pid_alive "$pid"; then
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$pid_file"
}
