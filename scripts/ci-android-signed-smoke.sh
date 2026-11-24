#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Install and cold-launch the EXACT release-signed APK on an empty CI emulator.
# No personal vault, tokens, backup, network model, or account is provisioned.
set -euo pipefail

apk_path="${1:?usage: ci-android-signed-smoke.sh signed.apk}"
package_name='com.dpsmpx.dpks'
if [[ ! -s "$apk_path" || ! -f "$apk_path" ]]; then
  echo '::error::Signed APK missing or empty'
  exit 1
fi
if [[ -z "${ANDROID_SDK_ROOT:-}" ]]; then
  echo '::error::ANDROID_SDK_ROOT must point to the CI SDK'
  exit 1
fi
if [[ ! -e /dev/kvm ]]; then
  echo '::error::Android emulator KVM unavailable; cannot claim install smoke PASS'
  exit 1
fi
if [[ ! -w /dev/kvm ]]; then
  sudo chmod 666 /dev/kvm
fi

sdkmanager 'emulator' 'system-images;android-35;google_apis;x86_64'
avd_name="dpks_smoke_${GITHUB_RUN_ID:-local}"
printf 'no\n' | avdmanager create avd --force --name "$avd_name" \
  --package 'system-images;android-35;google_apis;x86_64' > /dev/null

emulator_log="${RUNNER_TEMP:-/tmp}/dpks-android-emulator.log"
"$ANDROID_SDK_ROOT/emulator/emulator" -avd "$avd_name" -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -no-metrics \
  > "$emulator_log" 2>&1 &
emulator_pid=$!
smoke_passed=false
cleanup() {
  if [[ "$smoke_passed" != true && -f "$emulator_log" ]]; then
    # Empty CI emulator only: no user account, vault or secrets are present.
    # Print bounded engine diagnostics so failure is actionable next time.
    echo '::group::Android emulator startup diagnostics (last 60 lines)'
    tail -n 60 "$emulator_log"
    echo '::endgroup::'
  fi
  timeout 15 adb emu kill > /dev/null 2>&1 || true
  kill "$emulator_pid" 2> /dev/null || true
  wait "$emulator_pid" 2> /dev/null || true
}
trap cleanup EXIT

connected=false
for ((attempt=0; attempt<60; attempt++)); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    echo '::error::Android emulator process exited before adb connection'
    exit 1
  fi
  if timeout 6 adb devices 2>/dev/null | grep -Eq '^emulator-[0-9]+[[:space:]]+device$'; then
    connected=true
    break
  fi
  sleep 3
done
if [[ "$connected" != true ]]; then
  echo '::error::Android emulator did not appear in adb within the connection budget'
  exit 1
fi
ready=''
for ((attempt=0; attempt<100; attempt++)); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    echo '::error::Android emulator exited during boot'
    exit 1
  fi
  ready="$(timeout 8 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  [[ "$ready" == '1' ]] && break
  sleep 3
done
if [[ "$ready" != '1' ]]; then
  echo '::error::Android emulator boot timeout'
  exit 1
fi

# This is the same signed file that will be hashed and published, not a debug
# rebuild. Verification is intentionally fail-closed.
adb install -r "$apk_path"
if ! adb shell pm list packages "$package_name" | tr -d '\r' \
  | grep -Fxq "package:$package_name"; then
  echo '::error::DPKS package not installed'
  exit 1
fi
adb shell monkey -p "$package_name" -c android.intent.category.LAUNCHER 1 > /dev/null
sleep 7
if [[ -z "$(adb shell pidof "$package_name" 2>/dev/null | tr -d '\r' || true)" ]]; then
  echo '::error::Signed DPKS package did not remain running after cold launch'
  exit 1
fi
smoke_passed=true
echo 'PASS: release-signed com.dpsmpx.dpks installed and cold-launched on clean Android API 35 emulator.'
