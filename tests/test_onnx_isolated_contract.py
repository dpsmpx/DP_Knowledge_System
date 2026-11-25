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
        self.assertIn(".endsWith(\":dpks_onnx_cpu\")", service)
        self.assertIn("KnowledgeOnnxCpuBackend.smoke()", service)
        self.assertIn("KnowledgeOnnxSessionPipe.readVerified(stream, size, sha)", service)
        self.assertIn("KnowledgeOnnxCpuBackend.inspect(model)", service)
        self.assertIn("Arrays.fill(model, (byte) 0)", service)
        self.assertIn("mainHandler.postDelayed(clientDeadline", client)
        self.assertIn("response.arg1 != activeRequestId", client)
        self.assertIn("CANCEL_AND_EXIT", client)
        self.assertIn("onnxSmokeClient.start(", activity)
        self.assertNotIn("final KnowledgeOnnxSmoke.Report report = KnowledgeOnnxCpuBackend.smoke()", activity)

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


if __name__ == "__main__":
    unittest.main()
