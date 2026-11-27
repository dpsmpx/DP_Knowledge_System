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
