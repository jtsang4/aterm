#!/usr/bin/env bash
set -euo pipefail

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/init.sh"
TEST_TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEST_TMP_ROOT"' EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

make_executable() {
  local path="$1"
  cat > "$path"
  chmod +x "$path"
}

create_toolchain_stubs() {
  local root="$1"
  mkdir -p \
    "$root/jdk/bin" \
    "$root/sdk/cmdline-tools/latest/bin"

  make_executable "$root/jdk/bin/java" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF

  make_executable "$root/sdk/cmdline-tools/latest/bin/sdkmanager" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
echo "sdkmanager:$*" >> "${TEST_CALLS_LOG:?}"
if [[ "$*" == *"--licenses"* ]]; then
  exit 0
fi

mkdir -p "$ANDROID_SDK_ROOT/platform-tools" "$ANDROID_SDK_ROOT/emulator"
cat > "$ANDROID_SDK_ROOT/platform-tools/adb" <<'INNER'
#!/usr/bin/env bash
exit 0
INNER
chmod +x "$ANDROID_SDK_ROOT/platform-tools/adb"

cat > "$ANDROID_SDK_ROOT/emulator/emulator" <<'INNER'
#!/usr/bin/env bash
exit 0
INNER
chmod +x "$ANDROID_SDK_ROOT/emulator/emulator"

mkdir -p "$ANDROID_SDK_ROOT/platforms/android-35"
touch \
  "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" \
  "$ANDROID_SDK_ROOT/platforms/android-35/source.properties"

mkdir -p "$ANDROID_SDK_ROOT/build-tools/35.0.0"
cat > "$ANDROID_SDK_ROOT/build-tools/35.0.0/aapt2" <<'INNER'
#!/usr/bin/env bash
exit 0
INNER
chmod +x "$ANDROID_SDK_ROOT/build-tools/35.0.0/aapt2"
touch "$ANDROID_SDK_ROOT/build-tools/35.0.0/source.properties"

mkdir -p "$ANDROID_SDK_ROOT/system-images/android-35/google_apis/x86_64"
touch \
  "$ANDROID_SDK_ROOT/system-images/android-35/google_apis/x86_64/package.xml" \
  "$ANDROID_SDK_ROOT/system-images/android-35/google_apis/x86_64/source.properties" \
  "$ANDROID_SDK_ROOT/system-images/android-35/google_apis/x86_64/system.img" \
  "$ANDROID_SDK_ROOT/system-images/android-35/google_apis/x86_64/ramdisk.img"
EOF

  make_executable "$root/sdk/cmdline-tools/latest/bin/avdmanager" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
echo "avdmanager:$*" >> "${TEST_CALLS_LOG:?}"

name=""
while (($#)); do
  case "$1" in
    -n)
      name="$2"
      shift 2
      ;;
    *)
      shift
      ;;
  esac
done

[ -n "$name" ] || exit 1

avd_dir="$ANDROID_AVD_HOME/$name.avd"
mkdir -p "$avd_dir/data"
cat > "$ANDROID_AVD_HOME/$name.ini" <<INNER
avd.ini.encoding=UTF-8
path=$avd_dir
path.rel=avd/$name.avd
target=android-35
INNER

cat > "$avd_dir/config.ini" <<'INNER'
avd.ini.encoding = UTF-8
image.sysdir.1 = system-images/android-35/google_apis/x86_64/
INNER
EOF
}

create_sdk_payload() {
  local sdk_root="$1"
  mkdir -p "$sdk_root/platform-tools" "$sdk_root/emulator"
  make_executable "$sdk_root/platform-tools/adb" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  make_executable "$sdk_root/emulator/emulator" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF

  mkdir -p "$sdk_root/platforms/android-35"
  touch \
    "$sdk_root/platforms/android-35/android.jar" \
    "$sdk_root/platforms/android-35/source.properties"

  mkdir -p "$sdk_root/build-tools/35.0.0"
  make_executable "$sdk_root/build-tools/35.0.0/aapt2" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  touch "$sdk_root/build-tools/35.0.0/source.properties"

  mkdir -p "$sdk_root/system-images/android-35/google_apis/x86_64"
  touch \
    "$sdk_root/system-images/android-35/google_apis/x86_64/package.xml" \
    "$sdk_root/system-images/android-35/google_apis/x86_64/source.properties" \
    "$sdk_root/system-images/android-35/google_apis/x86_64/system.img" \
    "$sdk_root/system-images/android-35/google_apis/x86_64/ramdisk.img"
}

create_avd_payload() {
  local avd_home="$1"
  local name="$2"
  local avd_dir="$avd_home/$name.avd"
  mkdir -p "$avd_dir/data"
  cat > "$avd_home/$name.ini" <<EOF
avd.ini.encoding=UTF-8
path=$avd_dir
path.rel=avd/$name.avd
target=android-35
EOF
  cat > "$avd_dir/config.ini" <<'EOF'
avd.ini.encoding = UTF-8
image.sysdir.1 = system-images/android-35/google_apis/x86_64/
EOF
}

run_init() {
  local root="$1"
  shift
  env \
    ATERM_REPO_ROOT="$root/repo" \
    ATERM_JDK_DIR="$root/jdk" \
    ATERM_ANDROID_SDK_ROOT="$root/sdk" \
    ATERM_ANDROID_PREFS_ROOT="$root/android-home" \
    ATERM_ANDROID_AVD_HOME="$root/android-home/avd" \
    TEST_CALLS_LOG="$root/calls.log" \
    bash "$SCRIPT_PATH" "$@"
}

assert_run_fails() {
  local root="$1"
  shift
  if run_init "$root" "$@"; then
    fail "Expected command to fail: $*"
  fi
}

new_fake_install() {
  local name="$1"
  local root="$TEST_TMP_ROOT/$name"
  mkdir -p "$root/repo" "$root/android-home/avd"
  : > "$root/calls.log"
  create_toolchain_stubs "$root"
  echo "$root"
}

test_check_fails_for_missing_sdk_package() {
  local root
  root="$(new_fake_install missing-sdk)"
  create_avd_payload "$root/android-home/avd" "atermApi35"
  assert_run_fails "$root" --check
}

test_check_fails_for_missing_avd_contents() {
  local root
  root="$(new_fake_install missing-avd)"
  create_sdk_payload "$root/sdk"
  cat > "$root/android-home/avd/atermApi35.ini" <<EOF
avd.ini.encoding=UTF-8
path=$root/android-home/avd/atermApi35.avd
path.rel=avd/atermApi35.avd
target=android-35
EOF
  assert_run_fails "$root" --check
}

test_bootstrap_repairs_partial_state() {
  local root
  root="$(new_fake_install repair)"
  mkdir -p "$root/sdk/platform-tools" "$root/sdk/emulator"
  make_executable "$root/sdk/platform-tools/adb" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  make_executable "$root/sdk/emulator/emulator" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  cat > "$root/android-home/avd/atermApi35.ini" <<EOF
avd.ini.encoding=UTF-8
path=$root/android-home/avd/atermApi35.avd
path.rel=avd/atermApi35.avd
target=android-35
EOF

  assert_run_fails "$root" --check
  run_init "$root"
  run_init "$root" --check

  [ -f "$root/sdk/platforms/android-35/android.jar" ] || fail "android.jar was not restored"
  [ -x "$root/sdk/build-tools/35.0.0/aapt2" ] || fail "aapt2 was not restored"
  [ -f "$root/sdk/system-images/android-35/google_apis/x86_64/system.img" ] || fail "system image was not restored"
  [ -f "$root/android-home/avd/atermApi35.avd/config.ini" ] || fail "AVD config was not recreated"

  grep -q '^sdkmanager:' "$root/calls.log" || fail "sdkmanager was not used to repair packages"
  grep -q '^avdmanager:' "$root/calls.log" || fail "avdmanager was not used to repair the AVD"
}

test_healthy_rerun_is_noop() {
  local root
  root="$(new_fake_install healthy)"
  create_sdk_payload "$root/sdk"
  create_avd_payload "$root/android-home/avd" "atermApi35"

  : > "$root/calls.log"
  run_init "$root"
  run_init "$root" --check

  if [ -s "$root/calls.log" ]; then
    fail "Healthy bootstrap unexpectedly invoked installers: $(cat "$root/calls.log")"
  fi
}

test_check_fails_for_missing_sdk_package
test_check_fails_for_missing_avd_contents
test_bootstrap_repairs_partial_state
test_healthy_rerun_is_noop

echo "bootstrap-check recovery smoke tests passed"
