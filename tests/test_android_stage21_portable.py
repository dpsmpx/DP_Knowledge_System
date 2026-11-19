"""Stage 21 safety wiring checks; not a substitute for Android unit/device QA."""
from pathlib import Path
from xml.etree import ElementTree as ET
import unittest


SOURCE = Path(__file__).resolve().parents[1] / "third_party/markor/app/src/main"
JAVAS = SOURCE / "java/net/gsantner/markor/knowledge"
ANDROID = "{http://schemas.android.com/apk/res/android}"


class Stage21PortableWiring(unittest.TestCase):
    def test_ui_has_distinct_export_inspect_and_clean_restore(self):
        root = ET.parse(SOURCE / "res/layout/knowledge__activity.xml").getroot()
        ids = [e.get(ANDROID + "id") for e in root.iter()]
        hub = (JAVAS / "KnowledgeHubActivity.java").read_text()
        for name in ("knowledge_portable_export", "knowledge_portable_inspect",
                     "knowledge_portable_restore"):
            self.assertEqual(1, ids.count("@+id/" + name))
            self.assertIn("R.id." + name, hub)
        for locale in ("values", "values-ru"):
            strings = {e.attrib["name"]: e.text for e in ET.parse(
                SOURCE / "res" / locale / "knowledge_strings.xml").getroot()}
            for name in ("knowledge_portable_privacy", "knowledge_portable_failed",
                         "knowledge_portable_restore_warning",
                         "knowledge_portable_restore_preview"):
                self.assertTrue(strings[name])

    def test_encryption_verification_and_recovery_never_overwrite(self):
        cipher = (JAVAS / "KnowledgePortableCipher.java").read_text()
        archive = (JAVAS / "KnowledgePortableArchive.java").read_text()
        restore = (JAVAS / "KnowledgePortableRestore.java").read_text()
        hub = (JAVAS / "KnowledgeHubActivity.java").read_text()
        for required in ("DPKSB002", "900_000", "cipher.doFinal(payload)",
                         "if (encrypted.read() != -1)"):
            self.assertIn(required, cipher)
        for required in ("MessageDigest.getInstance(\"SHA-256\")",
                         "validName(name)", "!folded.add(",
                         "MAX_ARCHIVE_BYTES"):
            self.assertIn(required, archive)
        for required in ("KnowledgePortableArchive.verify(verifiedZip",
                         "new File(parent, \"DPKS_Restored_\"",
                         "journalCommit.commit", "!temp.renameTo(restored)"):
            self.assertIn(required, restore)
        self.assertIn("KnowledgePythonBridge.journalRestorePreview", hub)
        self.assertIn("KnowledgePythonBridge.journalRestoreCommit", hub)
        self.assertIn("_appSettings.setNotebookDirectory(restored)", hub)
        self.assertNotIn("new FileOutputStream(currentLibrary)", restore)


if __name__ == "__main__":
    unittest.main()
