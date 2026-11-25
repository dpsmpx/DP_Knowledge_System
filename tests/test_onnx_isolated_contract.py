"""Static safeguards for the DPKS-only synthetic ONNX isolated worker.

These checks do not replace merged-manifest, JVM or real-device testing.
"""
from pathlib import Path
import unittest
from xml.etree import ElementTree as ET


APP = Path(__file__).resolve().parents[1] / "third_party/markor/app/src"
NS = "{http://schemas.android.com/apk/res/android}"


class OnnxIsolatedWorkerContract(unittest.TestCase):
    def test_dpks_only_service_has_isolated_uid_and_no_export(self):
        manifest = ET.parse(APP / "flavorAtest/AndroidManifest.xml").getroot()
        services = list(manifest.iter("service"))
        self.assertEqual(1, len(services))
        self.assertEqual(".knowledge.KnowledgeOnnxIsolatedService",
                         services[0].get(NS + "name"))
        self.assertEqual("false", services[0].get(NS + "exported"))
        self.assertEqual("true", services[0].get(NS + "isolatedProcess"))
        self.assertEqual(":dpks_onnx_cpu", services[0].get(NS + "process"))
        for flavor in ("flavorDefault", "flavorGplay"):
            plain_manifest = APP / flavor / "AndroidManifest.xml"
            if plain_manifest.is_file():
                self.assertFalse(any("KnowledgeOnnxIsolatedService" in
                                     item.get(NS + "name", "")
                                     for item in ET.parse(plain_manifest).iter("service")))
            client = (APP / flavor / "java/net/gsantner/markor/knowledge/"
                      "KnowledgeOnnxIsolatedClient.java").read_text()
            self.assertIn("return false;", client)

    def test_ipc_is_scalar_only_with_cancellation_and_both_deadlines(self):
        base = "java/net/gsantner/markor/knowledge/"
        protocol = (APP / "main" / base / "KnowledgeOnnxIsolatedProtocol.java").read_text()
        service = (APP / "flavorAtest" / base / "KnowledgeOnnxIsolatedService.java").read_text()
        client = (APP / "flavorAtest" / base / "KnowledgeOnnxIsolatedClient.java").read_text()
        activity = (APP / "main" / base / "KnowledgeHubActivity.java").read_text()
        self.assertIn("WATCHDOG_MILLIS = 30_000L", protocol)
        self.assertIn("handler.postDelayed(deadline", service)
        self.assertIn("Process.killProcess(Process.myPid())", service)
        self.assertIn("KnowledgeOnnxProcessIdentity.isWorker()", service)
        self.assertIn("KnowledgeOnnxCpuBackend.smoke()", service)
        self.assertIn("KnowledgeOnnxSessionPipe.readVerified(stream, size, sha)", service)
        self.assertIn("KnowledgeOnnxCpuBackend.inspect(model)", service)
        self.assertIn("Arrays.fill(model, (byte) 0)", service)
        self.assertIn("mainHandler.postDelayed(clientDeadline", client)
        self.assertIn("response.arg1 != activeRequestId", client)
        self.assertIn("CANCEL_AND_EXIT", client)
        self.assertIn("onnxSmokeClient.start(", activity)
        self.assertNotIn("final KnowledgeOnnxSmoke.Report report = KnowledgeOnnxCpuBackend.smoke()", activity)

    def test_isolated_process_identity_uses_no_forbidden_activity_manager_call(self):
        folder = APP / "main/java/net/gsantner/markor"
        application = (folder / "ApplicationObject.java").read_text()
        identity = (folder / "knowledge/KnowledgeOnnxProcessIdentity.java").read_text()
        service = (APP / "flavorAtest/java/net/gsantner/markor/knowledge/"
                   "KnowledgeOnnxIsolatedService.java").read_text()
        self.assertNotIn("getMyMemoryState", application)
        self.assertNotIn("getMyMemoryState", identity)
        self.assertNotIn("getMyMemoryState", service)
        self.assertIn("Application.getProcessName()", identity)
        self.assertIn("Build.VERSION.SDK_INT >= 34", identity)
        self.assertIn("Process.isIsolated()", identity)
        self.assertIn("Process.isIsolatedUid(Process.myUid())", identity)
        self.assertIn('new FileInputStream("/proc/self/cmdline")', identity)
        self.assertIn('process.startsWith(PACKAGE_PREFIX + ":")', identity)

    def test_real_session_uses_fd_not_model_path_or_binder_payload(self):
        base = "java/net/gsantner/markor/knowledge/"
        client = (APP / "flavorAtest" / base / "KnowledgeOnnxIsolatedSessionClient.java").read_text()
        activity = (APP / "main" / base / "KnowledgeHubActivity.java").read_text()
        service = (APP / "flavorAtest" / base / "KnowledgeOnnxIsolatedService.java").read_text()
        self.assertIn("ParcelFileDescriptor.createPipe()", client)
        self.assertIn("data.putParcelable(KnowledgeOnnxIsolatedProtocol.PIPE_FD, readPipe)", client)
        self.assertIn("Math.min(64 * 1024, bytes.length - offset)", client)
        self.assertIn("KnowledgeOnnxSessionPipe.validSha256(expectedSha256)", client)
        self.assertIn("onnxSessionClient.start(bytes, entry.sha256", activity)
        self.assertNotIn("KnowledgeOnnxCpuBackend.inspect(bytes)", activity)
        self.assertIn("KnowledgeOnnxSessionPipe.readVerified(stream, size, sha)", service)
        for flavor in ("flavorDefault", "flavorGplay"):
            shim = (APP / flavor / base / "KnowledgeOnnxIsolatedSessionClient.java").read_text()
            self.assertIn("return false;", shim)

    def test_manual_fd_smoke_reuses_generated_graph_and_never_selects_user_model(self):
        base = "java/net/gsantner/markor/knowledge/"
        dialog = (APP / "main" / base / "KnowledgeModelSettingsDialog.java").read_text()
        activity = (APP / "main" / base / "KnowledgeHubActivity.java").read_text()
        pipe = (APP / "main" / base / "KnowledgeOnnxSessionPipe.java").read_text()
        self.assertIn(".setTitle(R.string.knowledge_models_onnx_fd_smoke)", dialog)
        self.assertIn(".setMessage(R.string.knowledge_models_onnx_fd_warning)", dialog)
        self.assertIn("(dialog, which) -> fdSmokeTester.run()", dialog)
        self.assertIn("final byte[] fixture = KnowledgeOnnxSmoke.identityModel()", activity)
        self.assertIn("KnowledgeOnnxSessionPipe.sha256Hex(fixture)", activity)
        self.assertIn("onnxSessionClient.start(fixture, digest", activity)
        self.assertIn("Arrays.fill(fixture, (byte) 0)", activity)
        self.assertIn("static String sha256Hex(final byte[] bytes)", pipe)


if __name__ == "__main__":
    unittest.main()
