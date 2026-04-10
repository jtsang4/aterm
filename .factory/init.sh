#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JDK_DIR="/root/.local/share/aterm-jdk-17"
ANDROID_SDK_ROOT="/root/Android/Sdk"
CMDLINE_TOOLS_DIR="$ANDROID_SDK_ROOT/cmdline-tools/latest"
AVD_NAME="atermApi35"
JDK_URL="${JDK_URL:-https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk}"
ANDROID_CMDLINE_TOOLS_URL="${ANDROID_CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-platforms;android-35}"
ANDROID_BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-build-tools;35.0.0}"
ANDROID_SYSTEM_IMAGE="${ANDROID_SYSTEM_IMAGE:-system-images;android-35;google_apis;x86_64}"

export JAVA_HOME="$JDK_DIR"
export ANDROID_SDK_ROOT
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$PATH"

need_cmd() {
  command -v "$1" >/dev/null 2>&1
}

download_file() {
  local url="$1"
  local destination="$2"
  curl --fail --show-error --location --retry 5 --retry-delay 2 "$url" -o "$destination"
}

ensure_jdk() {
  if [ -x "$JDK_DIR/bin/java" ]; then
    return
  fi

  mkdir -p "$(dirname "$JDK_DIR")"
  local tmpdir
  tmpdir="$(mktemp -d)"
  trap 'rm -rf "$tmpdir"' RETURN

  download_file "$JDK_URL" "$tmpdir/jdk.tar.gz"
  mkdir -p "$tmpdir/unpack"
  tar -xzf "$tmpdir/jdk.tar.gz" -C "$tmpdir/unpack"

  local extracted
  extracted="$(python3 - <<'PY' "$tmpdir/unpack"
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
children = [path for path in root.iterdir() if path.is_dir()]
if len(children) != 1:
    raise SystemExit(f"Expected exactly one extracted JDK directory in {root}, found {len(children)}")
print(children[0])
PY
)"

  rm -rf "$JDK_DIR"
  mv "$extracted" "$JDK_DIR"
  rm -rf "$tmpdir"
  trap - RETURN
}

ensure_cmdline_tools() {
  if [ -x "$CMDLINE_TOOLS_DIR/bin/sdkmanager" ]; then
    return
  fi

  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
  local tmpdir
  tmpdir="$(mktemp -d)"
  trap 'rm -rf "$tmpdir"' RETURN

  download_file "$ANDROID_CMDLINE_TOOLS_URL" "$tmpdir/cmdline-tools.zip"
  unzip -q "$tmpdir/cmdline-tools.zip" -d "$tmpdir/unpack"

  rm -rf "$CMDLINE_TOOLS_DIR"
  mkdir -p "$CMDLINE_TOOLS_DIR"
  mv "$tmpdir/unpack/cmdline-tools/"* "$CMDLINE_TOOLS_DIR"/
  rm -rf "$tmpdir"
  trap - RETURN
}

accept_android_licenses() {
  (
    set +o pipefail
    yes | sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null
  )
}

ensure_sdk_packages() {
  mkdir -p /root/.android
  touch /root/.android/repositories.cfg

  accept_android_licenses
  sdkmanager \
    --sdk_root="$ANDROID_SDK_ROOT" \
    "platform-tools" \
    "emulator" \
    "$ANDROID_PLATFORM" \
    "$ANDROID_BUILD_TOOLS" \
    "$ANDROID_SYSTEM_IMAGE"
}

ensure_avd() {
  if [ -f "/root/.android/avd/$AVD_NAME.ini" ]; then
    return
  fi

  mkdir -p /root/.android/avd
  echo no | avdmanager create avd \
    -n "$AVD_NAME" \
    -k "$ANDROID_SYSTEM_IMAGE" \
    --device "pixel_8" >/dev/null
}

ensure_local_properties() {
  if [ -f "$REPO_ROOT/settings.gradle.kts" ] || [ -f "$REPO_ROOT/settings.gradle" ] || [ -f "$REPO_ROOT/build.gradle.kts" ] || [ -f "$REPO_ROOT/build.gradle" ]; then
    cat > "$REPO_ROOT/local.properties" <<EOF
sdk.dir=$ANDROID_SDK_ROOT
EOF
  fi
}

check_only() {
  [ -x "$JDK_DIR/bin/java" ]
  [ -x "$CMDLINE_TOOLS_DIR/bin/sdkmanager" ]
  [ -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]
  [ -x "$ANDROID_SDK_ROOT/emulator/emulator" ]
  [ -f "/root/.android/avd/$AVD_NAME.ini" ]
  need_cmd java
  need_cmd sdkmanager
  need_cmd avdmanager

  if [ -f "$REPO_ROOT/local.properties" ]; then
    grep -qx "sdk.dir=$ANDROID_SDK_ROOT" "$REPO_ROOT/local.properties"
  fi
}

ensure_jdk
ensure_cmdline_tools

if [ "${1:-}" = "--check" ]; then
  check_only
  exit 0
fi

ensure_sdk_packages
ensure_avd
ensure_local_properties
check_only
