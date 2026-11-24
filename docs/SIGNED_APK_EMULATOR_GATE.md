# Release gate: signed DPKS APK install and cold launch

Status: **implemented in CI, first emulator execution pending**. The gate is
tested on a clean Android API 35 x86_64 image, not an owner's phone.

After the Python suite, Android JVM tests, release APK build, package-ID
validation, `zipalign`, `apksigner verify`, and SHA-256 generation, the
`knowledge-signed-prerelease.yml` workflow invokes
`scripts/ci-android-signed-smoke.sh` on the **same signed APK path** it would
later upload to GitHub Releases.

The shell script creates a disposable emulator, waits for boot completion,
executes `adb install -r`, verifies the exact `com.dpsmpx.dpks` package name,
launches through the normal launcher intent and checks that the process
remains alive. It has a bounded boot timeout, cleans up the emulator and
**fails closed** on missing KVM/SDK/APK, installation or launch failure.
Failed gates stop the release before publication; no debug APK is substituted.

The emulator is empty: no user account, vault, API token, backup destination,
secret or model provider is configured. Signing credentials remain injected
only into the earlier signing step and are not passed to the smoke script.

**Does not verify:** actual touch navigation, note storage permission,
SAF/document providers, TalkBack, font scaling, rotation, OEM battery limits,
keystore migration, backup restore, real source index, or physical device ABI.
Those remain in device QA. CI-02 can be marked *automated component passed*
only after the first real signed workflow succeeds with this gate enabled.
