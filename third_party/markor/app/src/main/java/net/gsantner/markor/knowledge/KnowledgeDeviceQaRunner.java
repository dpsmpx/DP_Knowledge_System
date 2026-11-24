/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Read-only live checks + isolated synthetic regressions to support Stage 15.
 * No remote requests, no consent decisions and no writes to the real vault,
 * app journal or the real derived index. Execute only on a background worker.
 */
public final class KnowledgeDeviceQaRunner {
    public static final int STRESS_NOTES = 20_000;
    private static final long MIN_STRESS_AVAILABLE_BYTES = 128L * 1024 * 1024;
    private static final String FIXTURE_TEXT = "# Synthetic title\n"
            + "An amber otter checks an isolated notebook. [[Beta]]\n"
            + "~~~\n[[Not A Link]]\n~~~\n";

    public interface Progress {
        void onProgress(String phase, int done, int total);
    }

    public interface Cancellation {
        boolean isCancelled();
    }

    private interface Check {
        String run() throws Exception;
    }

    private static final class SkippedCheck extends Exception {
        private final String reason;

        SkippedCheck(final String reason) {
            this.reason = reason;
        }
    }

    /** Fixed fields from artificial fixtures only; never an arbitrary SQLite error string. */
    private static final class SyntheticMismatch extends IllegalStateException {
        private final String countOnlyDetails;

        SyntheticMismatch(final String countOnlyDetails) {
            this.countOnlyDetails = countOnlyDetails;
        }
    }

    private final Context context;
    private final File notebook;
    private final KnowledgeLocalIndex actualIndex;
    private final Progress progress;
    private final Cancellation cancellation;
    private final boolean stress;
    private final boolean editorStage22;
    private final KnowledgeDeviceQaReport report;

    public KnowledgeDeviceQaRunner(final Context context, final File notebook,
                                   final KnowledgeLocalIndex actualIndex, final boolean stress,
                                   final Progress progress, final Cancellation cancellation) {
        this(context, notebook, actualIndex, stress, false, progress, cancellation);
    }

    public KnowledgeDeviceQaRunner(final Context context, final File notebook,
                                   final KnowledgeLocalIndex actualIndex, final boolean stress,
                                   final boolean editorStage22,
                                   final Progress progress, final Cancellation cancellation) {
        this.context = context.getApplicationContext();
        this.notebook = notebook;
        this.actualIndex = actualIndex;
        this.progress = progress;
        this.cancellation = cancellation;
        this.stress = stress;
        this.editorStage22 = editorStage22;
        this.report = new KnowledgeDeviceQaReport(editorStage22 ? "Stage22 editor synthetic"
                        : stress ? "20k synthetic stress" : "quick",
                context.getPackageName(), packageVersion(context),
                Build.VERSION.SDK_INT,
                Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0]);
    }

    /** One complete, redacted report even if individual checks fail or are cancelled. */
    public String run() {
        final long started = SystemClock.elapsedRealtime();
        try {
            step("ENV-01", () -> {
                final long free = context.getCacheDir().getUsableSpace();
                if (!"com.dpsmpx.dpks".equals(context.getPackageName())) {
                    throw new IllegalStateException("Wrong package");
                }
                if (!context.getCacheDir().isDirectory() || free <= 0) {
                    throw new IllegalStateException("Private cache unavailable");
                }
                return "private cache available=" + context.getCacheDir().isDirectory()
                        + ", freeMiB=" + free / 1024 / 1024
                        + ", heapUsedMiB=" + (Runtime.getRuntime().totalMemory()
                                - Runtime.getRuntime().freeMemory()) / 1024 / 1024
                        + ", batteryPct=" + batteryPercent();
            });
            if (editorStage22) {
                step("UI-STATE-22", this::checkSyntheticWorkspaceNavigation);
                step("UI-HISTORY-22", this::checkSyntheticProcessHistory);
                step("EDITOR-22-SUITE", this::checkSyntheticStage22Editor);
                report.add("SKIP", "EDITOR-22-UI", 0,
                        "real touch, keyboard, rotation, TalkBack, font scale and task lifecycle "
                                + "need observed device UI; synthetic bridge PASS is not UI signoff");
            } else {
                step("SCAN-01", this::checkRealVaultReadOnly);
                step("INDEX-01", this::checkRealIndexReadOnly);
                step("MD-01", this::checkMarkdown);
                step("CORE-01", this::checkPythonCore);
                step("EMBED-01", this::checkEmbeddingPrimitives);
                step("EMBED-SOURCE-19", this::checkSyntheticEmbeddingSourceSeal);
                step("CRYPTO-01", this::checkSyntheticCrypto);
                step("BACKUP-WRITE-01", this::checkSyntheticBackupWriter);
                step(stress ? "STRESS-20K" : "SANDBOX-01", this::checkSyntheticIndex);
                step("RECONCILE-16", this::checkSyntheticReconciliation);
                step("LEX-PHRASE-19", this::checkSyntheticPhraseRetrieval);
                step("CITE-LOCAL-19", this::checkSyntheticLocalCitations);
                step("CITE-COVER-19", this::checkSyntheticCitationCoverage);
                step("CITE-SEND-19", this::checkSyntheticConsentSourceRevision);
                step("DECISION-17", this::checkSyntheticDecisionLifecycle);
                step("REVISE-17", this::checkSyntheticRevision);
                step("SPLIT-17", this::checkSyntheticSplit);
                step("MERGE-17", this::checkSyntheticMerge);
                step("BATCH-17", this::checkSyntheticClaimBatch);
                step("VERSION-18-SUITE", this::checkSyntheticStage18Versions);
                step("BG-01", () -> "scheduler preference readable; enabled="
                        + KnowledgeBackgroundScheduler.isEnabled(context)
                        + ", alerts=" + KnowledgeBackgroundScheduler.notificationsEnabled(context)
                        + "; OEM delivery requires manual observation");
                report.add("SKIP", "MANUAL-01", 0,
                        "LLM provider, real embedding model and network privacy require consent/testing");
                report.add("SKIP", "MANUAL-02", 0,
                        "process death, reboot, notifications, battery and schedule timing need device observation");
                report.add("SKIP", "DEBT-01", 0,
                        "full app backup/restore, automated daily backups, cloud sync and permissions pending development");
                if (!stress) {
                    report.add("SKIP", "STRESS-20K", 0,
                            "run the separate 20k synthetic button to measure large-vault indexing");
                }
            }
        } catch (InterruptedIOException cancelled) {
            report.add("WARN", "RUN-CANCELLED", 0, "user cancelled; no full signoff");
        } catch (Exception failed) {
            // A failing step is reported inside step(). This outer guard is for setup.
            report.add("FAIL", "RUN-ERROR", 0, "failure type="
                    + safeErrorClass(failed));
        }
        return report.render(SystemClock.elapsedRealtime() - started);
    }

    /** Device-side pure transition checks; no touch automation or source/journal I/O. */
    private String checkSyntheticWorkspaceNavigation() throws SyntheticMismatch {
        final KnowledgeWorkspaceNavigation state = new KnowledgeWorkspaceNavigation(10, 20, 30, 40);
        if (state.page() != 10 || state.backAction() != KnowledgeWorkspaceNavigation.BackAction.FINISH
                || state.openDecisionDetail()) {
            throw new SyntheticMismatch("verdict=UI_DEFAULT_INVALID");
        }
        state.select(20);
        if (!state.openDecisionDetail()
                || state.backAction() != KnowledgeWorkspaceNavigation.BackAction.CLOSE_DETAIL) {
            throw new SyntheticMismatch("verdict=UI_DETAIL_BACK_INVALID");
        }
        state.closeDecisionDetail();
        if (state.backAction() != KnowledgeWorkspaceNavigation.BackAction.LIBRARY
                || state.select(30) != 30 || state.openDecisionDetail()
                || state.select(-1) != 10) {
            throw new SyntheticMismatch("verdict=UI_NAVIGATION_INVALID");
        }
        final String sample = "# Synthetic\n🎯 Марс является планетой.\n";
        if (!KnowledgeEditorReviewGuard.matchesSavedSource(sample, sample, sample)
                || KnowledgeEditorReviewGuard.matchesSavedSource(sample, sample + "!", sample)
                || KnowledgeEditorReviewGuard.matchesSavedSource(sample, sample, sample + "!")) {
            throw new SyntheticMismatch("verdict=UI_SAVE_GUARD_INVALID");
        }
        return "synthetic tab/back/detail transitions and unsaved-source handoff guard pass; "
                + "no touch or journal commit tested";
    }

    private String checkSyntheticProcessHistory() throws SyntheticMismatch {
        final KnowledgeProcessHistory history = new KnowledgeProcessHistory();
        for (int i = 1; i <= 40; i++) {
            history.record(KnowledgeProcessHistory.Operation.SCAN,
                    KnowledgeProcessHistory.Outcome.COMPLETED, i);
        }
        final KnowledgeProcessHistory restored =
                KnowledgeProcessHistory.fromStored(history.serialize());
        if (restored.recent().size() != KnowledgeProcessHistory.MAX_EVENTS
                || restored.recent().get(0).timestamp != 40
                || restored.recent().get(31).timestamp != 9
                || KnowledgeProcessHistory.fromStored(
                "123|/private/synthetic.md|STARTED\n").recent().size() != 0) {
            throw new SyntheticMismatch("verdict=UI_EVENT_LOG_INVALID");
        }
        return "bounded code-only events, privacy filter and persisted order verified; "
                + "actual long-running Android scheduling is not tested";
    }

    private void step(final String code, final Check check) throws InterruptedIOException {
        ensureRunning();
        notifyProgress(code, 0, 0);
        final long start = SystemClock.elapsedRealtime();
        try {
            final String detail = check.run();
            report.add("PASS", code, SystemClock.elapsedRealtime() - start, detail);
        } catch (InterruptedIOException cancelled) {
            throw cancelled;
        } catch (SkippedCheck skipped) {
            report.add("SKIP", code, SystemClock.elapsedRealtime() - start, skipped.reason);
        } catch (SyntheticMismatch mismatch) {
            report.add("FAIL", code, SystemClock.elapsedRealtime() - start,
                    mismatch.countOnlyDetails);
        } catch (Exception error) {
            report.add("FAIL", code, SystemClock.elapsedRealtime() - start,
                    "exception=" + safeErrorClass(error) + " (details withheld for privacy)");
        }
        ensureRunning();
    }

    /** Dedicated button: the selected vault/index/journal are never supplied. */
    private String checkSyntheticStage22Editor() throws Exception {
        ensureRunning();
        final JSONObject result = KnowledgePythonBridge.stage22EditorSelfTest(context,
                context.getCacheDir().getCanonicalPath());
        if (!"dpksys.stage22_editor_selftest.v1".equals(result.getString("protocol"))
                || !result.getBoolean("synthetic_only")
                || result.getBoolean("used_selected_vault")
                || !result.getBoolean("read_only_user_data")
                || !result.getBoolean("requires_manual_ui_qa")) {
            throw new SyntheticMismatch("verdict=UNTRUSTED_EDITOR_QA_CONTRACT");
        }
        final JSONArray checks = result.getJSONArray("checks");
        if (checks.length() < 10 || checks.length() > 40
                || result.getInt("passed") + result.getInt("failed") != checks.length()) {
            throw new SyntheticMismatch("verdict=EDITOR_QA_COUNT_MISMATCH");
        }
        int passed = 0;
        int failed = 0;
        for (int i = 0; i < checks.length(); i++) {
            ensureRunning();
            final JSONObject row = checks.getJSONObject(i);
            final String code = row.getString("code");
            if (!code.matches("EDITOR-[A-Z0-9-]{4,28}")) {
                throw new SyntheticMismatch("verdict=EDITOR_QA_CODE_MISMATCH");
            }
            final boolean ok = row.getBoolean("passed");
            report.add(ok ? "PASS" : "FAIL", code, 0,
                    ok ? "isolated synthetic bridge assertions passed"
                            : "isolated synthetic bridge assertions failed; details withheld");
            if (ok) passed++; else failed++;
        }
        if (passed != result.getInt("passed") || failed != result.getInt("failed")
                || failed != 0) {
            throw new SyntheticMismatch("syntheticChecks=" + checks.length()
                    + ", failedChecks=" + failed);
        }
        return "syntheticChecks=" + passed
                + ", failures=0, temporaryCacheCleaned=true, selectedVaultUntouched=true";
    }

    /** Private synthetic source-pair and journal test; no selected vault reads. */
    private String checkSyntheticStage18Versions() throws Exception {
        ensureRunning();
        final JSONObject result = KnowledgePythonBridge.stage18VersionSelfTest(context,
                context.getCacheDir().getCanonicalPath());
        if (!"dpksys.stage18_version_selftest.v1".equals(result.getString("protocol"))
                || !result.getBoolean("synthetic_only")
                || result.getBoolean("used_selected_vault")
                || !result.getBoolean("read_only_user_data")
                || !result.getBoolean("requires_manual_ui_qa")) {
            throw new SyntheticMismatch("verdict=UNTRUSTED_VERSION_QA_CONTRACT");
        }
        final JSONArray checks = result.getJSONArray("checks");
        if (checks.length() != 6
                || result.getInt("passed") + result.getInt("failed") != checks.length()) {
            throw new SyntheticMismatch("verdict=VERSION_QA_COUNT_MISMATCH");
        }
        int failures = 0;
        for (int i = 0; i < checks.length(); i++) {
            ensureRunning();
            final JSONObject row = checks.getJSONObject(i);
            if (!row.getString("code").matches("VERSION-[A-Z0-9-]{4,28}")) {
                throw new SyntheticMismatch("verdict=VERSION_QA_CODE_MISMATCH");
            }
            if (!row.getBoolean("passed")) failures++;
            report.add(row.getBoolean("passed") ? "PASS" : "FAIL", row.getString("code"), 0,
                    row.getBoolean("passed") ? "isolated version bridge assertions passed"
                            : "isolated version bridge assertions failed");
        }
        if (failures != 0 || result.getInt("failed") != failures) {
            throw new SyntheticMismatch("syntheticChecks=" + checks.length()
                    + ", failedChecks=" + failures);
        }
        return "syntheticChecks=6, failures=0, temporaryCacheCleaned=true, "
                + "selectedVaultUntouched=true";
    }

    private String checkRealVaultReadOnly() throws Exception {
        if (notebook == null || !notebook.isDirectory()) {
            throw new SkippedCheck("notebook not selected/accessible");
        }
        final KnowledgeVaultScanner.Snapshot scan = KnowledgeVaultScanner.scan(notebook,
                KnowledgeVaultScanner.MAX_NOTES, this::isCancelled,
                (notes, directories) -> notifyProgress("SCAN-01", notes, -1));
        int oversized = 0;
        for (final KnowledgeVaultScanner.Note note : scan.notes) {
            if (note.sizeBytes > KnowledgeLocalIndex.MAX_INDEX_BYTES_PER_NOTE) {
                oversized++;
            }
        }
        if (scan.truncated || scan.unreadableDirectories > 0) {
            report.add("WARN", "SCAN-PARTIAL", 0,
                    "truncated=" + scan.truncated + ", unreadableDirectories="
                            + scan.unreadableDirectories);
        }
        return "notes=" + scan.notes.size() + ", oversized=" + oversized
                + ", unreadableDirectories=" + scan.unreadableDirectories
                + ", truncated=" + scan.truncated + "; content not read";
    }

    private String checkRealIndexReadOnly() throws SkippedCheck {
        if (!actualIndex.hasDerivedIndex()) {
            throw new SkippedCheck("no committed derived index yet; refresh library first");
        }
        final KnowledgeLocalIndex.IndexHealth health = actualIndex.healthReport();
        if (!health.isHealthy()) {
            report.add("WARN", "INDEX-INTEGRITY", 0,
                    health.isStructurallyHealthy()
                            ? "SQLite read-only quick_check non-ok; structural counts agree; "
                                    + "cannot distinguish engine limitation from corruption; no repair performed"
                            : "index relationships inconsistent; rebuild requires explicit approval");
        }
        return "quick_check=" + ("ok".equals(health.quickCheck) ? "ok" : "not-ok")
                + ", sqliteVersion=" + safeVersion(health.sqliteVersion)
                + ", structuralHealthy=" + health.isStructurallyHealthy()
                + ", healthy=" + health.isHealthy()
                + ", sources=" + health.sources + ", fts=" + health.textRows
                + ", unverifiedSources=" + health.unverifiedSources
                + ", missingFts=" + health.missingTextRows
                + ", orphanFts=" + health.orphanTextRows
                + ", embeddedNotes=" + health.embeddedNotes
                + ", chunks=" + health.embeddedChunks
                + ", lsh=" + health.lshRows
                + ", orphanEmbeddings=" + health.orphanEmbeddingNotes
                + ", orphanChunks=" + health.orphanChunks
                + ", orphanLsh=" + health.orphanLsh
                + ", lastScanCount=" + health.lastScanCount
                + ", lastScanIncomplete=" + health.lastScanIncomplete;
    }

    private String checkMarkdown() {
        final KnowledgeMarkdownMetadata.Parsed parsed = KnowledgeMarkdownMetadata.parse(
                "synthetic.md", FIXTURE_TEXT);
        if (!"Synthetic title".equals(parsed.title)
                || !parsed.possibleLinks.equals(Arrays.asList("beta"))) {
            throw new IllegalStateException("Metadata mismatch");
        }
        return "heading parsed; 1 valid link; fenced pseudo-link excluded";
    }

    private String checkPythonCore() throws Exception {
        final JSONObject result = KnowledgePythonBridge.inspect(context,
                "synthetic.md", FIXTURE_TEXT);
        if (!result.getBoolean("coverage_ok") || !result.getBoolean("read_only")
                || result.getInt("fragment_count") < 1) {
            throw new IllegalStateException("Core coverage failed");
        }
        return "embedded Python core loaded; losslessCoverage=true; fragments="
                + result.getInt("fragment_count") + "; readOnly=true";
    }

    private String checkEmbeddingPrimitives() {
        final float[] vector = new float[]{2, 1, 0, 0, 1, 0, -1, 0};
        final float[] normalized = KnowledgeEmbeddingVectors.normalize(vector);
        final byte[] encoded = KnowledgeEmbeddingVectors.encode(normalized);
        final double similarity = KnowledgeEmbeddingVectors.similarity(normalized, encoded);
        final int[] buckets = KnowledgeEmbeddingLsh.buckets(normalized);
        final int[] probes = KnowledgeEmbeddingLsh.probes(normalized);
        if (similarity < .999 || similarity > 1.001
                || !Arrays.equals(normalized, KnowledgeEmbeddingVectors.decode(encoded))
                || buckets.length != KnowledgeEmbeddingLsh.TABLES
                || probes.length != KnowledgeEmbeddingLsh.PROBES) {
            throw new IllegalStateException("Vector roundtrip mismatch");
        }
        return "float32 roundtrip; cosine=" + String.format(Locale.ROOT, "%.4f", similarity)
                + "; LSH tables=" + buckets.length + "; probes=" + probes.length;
    }

    /** No real model call: validate revision-sealed checkpoints and legacy exclusion. */
    private String checkSyntheticEmbeddingSourceSeal() throws Exception {
        final File sandbox = new File(context.getCacheDir(),
                "dpks-device-qa-" + UUID.randomUUID());
        if (!sandbox.mkdir()) {
            throw new IOException("Could not create embedding sandbox");
        }
        try {
            final File vault = new File(sandbox, "vault");
            if (!vault.mkdir()) {
                throw new IOException("Could not create synthetic embedding vault");
            }
            final File source = new File(vault, "vector.md");
            final String text = "# Vector\nSynthetic embeddable source, no network.\n";
            writeFixture(source, text);
            final byte[] sourceHash = sha256(source);
            final byte[] wrongHash = sourceHash.clone();
            wrongHash[0] ^= 0x01;
            final File dbFile = new File(sandbox, "qa-vectors.sqlite");
            final KnowledgeLocalIndex testIndex = new KnowledgeLocalIndex(dbFile);
            testIndex.synchronize(vault, KnowledgeVaultScanner.scan(vault, 10,
                    this::isCancelled), this::isCancelled);
            final String key = "qa-synthetic-vector-model";
            final List<KnowledgeEmbeddingChunks.Chunk> chunks =
                    KnowledgeEmbeddingChunks.fromNote(text);
            final float[] vector = KnowledgeEmbeddingVectors.normalize(
                    new float[]{2, 1, 0, 0, 1, 0, -1, 0});
            if (chunks.size() != 1 || testIndex.beginEmbedding("vector.md",
                    source.lastModified(), source.length(), key,
                    chunks.size(), false, sourceHash) != 0) {
                throw new SyntheticMismatch("verdict=EMBED_SOURCE_BEGIN_MISMATCH");
            }
            boolean mismatchedBatchRejected = false;
            try {
                testIndex.appendEmbeddingBatch("vector.md", source.lastModified(),
                        source.length(), key, chunks, 0, new float[][]{vector}, wrongHash);
            } catch (IllegalStateException expected) {
                mismatchedBatchRejected = true;
            }
            if (!mismatchedBatchRejected || testIndex.hasEmbeddings("vector.md",
                    source.lastModified(), source.length(), key)) {
                throw new SyntheticMismatch("verdict=EMBED_SOURCE_STALE_BATCH_ACCEPTED");
            }
            testIndex.appendEmbeddingBatch("vector.md", source.lastModified(), source.length(),
                    key, chunks, 0, new float[][]{vector}, sourceHash);
            if (!testIndex.hasEmbeddings("vector.md", source.lastModified(), source.length(), key)
                    || !testIndex.embeddedPaths(key).contains("vector.md")
                    || testIndex.embeddingNoteCount(key) != 1
                    || testIndex.semanticSearch(key, vector, 8).size() != 1) {
                throw new SyntheticMismatch("verdict=EMBED_SOURCE_RETRIEVAL_MISMATCH");
            }
            boolean wrongSourceRejected = false;
            try {
                testIndex.beginEmbedding("vector.md", source.lastModified(), source.length(),
                        key, chunks.size(), false, wrongHash);
            } catch (IllegalStateException expected) {
                wrongSourceRejected = true;
            }
            if (!wrongSourceRejected || !testIndex.embeddedPaths(key).contains("vector.md")) {
                throw new SyntheticMismatch("verdict=EMBED_SOURCE_REVISION_ACCEPTED");
            }
            // Existing v1-v4 vectors have no trustworthy source-byte identity.
            // Keep their rows, but prevent search and reuse until explicit rebuild.
            try (SQLiteDatabase legacy = SQLiteDatabase.openOrCreateDatabase(dbFile, null)) {
                legacy.execSQL("UPDATE embedding_notes SET source_sha256=NULL "
                        + "WHERE path='vector.md'");
            }
            if (testIndex.hasEmbeddings("vector.md", source.lastModified(), source.length(), key)
                    || testIndex.embeddedPaths(key).contains("vector.md")
                    || testIndex.embeddingNoteCount(key) != 0
                    || !testIndex.semanticSearch(key, vector, 8).isEmpty()
                    || testIndex.beginEmbedding("vector.md", source.lastModified(),
                            source.length(), key, chunks.size(), false, sourceHash) != 0
                    || !Arrays.equals(sourceHash, sha256(source))) {
                throw new SyntheticMismatch("verdict=UNSEALED_LEGACY_VECTOR_REUSED");
            }
            return "syntheticVector=1, byteSeal=true, staleBatchRejected=true, "
                    + "legacyUnsealedExcluded=true, sourceHashUnchanged=true; synthetic cache only";
        } finally {
            removeSandbox(sandbox);
        }
    }

    private String checkSyntheticCrypto() throws GeneralSecurityException {
        final byte[] input = "synthetic backup only".getBytes(StandardCharsets.UTF_8);
        final char[] passphrase = "synthetic-only-qa-password".toCharArray();
        try {
            final byte[] encrypted = KnowledgeJournalCrypto.encrypt(input, passphrase);
            if (!Arrays.equals(input, KnowledgeJournalCrypto.decrypt(encrypted, passphrase))) {
                throw new GeneralSecurityException("Roundtrip mismatch");
            }
            encrypted[encrypted.length - 1] ^= 0x01;
            boolean tamperingRejected = false;
            try {
                KnowledgeJournalCrypto.decrypt(encrypted, passphrase);
            } catch (GeneralSecurityException expected) {
                tamperingRejected = true;
                // Expected authentication failure; real journals never opened.
            }
            if (!tamperingRejected) {
                throw new GeneralSecurityException("Tampering not rejected");
            }
            return "AES-GCM synthetic encrypted roundtrip and tamper rejection";
        } finally {
            Arrays.fill(passphrase, '\0');
            Arrays.fill(input, (byte) 0);
        }
    }

    private String checkSyntheticBackupWriter() throws IOException {
        final byte[] fixture = "synthetic encrypted data; not a real journal"
                .getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream saved = new ByteArrayOutputStream();
        KnowledgeJournalExportWriter.writeAndVerify(new KnowledgeJournalExportWriter.Destination() {
            @Override public OutputStream openWrite() { return saved; }
            @Override public InputStream openRead() {
                return new ByteArrayInputStream(saved.toByteArray());
            }
        }, fixture);
        if (!Arrays.equals(fixture, saved.toByteArray())) {
            throw new SyntheticMismatch("verdict=SYNTHETIC_BACKUP_WRITE_MISMATCH");
        }
        boolean refusedEmptyProvider = false;
        try {
            KnowledgeJournalExportWriter.writeAndVerify(
                    new KnowledgeJournalExportWriter.Destination() {
                        @Override public OutputStream openWrite() {
                            return new ByteArrayOutputStream();
                        }
                        @Override public InputStream openRead() {
                            return new ByteArrayInputStream(new byte[0]);
                        }
                    }, fixture);
        } catch (IOException expected) {
            refusedEmptyProvider = true;
        }
        if (!refusedEmptyProvider) {
            throw new SyntheticMismatch("verdict=EMPTY_BACKUP_PROVIDER_NOT_REJECTED");
        }
        return "synthetic stream closed/read-back verified, zero-byte provider rejected; "
                + "real SAF/document picker still requires manual device QA";
    }

    private String checkSyntheticIndex() throws Exception {
        final File cache = context.getCacheDir();
        if (stress && cache.getUsableSpace() < MIN_STRESS_AVAILABLE_BYTES) {
            throw new SkippedCheck("need at least 128 MiB free cache");
        }
        final File sandbox = new File(cache, "dpks-device-qa-" + UUID.randomUUID());
        if (!sandbox.mkdir()) {
            throw new IOException("Could not create QA sandbox");
        }
        try {
            final File vault = new File(sandbox, "vault");
            if (!vault.mkdir()) {
                throw new IOException("Could not create fixture vault");
            }
            final int total = stress ? STRESS_NOTES : 3;
            File reference = null;
            byte[] referenceHash = null;
            for (int i = 0; i < total; i++) {
                ensureRunning();
                final File dir = stress ? new File(vault, String.format(Locale.ROOT,
                        "group-%03d", i / 200)) : vault;
                if (!dir.isDirectory() && !dir.mkdir()) {
                    throw new IOException("Could not create fixture group");
                }
                final File note = new File(dir, String.format(Locale.ROOT, "qa-%05d.md", i));
                final StringBuilder body = new StringBuilder(FIXTURE_TEXT)
                        .append("Synthetic id ").append(i).append('\n');
                if (stress && i < 48) {
                    // Early, path-ordered distractors contain both words but
                    // not the adjacent phrase used by the four late labels.
                    body.append("Crimson is separated from the unrelated cedar token.\n");
                } else if (stress && i >= total - 4) {
                    body.append(i % 2 == 0
                            ? "Crimson cedar supports a wholly synthetic claim.\n"
                            : "Crimson cedar contradicts a wholly synthetic claim.\n");
                }
                writeFixture(note, body.toString());
                if (i == 0) {
                    reference = note;
                    referenceHash = sha256(note);
                }
                if (i % 512 == 0) {
                    notifyProgress("fixture generation", i, total);
                }
            }
            writeFixture(new File(vault, ".private.md"), "# ignored\n");
            writeFixture(new File(vault, "not-a-note.txt"), "# ignored\n");
            final KnowledgeVaultScanner.Snapshot scan = KnowledgeVaultScanner.scan(vault,
                    STRESS_NOTES + 10, this::isCancelled,
                    (count, dirs) -> notifyProgress("synthetic scan", count, total));
            if (scan.notes.size() != total || scan.truncated
                    || scan.unreadableDirectories != 0) {
                throw new IOException("Synthetic scanner results mismatch");
            }
            final KnowledgeLocalIndex testIndex = new KnowledgeLocalIndex(
                    new File(sandbox, "qa-index.sqlite"));
            final long indexStart = SystemClock.elapsedRealtime();
            final KnowledgeLocalIndex.SyncResult first = testIndex.synchronize(vault, scan,
                    this::isCancelled, (count, sum) -> notifyProgress("synthetic FTS", count, sum));
            final long indexMillis = SystemClock.elapsedRealtime() - indexStart;
            ensureRunning();
            final String retrievalMetrics = stress
                    ? benchmarkSynthetic20kRetrieval(testIndex) : "";
            final List<KnowledgeLocalIndex.Hit> hits = testIndex.search("amber", 8);
            final KnowledgeLocalIndex.SearchPage firstPage = testIndex.searchPage("", 0, 60);
            final KnowledgeLocalIndex.SearchPage lastPage = testIndex.searchPage(
                    "amber", total + 120, 60);
            if (firstPage.total != total || firstPage.offset != 0
                    || firstPage.hits.size() != Math.min(total, 60)
                    || lastPage.total != total || lastPage.hits.isEmpty()
                    || lastPage.nextOffset != -1
                    || lastPage.offset != ((total - 1) / 60) * 60) {
                throw new SyntheticMismatch("verdict=BOUNDED_FTS_PAGING_MISMATCH");
            }
            if (total > 60) {
                final KnowledgeLocalIndex.SearchPage secondPage = testIndex.searchPage("", 60, 60);
                if (secondPage.total != total || secondPage.hits.size() != 60
                        || firstPage.hits.get(59).relativePath.equals(
                                secondPage.hits.get(0).relativePath)) {
                    throw new SyntheticMismatch("verdict=NONDETERMINISTIC_FTS_PAGING");
                }
            }
            final KnowledgeLocalIndex.IndexHealth readOnlyHealth = testIndex.healthReport();
            // This second connection is ONLY for a brand-new disposable QA DB.
            // The actual user index is never opened read/write by diagnostics.
            final KnowledgeLocalIndex.IndexHealth comparisonHealth =
                    testIndex.syntheticHealthOnReadWriteConnection();
            final KnowledgeDeviceQaSqliteVerdict.Result verdict =
                    KnowledgeDeviceQaSqliteVerdict.assess(total, first.indexed, hits.size(),
                            readOnlyHealth.sources, readOnlyHealth.textRows,
                            readOnlyHealth.missingTextRows, readOnlyHealth.orphanTextRows,
                            readOnlyHealth.isStructurallyHealthy(),
                            "ok".equals(readOnlyHealth.quickCheck),
                            "ok".equals(comparisonHealth.quickCheck));
            final String counts = "syntheticNotes=" + total + ", indexed=" + first.indexed
                    + ", FTS hits=" + hits.size() + ", sources=" + readOnlyHealth.sources
                    + ", fts=" + readOnlyHealth.textRows
                    + ", missingFts=" + readOnlyHealth.missingTextRows
                    + ", orphanFts=" + readOnlyHealth.orphanTextRows
                    + ", structuralHealthy=" + readOnlyHealth.isStructurallyHealthy()
                    + ", sqliteVersion=" + safeVersion(readOnlyHealth.sqliteVersion)
                    + ", quickCheckReadOnly=" + quickStatus(readOnlyHealth.quickCheck)
                    + ", quickCheckSyntheticReadWrite=" + quickStatus(comparisonHealth.quickCheck)
                    + ", paging=true, verdict=" + verdict.code;
            if (!verdict.passed) {
                throw new SyntheticMismatch(counts);
            }
            if (verdict.readOnlyDiscrepancy) {
                report.add("WARN", "SQLITE-RO-CHECK", 0,
                        "synthetic FTS quick_check differs by connection mode; "
                                + "real index remains read-only and unmodified; "
                                + "manual/engine follow-up required");
            }
            if (!stress) {
                final KnowledgeLocalIndex.SyncResult second = testIndex.synchronize(vault,
                        scan, this::isCancelled);
                if (second.reused != total || second.indexed != 0) {
                    throw new SyntheticMismatch(counts + ", incrementalReused=" + second.reused
                            + ", incrementalIndexed=" + second.indexed
                            + ", verdict=INCREMENTAL_SYNC_MISMATCH");
                }
            }
            if (!Arrays.equals(referenceHash, sha256(reference))) {
                throw new SyntheticMismatch(counts + ", verdict=SOURCE_HASH_CHANGED");
            }
            return counts
                    + ", indexingMillis=" + indexMillis
                    + retrievalMetrics
                    + (stress ? ", 20k target exercised" : ", incremental reuse verified")
                    + "; synthetic source hash unchanged; all fixtures confined to app cache";
        } finally {
            removeSandbox(sandbox);
        }
    }

    /** Labels are predetermined, synthetic and intentionally adversarial.
     * Measures Android FTS4 lookup, not learned relevance or semantic proof.
     */
    private String benchmarkSynthetic20kRetrieval(final KnowledgeLocalIndex testIndex)
            throws IOException {
        final String question = "Which crimson cedar?";
        final long[] microseconds = new long[21];
        for (int attempt = -2; attempt < microseconds.length; attempt++) {
            ensureRunning();
            final long started = SystemClock.elapsedRealtimeNanos();
            final List<KnowledgeLocalIndex.Hit> ranked = testIndex.evidenceSearch(question, 6);
            final long elapsed = SystemClock.elapsedRealtimeNanos() - started;
            if (ranked.size() != 6) {
                throw new SyntheticMismatch("verdict=STRESS_RETRIEVAL_RESULT_COUNT");
            }
            for (int label = 0; label < 4; label++) {
                final int i = STRESS_NOTES - 4 + label;
                final String expected = String.format(Locale.ROOT,
                        "group-%03d/qa-%05d.md", i / 200, i);
                if (!expected.equals(ranked.get(label).relativePath)) {
                    throw new SyntheticMismatch("verdict=STRESS_PHRASE_RECALL_OR_ORDER");
                }
            }
            if (attempt >= 0) {
                microseconds[attempt] = Math.max(0, elapsed / 1_000L);
            }
        }
        Arrays.sort(microseconds);
        return ", phraseRecallAt6=4/4, phrasePrecisionAt6=4/6"
                + ", retrievalTrials=21, retrievalP50us=" + microseconds[10]
                + ", retrievalP95us=" + microseconds[19]
                + ", fixture=adversarial-two-token-only, noSemanticTruthClaim=true";
    }

    /** Synthetic rename, complete/partial scan and cancellation regression. */
    private String checkSyntheticReconciliation() throws Exception {
        final File sandbox = new File(context.getCacheDir(),
                "dpks-device-qa-" + UUID.randomUUID());
        if (!sandbox.mkdir()) {
            throw new IOException("Could not create reconciliation sandbox");
        }
        try {
            final File vault = new File(sandbox, "vault");
            if (!vault.mkdir()) {
                throw new IOException("Could not create reconciliation fixture vault");
            }
            final File alpha = new File(vault, "alpha.md");
            final File oldName = new File(vault, "beta.md");
            final File newName = new File(vault, "gamma.md");
            final String alphaText = "# Alpha\nSynthetic amber source points to [[beta]].\n";
            final String betaText = "# Beta\nSynthetic violet source.\n";
            writeFixture(alpha, alphaText);
            writeFixture(oldName, betaText);
            final byte[] unchangedHash = sha256(alpha);
            final KnowledgeLocalIndex testIndex = new KnowledgeLocalIndex(
                    new File(sandbox, "qa-reconciliation.sqlite"));
            final KnowledgeVaultScanner.Snapshot firstScan = KnowledgeVaultScanner.scan(
                    vault, 10, this::isCancelled);
            // Simulate a populated v3 index. The v4 upgrade must preserve its
            // FTS row, verify source text, and backfill the missing byte seal.
            final File legacyDb = new File(sandbox, "qa-v3-migration.sqlite");
            try (SQLiteDatabase legacy = SQLiteDatabase.openOrCreateDatabase(legacyDb, null)) {
                legacy.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "path TEXT NOT NULL UNIQUE, modified INTEGER NOT NULL, "
                        + "size INTEGER NOT NULL, title TEXT NOT NULL, "
                        + "oversized INTEGER NOT NULL, generation TEXT NOT NULL)");
                legacy.execSQL("CREATE VIRTUAL TABLE documents_fts "
                        + "USING fts4(path, title, body, tokenize=unicode61)");
                legacy.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                legacy.execSQL("INSERT INTO notes(id,path,modified,size,title,oversized,generation) "
                                + "VALUES(1,?,?,?,?,0,'old-generation')",
                        new Object[]{"alpha.md", alpha.lastModified(), alpha.length(), "Alpha"});
                legacy.execSQL("INSERT INTO documents_fts(docid,path,title,body) VALUES(1,?,?,?)",
                        new Object[]{"alpha.md", "Alpha", alphaText});
                legacy.execSQL("INSERT INTO meta(key,value) VALUES('root',?)",
                        new Object[]{vault.getCanonicalPath()});
                legacy.execSQL("INSERT INTO meta(key,value) VALUES('last_scan_generation',"
                        + "'old-generation')");
                legacy.execSQL("PRAGMA user_version=3");
            }
            final KnowledgeLocalIndex migratedIndex = new KnowledgeLocalIndex(legacyDb);
            final KnowledgeLocalIndex.SyncResult migrated = migratedIndex.synchronize(
                    vault, firstScan, this::isCancelled);
            if (migrated.reused != 1 || migrated.indexed != 1
                    || !migratedIndex.matchesIndexedEvidenceSource("alpha.md", alpha.lastModified(),
                            alpha.length(), alphaText.getBytes(StandardCharsets.UTF_8))) {
                throw new SyntheticMismatch("verdict=V3_SOURCE_SEAL_MIGRATION_MISMATCH");
            }
            final KnowledgeLocalIndex.SyncResult first = testIndex.synchronize(
                    vault, firstScan, this::isCancelled);
            if (first.indexed != 2 || first.removed != 0
                    || testIndex.possibleBacklinks("beta.md", 10).size() != 1) {
                throw new SyntheticMismatch("verdict=INITIAL_RECONCILIATION_MISMATCH");
            }
            if (!testIndex.matchesIndexedEvidenceText("alpha.md", alpha.lastModified(),
                    alpha.length(), alphaText)
                    || testIndex.matchesIndexedEvidenceText("alpha.md", alpha.lastModified(),
                            alpha.length(), alphaText.replace("amber", "amper"))) {
                throw new SyntheticMismatch("verdict=INDEXED_SOURCE_HASH_MISMATCH");
            }
            final byte[] indexedAlpha = alphaText.getBytes(StandardCharsets.UTF_8);
            final byte[] rewrittenAlpha = alphaText.replace("amber", "amper")
                    .getBytes(StandardCharsets.UTF_8);
            if (!testIndex.matchesIndexedEvidenceSource("alpha.md", alpha.lastModified(),
                    alpha.length(), indexedAlpha)
                    || testIndex.matchesIndexedEvidenceSource("alpha.md", alpha.lastModified(),
                            alpha.length(), rewrittenAlpha)) {
                throw new SyntheticMismatch("verdict=RAW_SOURCE_SEAL_MISMATCH");
            }
            final List<KnowledgeLocalIndex.Hit> rankedEvidence =
                    testIndex.evidenceSearch("synthetic violet", 10);
            if (rankedEvidence.size() != 2
                    || !"beta.md".equals(rankedEvidence.get(0).relativePath)) {
                throw new SyntheticMismatch("verdict=ALL_TERM_RANKING_MISMATCH");
            }
            if (!oldName.renameTo(newName)) {
                throw new IOException("Synthetic rename failed");
            }
            final KnowledgeVaultScanner.Snapshot renamedScan = KnowledgeVaultScanner.scan(
                    vault, 10, this::isCancelled);
            final KnowledgeLocalIndex.SyncResult renamed = testIndex.synchronize(
                    vault, renamedScan, this::isCancelled);
            boolean foundAlpha = false;
            boolean foundGamma = false;
            boolean foundObsoleteBeta = false;
            for (final KnowledgeLocalIndex.Hit hit : testIndex.search("", 10)) {
                foundAlpha |= "alpha.md".equals(hit.relativePath);
                foundGamma |= "gamma.md".equals(hit.relativePath);
                foundObsoleteBeta |= "beta.md".equals(hit.relativePath);
            }
            if (renamed.indexed != 1 || renamed.reused != 1 || renamed.removed != 1
                    || renamed.unverified != 0 || renamed.keptMissingEntries
                    || !foundAlpha || !foundGamma || foundObsoleteBeta
                    || !testIndex.search("violet", 10).get(0).relativePath.equals("gamma.md")
                    || testIndex.possibleBacklinks("gamma.md", 10).size() != 0) {
                throw new SyntheticMismatch("verdict=FULL_SCAN_RENAME_MISMATCH");
            }
            final KnowledgeVaultScanner.Snapshot partialScan = KnowledgeVaultScanner.scan(
                    vault, 1, this::isCancelled);
            final KnowledgeLocalIndex.SyncResult partial = testIndex.synchronize(
                    vault, partialScan, this::isCancelled);
            int unverifiedHits = 0;
            final List<KnowledgeLocalIndex.Hit> partialHits = testIndex.search("", 10);
            for (final KnowledgeLocalIndex.Hit hit : partialHits) {
                if (!hit.confirmedInLastScan) {
                    unverifiedHits++;
                }
            }
            if (!partial.keptMissingEntries || partial.removed != 0
                    || partial.unverified != 1 || unverifiedHits != 1
                    || partialHits.size() != 2 || !partialHits.get(0).confirmedInLastScan
                    || testIndex.evidenceSearch("synthetic", 10).size() != 1) {
                throw new SyntheticMismatch("verdict=PARTIAL_SCAN_MISMATCH");
            }
            if (testIndex.matchesIndexedEvidenceText("gamma.md", newName.lastModified(),
                    newName.length(), betaText)
                    || testIndex.matchesIndexedEvidenceSource("gamma.md", newName.lastModified(),
                            newName.length(), betaText.getBytes(StandardCharsets.UTF_8))) {
                throw new SyntheticMismatch("verdict=UNVERIFIED_INDEXED_TEXT_ACCEPTED");
            }
            final List<KnowledgeLocalIndex.Hit> partialEvidence =
                    testIndex.evidenceSearch("amber violet synthetic", 10);
            // Catch a formerly silent FTS4 multi-term OR zero-hit failure.
            if (partialEvidence.size() != 1
                    || !"alpha.md".equals(partialEvidence.get(0).relativePath)) {
                throw new SyntheticMismatch("verdict=PARTIAL_OR_EVIDENCE_MISMATCH");
            }
            for (final KnowledgeLocalIndex.Hit evidence : partialEvidence) {
                if (!evidence.confirmedInLastScan) {
                    throw new SyntheticMismatch("verdict=UNVERIFIED_EVIDENCE");
                }
            }
            final KnowledgeLocalIndex.SyncResult restored = testIndex.synchronize(
                    vault, renamedScan, this::isCancelled);
            if (restored.reused != 2 || restored.unverified != 0) {
                throw new SyntheticMismatch("verdict=FULL_RESCAN_MISMATCH");
            }
            if (testIndex.evidenceSearch("amber violet synthetic", 10).size() != 2) {
                throw new SyntheticMismatch("verdict=RESTORED_OR_EVIDENCE_MISMATCH");
            }
            boolean cancelled = false;
            try {
                testIndex.synchronize(vault, renamedScan, () -> true);
            } catch (InterruptedIOException expected) {
                cancelled = true;
            }
            if (!cancelled || testIndex.search("", 10).size() != 2
                    || !testIndex.search("", 10).get(0).confirmedInLastScan
                    || !Arrays.equals(unchangedHash, sha256(alpha))) {
                throw new SyntheticMismatch("verdict=CANCELLATION_ROLLBACK_MISMATCH");
            }
            return "renamed=1, derivedOldRemoved=1, partialUnverified=1, "
                    + "partialRemoved=0, evidenceExcluded=true, "
                    + "v3Migration=true, rawSourceSeal=true, cancelRollback=true, "
                    + "sourceHashUnchanged=true; synthetic cache only";
        } finally {
            removeSandbox(sandbox);
        }
    }

    /** Adversarial path ordering with opposite artificial statements, no LLM. */
    private String checkSyntheticPhraseRetrieval() throws Exception {
        final File sandbox = new File(context.getCacheDir(),
                "dpks-device-qa-" + UUID.randomUUID());
        if (!sandbox.mkdir()) {
            throw new IOException("Could not create lexical sandbox");
        }
        try {
            final File vault = new File(sandbox, "vault");
            if (!vault.mkdir()) {
                throw new IOException("Could not create lexical fixture vault");
            }
            final File reference = new File(vault, "a-00.md");
            for (int i = 0; i < 30; i++) {
                writeFixture(new File(vault, String.format(Locale.ROOT, "a-%02d.md", i)),
                        "# Decoy\nAmber synthetic-distance violet.\n");
            }
            final byte[] unchanged = sha256(reference);
            writeFixture(new File(vault, "z-support.md"),
                    "# Evidence\nAmber violet supports an artificial statement.\n");
            writeFixture(new File(vault, "z-refute.md"),
                    "# Evidence\nAmber violet refutes an artificial statement.\n");
            final KnowledgeLocalIndex testIndex = new KnowledgeLocalIndex(
                    new File(sandbox, "qa-phrase.sqlite"));
            testIndex.synchronize(vault, KnowledgeVaultScanner.scan(vault, 50,
                    this::isCancelled), this::isCancelled);
            final List<KnowledgeLocalIndex.Hit> ranked =
                    testIndex.evidenceSearch("Which amber violet?", 6);
            if (ranked.size() != 6 || !"z-refute.md".equals(ranked.get(0).relativePath)
                    || !"z-support.md".equals(ranked.get(1).relativePath)
                    || !Arrays.equals(unchanged, sha256(reference))) {
                throw new SyntheticMismatch("verdict=PHRASE_PRIORITY_OR_CONFLICT_LOSS");
            }
            return "syntheticNotes=32, exactPhraseTop2=true, "
                    + "opposingSourcesPreserved=true, noSemanticProof=true, "
                    + "sourceHashUnchanged=true; synthetic cache only";
        } finally {
            removeSandbox(sandbox);
        }
    }

    /** Pure, count-only quote attribution fixture; no network or user vault. */
    private String checkSyntheticLocalCitations() {
        final List<KnowledgeLlmEvidence.Item> sources = Arrays.asList(
                new KnowledgeLlmEvidence.Item("synthetic-one.md", "Amber violet is true."),
                new KnowledgeLlmEvidence.Item("synthetic-two.md", "Amber violet is false."));
        final KnowledgeLlmCitationAudit.Review correct = KnowledgeLlmCitationAudit.inspect(
                "«Amber violet is true.» [1], «Amber violet is false.» [2]", sources);
        final KnowledgeLlmCitationAudit.Review laundered = KnowledgeLlmCitationAudit.inspect(
                "«Amber violet is true.» [2]. A different sentence cites [1].", sources);
        final KnowledgeLlmCitationAudit.Review mixed = KnowledgeLlmCitationAudit.inspect(
                "«Amber violet is true.» [1][2]", sources);
        if (correct.unverifiedQuotes != 0 || correct.mismatchedQuoteCitations != 0
                || laundered.unverifiedQuotes != 1 || laundered.mismatchedQuoteCitations != 1
                || mixed.unverifiedQuotes != 0 || mixed.mismatchedQuoteCitations != 1) {
            throw new SyntheticMismatch("verdict=LOCAL_CITATION_ATTRIBUTION_MISMATCH");
        }
        return "syntheticSources=2, localQuotesVerified=true, "
                + "distantCitationLaunderingRejected=true, mixedSourcesFlagged=true; "
                + "no model request or selected vault access";
    }

    /** Code samples cannot masquerade as cited claims; content is synthetic. */
    private String checkSyntheticCitationCoverage() {
        final List<KnowledgeLlmEvidence.Item> sources = Arrays.asList(
                new KnowledgeLlmEvidence.Item("synthetic-one.md", "Amber violet is true."),
                new KnowledgeLlmEvidence.Item("synthetic-two.md", "Amber violet is false."));
        final KnowledgeLlmCitationAudit.Review covered = KnowledgeLlmCitationAudit.inspect(
                "Amber violet is true [1]. Amber violet is false [2].", sources);
        final KnowledgeLlmCitationAudit.Review uncited = KnowledgeLlmCitationAudit.inspect(
                "Amber violet is true [1]. Another unsupported synthetic claim is asserted.",
                sources);
        final KnowledgeLlmCitationAudit.Review spoofed = KnowledgeLlmCitationAudit.inspect(
                "```\nAmber violet is true [1].\n```\n"
                        + "Another unsupported synthetic claim is asserted.", sources);
        if (covered.uncitedProseSpans != 0 || uncited.uncitedProseSpans != 1
                || !uncited.hasValidReferences() || spoofed.hasValidReferences()
                || spoofed.referenceCount != 0 || spoofed.uncitedProseSpans != 1) {
            throw new SyntheticMismatch("verdict=UNCITED_CLAIM_OR_CODE_REFERENCE_LAUNDERING");
        }
        return "syntheticSources=2, mixedCitationCoverageFlagged=true, "
                + "codeFenceCitationsExcluded=true, heuristicNotSemanticProof=true; "
                + "no network or selected vault access";
    }

    /** Synthetic cache-only post-approval mutation; no LLM call is made. */
    private String checkSyntheticConsentSourceRevision() throws Exception {
        final File sandbox = new File(context.getCacheDir(),
                "dpks-device-qa-" + UUID.randomUUID());
        if (!sandbox.mkdir()) throw new IOException("Cannot create consent sandbox");
        try {
            final File file = new File(sandbox, "approved.md");
            final byte[] approved = "Amber violet is true.".getBytes(StandardCharsets.UTF_8);
            final byte[] changed = "Amber violet is fake.".getBytes(StandardCharsets.UTF_8);
            if (approved.length != changed.length) {
                throw new SyntheticMismatch("verdict=FIXTURE_SIZE_MISMATCH");
            }
            writeFixture(file, "Amber violet is true.");
            final long modified = file.lastModified();
            final KnowledgeLlmEvidence.ConsentSeal seal = new KnowledgeLlmEvidence.ConsentSeal(
                    "approved.md", modified, file.length(), approved);
            if (!seal.matchesCurrentBytes(approved)
                    || !KnowledgeLlmEvidence.matchesIndexedSnapshot(file, modified, approved.length)) {
                throw new SyntheticMismatch("verdict=APPROVED_SEAL_INVALID");
            }
            writeFixture(file, "Amber violet is fake.");
            if (!file.setLastModified(modified)
                    || !KnowledgeLlmEvidence.matchesIndexedSnapshot(file,
                            modified, approved.length)
                    || seal.matchesCurrentBytes(changed)) {
                throw new SyntheticMismatch("verdict=SAME_METADATA_EDIT_NOT_REJECTED");
            }
            return "syntheticSource=1, approvedBytesSealed=true, "
                    + "sameSizeSameMtimeRewriteRejected=true, noModelCall=true, "
                    + "selectedVaultUntouched=true; temporary cache only";
        } finally {
            removeSandbox(sandbox);
        }
    }

    /** Exercise decision preview, append-only retraction and stale-head rejection. */
    private String checkSyntheticDecisionLifecycle() throws Exception {
        final File sandbox = new File(context.getCacheDir(),
                "dpks-device-qa-" + UUID.randomUUID());
        if (!sandbox.mkdir()) {
            throw new IOException("Could not create decision sandbox");
        }
        try {
            ensureRunning();
            final String source = "# Synthetic test\nСтресс приводит к бессоннице.\n";
            final String relativePath = "synthetic-decision.md";
            final String workspace = new File(sandbox, "workspace").getAbsolutePath();
            final JSONObject inspected = KnowledgePythonBridge.inspect(context,
                    relativePath, source);
            final String fragmentId = inspected.getJSONArray("fragments")
                    .getJSONObject(1).getString("id");
            final JSONObject preview = KnowledgePythonBridge.preview(context,
                    workspace, relativePath, source, fragmentId);
            final String reading = preview.getJSONObject("preview").getJSONArray("options")
                    .getJSONObject(0).getString("reading_id");
            final String initialHead = preview.isNull("journal_head") ? null
                    : preview.getString("journal_head");
            final JSONObject committed = KnowledgePythonBridge.decide(context, workspace,
                    relativePath, source, fragmentId, reading, "fact",
                    preview.getString("source_hash"), initialHead);
            final JSONObject listing = KnowledgePythonBridge.activeDecisions(context,
                    workspace, 0);
            if (listing.getInt("total") != 1
                    || listing.getJSONArray("records").length() != 1) {
                throw new SyntheticMismatch("verdict=DECISION_LIST_MISMATCH");
            }
            final String recordId = listing.getJSONArray("records").getJSONObject(0)
                    .getString("record_id");
            final JSONObject retractionPreview = KnowledgePythonBridge.previewRetraction(
                    context, workspace, recordId);
            if (!retractionPreview.getBoolean("read_only")
                    || !committed.getString("journal_head").equals(
                            retractionPreview.getString("journal_head"))) {
                throw new SyntheticMismatch("verdict=DECISION_PREVIEW_MISMATCH");
            }
            boolean staleRejected = false;
            try {
                KnowledgePythonBridge.retractDecision(context, workspace,
                        recordId, "J:stale-head-for-device-qa");
            } catch (Exception expected) {
                staleRejected = true;
            }
            if (!staleRejected || KnowledgePythonBridge.activeDecisions(context,
                    workspace, 0).getInt("total") != 1) {
                throw new SyntheticMismatch("verdict=STALE_HEAD_NOT_REJECTED");
            }
            ensureRunning();
            final JSONObject retracted = KnowledgePythonBridge.retractDecision(context,
                    workspace, recordId, retractionPreview.getString("journal_head"));
            if (!"retract".equals(retracted.getString("decision"))
                    || retracted.getJSONArray("journal_ids").length() != 1
                    || KnowledgePythonBridge.activeDecisions(context, workspace, 0)
                            .getInt("total") != 0) {
                throw new SyntheticMismatch("verdict=RETRACTION_MISMATCH");
            }
            boolean repeatedRejected = false;
            try {
                KnowledgePythonBridge.retractDecision(context, workspace, recordId,
                        retracted.getString("journal_head"));
            } catch (Exception expected) {
                repeatedRejected = true;
            }
            if (!repeatedRejected || !preview.getString("source_hash").equals(
                    KnowledgePythonBridge.preview(context, workspace, relativePath,
                            source, fragmentId).getString("source_hash"))) {
                throw new SyntheticMismatch("verdict=REPEAT_OR_SOURCE_MISMATCH");
            }
            return "syntheticAccepted=1, activeAfterRetract=0, journalAppended=true, "
                    + "staleHeadRejected=true, repeatedRetractRejected=true, "
                    + "sourceHashUnchanged=true; synthetic cache only";
        } finally {
            removeSandbox(sandbox);
        }
    }

    /** One accepted synthetic statement, explicit one-node revision, no source writes. */
    private String checkSyntheticRevision() throws Exception {
        final File sandbox = new File(context.getCacheDir(),
                "dpks-device-qa-" + UUID.randomUUID());
        if (!sandbox.mkdir()) {
            throw new IOException("Could not create revision sandbox");
        }
        try {
            ensureRunning();
            final String source = "# Synthetic test\nСтресс приводит к бессоннице.\n";
            final String replacement = "Стресс вызывает усталость.";
            final String relativePath = "synthetic-revision.md";
            final String workspace = new File(sandbox, "workspace").getAbsolutePath();
            final String fragment = KnowledgePythonBridge.inspect(context,
                    relativePath, source).getJSONArray("fragments")
                    .getJSONObject(1).getString("id");
            final JSONObject first = KnowledgePythonBridge.preview(context,
                    workspace, relativePath, source, fragment);
            final JSONObject accepted = KnowledgePythonBridge.decide(context,
                    workspace, relativePath, source, fragment,
                    first.getJSONObject("preview").getJSONArray("options")
                            .getJSONObject(0).getString("reading_id"),
                    "fact", first.getString("source_hash"), null);
            final JSONObject listing = KnowledgePythonBridge.activeDecisions(context,
                    workspace, 0);
            if (listing.getInt("total") != 1) {
                throw new SyntheticMismatch("verdict=INITIAL_REVISION_LIST_MISMATCH");
            }
            final String oldId = listing.getJSONArray("records").getJSONObject(0)
                    .getString("record_id");
            final JSONObject preview = KnowledgePythonBridge.previewRevision(context,
                    workspace, oldId, replacement);
            if (!preview.getBoolean("read_only")
                    || !preview.getBoolean("requires_human_confirmation")
                    || preview.getJSONArray("options").length() != 1
                    || !preview.getString("journal_head").equals(
                            accepted.getString("journal_head"))) {
                throw new SyntheticMismatch("verdict=REVISION_PREVIEW_MISMATCH");
            }
            final String reading = preview.getJSONArray("options")
                    .getJSONObject(0).getString("reading_id");
            boolean staleRejected = false;
            try {
                KnowledgePythonBridge.reviseDecision(context, workspace, oldId,
                        replacement, reading, "synthetic", preview.getString("statement_hash"),
                        "J:stale-head-for-device-qa");
            } catch (Exception expected) {
                staleRejected = true;
            }
            if (!staleRejected || KnowledgePythonBridge.activeDecisions(context,
                    workspace, 0).getInt("total") != 1) {
                throw new SyntheticMismatch("verdict=REVISION_STALE_HEAD_NOT_REJECTED");
            }
            ensureRunning();
            final JSONObject revised = KnowledgePythonBridge.reviseDecision(context,
                    workspace, oldId, replacement, reading, "synthetic manual correction",
                    preview.getString("statement_hash"), preview.getString("journal_head"));
            final JSONObject active = KnowledgePythonBridge.activeDecisions(context,
                    workspace, 0);
            if (!"revise".equals(revised.getString("decision"))
                    || revised.getJSONArray("journal_ids").length() != 1
                    || oldId.equals(revised.getString("new_record_id"))
                    || active.getInt("total") != 1
                    || !revised.getString("new_record_id").equals(active
                            .getJSONArray("records").getJSONObject(0).getString("record_id"))) {
                throw new SyntheticMismatch("verdict=REVISION_COMMIT_MISMATCH");
            }
            boolean replayRejected = false;
            try {
                KnowledgePythonBridge.reviseDecision(context, workspace, oldId,
                        replacement, reading, "again", preview.getString("statement_hash"),
                        revised.getString("journal_head"));
            } catch (Exception expected) {
                replayRejected = true;
            }
            if (!replayRejected || !first.getString("source_hash").equals(
                    KnowledgePythonBridge.preview(context, workspace, relativePath,
                            source, fragment).getString("source_hash"))) {
                throw new SyntheticMismatch("verdict=REVISION_REPLAY_OR_SOURCE_MISMATCH");
            }
            return "syntheticRevisions=1, activeAfterRevise=1, oldObsoleted=true, "
                    + "staleHeadRejected=true, replayRejected=true, "
                    + "sourceHashUnchanged=true; synthetic cache only";
        } finally {
            removeSandbox(sandbox);
        }
    }

    /** One atomic split in a private synthetic journal, with stale/replay protection. */
    private String checkSyntheticSplit() throws Exception {
        final File sandbox = new File(context.getCacheDir(),
                "dpks-device-qa-" + UUID.randomUUID());
        if (!sandbox.mkdir()) {
            throw new IOException("Could not create split sandbox");
        }
        try {
            ensureRunning();
            final String source = "# Synthetic test\nСтресс приводит к бессоннице.\n";
            final String firstStatement = "Стресс вызывает усталость.";
            final String secondStatement = "Шум вызывает бессонницу.";
            final String path = "synthetic-split.md";
            final String workspace = new File(sandbox, "workspace").getAbsolutePath();
            final String fragment = KnowledgePythonBridge.inspect(context, path, source)
                    .getJSONArray("fragments").getJSONObject(1).getString("id");
            final JSONObject initial = KnowledgePythonBridge.preview(context,
                    workspace, path, source, fragment);
            KnowledgePythonBridge.decide(context, workspace, path, source, fragment,
                    initial.getJSONObject("preview").getJSONArray("options")
                            .getJSONObject(0).getString("reading_id"), "fact",
                    initial.getString("source_hash"), null);
            final JSONObject listing = KnowledgePythonBridge.activeDecisions(context,
                    workspace, 0);
            if (listing.getInt("total") != 1) {
                throw new SyntheticMismatch("verdict=SPLIT_INITIAL_LIST_MISMATCH");
            }
            final String old = listing.getJSONArray("records").getJSONObject(0)
                    .getString("record_id");
            final JSONObject preview = KnowledgePythonBridge.previewSplit(context,
                    workspace, old, firstStatement, secondStatement);
            if (!preview.getBoolean("read_only")
                    || !preview.getBoolean("requires_human_confirmation")
                    || preview.getJSONArray("first_options").length() != 1
                    || preview.getJSONArray("second_options").length() != 1) {
                throw new SyntheticMismatch("verdict=SPLIT_PREVIEW_MISMATCH");
            }
            final String firstReading = preview.getJSONArray("first_options")
                    .getJSONObject(0).getString("reading_id");
            final String secondReading = preview.getJSONArray("second_options")
                    .getJSONObject(0).getString("reading_id");
            boolean staleRejected = false;
            try {
                KnowledgePythonBridge.splitDecision(context, workspace, old,
                        firstStatement, secondStatement, firstReading, secondReading,
                        "synthetic", preview.getString("split_hash"), "J:stale-head");
            } catch (Exception expected) {
                staleRejected = true;
            }
            if (!staleRejected || KnowledgePythonBridge.activeDecisions(context,
                    workspace, 0).getInt("total") != 1) {
                throw new SyntheticMismatch("verdict=SPLIT_STALE_HEAD_NOT_REJECTED");
            }
            ensureRunning();
            final JSONObject split = KnowledgePythonBridge.splitDecision(context,
                    workspace, old, firstStatement, secondStatement,
                    firstReading, secondReading, "explicit synthetic split",
                    preview.getString("split_hash"), preview.getString("journal_head"));
            final JSONObject active = KnowledgePythonBridge.activeDecisions(context,
                    workspace, 0);
            if (!"split".equals(split.getString("decision"))
                    || split.getJSONArray("journal_ids").length() != 1
                    || split.getJSONArray("new_record_ids").length() != 2
                    || active.getInt("total") != 2) {
                throw new SyntheticMismatch("verdict=SPLIT_COMMIT_MISMATCH");
            }
            boolean replayRejected = false;
            try {
                KnowledgePythonBridge.splitDecision(context, workspace, old,
                        firstStatement, secondStatement, firstReading, secondReading,
                        "repeat", preview.getString("split_hash"), split.getString("journal_head"));
            } catch (Exception expected) {
                replayRejected = true;
            }
            if (!replayRejected || !initial.getString("source_hash").equals(
                    KnowledgePythonBridge.preview(context, workspace, path,
                            source, fragment).getString("source_hash"))) {
                throw new SyntheticMismatch("verdict=SPLIT_REPLAY_OR_SOURCE_MISMATCH");
            }
            return "syntheticSplits=1, activeAfterSplit=2, oldObsoleted=true, "
                    + "atomicJournalEntry=true, staleHeadRejected=true, replayRejected=true, "
                    + "sourceHashUnchanged=true; synthetic cache only";
        } finally {
            removeSandbox(sandbox);
        }
    }

    /** Two explicit synthetic records merged into one by a single private journal event. */
    private String checkSyntheticMerge() throws Exception {
        final File sandbox = new File(context.getCacheDir(),
                "dpks-device-qa-" + UUID.randomUUID());
        if (!sandbox.mkdir()) {
            throw new IOException("Could not create merge sandbox");
        }
        try {
            ensureRunning();
            final String workspace = new File(sandbox, "workspace").getAbsolutePath();
            final String firstText = "# Synthetic A\nСтресс приводит к бессоннице.\n";
            final String secondText = "# Synthetic B\nШум вызывает бессонницу.\n";
            final String firstPath = "synthetic-merge-a.md";
            final String secondPath = "synthetic-merge-b.md";
            final String firstFragment = KnowledgePythonBridge.inspect(context, firstPath,
                    firstText).getJSONArray("fragments").getJSONObject(1).getString("id");
            final JSONObject firstPreview = KnowledgePythonBridge.preview(context,
                    workspace, firstPath, firstText, firstFragment);
            final JSONObject accepted = KnowledgePythonBridge.decide(context, workspace,
                    firstPath, firstText, firstFragment,
                    firstPreview.getJSONObject("preview").getJSONArray("options")
                            .getJSONObject(0).getString("reading_id"), "fact",
                    firstPreview.getString("source_hash"), null);
            final String secondFragment = KnowledgePythonBridge.inspect(context, secondPath,
                    secondText).getJSONArray("fragments").getJSONObject(1).getString("id");
            final JSONObject secondPreview = KnowledgePythonBridge.preview(context,
                    workspace, secondPath, secondText, secondFragment);
            KnowledgePythonBridge.decide(context, workspace, secondPath,
                    secondText, secondFragment,
                    secondPreview.getJSONObject("preview").getJSONArray("options")
                            .getJSONObject(0).getString("reading_id"), "fact",
                    secondPreview.getString("source_hash"), accepted.getString("journal_head"));
            final JSONObject listing = KnowledgePythonBridge.activeDecisions(context,
                    workspace, 0);
            if (listing.getInt("total") != 2) {
                throw new SyntheticMismatch("verdict=MERGE_INITIAL_LIST_MISMATCH");
            }
            final String firstId = listing.getJSONArray("records")
                    .getJSONObject(0).getString("record_id");
            final String secondId = listing.getJSONArray("records")
                    .getJSONObject(1).getString("record_id");
            final String mergedText = "Кофе вызывает бессонницу.";
            final JSONObject preview = KnowledgePythonBridge.previewMerge(context,
                    workspace, firstId, secondId, mergedText);
            if (!preview.getBoolean("read_only")
                    || !preview.getBoolean("requires_human_confirmation")
                    || preview.getJSONArray("old_summaries").length() != 2
                    || preview.getJSONArray("options").length() != 1) {
                throw new SyntheticMismatch("verdict=MERGE_PREVIEW_MISMATCH");
            }
            final String reading = preview.getJSONArray("options")
                    .getJSONObject(0).getString("reading_id");
            boolean staleRejected = false;
            try {
                KnowledgePythonBridge.mergeDecisions(context, workspace,
                        firstId, secondId, mergedText, reading, "synthetic",
                        preview.getString("statement_hash"), "J:stale-head");
            } catch (Exception expected) {
                staleRejected = true;
            }
            if (!staleRejected || KnowledgePythonBridge.activeDecisions(context,
                    workspace, 0).getInt("total") != 2) {
                throw new SyntheticMismatch("verdict=MERGE_STALE_HEAD_NOT_REJECTED");
            }
            ensureRunning();
            final JSONObject merged = KnowledgePythonBridge.mergeDecisions(context,
                    workspace, firstId, secondId, mergedText, reading,
                    "explicit synthetic merge", preview.getString("statement_hash"),
                    preview.getString("journal_head"));
            final JSONObject remaining = KnowledgePythonBridge.activeDecisions(context,
                    workspace, 0);
            if (!"merge".equals(merged.getString("decision"))
                    || merged.getJSONArray("journal_ids").length() != 1
                    || remaining.getInt("total") != 1
                    || !merged.getString("new_record_id").equals(
                            remaining.getJSONArray("records").getJSONObject(0)
                                    .getString("record_id"))) {
                throw new SyntheticMismatch("verdict=MERGE_COMMIT_MISMATCH");
            }
            boolean replayRejected = false;
            try {
                KnowledgePythonBridge.mergeDecisions(context, workspace,
                        firstId, secondId, mergedText, reading, "repeat",
                        preview.getString("statement_hash"), merged.getString("journal_head"));
            } catch (Exception expected) {
                replayRejected = true;
            }
            if (!replayRejected || !firstPreview.getString("source_hash").equals(
                    KnowledgePythonBridge.preview(context, workspace,
                            firstPath, firstText, firstFragment).getString("source_hash"))
                    || !secondPreview.getString("source_hash").equals(
                            KnowledgePythonBridge.preview(context, workspace,
                                    secondPath, secondText, secondFragment)
                                    .getString("source_hash"))) {
                throw new SyntheticMismatch("verdict=MERGE_REPLAY_OR_SOURCE_MISMATCH");
            }
            return "syntheticMerges=1, activeAfterMerge=1, oldObsoleted=2, "
                    + "atomicJournalEntry=true, staleHeadRejected=true, replayRejected=true, "
                    + "sourceHashUnchanged=true; synthetic cache only";
        } finally {
            removeSandbox(sandbox);
        }
    }

    /** Two independently read synthetic sources, one bounded, human-only batch event. */
    private String checkSyntheticClaimBatch() throws Exception {
        final File sandbox = new File(context.getCacheDir(),
                "dpks-device-qa-" + UUID.randomUUID());
        if (!sandbox.mkdir()) throw new IOException("Could not create batch sandbox");
        try {
            ensureRunning();
            final String workspace = new File(sandbox, "workspace").getAbsolutePath();
            final String[] paths = {"synthetic-batch-a.md", "synthetic-batch-b.md"};
            final String[] sources = {"# Alpha\nСтресс приводит к бессоннице.\n",
                    "# Beta\nКофе вызывает усталость.\n"};
            final JSONArray selections = new JSONArray();
            for (int i = 0; i < paths.length; i++) {
                final String fragment = KnowledgePythonBridge.inspect(context, paths[i],
                        sources[i]).getJSONArray("fragments").getJSONObject(1).getString("id");
                final JSONObject preview = KnowledgePythonBridge.preview(context, workspace,
                        paths[i], sources[i], fragment);
                final String reading = preview.getJSONObject("preview").getJSONArray("options")
                        .getJSONObject(0).getString("reading_id");
                final JSONObject entry = new JSONObject();
                entry.put("path", paths[i]);
                entry.put("markdown", sources[i]);
                entry.put("fragment_id", fragment);
                entry.put("reading_id", reading);
                selections.put(entry);
            }
            final JSONObject preview = KnowledgePythonBridge.previewClaimBatch(context,
                    workspace, selections.toString());
            if (!preview.getBoolean("read_only")
                    || !preview.getBoolean("requires_human_confirmation")
                    || preview.getJSONArray("items").length() != 2
                    || !sources[0].contains(preview.getJSONArray("items").getJSONObject(0)
                            .getString("source_excerpt"))
                    || preview.getJSONArray("items").getJSONObject(0)
                            .getBoolean("source_truncated")) {
                throw new SyntheticMismatch("verdict=BATCH_PREVIEW_MISMATCH");
            }
            // Fresh opposing claims must not bypass individual review merely
            // because neither assertion appears in the current journal.
            final String opposingSource = "# Opposing\nСтресс не приводит к бессоннице.\n";
            final String opposingPath = "synthetic-batch-opposing.md";
            final String opposingFragment = KnowledgePythonBridge.inspect(context,
                    opposingPath, opposingSource).getJSONArray("fragments")
                    .getJSONObject(1).getString("id");
            final String opposingReading = KnowledgePythonBridge.preview(context, workspace,
                    opposingPath, opposingSource, opposingFragment)
                    .getJSONObject("preview").getJSONArray("options")
                    .getJSONObject(0).getString("reading_id");
            final JSONObject opposingEntry = new JSONObject();
            opposingEntry.put("path", opposingPath);
            opposingEntry.put("markdown", opposingSource);
            opposingEntry.put("fragment_id", opposingFragment);
            opposingEntry.put("reading_id", opposingReading);
            final JSONArray opposingBatch = new JSONArray();
            opposingBatch.put(selections.getJSONObject(0));
            opposingBatch.put(opposingEntry);
            boolean opposingRejected = false;
            try {
                KnowledgePythonBridge.previewClaimBatch(context, workspace,
                        opposingBatch.toString());
            } catch (Exception expected) {
                opposingRejected = true;
            }
            if (!opposingRejected) {
                throw new SyntheticMismatch("verdict=BATCH_OPPOSING_READINGS_ALLOWED");
            }
            boolean staleRejected = false;
            try {
                KnowledgePythonBridge.acceptClaimBatch(context, workspace,
                        selections.toString(), preview.getString("batch_hash"), "J:stale-head");
            } catch (Exception expected) {
                staleRejected = true;
            }
            final JSONObject changed = new JSONObject(selections.getJSONObject(1).toString());
            changed.put("markdown", sources[1] + "\nchanged");
            final JSONArray altered = new JSONArray();
            altered.put(selections.getJSONObject(0));
            altered.put(changed);
            boolean sourceRejected = false;
            try {
                KnowledgePythonBridge.acceptClaimBatch(context, workspace,
                        altered.toString(), preview.getString("batch_hash"), null);
            } catch (Exception expected) {
                sourceRejected = true;
            }
            if (!staleRejected || !sourceRejected
                    || KnowledgePythonBridge.activeDecisions(context, workspace, 0)
                            .getInt("total") != 0) {
                throw new SyntheticMismatch("verdict=BATCH_GUARDS_MISMATCH");
            }
            ensureRunning();
            final JSONObject accepted = KnowledgePythonBridge.acceptClaimBatch(context,
                    workspace, selections.toString(), preview.getString("batch_hash"), null);
            final JSONObject active = KnowledgePythonBridge.activeDecisions(context,
                    workspace, 0);
            boolean unchanged = true;
            for (int i = 0; i < paths.length; i++) {
                final JSONObject original = preview.getJSONArray("items").getJSONObject(i);
                final JSONObject after = KnowledgePythonBridge.preview(context, workspace,
                        paths[i], sources[i], selections.getJSONObject(i).getString("fragment_id"));
                unchanged &= original.getString("source_hash")
                        .equals(after.getString("source_hash"));
            }
            if (!"batch_accept".equals(accepted.getString("decision"))
                    || accepted.getInt("count") != 2
                    || accepted.getJSONArray("journal_ids").length() != 1
                    || active.getInt("total") != 2 || !unchanged) {
                throw new SyntheticMismatch("verdict=BATCH_COMMIT_MISMATCH");
            }
            boolean replayRejected = false;
            try {
                KnowledgePythonBridge.acceptClaimBatch(context, workspace,
                        selections.toString(), preview.getString("batch_hash"), null);
            } catch (Exception expected) {
                replayRejected = true;
            }
            if (!replayRejected) throw new SyntheticMismatch("verdict=BATCH_REPLAY_ALLOWED");
            return "syntheticClaims=2, activeAfterBatch=2, atomicJournalEntry=true, "
                    + "sourceExcerptVerified=true, opposingBundleRejected=true, "
                    + "staleHeadRejected=true, changedSourceRejected=true, replayRejected=true, "
                    + "sourceHashUnchanged=true; synthetic cache only";
        } finally {
            removeSandbox(sandbox);
        }
    }

    private static String quickStatus(final String value) {
        return "ok".equals(value) ? "ok" : "not-ok";
    }

    /** SQLite version is not user data, but still bound its characters and length. */
    private static String safeVersion(final String version) {
        return version != null && version.matches("[0-9]{1,3}(\\.[0-9]{1,3}){1,3}")
                ? version : "unknown";
    }

    private static void writeFixture(final File destination, final String content) throws IOException {
        try (FileOutputStream out = new FileOutputStream(destination)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] sha256(final File input) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(input)) {
            final byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return digest.digest();
    }

    /** Delete only our freshly created, uniquely named private fixture tree. */
    private static void removeSandbox(final File folder) {
        if (!folder.getName().startsWith("dpks-device-qa-")) {
            return;
        }
        deleteTree(folder);
    }

    private static void deleteTree(final File path) {
        if (path.isDirectory()) {
            final File[] children = path.listFiles();
            if (children != null) {
                for (final File child : children) {
                    deleteTree(child);
                }
            }
        }
        // Ignore cleanup failures; the cache can be cleared by Android later.
        path.delete();
    }

    private void ensureRunning() throws InterruptedIOException {
        if (isCancelled()) {
            throw new InterruptedIOException("Device QA cancelled");
        }
    }

    private boolean isCancelled() {
        return Thread.currentThread().isInterrupted()
                || (cancellation != null && cancellation.isCancelled());
    }

    private void notifyProgress(final String phase, final int done, final int total) {
        if (progress != null) {
            progress.onProgress(phase, done, total);
        }
    }

    private static String safeErrorClass(final Exception failure) {
        // Exception messages/stack traces may contain private absolute paths.
        return failure.getClass().getSimpleName();
    }

    private static String packageVersion(final Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0)
                    .versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            return "unavailable";
        }
    }

    private int batteryPercent() {
        final Intent battery = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) {
            return -1;
        }
        final int charge = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        return charge < 0 || scale <= 0 ? -1 : (int) ((long) charge * 100 / scale);
    }
}
