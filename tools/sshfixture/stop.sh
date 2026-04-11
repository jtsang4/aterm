#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
RUNTIME_DIR="${ATERM_SSH_FIXTURE_RUNTIME_DIR:-$ROOT_DIR/tools/sshfixture/runtime}"
PID_FILE="$RUNTIME_DIR/server.pid"

if [ ! -f "$PID_FILE" ]; then
  exit 0
fi

PID="$(cat "$PID_FILE")"
rm -f "$PID_FILE"

if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
  kill "$PID"
  for _ in $(seq 1 30); do
    if ! kill -0 "$PID" 2>/dev/null; then
      exit 0
    fi
    sleep 1
  done
  kill -9 "$PID" 2>/dev/null || true
fi
