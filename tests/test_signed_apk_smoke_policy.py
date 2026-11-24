"""Static guardrails for the signed-apk-on-emulator release gate."""
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/knowledge-signed-prerelease.yml"
SCRIPT = ROOT / "scripts/ci-android-signed-smoke.sh"


class SignedApkSmokePolicyTests(unittest.TestCase):
    def test_same_signed_artifact_installed_before_publish(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        sign = workflow.index("- name: Align, sign and independently verify preview APK")
        smoke = workflow.index("- name: Install and cold-launch the exact signed APK")
        publish = workflow.index("- name: Publish signed DP Knowledge APK")
        self.assertLess(sign, smoke)
        self.assertLess(smoke, publish)
        self.assertIn('bash scripts/ci-android-signed-smoke.sh "$APK_PATH"', workflow)
        self.assertIn('printf \'APK_PATH=%s\\n\' "$apk" >> "$GITHUB_ENV"', workflow)

    def test_emulator_smoke_is_fail_closed_and_does_not_use_debug_apk(self):
        script = SCRIPT.read_text(encoding="utf-8")
        for required in ("set -euo pipefail", "adb install -r \"$apk_path\"",
                         "sys.boot_completed", "adb shell pidof", "package:$package_name",
                         "::error::", "trap cleanup EXIT"):
            self.assertIn(required, script)
        self.assertNotIn("assembleDebug", script)
        syntax = subprocess.run(["bash", "-n", str(SCRIPT)], capture_output=True)
        self.assertEqual(0, syntax.returncode, syntax.stderr.decode())
        with tempfile.TemporaryDirectory() as tmp:
            missing = subprocess.run(["bash", str(SCRIPT), str(Path(tmp) / "missing.apk")],
                                     capture_output=True, text=True)
            self.assertNotEqual(0, missing.returncode)
            self.assertIn("Signed APK missing or empty", missing.stdout)


if __name__ == "__main__":
    unittest.main()
