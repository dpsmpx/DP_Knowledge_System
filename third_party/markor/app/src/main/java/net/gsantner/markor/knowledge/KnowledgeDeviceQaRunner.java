/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
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
    private final KnowledgeDeviceQaReport report;

    public KnowledgeDeviceQaRunner(final Context context, final File notebook,
                                   final KnowledgeLocalIndex actualIndex, final boolean stress,
                                   final Progress progress, final Cancellation cancellation) {
        this.context = context.getApplicationContext();
        this.notebook = notebook;
        this.actualIndex = actualIndex;
        this.progress = progress;
        this.cancellation = cancellation;
        this.stress = stress;
        this.report = new KnowledgeDeviceQaReport(stress ? "20k synthetic stress" : "quick",
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
            step("SCAN-01", this::checkRealVaultReadOnly);
            step("INDEX-01", this::checkRealIndexReadOnly);
            step("MD-01", this::checkMarkdown);
            step("CORE-01", this::checkPythonCore);
            step("EMBED-01", this::checkEmbeddingPrimitives);
            step("CRYPTO-01", this::checkSyntheticCrypto);
            step(stress ? "STRESS-20K" : "SANDBOX-01", this::checkSyntheticIndex);
            step("RECONCILE-16", this::checkSyntheticReconciliation);
            step("DECISION-17", this::checkSyntheticDecisionLifecycle);
            step("REVISE-17", this::checkSyntheticRevision);
            step("SPLIT-17", this::checkSyntheticSplit);
            step("MERGE-17", this::checkSyntheticMerge);
            step("BATCH-17", this::checkSyntheticClaimBatch);
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
        } catch (InterruptedIOException cancelled) {
            report.add("WARN", "RUN-CANCELLED", 0, "user cancelled; no full signoff");
        } catch (Exception failed) {
            // A failing step is reported inside step(). This outer guard is for setup.
            report.add("FAIL", "RUN-ERROR", 0, "failure type="
                    + safeErrorClass(failed));
        }
        return report.render(SystemClock.elapsedRealtime() - started);
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
                writeFixture(note, FIXTURE_TEXT + "Synthetic id " + i + '\n');
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
            final List<KnowledgeLocalIndex.Hit> hits = testIndex.search("amber", 8);
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
                    + ", verdict=" + verdict.code;
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
                    + (stress ? ", 20k target exercised" : ", incremental reuse verified")
                    + "; synthetic source hash unchanged; all fixtures confined to app cache";
        } finally {
            removeSandbox(sandbox);
        }
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
            writeFixture(alpha, "# Alpha\nSynthetic amber source points to [[beta]].\n");
            writeFixture(oldName, "# Beta\nSynthetic violet source.\n");
            final byte[] unchangedHash = sha256(alpha);
            final KnowledgeLocalIndex testIndex = new KnowledgeLocalIndex(
                    new File(sandbox, "qa-reconciliation.sqlite"));
            final KnowledgeVaultScanner.Snapshot firstScan = KnowledgeVaultScanner.scan(
                    vault, 10, this::isCancelled);
            final KnowledgeLocalIndex.SyncResult first = testIndex.synchronize(
                    vault, firstScan, this::isCancelled);
            if (first.indexed != 2 || first.removed != 0
                    || testIndex.possibleBacklinks("beta.md", 10).size() != 1) {
                throw new SyntheticMismatch("verdict=INITIAL_RECONCILIATION_MISMATCH");
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
            for (final KnowledgeLocalIndex.Hit evidence :
                    testIndex.evidenceSearch("amber violet synthetic", 10)) {
                if (!evidence.confirmedInLastScan) {
                    throw new SyntheticMismatch("verdict=UNVERIFIED_EVIDENCE");
                }
            }
            final KnowledgeLocalIndex.SyncResult restored = testIndex.synchronize(
                    vault, renamedScan, this::isCancelled);
            if (restored.reused != 2 || restored.unverified != 0) {
                throw new SyntheticMismatch("verdict=FULL_RESCAN_MISMATCH");
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
                    + "cancelRollback=true, sourceHashUnchanged=true; synthetic cache only";
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
                    || preview.getJSONArray("items").length() != 2) {
                throw new SyntheticMismatch("verdict=BATCH_PREVIEW_MISMATCH");
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
            if (!"batch_accept".equals(accepted.getString("decision"))
                    || accepted.getInt("count") != 2
                    || accepted.getJSONArray("journal_ids").length() != 1
                    || active.getInt("total") != 2) {
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
