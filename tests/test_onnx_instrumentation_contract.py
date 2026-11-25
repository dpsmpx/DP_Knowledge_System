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
        self.assertIn("api: 35", action)
        self.assertIn("api: 28", action)
        self.assertNotIn("api: 24", action)
        self.assertIn("runner: ubuntu-latest", action)
        self.assertIn("runner: ubuntu-22.04", action)
        self.assertIn("runs-on: ${{ matrix.runner }}", action)
        self.assertIn("timeout-minutes: 23", action)
        self.assertIn("max-parallel: 1", action)
        self.assertIn("api-level: ${{ matrix.api }}", action)
        self.assertIn("name: onnx-emulator-api${{ matrix.api }}-instrumented-results", action)
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
                     "cancellingPendingBindSilencesCallbacks",
                     "debugStallWatchdogKillsOnlyWorker",
                     "cancellingDebugStallKillsWorkerBeforeWatchdog",
                     "fdSessionRejectsWrongShaBeforeNativeOpen",
                     "fdSessionRejectsTruncatedAndTrailingBytes",
                     "cancellingBlockedFdReadKillsOnlyWorker"):
            self.assertIn(case, suite)
        self.assertIn("KnowledgeOnnxSmoke.identityModel()", suite)
        self.assertNotIn("readForCpuSession", suite)
        self.assertNotIn("modelPackages", suite)

    def test_synthetic_stall_is_debug_only_and_binder_observed(self):
        service = (APP / "src/flavorAtest/java/net/gsantner/markor/knowledge/"
                   "KnowledgeOnnxIsolatedService.java").read_text()
        suite = (APP / "src/androidTest/java/net/gsantner/markor/knowledge/"
                 "KnowledgeOnnxIsolatedLifecycleInstrumentedTest.java").read_text()
        self.assertIn("if (!BuildConfig.DEBUG || message.replyTo == null", service)
        self.assertIn("DEBUG_WATCHDOG_MILLIS", service)
        self.assertIn("new CountDownLatch(1).await()", service)
        self.assertIn("binder.linkToDeath", suite)
        self.assertIn("KnowledgeOnnxIsolatedProtocol.CANCEL_AND_EXIT", suite)

    def test_fd_failure_cases_use_generated_bytes_not_imported_files(self):
        suite = (APP / "src/androidTest/java/net/gsantner/markor/knowledge/"
                 "KnowledgeOnnxIsolatedLifecycleInstrumentedTest.java").read_text()
        self.assertIn("ParcelFileDescriptor.createPipe()", suite)
        self.assertIn("KnowledgeOnnxSessionPipe.sha256Hex(graph)", suite)
        self.assertIn("KnowledgeOnnxIsolatedProtocol.RESULT_SESSION_FAILED", suite)
        self.assertIn("connection.died.await(5, TimeUnit.SECONDS)", suite)
        self.assertNotIn("KnowledgeModelPackageStore", suite)

    def test_older_android_process_identity_fallback_remains(self):
        identity = (APP / "src/main/java/net/gsantner/markor/knowledge/"
                    "KnowledgeOnnxProcessIdentity.java").read_text()
        self.assertIn("Build.VERSION.SDK_INT >= 28", identity)
        self.assertIn("Build.VERSION.SDK_INT >= 34", identity)
        self.assertIn("Process.isIsolated()", identity)
        self.assertIn("Process.isIsolatedUid(Process.myUid())", identity)
        self.assertIn('new FileInputStream("/proc/self/cmdline")', identity)


if __name__ == "__main__":
    unittest.main()
