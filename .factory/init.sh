#!/bin/sh
[ -n "${BASH_VERSION:-}" ] || exec bash "$0" "$@"
set -euo pipefail

REPO_ROOT="${ATERM_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
JDK_DIR="${ATERM_JDK_DIR:-/root/.local/share/aterm-jdk-17}"
ANDROID_SDK_ROOT="${ATERM_ANDROID_SDK_ROOT:-/root/Android/Sdk}"
ANDROID_PREFS_ROOT="${ATERM_ANDROID_PREFS_ROOT:-/root/.android}"
ANDROID_AVD_HOME="${ATERM_ANDROID_AVD_HOME:-$ANDROID_PREFS_ROOT/avd}"
CMDLINE_TOOLS_DIR="${ATERM_CMDLINE_TOOLS_DIR:-$ANDROID_SDK_ROOT/cmdline-tools/latest}"
AVD_NAME="${ATERM_AVD_NAME:-atermApi35}"
JDK_URL="${JDK_URL:-https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk}"
ANDROID_CMDLINE_TOOLS_URL="${ANDROID_CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-platforms;android-35}"
ANDROID_BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-build-tools;35.0.0}"
ANDROID_SYSTEM_IMAGE="${ANDROID_SYSTEM_IMAGE:-system-images;android-35;google_apis;x86_64}"
ANDROID_PLATFORM_DIR="$ANDROID_SDK_ROOT/${ANDROID_PLATFORM//;/\/}"
ANDROID_BUILD_TOOLS_DIR="$ANDROID_SDK_ROOT/${ANDROID_BUILD_TOOLS//;/\/}"
ANDROID_SYSTEM_IMAGE_DIR="$ANDROID_SDK_ROOT/${ANDROID_SYSTEM_IMAGE//;/\/}"
AVD_SYSTEM_IMAGE_RELATIVE="${ANDROID_SYSTEM_IMAGE//;/\/}/"
AVD_PLATFORM_TARGET="${ANDROID_PLATFORM#platforms;}"
AVD_DIR="$ANDROID_AVD_HOME/$AVD_NAME.avd"
AVD_INI="$ANDROID_AVD_HOME/$AVD_NAME.ini"

export JAVA_HOME="$JDK_DIR"
export ANDROID_SDK_ROOT
export ANDROID_AVD_HOME
export ANDROID_USER_HOME="$ANDROID_PREFS_ROOT"
export PATH="$JAVA_HOME/bin:$CMDLINE_TOOLS_DIR/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$PATH"

need_cmd() {
  command -v "$1" >/dev/null 2>&1
}

require_host_prerequisites() {
  local required=(curl tar unzip python3 yes openssl)
  local missing=()
  local cmd
  for cmd in "${required[@]}"; do
    if ! need_cmd "$cmd"; then
      missing+=("$cmd")
    fi
  done

  if [ ${#missing[@]} -gt 0 ]; then
    printf 'Missing required host tools: %s\n' "${missing[*]}" >&2
    exit 1
  fi
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
  if [ -x "$CMDLINE_TOOLS_DIR/bin/sdkmanager" ] && [ -x "$CMDLINE_TOOLS_DIR/bin/avdmanager" ]; then
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

sdk_packages_are_healthy() {
  [ -x "$ANDROID_SDK_ROOT/platform-tools/adb" ] &&
    [ -x "$ANDROID_SDK_ROOT/emulator/emulator" ] &&
    [ -f "$ANDROID_PLATFORM_DIR/android.jar" ] &&
    [ -f "$ANDROID_PLATFORM_DIR/source.properties" ] &&
    [ -x "$ANDROID_BUILD_TOOLS_DIR/aapt2" ] &&
    [ -f "$ANDROID_BUILD_TOOLS_DIR/source.properties" ] &&
    [ -f "$ANDROID_SYSTEM_IMAGE_DIR/package.xml" ] &&
    [ -f "$ANDROID_SYSTEM_IMAGE_DIR/source.properties" ] &&
    [ -f "$ANDROID_SYSTEM_IMAGE_DIR/system.img" ] &&
    [ -f "$ANDROID_SYSTEM_IMAGE_DIR/ramdisk.img" ]
}

ensure_sdk_packages() {
  mkdir -p "$ANDROID_PREFS_ROOT" "$ANDROID_AVD_HOME"
  touch "$ANDROID_PREFS_ROOT/repositories.cfg"

  if sdk_packages_are_healthy; then
    return
  fi

  accept_android_licenses
  sdkmanager \
    --sdk_root="$ANDROID_SDK_ROOT" \
    "platform-tools" \
    "emulator" \
    "$ANDROID_PLATFORM" \
    "$ANDROID_BUILD_TOOLS" \
    "$ANDROID_SYSTEM_IMAGE"
}

avd_is_healthy() {
  [ -f "$AVD_INI" ] &&
    [ -d "$AVD_DIR" ] &&
    [ -f "$AVD_DIR/config.ini" ] &&
    [ -d "$AVD_DIR/data" ] &&
    grep -Fqx "path=$AVD_DIR" "$AVD_INI" &&
    grep -Fqx "target=$AVD_PLATFORM_TARGET" "$AVD_INI" &&
    grep -Fqx "image.sysdir.1 = $AVD_SYSTEM_IMAGE_RELATIVE" "$AVD_DIR/config.ini"
}

reset_avd() {
  rm -f "$AVD_INI"
  rm -rf "$AVD_DIR"
}

ensure_avd() {
  if avd_is_healthy; then
    return
  fi

  reset_avd
  mkdir -p "$ANDROID_AVD_HOME"
  echo no | avdmanager create avd \
    -n "$AVD_NAME" \
    -k "$ANDROID_SYSTEM_IMAGE" \
    --device "pixel_8" >/dev/null

  avd_is_healthy
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
  [ -x "$CMDLINE_TOOLS_DIR/bin/avdmanager" ]
  need_cmd java
  need_cmd sdkmanager
  need_cmd avdmanager
  sdk_packages_are_healthy
  avd_is_healthy

  if [ -f "$REPO_ROOT/local.properties" ]; then
    grep -qx "sdk.dir=$ANDROID_SDK_ROOT" "$REPO_ROOT/local.properties"
  fi
}

main() {
  require_host_prerequisites
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
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
