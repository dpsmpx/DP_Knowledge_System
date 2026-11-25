"""Synthetic Android emulator QA wiring; not a replacement for running the emulator."""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "third_party/markor/app"


class IsolatedOnnxInstrumentationContract(unittest.TestCase):
    def test_ci_runs_only_dpks_synthetic_android_instrumentation(self):
        action = (ROOT / ".github/workflows/android-onnx-isolated-emulator.yml").read_text()
        self.assertIn("workflow_dispatch:", action)
        self.assertIn("pull_request:", action)
        self.assertIn("reactivecircus/android-emulator-runner@v2", action)
        self.assertIn("api-level: 35", action)
        self.assertIn("arch: x86_64", action)
        self.assertIn("connectedFlavorAtestDebugAndroidTest", action)
        self.assertIn("bash -lc 'cd third_party/markor && chmod +x gradlew &&", action)
        self.assertNotIn("connectedFlavorDefaultDebugAndroidTest", action)

    def test_app_runner_is_only_test_dependency(self):
        gradle = (APP / "build.gradle").read_text()
        self.assertIn('testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"',
                      gradle)
        self.assertIn("androidTestImplementation 'androidx.test:runner:1.7.0'", gradle)
        self.assertIn("androidTestImplementation 'androidx.test.ext:junit:1.3.0'", gradle)

    def test_synthetic_lifecycle_cases_do_not_open_imported_models(self):
        suite = (APP / "src/androidTest/java/net/gsantner/markor/knowledge/"
                 "KnowledgeOnnxIsolatedLifecycleInstrumentedTest.java").read_text()
        for case in ("manifestWorkerIsPrivateAndIsolated",
                     "generatedIdentitySmokeCanRunTwiceAcrossWorkerLifecycle",
                     "syntheticFdSessionUsesWorkerAndVerifiesOneInputOutput",
                     "cancellingPendingBindSilencesCallbacks"):
            self.assertIn(case, suite)
        self.assertIn("KnowledgeOnnxSmoke.identityModel()", suite)
        self.assertNotIn("readForCpuSession", suite)
        self.assertNotIn("modelPackages", suite)


if __name__ == "__main__":
    unittest.main()
