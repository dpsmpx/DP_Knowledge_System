"""Static safeguards for manual, local E5 tokenizer reference comparison.

Android/JVM tests still validate runtime behavior; this verifies the user-facing
workflow is explicitly opt-in and has both English and Russian copy.
"""

from pathlib import Path
import unittest
from xml.etree import ElementTree as ET


ROOT = Path(__file__).resolve().parents[1] / "third_party/markor/app/src/main"
BASE = ROOT / "java/net/gsantner/markor/knowledge"
IDS = {
    "knowledge_models_e5_qa_compare",
    "knowledge_models_e5_qa_compare_prompt",
    "knowledge_models_e5_qa_reference_tokenizer",
    "knowledge_models_e5_qa_reference_fingerprint",
    "knowledge_models_e5_qa_reference_invalid",
    "knowledge_models_e5_qa_compare_action",
    "knowledge_models_e5_qa_reference_match",
    "knowledge_models_e5_qa_reference_different_tokenizer",
    "knowledge_models_e5_qa_reference_different_ids",
}


class E5ReferenceUiContract(unittest.TestCase):
    def test_full_e5_onnx_host_json_import_is_opt_in_bounded_and_localized(self):
        names = {
            "knowledge_models_e5_graph_host_json_hint",
            "knowledge_models_e5_graph_host_json_invalid",
            "knowledge_models_e5_graph_reference_different_counts",
        }
        for locale in ("values", "values-ru"):
            strings = ET.parse(ROOT / f"res/{locale}/knowledge_strings.xml").getroot()
            by_name = {element.get("name"): element.text or ""
                       for element in strings.findall("string")}
            for field in names:
                self.assertTrue(by_name[field].strip(), (locale, field))
        helper = (BASE / "KnowledgeE5OnnxReferenceComparison.java").read_text()
        activity = (BASE / "KnowledgeHubActivity.java").read_text()
        self.assertIn("static HostMetadata parseHostMetadata(", helper)
        self.assertIn("MAX_HOST_JSON_CHARS = 2048", helper)
        self.assertIn("requireUniqueLiteralKeys(json)", helper)
        self.assertIn("compareHostMetadata(", helper)
        self.assertIn("hostJson.getText().toString().trim()", activity)
        self.assertIn("KnowledgeE5OnnxReferenceComparison.parseHostMetadata(", activity)
        self.assertIn("new InputFilter.LengthFilter(", activity)
        self.assertNotIn("runnable", helper)

    def test_manual_e5_graph_fingerprint_is_localized_and_copyable(self):
        for locale in ("values", "values-ru"):
            strings = ET.parse(ROOT / f"res/{locale}/knowledge_strings.xml").getroot()
            by_name = {element.get("name"): element.text or ""
                       for element in strings.findall("string")}
            self.assertIn("%1$s", by_name["knowledge_models_e5_graph_fingerprint"])
            self.assertIn("DPKS-E5-IO-v1", by_name["knowledge_models_e5_graph_fingerprint"])
        activity = (BASE / "KnowledgeHubActivity.java").read_text()
        probe = (BASE / "KnowledgeE5OnnxMetadataProbe.java").read_text()
        self.assertIn("result.descriptorSha256", activity)
        self.assertIn('ClipData.newPlainText("DPKS E5 I/O fingerprint"', activity)
        self.assertIn("KnowledgeE5OnnxSignature.descriptorSha256(in, out)", probe)

    def test_localized_ui_has_explicit_manual_reference_and_no_readiness_claim(self):
        for locale in ("values", "values-ru"):
            strings = ET.parse(ROOT / f"res/{locale}/knowledge_strings.xml").getroot()
            by_name = {element.get("name"): element.text or ""
                       for element in strings.findall("string")}
            self.assertTrue(IDS.issubset(by_name))
            for field in IDS:
                self.assertTrue(by_name[field].strip(), (locale, field))
            self.assertIn("model_sha256", by_name[
                "knowledge_models_e5_qa_reference_tokenizer"])
            self.assertIn("golden_fingerprint_sha256", by_name[
                "knowledge_models_e5_qa_reference_fingerprint"])

    def test_comparison_checks_two_sha_values_and_does_not_mark_model_runnable(self):
        helper = (BASE / "KnowledgeE5GoldenQa.java").read_text()
        activity = (BASE / "KnowledgeHubActivity.java").read_text()
        self.assertIn("ReferenceVerdict compareReference(", helper)
        self.assertIn("!validSha256(referenceTokenizerSha)", helper)
        self.assertIn("!validSha256(referenceFingerprintSha)", helper)
        self.assertIn("MessageDigest.isEqual(", helper)
        self.assertIn("showE5GoldenReferenceComparison(report)", activity)
        self.assertIn("KnowledgeE5GoldenQa.compareReference(", activity)
        self.assertIn("modelStatus.setText(messageId)", activity)
        self.assertNotIn("runnable", helper)
        self.assertNotIn("KnowledgeModelPackageStore", helper.split(
            "static ReferenceVerdict compareReference(")[1].split(
            "static boolean validSha256(")[0])


if __name__ == "__main__":
    unittest.main()
