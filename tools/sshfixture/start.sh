#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
RUNTIME_DIR="${ATERM_SSH_FIXTURE_RUNTIME_DIR:-$ROOT_DIR/tools/sshfixture/runtime}"
PID_FILE="$RUNTIME_DIR/server.pid"
LOG_FILE="$RUNTIME_DIR/server.log"

mkdir -p "$RUNTIME_DIR"

if [ -f "$PID_FILE" ]; then
  PID="$(cat "$PID_FILE")"
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    echo "SSH fixture already running on pid $PID"
    exit 0
  fi
  rm -f "$PID_FILE"
fi

ATERM_SSH_FIXTURE_RUNTIME_DIR="$RUNTIME_DIR" \
JAVA_HOME=/root/.local/share/aterm-jdk-17 \
ANDROID_SDK_ROOT=/root/Android/Sdk \
PATH=/root/.local/share/aterm-jdk-17/bin:/root/Android/Sdk/cmdline-tools/latest/bin:/root/Android/Sdk/platform-tools:/root/Android/Sdk/emulator:$PATH \
"$ROOT_DIR/gradlew" :tools:sshfixture:run --args="start" >"$LOG_FILE" 2>&1 &
PID=$!
echo "$PID" > "$PID_FILE"

for _ in $(seq 1 60); do
  if [ -f "$RUNTIME_DIR/fixture-metadata.env" ] && nc -z 127.0.0.1 3122 2>/dev/null; then
    echo "SSH fixture ready (pid $PID)"
    exit 0
  fi
  if ! kill -0 "$PID" 2>/dev/null; then
    echo "SSH fixture exited unexpectedly"
    cat "$LOG_FILE"
    exit 1
  fi
  sleep 1
done

echo "SSH fixture failed to become ready"
cat "$LOG_FILE"
exit 1
