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

    def test_pairing_is_manual_and_signed_snapshot_never_auto_merges(self):
        hub = (JAVAS / "KnowledgeHubActivity.java").read_text()
        peer = (JAVAS / "KnowledgePeerIdentity.java").read_text()
        archive = (JAVAS / "KnowledgePortableArchive.java").read_text()
        backup = (JAVAS / "KnowledgeDailyBackupWorker.java").read_text()
        root = ET.parse(SOURCE / "res/layout/knowledge__activity.xml").getroot()
        ids = [e.get(ANDROID + "id") for e in root.iter()]
        for item in ("knowledge_sync_my_device", "knowledge_sync_pair",
                     "knowledge_sync_trusted"):
            self.assertEqual(ids.count("@+id/" + item), 1)
            self.assertIn("R.id." + item, hub)
        for required in ("AndroidKeyStore", "secp256r1", "SHA256withECDSA",
                         "verificationCode(invitation)", "pairedFingerprints",
                         "static void revoke", "static Verification verifyJournal"):
            self.assertIn(required, peer)
        self.assertIn("KnowledgePeerIdentity.signJournal(journal)", hub)
        self.assertIn("KnowledgePeerIdentity.signJournal(journal)", backup)
        self.assertIn("KnowledgePortableRestore.verifyPeer(this, staged)", hub)
        self.assertIn('static final String PEER = "meta/peer.json"', archive)
        self.assertNotIn("journalRestoreCommit(this, workspacePath(), foreign)", hub)

    def test_foreign_journal_inbox_is_encrypted_and_never_replayed(self):
        hub = (JAVAS / "KnowledgeHubActivity.java").read_text()
        inbox = (JAVAS / "KnowledgePeerInbox.java").read_text()
        root = ET.parse(SOURCE / "res/layout/knowledge__activity.xml").getroot()
        ids = [e.get(ANDROID + "id") for e in root.iter()]
        self.assertEqual(ids.count("@+id/knowledge_sync_inbox"), 1)
        self.assertIn("R.id.knowledge_sync_inbox", hub)
        self.assertIn("KnowledgePeerInbox.save(", hub)
        self.assertIn("KnowledgePeerInbox.open(this, name)", hub)
        self.assertIn("KnowledgePortableRestore.peerProof(staged)", hub)
        for required in ("getNoBackupFilesDir()", "AndroidKeyStore",
                         "AES/GCM/NoPadding", "cipher.updateAAD(MAGIC)",
                         "KnowledgePeerIdentity.verifyJournal(", "if (!verified.paired)",
                         "MAX_RECORDS = 100", "return false; // Retry"):
            self.assertIn(required, inbox)
        self.assertNotIn("journalRestoreCommit", inbox)
        self.assertNotIn("FileOutputStream(currentLibrary)", inbox)


    def test_optional_model_settings_migrate_without_endpoints_or_secrets(self):
        hub = (JAVAS / "KnowledgeHubActivity.java").read_text()
        settings = (JAVAS / "KnowledgePortableSettings.java").read_text()
        worker = (JAVAS / "KnowledgeDailyBackupWorker.java").read_text()
        self.assertIn("migrateModels.setChecked(false)", hub)
        self.assertIn("KnowledgePortableSettings.read(staged)", hub)
        self.assertIn("KnowledgePortableSettings.apply(", hub)
        self.assertIn("KnowledgePortableSettings.snapshot(null)", worker)
        for required in ("knowledge_embedding_model", "knowledge_llm_model",
                         '"secrets_exported", false',
                         '"derived_indexes_exported", false',
                         "Unsupported or unsafe portable settings"):
            self.assertIn(required, settings)
        for secret in ("knowledge_embedding_endpoint", "knowledge_llm_endpoint",
                       "API_KEY", "SharedPreferences.getAll"):
            self.assertNotIn(secret, settings)

    def test_paged_foreign_events_stay_read_only_and_recheck_commitment(self):
        hub = (JAVAS / "KnowledgeHubActivity.java").read_text()
        bridge = (JAVAS / "KnowledgePythonBridge.java").read_text()
        android_bridge = (SOURCE / "python/ksys_android_bridge.py").read_text()
        self.assertIn("KnowledgePythonBridge.journalSyncEvents(", hub)
        self.assertIn("KnowledgePythonBridge.journalSyncEventDetail(", hub)
        self.assertIn('"dpksys.sync-events.v1".equals(protocol)', bridge)
        self.assertIn('"dpksys.sync-event-detail.v1".equals(protocol)', bridge)
        self.assertIn("def journal_sync_event_list(", android_bridge)
        self.assertIn("def journal_sync_event_detail(", android_bridge)
        self.assertIn("if (!snapshot.peer.paired)", hub)
        self.assertNotIn("journalRestoreCommit(this, workspacePath(), snapshot.journal)", hub)

    def test_daily_schedule_seals_passphrase_and_never_uploads_vault(self):
        scheduler = (JAVAS / "KnowledgeDailyBackup.java").read_text()
        worker = (JAVAS / "KnowledgeDailyBackupWorker.java").read_text()
        sealed = (JAVAS / "KnowledgeBackupKeyStore.java").read_text()
        self.assertIn("24, TimeUnit.HOURS", scheduler)
        self.assertIn("KnowledgeBackupKeyStore.save", scheduler)
        self.assertIn("KnowledgeBackupKeyStore.clear", scheduler)
        self.assertIn("AndroidKeyStore", sealed)
        self.assertIn("KeyProperties.BLOCK_MODE_GCM", sealed)
        self.assertIn("KeyProperties.ENCRYPTION_PADDING_NONE", sealed)
        self.assertIn("KnowledgePortableArchive.write", worker)
        self.assertIn("KnowledgePortableArchive.verify(stage", worker)
        self.assertIn("if (!tmp.renameTo(permanent))", worker)
        self.assertIn("while (names.size() > 3)", worker)
        for forbidden in ("HttpURLConnection", "new URL(", "http://", "https://"):
            self.assertNotIn(forbidden, worker)

    def test_external_saf_is_opt_in_and_uploads_ciphertext_only(self):
        external = (JAVAS / "KnowledgeExternalBackup.java").read_text()
        worker = (JAVAS / "KnowledgeDailyBackupWorker.java").read_text()
        hub = (JAVAS / "KnowledgeHubActivity.java").read_text()
        self.assertIn("takePersistableUriPermission", external)
        self.assertIn("releasePersistableUriPermission", external)
        self.assertIn("KnowledgeExternalBackup.destination(context)", worker)
        self.assertIn("copyVerifiedToSaf(context", worker)
        self.assertIn("KnowledgePortableCipher.decryptToVerifiedStaging", worker)
        self.assertIn("if (!confirmed) output.delete()", worker)
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", hub)
        self.assertIn("R.string.knowledge_external_backup_warning", hub)
        self.assertIn("KnowledgeExternalBackup.configure(this, data.getData()", hub)
        self.assertNotIn("KnowledgeExternalBackup.configure(this, notebook", hub)

    def test_peer_journal_comparison_is_explicit_and_read_only(self):
        hub = (JAVAS / "KnowledgeHubActivity.java").read_text()
        bridge = (JAVAS / "KnowledgePythonBridge.java").read_text()
        python_bridge = (SOURCE / "python/ksys_android_bridge.py").read_text()
        self.assertIn("R.id.knowledge_portable_compare_peer", hub)
        self.assertIn("KnowledgePythonBridge.journalSyncPreview(", hub)
        self.assertIn("KnowledgePortableArchive.verify(staged", hub)
        self.assertIn("KnowledgePortableRestore.journalText(staged)", hub)
        self.assertIn('"dpksys.sync-preview.v1".equals(protocol)', bridge)
        self.assertIn("def journal_sync_preview(workspace_dir:", python_bridge)
        self.assertIn("preview_journals(local_text, foreign_text)", python_bridge)


if __name__ == "__main__":
    unittest.main()
