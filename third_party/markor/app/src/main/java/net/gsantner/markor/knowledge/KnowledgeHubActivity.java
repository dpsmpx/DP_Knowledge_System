/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Added to the Markor fork for DP Knowledge System.
 */
package net.gsantner.markor.knowledge;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import net.gsantner.markor.R;
import net.gsantner.markor.activity.DocumentActivity;
import net.gsantner.markor.activity.MarkorBaseActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Offline knowledge workspace: discover files without changing them, maintain
 * an app-private disposable text index, and open a chosen file in Markor.
 */
public final class KnowledgeHubActivity extends MarkorBaseActivity {
    private static final int MAX_VISIBLE = 250;
    private static final int MAX_EMBED_NOTES_PER_RUN = 120;
    private static final int REQUEST_EXPORT_JOURNAL = 40021;
    private static final int REQUEST_IMPORT_JOURNAL = 40022;
    private static final String[] AUDIT_CATEGORIES = {
            "authoritative_conflicts", "source_conflicts", "ambiguous",
            "unresolved", "unparsed", "source_problems", "knowledge_gaps"
    };
    private static final int[] AUDIT_CATEGORY_LABELS = {
            R.string.knowledge_audit_authoritative, R.string.knowledge_audit_source_conflicts,
            R.string.knowledge_audit_ambiguous, R.string.knowledge_audit_unresolved,
            R.string.knowledge_audit_unparsed, R.string.knowledge_audit_source_problems,
            R.string.knowledge_audit_knowledge_gaps
    };

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService analysisWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService embeddingWorker = Executors.newSingleThreadExecutor();
    private final AtomicInteger scanGeneration = new AtomicInteger();
    private final AtomicInteger queryGeneration = new AtomicInteger();
    private final AtomicInteger analysisGeneration = new AtomicInteger();
    private final AtomicInteger auditGeneration = new AtomicInteger();
    private final AtomicInteger embeddingGeneration = new AtomicInteger();
    private final List<KnowledgeLocalIndex.Hit> displayed = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    private Future<?> runningScan;
    private Future<?> runningSearch;
    private Future<?> runningAnalysis;
    private Future<?> runningAudit;
    private Future<?> runningEmbedding;
    private KnowledgeVaultScanner.Snapshot snapshot;
    private KnowledgeLocalIndex.SyncResult syncResult;
    private KnowledgeLocalIndex index;
    private File notebook;
    private ArrayAdapter<String> adapter;
    private TextView status;
    private TextView analysisStatus;
    private Button analysisButton;
    private Button auditButton;
    private Button embeddingButton;
    private CheckBox automaticAnalysis;
    private boolean analysisActive;
    private boolean auditActive;
    private boolean embeddingActive;
    private ProgressBar progress;
    private EditText filter;
    private char[] pendingExportPassword;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.knowledge__activity);

        final Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        final ActionBar bar = getSupportActionBar();
        if (bar != null) {
            bar.setTitle(R.string.knowledge_hub_title);
            bar.setDisplayHomeAsUpEnabled(true);
        }

        notebook = _appSettings.getNotebookDirectory();
        index = new KnowledgeLocalIndex(this);
        final TextView root = findViewById(R.id.knowledge_root);
        root.setText(notebook == null ? getString(R.string.knowledge_no_folder)
                : getString(R.string.knowledge_root_label, notebook.getAbsolutePath()));

        status = findViewById(R.id.knowledge_status);
        analysisStatus = findViewById(R.id.knowledge_bulk_status);
        analysisButton = findViewById(R.id.knowledge_bulk_analyze);
        embeddingButton = findViewById(R.id.knowledge_embedding_build);
        embeddingButton.setOnClickListener(view -> {
            if (embeddingActive) {
                cancelEmbeddingBuild();
            } else {
                promptEmbeddingSettings(this::prepareEmbeddingBuild);
            }
        });
        embeddingButton.setOnLongClickListener(view -> {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.knowledge_embedding_clear)
                    .setMessage(R.string.knowledge_embedding_clear_warning)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.knowledge_embedding_clear, (dialog, which) -> {
                        cancelEmbeddingBuild();
                        embeddingWorker.submit(() -> {
                            index.clearEmbeddings();
                            runOnUiThread(() -> {
                                if (!isFinishing() && !isDestroyed()) {
                                    status.setText(R.string.knowledge_embedding_cleared);
                                }
                            });
                        });
                    }).show();
            return true;
        });
        findViewById(R.id.knowledge_semantic_search).setOnClickListener(
                view -> promptSemanticQuestion());
        auditButton = findViewById(R.id.knowledge_full_audit);
        auditButton.setOnClickListener(view -> {
            if (auditActive) {
                cancelFullAudit();
            } else {
                startFullAudit();
            }
        });
        automaticAnalysis = findViewById(R.id.knowledge_bulk_auto);
        automaticAnalysis.setChecked(getPreferences(MODE_PRIVATE)
                .getBoolean("semantic_analysis_on_refresh", false));
        automaticAnalysis.setOnCheckedChangeListener((button, enabled) -> {
            getPreferences(MODE_PRIVATE).edit()
                    .putBoolean("semantic_analysis_on_refresh", enabled).apply();
            if (enabled && snapshot != null) {
                startBulkAnalysis();
            }
        });
        analysisButton.setOnClickListener(view -> {
            if (analysisActive) {
                cancelBulkAnalysis();
            } else {
                startBulkAnalysis();
            }
        });
        findViewById(R.id.knowledge_bulk_review).setOnClickListener(
                view -> showCandidateQueue(0));
        progress = findViewById(R.id.knowledge_progress);
        filter = findViewById(R.id.knowledge_filter);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        final ListView list = findViewById(R.id.knowledge_list);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < displayed.size()) {
                openIndexedNote(displayed.get(position).relativePath);
            }
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < displayed.size()) {
                final String path = displayed.get(position).relativePath;
                new AlertDialog.Builder(this)
                        .setTitle(path)
                        .setItems(new String[]{getString(R.string.knowledge_inspect_note),
                                        getString(R.string.knowledge_possible_backlinks),
                                        getString(R.string.knowledge_semantic_relations)},
                                (dialog, which) -> {
                                    if (which == 0) {
                                        inspectWithCore(path);
                                    } else if (which == 1) {
                                        showPossibleBacklinks(path);
                                    } else {
                                        showSemanticRelations(path);
                                    }
                                })
                        .show();
            }
            return true;
        });

        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshResults();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        final Button refresh = findViewById(R.id.knowledge_refresh);
        refresh.setOnClickListener(view -> scanNotebook());
        findViewById(R.id.knowledge_ask).setOnClickListener(view -> promptKnowledgeQuery());
        findViewById(R.id.knowledge_llm_ask).setOnClickListener(view -> promptLlmQuestion());
        findViewById(R.id.knowledge_diagnostics).setOnClickListener(view -> showKnowledgeDiagnostics());
        findViewById(R.id.knowledge_backup_export).setOnClickListener(view -> requestJournalExport());
        findViewById(R.id.knowledge_backup_import).setOnClickListener(view -> requestJournalImport());
        scanNotebook();
    }

    private void scanNotebook() {
        if (embeddingActive) {
            cancelEmbeddingBuild();
        }
        auditGeneration.incrementAndGet();
        if (auditActive) {
            cancelFullAudit();
        }
        if (analysisActive) {
            cancelBulkAnalysis();
        }
        final int generation = scanGeneration.incrementAndGet();
        queryGeneration.incrementAndGet();
        if (runningScan != null) {
            runningScan.cancel(true);
        }
        if (runningSearch != null) {
            runningSearch.cancel(true);
        }
        final File root = notebook;
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.knowledge_scanning);

        runningScan = worker.submit(() -> {
            try {
                final KnowledgeVaultScanner.Cancellation cancelled =
                        () -> Thread.currentThread().isInterrupted() || scanGeneration.get() != generation;
                final KnowledgeVaultScanner.Snapshot result =
                        KnowledgeVaultScanner.scan(root, KnowledgeVaultScanner.MAX_NOTES, cancelled);
                final KnowledgeLocalIndex.SyncResult projection =
                        index.synchronize(root, result, cancelled);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == generation) {
                        snapshot = result;
                        syncResult = projection;
                        progress.setVisibility(View.GONE);
                        refreshResults();
                        if (automaticAnalysis.isChecked()) {
                            startBulkAnalysis();
                        }
                    }
                });
            } catch (InterruptedIOException ignored) {
                // An older scan was superseded; never present partial results.
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == generation) {
                        progress.setVisibility(View.GONE);
                        status.setText(R.string.knowledge_unavailable);
                        snapshot = null;
                        displayed.clear();
                        labels.clear();
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        });
    }

    private void startBulkAnalysis() {
        if (auditActive) {
            analysisStatus.setText(R.string.knowledge_audit_wait);
            return;
        }
        if (notebook == null || !notebook.isDirectory() || isFinishing() || isDestroyed()) {
            analysisStatus.setText(R.string.knowledge_unavailable);
            return;
        }
        if (runningAnalysis != null) {
            runningAnalysis.cancel(true);
        }
        final int generation = analysisGeneration.incrementAndGet();
        analysisActive = true;
        analysisButton.setText(R.string.knowledge_bulk_cancel);
        analysisStatus.setText(getString(R.string.knowledge_bulk_running, 0));
        final String root = notebook.getAbsolutePath();
        runningAnalysis = analysisWorker.submit(() -> {
            try {
                final JSONObject result = KnowledgePythonBridge.analyzeVault(
                        this, workspacePath(), root, count -> {
                            if (Thread.currentThread().isInterrupted()
                                    || analysisGeneration.get() != generation) {
                                return false;
                            }
                            if (count % 25 == 0) {
                                runOnUiThread(() -> {
                                    if (!isFinishing() && !isDestroyed()
                                            && analysisGeneration.get() == generation) {
                                        analysisStatus.setText(getString(
                                                R.string.knowledge_bulk_running, count));
                                    }
                                });
                            }
                            return true;
                        });
                final JSONObject totals = result.getJSONObject("totals");
                final JSONObject byStatus = totals.getJSONObject("by_status");
                final int skipped = byStatus.optInt("SKIPPED_SIZE_LIMIT")
                        + byStatus.optInt("SKIPPED_UNREADABLE")
                        + byStatus.optInt("SKIPPED_OUTSIDE_VAULT");
                final boolean partial = result.optBoolean("truncated")
                        || result.optInt("unreadable_directories") > 0
                        || result.optInt("coverage_failures") > 0 || skipped > 0;
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()
                            && analysisGeneration.get() == generation) {
                        analysisActive = false;
                        analysisButton.setText(R.string.knowledge_bulk_analyze);
                        analysisStatus.setText(getString(R.string.knowledge_bulk_done,
                                totals.optInt("files"), totals.optInt("fragments"),
                                totals.optInt("candidates"), totals.optInt("unresolved"), skipped)
                                + (partial ? "\n" + getString(R.string.knowledge_bulk_partial) : ""));
                    }
                });
            } catch (Exception ignored) {
                // Do not log source content, filenames, Python exception bodies or tokens.
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()
                            && analysisGeneration.get() == generation) {
                        analysisActive = false;
                        analysisButton.setText(R.string.knowledge_bulk_analyze);
                        analysisStatus.setText(R.string.knowledge_bulk_failed);
                    }
                });
            }
        });
    }

    private void cancelBulkAnalysis() {
        analysisGeneration.incrementAndGet();
        if (runningAnalysis != null) {
            runningAnalysis.cancel(true);
        }
        analysisActive = false;
        analysisButton.setText(R.string.knowledge_bulk_analyze);
        analysisStatus.setText(R.string.knowledge_bulk_stopped);
    }

    private void startFullAudit() {
        if (analysisActive) {
            analysisStatus.setText(R.string.knowledge_audit_wait);
            return;
        }
        if (notebook == null || !notebook.isDirectory() || isFinishing() || isDestroyed()) {
            analysisStatus.setText(R.string.knowledge_unavailable);
            return;
        }
        final int generation = auditGeneration.incrementAndGet();
        auditActive = true;
        auditButton.setText(R.string.knowledge_audit_cancel);
        analysisStatus.setText(getString(R.string.knowledge_audit_running, 0));
        final String root = notebook.getAbsolutePath();
        runningAudit = analysisWorker.submit(() -> {
            try {
                final JSONObject result = KnowledgePythonBridge.fullAudit(
                        this, workspacePath(), root, count -> {
                            if (Thread.currentThread().isInterrupted()
                                    || auditGeneration.get() != generation) {
                                return false;
                            }
                            if (count % 25 == 0) {
                                runOnUiThread(() -> {
                                    if (!isFinishing() && !isDestroyed()
                                            && auditGeneration.get() == generation) {
                                        analysisStatus.setText(getString(
                                                R.string.knowledge_audit_running, count));
                                    }
                                });
                            }
                            return true;
                        });
                final JSONObject summary = result.getJSONObject("summary");
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()
                            && auditGeneration.get() == generation) {
                        auditActive = false;
                        auditButton.setText(R.string.knowledge_audit_start);
                        analysisStatus.setText(R.string.knowledge_audit_ready);
                        showAuditSummary(summary);
                    }
                });
            } catch (Exception ignored) {
                // Private text and Python errors must never enter public logs.
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()
                            && auditGeneration.get() == generation) {
                        auditActive = false;
                        auditButton.setText(R.string.knowledge_audit_start);
                        analysisStatus.setText(R.string.knowledge_audit_failed);
                    }
                });
            }
        });
    }

    private void cancelFullAudit() {
        auditGeneration.incrementAndGet();
        if (runningAudit != null) {
            runningAudit.cancel(true);
        }
        auditActive = false;
        auditButton.setText(R.string.knowledge_audit_start);
        analysisStatus.setText(R.string.knowledge_audit_cancelled);
    }

    private void showAuditSummary(final JSONObject summary) {
        final JSONObject numeric = summary.optJSONObject("numeric_consistency");
        final StringBuilder text = new StringBuilder(getString(R.string.knowledge_audit_summary,
                summary.optInt("indexed_documents"), summary.optInt("indexed_fragments"),
                summary.optInt("candidate_readings"), summary.optString("journal_integrity"),
                numeric == null ? "?" : numeric.optString("status")));
        text.append("\n\n").append(getString(R.string.knowledge_audit_scan_status,
                summary.optBoolean("scan_complete")
                        ? getString(R.string.knowledge_audit_complete)
                        : getString(R.string.knowledge_audit_incomplete),
                summary.optInt("skipped_this_scan"),
                summary.optInt("not_seen_in_latest_scan"),
                summary.optInt("unreadable_directories")));
        text.append("\n\n").append(getString(R.string.knowledge_audit_limit_notice));
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_audit_title)
                .setMessage(text.toString())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_audit_categories,
                        (dialog, which) -> showAuditCategories(summary))
                .show();
    }

    private void showAuditCategories(final JSONObject summary) {
        final JSONObject counts = summary.optJSONObject("counts");
        if (counts == null) {
            analysisStatus.setText(R.string.knowledge_audit_failed);
            return;
        }
        final String[] labels = new String[AUDIT_CATEGORIES.length];
        for (int i = 0; i < labels.length; i++) {
            final Object value = counts.opt(AUDIT_CATEGORIES[i]);
            final String number = value == null || JSONObject.NULL.equals(value)
                    ? "?" : String.valueOf(value);
            labels[i] = getString(AUDIT_CATEGORY_LABELS[i]) + " · " + number;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_audit_categories)
                .setItems(labels, (dialog, which) -> {
                    if (counts.isNull(AUDIT_CATEGORIES[which])) {
                        new AlertDialog.Builder(this)
                                .setMessage(R.string.knowledge_audit_journal_invalid)
                                .setPositiveButton(android.R.string.ok, null).show();
                    } else {
                        showAuditPage(AUDIT_CATEGORIES[which], 0);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showAuditPage(final String category, final int offset) {
        final int generation = auditGeneration.get();
        worker.submit(() -> {
            try {
                final JSONObject result = KnowledgePythonBridge.auditPage(
                        this, workspacePath(), category, offset);
                final JSONArray findings = result.getJSONArray("findings");
                final String[] names = new String[findings.length()];
                for (int i = 0; i < names.length; i++) {
                    names[i] = auditFindingLabel(category, findings.getJSONObject(i));
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || auditGeneration.get() != generation) {
                        return;
                    }
                    final AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                            .setTitle(R.string.knowledge_audit_title)
                            .setNegativeButton(android.R.string.cancel, null);
                    if (names.length == 0) {
                        dialog.setMessage(R.string.knowledge_audit_no_findings);
                    } else {
                        dialog.setItems(names, (d, position) -> {
                            final JSONObject finding = findings.optJSONObject(position);
                            if (finding != null) {
                                inspectAuditFinding(category, finding);
                            }
                        });
                    }
                    if (result.optBoolean("has_more")) {
                        dialog.setNeutralButton(R.string.knowledge_bulk_queue_next,
                                (d, which) -> showAuditPage(category, offset + names.length));
                    }
                    dialog.show();
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()
                            && auditGeneration.get() == generation) {
                        analysisStatus.setText(R.string.knowledge_audit_failed);
                    }
                });
            }
        });
    }

    private String auditFindingLabel(final String category, final JSONObject finding) {
        if ("source_conflicts".equals(category)) {
            final JSONObject a = finding.optJSONArray("positive_sources") == null ? null
                    : finding.optJSONArray("positive_sources").optJSONObject(0);
            final JSONObject b = finding.optJSONArray("negative_sources") == null ? null
                    : finding.optJSONArray("negative_sources").optJSONObject(0);
            return finding.optString("predicate") + " · "
                    + (a == null ? "?" : a.optString("path")) + " ↔ "
                    + (b == null ? "?" : b.optString("path"));
        }
        if ("authoritative_conflicts".equals(category)) {
            return finding.optString("positive") + " ↔ " + finding.optString("negative");
        }
        if ("knowledge_gaps".equals(category)) {
            return finding.optString("type") + " · " + finding.optString("reference")
                    + " " + finding.optString("detail");
        }
        return finding.optString("path") + " · "
                + finding.optString("locator", finding.optString("status")) + "\n"
                + finding.optString("preview");
    }

    private void inspectAuditFinding(final String category, final JSONObject finding) {
        if ("source_conflicts".equals(category)) {
            final JSONArray positives = finding.optJSONArray("positive_sources");
            final JSONArray negatives = finding.optJSONArray("negative_sources");
            final JSONObject a = positives == null ? null : positives.optJSONObject(0);
            final JSONObject b = negatives == null ? null : negatives.optJSONObject(0);
            final String first = a == null ? "" : a.optString("path");
            final String second = b == null ? "" : b.optString("path");
            new AlertDialog.Builder(this)
                    .setTitle(R.string.knowledge_audit_source_conflicts)
                    .setMessage(finding.optString("atom"))
                    .setNeutralButton(R.string.knowledge_audit_open_positive,
                            (d, which) -> openIndexedNote(first))
                    .setPositiveButton(R.string.knowledge_audit_open_negative,
                            (d, which) -> openIndexedNote(second))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else if ("authoritative_conflicts".equals(category)) {
            final String first = finding.optString("path");
            final String second = finding.optString("other_path");
            new AlertDialog.Builder(this)
                    .setTitle(R.string.knowledge_audit_authoritative)
                    .setMessage(finding.optString("positive") + "\n↔\n"
                            + finding.optString("negative"))
                    .setNeutralButton(R.string.knowledge_audit_open_positive,
                            (d, which) -> openIndexedNote(first))
                    .setPositiveButton(R.string.knowledge_audit_open_negative,
                            (d, which) -> openIndexedNote(second))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else if (!finding.optString("path").isEmpty()) {
            openIndexedNote(finding.optString("path"));
        } else {
            new AlertDialog.Builder(this)
                    .setMessage(auditFindingLabel(category, finding))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void showCandidateQueue(final int offset) {
        final int generation = analysisGeneration.get();
        worker.submit(() -> {
            try {
                final JSONObject page = KnowledgePythonBridge.reviewQueue(
                        this, workspacePath(), offset);
                final JSONArray candidates = page.getJSONArray("candidates");
                final String[] names = new String[candidates.length()];
                for (int i = 0; i < names.length; i++) {
                    final JSONObject item = candidates.getJSONObject(i);
                    names[i] = item.optString("path") + " · " + item.optString("locator")
                            + "\n" + item.optString("preview");
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || analysisGeneration.get() != generation) {
                        return;
                    }
                    if (names.length == 0) {
                        new AlertDialog.Builder(this)
                                .setMessage(R.string.knowledge_bulk_queue_empty)
                                .setPositiveButton(android.R.string.ok, null).show();
                        return;
                    }
                    final AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.knowledge_bulk_queue_title,
                                    page.optInt("total")))
                            .setItems(names, (view, selected) -> {
                                final JSONObject item = candidates.optJSONObject(selected);
                                if (item != null) {
                                    previewFragment(item.optString("path"),
                                            item.optString("fragment_id"));
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null);
                    if (offset + names.length < page.optInt("total")) {
                        dialog.setNeutralButton(R.string.knowledge_bulk_queue_next,
                                (view, which) -> showCandidateQueue(offset + names.length));
                    }
                    dialog.show();
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()
                            && analysisGeneration.get() == generation) {
                        analysisStatus.setText(R.string.knowledge_bulk_failed);
                    }
                });
            }
        });
    }

    private void refreshResults() {
        if (snapshot == null) {
            return;
        }
        final String query = filter.getText().toString().trim();
        final int generation = queryGeneration.incrementAndGet();
        if (runningSearch != null) {
            runningSearch.cancel(true);
        }
        runningSearch = worker.submit(() -> {
            try {
                final List<KnowledgeLocalIndex.Hit> hits = index.search(query, MAX_VISIBLE);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && queryGeneration.get() == generation) {
                        displayed.clear();
                        labels.clear();
                        for (final KnowledgeLocalIndex.Hit hit : hits) {
                            displayed.add(hit);
                            labels.add(hit.title.equals(hit.relativePath) ? hit.relativePath
                                    : hit.title + " — " + hit.relativePath);
                        }
                        adapter.notifyDataSetChanged();
                        showIndexStatus(hits.size());
                    }
                });
            } catch (RuntimeException ignored) {
                runOnUiThread(() -> {
                    if (queryGeneration.get() == generation && !isFinishing()) {
                        status.setText(R.string.knowledge_index_failed);
                    }
                });
            }
        });
    }

    private void showIndexStatus(final int displayedCount) {
        final StringBuilder summary = new StringBuilder(getString(R.string.knowledge_index_status,
                snapshot.notes.size(), displayedCount, syncResult.indexed, syncResult.reused));
        if (syncResult.oversized > 0) {
            summary.append(' ').append(getString(R.string.knowledge_oversized, syncResult.oversized));
        }
        if (syncResult.keptMissingEntries) {
            summary.append(' ').append(getString(R.string.knowledge_partial_index));
        }
        status.setText(summary);
    }

    private void openIndexedNote(final String relativePath) {
        if (notebook == null) {
            return;
        }
        try {
            final String rootPath = notebook.getCanonicalPath() + File.separator;
            final File file = new File(notebook, relativePath).getCanonicalFile();
            if (file.getPath().startsWith(rootPath) && file.isFile()) {
                DocumentActivity.launch(this, file, false, null);
                return;
            }
        } catch (IOException | SecurityException ignored) {
            // A moved or revoked source is never opened through a stale index.
        }
        Toast.makeText(this, R.string.knowledge_file_missing, Toast.LENGTH_SHORT).show();
    }

    private void showPossibleBacklinks(final String relativePath) {
        final int scan = scanGeneration.get();
        worker.submit(() -> {
            try {
                final List<String> links = index.possibleBacklinks(relativePath, 50);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.knowledge_possible_backlinks)
                                .setItems(links.isEmpty()
                                        ? new String[]{getString(R.string.knowledge_no_backlinks)}
                                        : links.toArray(new String[0]),
                                        (dialog, which) -> {
                                            if (which < links.size()) {
                                                openIndexedNote(links.get(which));
                                            }
                                        })
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    }
                });
            } catch (RuntimeException ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && scanGeneration.get() == scan) {
                        Toast.makeText(this, R.string.knowledge_index_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void showSemanticRelations(final String relativePath) {
        final int scan = scanGeneration.get();
        status.setText(R.string.knowledge_semantic_working);
        worker.submit(() -> {
            try {
                final JSONObject result = KnowledgePythonBridge.relationships(
                        this, workspacePath(), relativePath);
                final JSONArray matches = result.getJSONArray("matches");
                final String[] labels = new String[matches.length()];
                for (int i = 0; i < labels.length; i++) {
                    final JSONObject match = matches.getJSONObject(i);
                    final boolean conflict = "possible_conflict".equals(match.optString("kind"));
                    labels[i] = getString(conflict ? R.string.knowledge_possible_conflict
                                    : R.string.knowledge_possible_agreement)
                            + " · " + match.optString("path")
                            + "\n" + match.optString("predicate") + " · "
                            + match.optString("source_locator") + " ↔ "
                            + match.optString("other_locator");
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || scanGeneration.get() != scan) {
                        return;
                    }
                    final AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.knowledge_semantic_relations)
                                    + (result.optBoolean("truncated")
                                    ? " · " + getString(R.string.knowledge_semantic_limited) : ""))
                            .setPositiveButton(android.R.string.ok, null);
                    if (labels.length == 0) {
                        dialog.setMessage(R.string.knowledge_no_semantic_relations);
                    } else {
                        dialog.setItems(labels, (d, position) -> {
                            final JSONObject match = matches.optJSONObject(position);
                            if (match != null) {
                                openIndexedNote(match.optString("path"));
                            }
                        });
                    }
                    dialog.show();
                    if (snapshot != null && syncResult != null) {
                        showIndexStatus(displayed.size());
                    }
                });
            } catch (Exception ignored) {
                // Never include note paths or private source content in logs.
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_semantic_failed);
                    }
                });
            }
        });
    }

    private void inspectWithCore(final String relativePath) {
        final int scan = scanGeneration.get();
        status.setText(R.string.knowledge_inspecting);
        worker.submit(() -> {
            try {
                final String source = readSelectedNote(relativePath);
                final JSONObject result = KnowledgePythonBridge.inspect(this, relativePath, source);
                final String formatted = formatCoreResult(result);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.knowledge_inspection_title)
                                .setMessage(formatted)
                                .setNeutralButton(R.string.knowledge_review_fragments,
                                        (dialog, which) -> chooseFragment(relativePath, result))
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                        if (syncResult != null && snapshot != null) {
                            showIndexStatus(displayed.size());
                        }
                    }
                });
            } catch (Exception ignored) {
                // Never print source text or engine exceptions into public logs.
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_analysis_failed);
                    }
                });
            }
        });
    }

    private String readSelectedNote(final String relativePath) throws IOException {
        if (notebook == null) {
            throw new IOException("No notebook");
        }
        final File root = notebook.getCanonicalFile();
        final File source = new File(root, relativePath).getCanonicalFile();
        if (!source.getPath().startsWith(root.getPath() + File.separator)
                || !source.isFile() || source.length() > 2_000_000) {
            throw new IOException("Note absent, escaped root or oversized");
        }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        try (FileInputStream in = new FileInputStream(source)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted() || bytes.size() + n > 2_000_000) {
                    throw new IOException("Inspection cancelled or source too large");
                }
                bytes.write(buffer, 0, n);
            }
        }
        return new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private String formatCoreResult(final JSONObject result) throws Exception {
        final StringBuilder b = new StringBuilder();
        b.append(getString(R.string.knowledge_core_version, result.getString("engine_version")))
                .append('\n').append(getString(R.string.knowledge_core_coverage,
                        result.getBoolean("coverage_ok")))
                .append('\n').append(getString(R.string.knowledge_core_counts,
                        result.getInt("block_count"), result.getInt("fragment_count")))
                .append('\n').append(getString(R.string.knowledge_unconfirmed))
                .append("\n\n");
        final JSONArray fragments = result.getJSONArray("fragments");
        for (int i = 0; i < fragments.length(); i++) {
            final JSONObject fragment = fragments.getJSONObject(i);
            b.append(fragment.getString("locator")).append(" · ")
                    .append(fragment.getString("kind")).append(" / ")
                    .append(fragment.getString("formalization")).append(" · ")
                    .append(fragment.getInt("candidate_count"))
                    .append('\n').append(fragment.getString("preview")).append("\n\n");
        }
        if (result.optBoolean("partial_display")) {
            b.append(getString(R.string.knowledge_inspection_partial));
        }
        return b.toString();
    }

    private String workspacePath() {
        return new File(getNoBackupFilesDir(), "knowledge_workspace_v1").getAbsolutePath();
    }

    private void chooseFragment(final String relativePath, final JSONObject inspection) {
        try {
            final JSONArray fragments = inspection.getJSONArray("fragments");
            final String[] names = new String[fragments.length()];
            for (int i = 0; i < fragments.length(); i++) {
                final JSONObject f = fragments.getJSONObject(i);
                names[i] = f.getString("locator") + " · " + f.getString("preview");
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.knowledge_review_fragments)
                    .setItems(names, (dialog, which) -> {
                        final JSONObject selected = fragments.optJSONObject(which);
                        if (selected != null) {
                            previewFragment(relativePath, selected.optString("id"));
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } catch (Exception ignored) {
            Toast.makeText(this, R.string.knowledge_analysis_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void previewFragment(final String relativePath, final String fragmentId) {
        final int scan = scanGeneration.get();
        status.setText(R.string.knowledge_inspecting);
        worker.submit(() -> {
            try {
                final JSONObject preview = KnowledgePythonBridge.preview(this, workspacePath(),
                        relativePath, readSelectedNote(relativePath), fragmentId);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        chooseReading(relativePath, fragmentId, preview);
                        if (syncResult != null && snapshot != null) {
                            showIndexStatus(displayed.size());
                        }
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_analysis_failed);
                    }
                });
            }
        });
    }

    private void chooseReading(final String relativePath, final String fragmentId,
                               final JSONObject packet) {
        final JSONObject preview = packet.optJSONObject("preview");
        if (preview == null) {
            return;
        }
        final JSONArray options = preview.optJSONArray("options");
        if (options == null || options.length() == 0) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.knowledge_no_interpretation)
                    .setMessage(preview.optString("reason", getString(R.string.knowledge_no_interpretation)))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        final String[] labels = new String[options.length()];
        for (int i = 0; i < options.length(); i++) {
            final JSONObject option = options.optJSONObject(i);
            labels[i] = option == null ? "?" : option.optString("reading_id") + " · "
                    + option.optString("paraphrase");
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.knowledge_select_interpretation,
                        preview.optString("status")))
                .setItems(labels, (dialog, which) -> {
                    final JSONObject option = options.optJSONObject(which);
                    if (option != null) {
                        chooseDecision(relativePath, fragmentId, packet, option);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void chooseDecision(final String relativePath, final String fragmentId,
                                final JSONObject packet, final JSONObject option) {
        final String[] roles = {"claim", "hypothesis", "fact", "reject", "defer"};
        final String[] labels = {
                getString(R.string.knowledge_decision_claim),
                getString(R.string.knowledge_decision_hypothesis),
                getString(R.string.knowledge_decision_fact),
                getString(R.string.knowledge_decision_reject),
                getString(R.string.knowledge_decision_defer)
        };
        final JSONObject preview = packet.optJSONObject("preview");
        final String previewStatus = preview == null ? "" : preview.optString("status");
        final JSONArray diagnostics = option.optJSONArray("diagnostics");
        boolean errors = false;
        if (diagnostics != null) {
            for (int i = 0; i < diagnostics.length(); i++) {
                final JSONObject d = diagnostics.optJSONObject(i);
                if (d != null && "error".equalsIgnoreCase(d.optString("severity"))) {
                    errors = true;
                }
            }
        }
        final boolean canAccept = !"UNRESOLVED".equals(previewStatus) && !errors;
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_select_decision)
                .setItems(labels, (dialog, which) -> {
                    if (which < 3 && !canAccept) {
                        Toast.makeText(this, R.string.knowledge_cannot_accept,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    confirmDecision(relativePath, fragmentId, packet, option, roles[which], labels[which]);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDecision(final String relativePath, final String fragmentId,
                                 final JSONObject packet, final JSONObject option,
                                 final String role, final String roleLabel) {
        final StringBuilder details = new StringBuilder();
        details.append(getString(R.string.knowledge_unconfirmed)).append("\n\n")
                .append(relativePath).append("\n")
                .append(packet.optString("locator")).append("\n\n")
                .append(packet.optString("source_text")).append("\n\n")
                .append(option.optString("paraphrase")).append("\n\n")
                .append(option.optJSONArray("ksf")).append("\n\n")
                .append(getString(R.string.knowledge_conflicts,
                        option.optJSONArray("conflicts") == null ? 0 :
                                option.optJSONArray("conflicts").length())).append("\n")
                .append(getString(R.string.knowledge_diagnostic_count,
                        option.optJSONArray("diagnostics") == null ? 0 :
                                option.optJSONArray("diagnostics").length())).append("\n\n")
                .append(getString(R.string.knowledge_decision_warning));
        new AlertDialog.Builder(this)
                .setTitle(roleLabel)
                .setMessage(details.toString())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_confirm_decision,
                        (dialog, which) -> recordDecision(relativePath, fragmentId, packet,
                                option.optString("reading_id"), role))
                .show();
    }

    private void recordDecision(final String relativePath, final String fragmentId,
                                final JSONObject packet, final String readingId, final String role) {
        final int scan = scanGeneration.get();
        status.setText(R.string.knowledge_recording_decision);
        worker.submit(() -> {
            try {
                final String head = packet.isNull("journal_head")
                        ? null : packet.getString("journal_head");
                final JSONObject result = KnowledgePythonBridge.decide(this, workspacePath(),
                        relativePath, readSelectedNote(relativePath), fragmentId, readingId,
                        role, packet.getString("source_hash"), head);
                if (result.optJSONArray("journal_ids") == null) {
                    throw new IllegalStateException("Missing knowledge journal identifier");
                }
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        Toast.makeText(this, R.string.knowledge_decision_saved,
                                Toast.LENGTH_LONG).show();
                        if (syncResult != null && snapshot != null) {
                            showIndexStatus(displayed.size());
                        }
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_decision_failed);
                    }
                });
            }
        });
    }

    private interface EmbeddingReady {
        void proceed(URL endpoint, String model, String apiKey);
    }

    private interface SemanticMatches {
        void receive(List<KnowledgeLocalIndex.SemanticHit> matches);
    }

    private void promptEmbeddingSettings(final EmbeddingReady onReady) {
        final LinearLayout fields = new LinearLayout(this);
        fields.setPadding(24, 8, 24, 8);
        fields.setOrientation(LinearLayout.VERTICAL);
        final EditText endpointText = new EditText(this);
        endpointText.setSingleLine(true);
        endpointText.setHint(R.string.knowledge_llm_endpoint_hint);
        endpointText.setText(getPreferences(MODE_PRIVATE)
                .getString("knowledge_embedding_endpoint", ""));
        final EditText modelText = new EditText(this);
        modelText.setSingleLine(true);
        modelText.setHint(R.string.knowledge_embedding_model_hint);
        modelText.setText(getPreferences(MODE_PRIVATE)
                .getString("knowledge_embedding_model", ""));
        final EditText apiKeyText = new EditText(this);
        apiKeyText.setSingleLine(true);
        apiKeyText.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyText.setHint(R.string.knowledge_llm_key_hint);
        fields.addView(endpointText);
        fields.addView(modelText);
        fields.addView(apiKeyText);
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_embedding_settings)
                .setView(fields)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_llm_continue, (dialog, which) -> {
                    try {
                        final URL endpoint = KnowledgeEmbeddingClient.endpoint(
                                endpointText.getText().toString());
                        final String model = modelText.getText().toString().trim();
                        KnowledgeEmbeddingClient.modelKey(endpoint, model);
                        final String key = apiKeyText.getText().toString();
                        if (key.length() > 1024 || key.indexOf('\n') >= 0
                                || key.indexOf('\r') >= 0) {
                            throw new IllegalArgumentException("Invalid key");
                        }
                        // Only endpoint and model metadata are saved. The key is per-action.
                        getPreferences(MODE_PRIVATE).edit()
                                .putString("knowledge_embedding_endpoint", endpoint.toString())
                                .putString("knowledge_embedding_model", model).apply();
                        onReady.proceed(endpoint, model, key);
                    } catch (IllegalArgumentException invalid) {
                        Toast.makeText(this, R.string.knowledge_embedding_config_invalid,
                                Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void prepareEmbeddingBuild(final URL endpoint, final String model,
                                       final String apiKey) {
        final KnowledgeVaultScanner.Snapshot selectedSnapshot = snapshot;
        if (selectedSnapshot == null) {
            status.setText(R.string.knowledge_embedding_need_scan);
            return;
        }
        final int scan = scanGeneration.get();
        final String key = KnowledgeEmbeddingClient.modelKey(endpoint, model);
        status.setText(R.string.knowledge_embedding_batch_check);
        worker.submit(() -> {
            try {
                final Set<String> done = index.embeddedPaths(key);
                final List<KnowledgeVaultScanner.Note> pending = new ArrayList<>();
                for (final KnowledgeVaultScanner.Note note : selectedSnapshot.notes) {
                    if (scanGeneration.get() != scan || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    if (note.sizeBytes <= KnowledgeLocalIndex.MAX_INDEX_BYTES_PER_NOTE
                            && !done.contains(note.relativePath)) {
                        pending.add(note);
                        if (pending.size() >= MAX_EMBED_NOTES_PER_RUN) {
                            break;
                        }
                    }
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || scanGeneration.get() != scan) {
                        return;
                    }
                    if (pending.isEmpty()) {
                        status.setText(R.string.knowledge_embedding_up_to_date);
                        return;
                    }
                    final StringBuilder names = new StringBuilder();
                    for (int i = 0; i < Math.min(40, pending.size()); i++) {
                        names.append("\n• ").append(pending.get(i).relativePath);
                    }
                    if (pending.size() > 40) {
                        names.append("\n… +").append(pending.size() - 40);
                    }
                    final String plainWarning = "http".equals(endpoint.getProtocol())
                            ? "\n\n" + getString(R.string.knowledge_llm_plaintext_warning) : "";
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.knowledge_embedding_consent_title)
                            .setMessage(getString(R.string.knowledge_embedding_consent,
                                    pending.size(), endpoint.getHost(), model,
                                    getString(R.string.knowledge_embedding_partial_warning))
                                    + plainWarning + names)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.knowledge_llm_send,
                                    (dialog, which) -> startEmbeddingBuild(endpoint, model,
                                            apiKey, key, pending, scan))
                            .show();
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_embedding_failed);
                    }
                });
            }
        });
    }

    private void cancelEmbeddingBuild() {
        embeddingGeneration.incrementAndGet();
        if (runningEmbedding != null) {
            runningEmbedding.cancel(true);
        }
        embeddingActive = false;
        if (embeddingButton != null) {
            embeddingButton.setText(R.string.knowledge_embedding_build);
        }
        if (status != null) {
            status.setText(R.string.knowledge_embedding_cancelled);
        }
    }

    private void startEmbeddingBuild(final URL endpoint, final String model, final String apiKey,
                                     final String modelKey,
                                     final List<KnowledgeVaultScanner.Note> pending,
                                     final int scan) {
        if (embeddingActive || scanGeneration.get() != scan) {
            return;
        }
        embeddingActive = true;
        embeddingButton.setText(R.string.knowledge_embedding_cancel);
        final int operation = embeddingGeneration.incrementAndGet();
        status.setText(getString(R.string.knowledge_embedding_running, 0, pending.size()));
        runningEmbedding = embeddingWorker.submit(() -> {
            int completed = 0;
            int skipped = 0;
            try {
                for (int i = 0; i < pending.size(); i += 2) {
                    if (Thread.currentThread().isInterrupted() || scanGeneration.get() != scan
                            || embeddingGeneration.get() != operation) {
                        return;
                    }
                    final List<String> inputs = new ArrayList<>();
                    final List<KnowledgeVaultScanner.Note> noteBatch = new ArrayList<>();
                    final List<List<KnowledgeEmbeddingChunks.Chunk>> chunksBatch = new ArrayList<>();
                    for (int j = i; j < Math.min(pending.size(), i + 2); j++) {
                        final KnowledgeVaultScanner.Note note = pending.get(j);
                        try {
                            if (note.file.lastModified() != note.modifiedAtMillis
                                    || note.file.length() != note.sizeBytes) {
                                skipped++;
                                continue;
                            }
                            final String text = readSelectedNote(note.relativePath);
                            if (note.file.lastModified() != note.modifiedAtMillis
                                    || note.file.length() != note.sizeBytes) {
                                skipped++;
                                continue;
                            }
                            final List<KnowledgeEmbeddingChunks.Chunk> chunks =
                                    KnowledgeEmbeddingChunks.fromNote(text);
                            if (chunks.isEmpty()) {
                                skipped++;
                                continue;
                            }
                            noteBatch.add(note);
                            chunksBatch.add(chunks);
                            for (final KnowledgeEmbeddingChunks.Chunk chunk : chunks) {
                                inputs.add(chunk.text);
                            }
                        } catch (IOException inaccessible) {
                            skipped++;
                        }
                    }
                    if (!inputs.isEmpty()) {
                        final float[][] vectors = KnowledgeEmbeddingClient.embed(
                                endpoint, model, apiKey, inputs);
                        int offset = 0;
                        for (int j = 0; j < noteBatch.size(); j++) {
                            final KnowledgeVaultScanner.Note note = noteBatch.get(j);
                            final List<KnowledgeEmbeddingChunks.Chunk> chunks = chunksBatch.get(j);
                            if (note.file.lastModified() != note.modifiedAtMillis
                                    || note.file.length() != note.sizeBytes) {
                                skipped++;
                                offset += chunks.size();
                                continue;
                            }
                            final float[][] assigned = Arrays.copyOfRange(
                                    vectors, offset, offset + chunks.size());
                            offset += chunks.size();
                            index.storeEmbeddings(note.relativePath, note.modifiedAtMillis,
                                    note.sizeBytes, modelKey, chunks, assigned);
                            completed++;
                        }
                    }
                    final int progressNow = Math.min(pending.size(), i + 2);
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()
                                && embeddingGeneration.get() == operation) {
                            status.setText(getString(R.string.knowledge_embedding_running,
                                    progressNow, pending.size()));
                        }
                    });
                }
                final int succeeded = completed;
                final int missing = skipped;
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()
                            && embeddingGeneration.get() == operation) {
                        embeddingActive = false;
                        embeddingButton.setText(R.string.knowledge_embedding_build);
                        status.setText(getString(R.string.knowledge_embedding_done,
                                succeeded, missing));
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()
                            && embeddingGeneration.get() == operation) {
                        embeddingActive = false;
                        embeddingButton.setText(R.string.knowledge_embedding_build);
                        status.setText(R.string.knowledge_embedding_failed);
                    }
                });
            }
        });
    }

    private void promptSemanticQuestion() {
        final EditText question = new EditText(this);
        question.setHint(R.string.knowledge_embedding_query);
        question.setSingleLine(false);
        question.setMinLines(2);
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_semantic_search)
                .setView(question)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_llm_continue, (dialog, which) -> {
                    final String value = question.getText().toString().trim();
                    if (value.isEmpty() || value.length() > 1200) {
                        status.setText(R.string.knowledge_llm_invalid);
                    } else {
                        promptEmbeddingSettings((endpoint, model, key) ->
                                consentSemanticQuestion(value, endpoint, model, key,
                                        this::showSemanticResults));
                    }
                }).show();
    }

    private void consentSemanticQuestion(final String question, final URL endpoint,
                                         final String model, final String apiKey,
                                         final SemanticMatches callback) {
        final String modelKey = KnowledgeEmbeddingClient.modelKey(endpoint, model);
        if (index.embeddingNoteCount(modelKey) < 1) {
            status.setText(R.string.knowledge_embedding_no_index);
            return;
        }
        final String transportWarning = "http".equals(endpoint.getProtocol())
                ? "\n" + getString(R.string.knowledge_llm_plaintext_warning) : "";
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_embedding_send_query)
                .setMessage(getString(R.string.knowledge_embedding_query_consent,
                        endpoint.getHost(), model) + transportWarning + "\n\n" + question)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_embedding_send_query,
                        (dialog, which) -> performSemanticQuery(
                                question, endpoint, model, apiKey, modelKey, callback))
                .show();
    }

    private void performSemanticQuery(final String question, final URL endpoint,
                                      final String model, final String apiKey,
                                      final String modelKey, final SemanticMatches callback) {
        final int scan = scanGeneration.get();
        status.setText(R.string.knowledge_embedding_querying);
        embeddingWorker.submit(() -> {
            try {
                final float[] query = KnowledgeEmbeddingClient.embed(endpoint, model, apiKey,
                        Collections.singletonList(question))[0];
                final List<KnowledgeLocalIndex.SemanticHit> matches =
                        index.semanticSearch(modelKey, query, 24);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        callback.receive(matches);
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_embedding_failed);
                    }
                });
            }
        });
    }

    private void showSemanticResults(final List<KnowledgeLocalIndex.SemanticHit> matches) {
        if (matches.isEmpty()) {
            status.setText(R.string.knowledge_embedding_no_matches);
            return;
        }
        final String[] items = new String[matches.size()];
        for (int i = 0; i < matches.size(); i++) {
            final KnowledgeLocalIndex.SemanticHit match = matches.get(i);
            items[i] = String.format(java.util.Locale.ROOT, "%.3f · %s",
                    match.score, match.relativePath);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_embedding_results)
                .setItems(items, (dialog, which) ->
                        openIndexedNote(matches.get(which).relativePath))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void maybeOfferSemanticForLlm(final String question, final URL chatEndpoint,
                                          final String chatModel, final String chatKey) {
        final String savedEndpoint = getPreferences(MODE_PRIVATE)
                .getString("knowledge_embedding_endpoint", "");
        final String savedModel = getPreferences(MODE_PRIVATE)
                .getString("knowledge_embedding_model", "");
        try {
            final URL endpoint = KnowledgeEmbeddingClient.endpoint(savedEndpoint);
            final String key = KnowledgeEmbeddingClient.modelKey(endpoint, savedModel);
            if (index.embeddingNoteCount(key) > 0) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.knowledge_embedding_choose_retrieval)
                        .setMessage(R.string.knowledge_embedding_choose_retrieval_message)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setNeutralButton(R.string.knowledge_embedding_use_lexical,
                                (dialog, which) -> prepareLlmEvidence(
                                        question, chatEndpoint, chatModel, chatKey))
                        .setPositiveButton(R.string.knowledge_embedding_use_semantic,
                                (dialog, which) -> promptEmbeddingSettings(
                                        (queryEndpoint, queryModel, embeddingKey) ->
                                                consentSemanticQuestion(question, queryEndpoint,
                                                        queryModel, embeddingKey, ranked ->
                                                                prepareSemanticLlmEvidence(question,
                                                                        chatEndpoint, chatModel,
                                                                        chatKey, ranked))))
                        .show();
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // No configured semantic index yet: the established lexical path still works.
        }
        prepareLlmEvidence(question, chatEndpoint, chatModel, chatKey);
    }

    private void prepareSemanticLlmEvidence(final String question, final URL endpoint,
                                            final String model, final String apiKey,
                                            final List<KnowledgeLocalIndex.SemanticHit> ranked) {
        final int scan = scanGeneration.get();
        status.setText(R.string.knowledge_llm_retrieving);
        worker.submit(() -> {
            try {
                final List<KnowledgeLlmEvidence.Item> sources = new ArrayList<>();
                for (final KnowledgeLocalIndex.SemanticHit match : ranked) {
                    if (Thread.currentThread().isInterrupted() || scanGeneration.get() != scan) {
                        return;
                    }
                    if (sources.size() >= KnowledgeLlmClient.MAX_EVIDENCE) {
                        break;
                    }
                    try {
                        final File file = new File(notebook, match.relativePath);
                        if (file.lastModified() != match.modifiedAtMillis
                                || file.length() != match.sizeBytes) {
                            continue;
                        }
                        final String content = readSelectedNote(match.relativePath);
                        if (file.lastModified() != match.modifiedAtMillis
                                || file.length() != match.sizeBytes) {
                            continue;
                        }
                        final String excerpt = KnowledgeEmbeddingChunks.atOffset(
                                content, match.excerptStart);
                        if (!excerpt.trim().isEmpty()) {
                            sources.add(new KnowledgeLlmEvidence.Item(match.relativePath, excerpt));
                        }
                    } catch (IOException ignored) {
                        // Changed or inaccessible indexed files are never disclosed.
                    }
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || scanGeneration.get() != scan) {
                        return;
                    }
                    if (sources.isEmpty()) {
                        status.setText(R.string.knowledge_llm_no_sources);
                    } else {
                        requestLlmConsent(endpoint, model, apiKey,
                                KnowledgeLlmClient.requestPreview(question, sources), sources);
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_llm_failed);
                    }
                });
            }
        });
    }

    private void promptLlmQuestion() {
        final EditText question = new EditText(this);
        question.setHint(R.string.knowledge_llm_question);
        question.setSingleLine(false);
        question.setMinLines(2);
        question.setMaxLines(4);
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_llm_ask)
                .setView(question)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_llm_continue,
                        (dialog, which) -> promptLlmEndpoint(question.getText().toString()))
                .show();
    }

    private void promptLlmEndpoint(final String question) {
        if (question.trim().isEmpty() || question.length() > 1200) {
            Toast.makeText(this, R.string.knowledge_llm_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        final LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(24, 8, 24, 8);
        final EditText endpoint = new EditText(this);
        endpoint.setSingleLine(true);
        endpoint.setHint(R.string.knowledge_llm_endpoint_hint);
        endpoint.setText(getPreferences(MODE_PRIVATE).getString("knowledge_llm_endpoint", ""));
        final EditText model = new EditText(this);
        model.setSingleLine(true);
        model.setHint(R.string.knowledge_llm_model_hint);
        model.setText(getPreferences(MODE_PRIVATE).getString("knowledge_llm_model", ""));
        final EditText apiKey = new EditText(this);
        apiKey.setSingleLine(true);
        apiKey.setHint(R.string.knowledge_llm_key_hint);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(endpoint);
        fields.addView(model);
        fields.addView(apiKey);
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_llm_settings)
                .setView(fields)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_llm_continue, (dialog, which) -> {
                    try {
                        final URL selected = KnowledgeLlmClient.endpoint(endpoint.getText().toString());
                        final String selectedModel = model.getText().toString().trim();
                        if (selectedModel.isEmpty() || selectedModel.length() > 128) {
                            throw new IllegalArgumentException("Missing model");
                        }
                        // Store only non-secret endpoint/model metadata. API keys
                        // are session-only and never enter preferences or logs.
                        getPreferences(MODE_PRIVATE).edit()
                                .putString("knowledge_llm_endpoint", selected.toString())
                                .putString("knowledge_llm_model", selectedModel).apply();
                        maybeOfferSemanticForLlm(question, selected, selectedModel,
                                apiKey.getText().toString());
                    } catch (IllegalArgumentException invalid) {
                        Toast.makeText(this, R.string.knowledge_llm_invalid,
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void prepareLlmEvidence(final String question, final URL endpoint,
                                    final String model, final String apiKey) {
        final int generation = scanGeneration.get();
        status.setText(R.string.knowledge_llm_retrieving);
        worker.submit(() -> {
            try {
                final List<KnowledgeLocalIndex.Hit> matches = index.evidenceSearch(question, 24);
                final List<KnowledgeLlmEvidence.Item> excerpts = new ArrayList<>();
                for (final KnowledgeLocalIndex.Hit match : matches) {
                    if (Thread.currentThread().isInterrupted() || scanGeneration.get() != generation) {
                        return;
                    }
                    if (excerpts.size() >= KnowledgeLlmClient.MAX_EVIDENCE) {
                        break;
                    }
                    try {
                        final String content = readSelectedNote(match.relativePath);
                        final String excerpt = KnowledgeLlmEvidence.excerpt(content, question);
                        if (!excerpt.trim().isEmpty()) {
                            excerpts.add(new KnowledgeLlmEvidence.Item(match.relativePath, excerpt));
                        }
                    } catch (IOException ignored) {
                        // An inaccessible or stale indexed note must not be sent.
                    }
                }
                if (excerpts.isEmpty()) {
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed() && scanGeneration.get() == generation) {
                            status.setText(R.string.knowledge_llm_no_sources);
                        }
                    });
                    return;
                }
                final String exactPreview = KnowledgeLlmClient.requestPreview(question, excerpts);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == generation) {
                        requestLlmConsent(endpoint, model, apiKey, exactPreview, excerpts);
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == generation) {
                        status.setText(R.string.knowledge_llm_failed);
                    }
                });
            }
        });
    }

    private void requestLlmConsent(final URL endpoint, final String model,
                                   final String apiKey, final String exactPreview,
                                   final List<KnowledgeLlmEvidence.Item> excerpts) {
        final String privacy = getString(R.string.knowledge_llm_privacy,
                endpoint.getHost(), model, excerpts.size());
        final String warning = "http".equals(endpoint.getProtocol())
                ? "\n" + getString(R.string.knowledge_llm_plaintext_warning) : "";
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_llm_consent_title)
                .setMessage(privacy + warning + "\n\n" + exactPreview)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_llm_send,
                        (dialog, which) -> sendLlmQuestion(endpoint, model, apiKey,
                                exactPreview, excerpts))
                .show();
    }

    private void sendLlmQuestion(final URL endpoint, final String model,
                                 final String apiKey, final String exactPreview,
                                 final List<KnowledgeLlmEvidence.Item> excerpts) {
        final int generation = scanGeneration.get();
        status.setText(R.string.knowledge_llm_requesting);
        worker.submit(() -> {
            try {
                final String answer = KnowledgeLlmClient.ask(endpoint, model, apiKey, exactPreview);
                final boolean cited = hasValidLlmCitation(answer, excerpts.size());
                final StringBuilder display = new StringBuilder(
                        getString(R.string.knowledge_llm_unverified));
                if (!cited) {
                    display.append("\n").append(getString(R.string.knowledge_llm_no_citations));
                }
                display.append("\n\n").append(answer).append("\n\n")
                        .append(getString(R.string.knowledge_llm_sources)).append("\n");
                for (int i = 0; i < excerpts.size(); i++) {
                    display.append("[").append(i + 1).append("] ")
                            .append(excerpts.get(i).path).append("\n");
                }
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == generation) {
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.knowledge_llm_answer)
                                .setMessage(display.toString())
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                        if (snapshot != null && syncResult != null) {
                            showIndexStatus(displayed.size());
                        }
                    }
                });
            } catch (Exception ignored) {
                // No question, excerpts, response bodies or API keys in logcat.
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == generation) {
                        status.setText(R.string.knowledge_llm_failed);
                    }
                });
            }
        });
    }

    static boolean hasValidLlmCitation(final String answer, final int sourceCount) {
        final Matcher citations = Pattern.compile("\\[(\\d{1,2})\\]").matcher(answer);
        while (citations.find()) {
            final int number = Integer.parseInt(citations.group(1));
            if (number >= 1 && number <= sourceCount) {
                return true;
            }
        }
        return false;
    }

    private void promptKnowledgeQuery() {
        final EditText question = new EditText(this);
        question.setHint(R.string.knowledge_query_hint);
        question.setSingleLine(false);
        question.setMinLines(2);
        question.setMaxLines(5);
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_ask)
                .setView(question)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_ask,
                        (dialog, which) -> askKnowledge(question.getText().toString()))
                .show();
    }

    private void askKnowledge(final String question) {
        if (question.trim().isEmpty()) {
            return;
        }
        final int scan = scanGeneration.get();
        status.setText(R.string.knowledge_reasoning);
        worker.submit(() -> {
            try {
                final JSONObject result = KnowledgePythonBridge.query(this, workspacePath(), question);
                final String formatted = formatQueryResult(result);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.knowledge_answer_title)
                                .setMessage(formatted)
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                        if (snapshot != null && syncResult != null) {
                            showIndexStatus(displayed.size());
                        }
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_query_failed);
                    }
                });
            }
        });
    }

    private String formatQueryResult(final JSONObject result) {
        final StringBuilder out = new StringBuilder();
        out.append(getString(R.string.knowledge_query_status, result.optString("status")))
                .append("\n\n").append(result.optString("answer"));
        final JSONObject sections = result.optJSONObject("sections");
        if (sections != null) {
            for (final String key : new String[]{
                    "evidence", "derivation", "contradictions", "unknowns",
                    "notes", "bindings", "comparison", "trace", "definition", "preview"}) {
                final Object details = sections.opt(key);
                if (details == null || JSONObject.NULL.equals(details)
                        || "[]".equals(details.toString()) || "{}".equals(details.toString())) {
                    continue;
                }
                out.append("\n\n").append(key.toUpperCase(java.util.Locale.ROOT))
                        .append(":\n").append(details.toString());
                if (out.length() > 16_000) {
                    out.setLength(16_000);
                    out.append("\n…");
                    break;
                }
            }
        }
        return out.toString();
    }

    private void showKnowledgeDiagnostics() {
        final int scan = scanGeneration.get();
        status.setText(R.string.knowledge_reasoning);
        worker.submit(() -> {
            try {
                final JSONObject result = KnowledgePythonBridge.diagnostics(this, workspacePath());
                final JSONObject unknowns = result.optJSONObject("unknowns");
                final StringBuilder details = new StringBuilder();
                details.append(getString(R.string.knowledge_state_counts,
                        result.getInt("record_count"), result.getInt("authoritative_count"),
                        result.getInt("claim_count"), result.getInt("contradiction_count")));
                if (unknowns != null) {
                    details.append("\n\n").append(getString(R.string.knowledge_state_unknowns,
                            jsonArrayLength(unknowns, "open_hypotheses"),
                            jsonArrayLength(unknowns, "deferred_candidates"),
                            jsonArrayLength(unknowns, "claims_not_reviewed")));
                }
                final JSONArray conflicts = result.optJSONArray("contradictions");
                if (conflicts != null) {
                    for (int i = 0; i < Math.min(conflicts.length(), 12); i++) {
                        final JSONObject conflict = conflicts.optJSONObject(i);
                        if (conflict == null) {
                            continue;
                        }
                        details.append("\n\n").append(getString(R.string.knowledge_conflict_heading))
                                .append(" #").append(i + 1).append("\n")
                                .append(conflict.optJSONObject("positive")).append("\n")
                                .append(conflict.optJSONObject("negative"));
                    }
                }
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.knowledge_diagnostics)
                                .setMessage(details.toString())
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                        if (snapshot != null && syncResult != null) {
                            showIndexStatus(displayed.size());
                        }
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_query_failed);
                    }
                });
            }
        });
    }

    private static int jsonArrayLength(final JSONObject value, final String key) {
        final JSONArray a = value.optJSONArray(key);
        return a == null ? 0 : a.length();
    }

    private static EditText passwordInput(final Activity activity) {
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSingleLine(true);
        return input;
    }

    private void requestJournalExport() {
        final android.widget.LinearLayout fields = new android.widget.LinearLayout(this);
        fields.setOrientation(android.widget.LinearLayout.VERTICAL);
        fields.setPadding(24, 0, 24, 0);
        final EditText password = passwordInput(this);
        final EditText confirm = passwordInput(this);
        password.setHint(R.string.knowledge_backup_password);
        confirm.setHint(R.string.knowledge_backup_password_confirm);
        fields.addView(password);
        fields.addView(confirm);
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_backup_export)
                .setMessage(R.string.knowledge_backup_privacy)
                .setView(fields)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_backup_export, (dialog, which) -> {
                    final String first = password.getText().toString();
                    if (first.length() < 12 || !first.equals(confirm.getText().toString())) {
                        Toast.makeText(this, R.string.knowledge_backup_password_invalid,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (pendingExportPassword != null) {
                        Arrays.fill(pendingExportPassword, '\0');
                    }
                    pendingExportPassword = first.toCharArray();
                    final Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/octet-stream");
                    intent.putExtra(Intent.EXTRA_TITLE, "DP_Knowledge_Journal.dpks");
                    startActivityForResult(intent, REQUEST_EXPORT_JOURNAL);
                })
                .show();
    }

    private void requestJournalImport() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_backup_import)
                .setMessage(R.string.knowledge_backup_import_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_backup_import, (dialog, which) -> {
                    final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/octet-stream");
                    startActivityForResult(intent, REQUEST_IMPORT_JOURNAL);
                })
                .show();
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_EXPORT_JOURNAL) {
            final char[] password = pendingExportPassword;
            pendingExportPassword = null;
            if (password == null) {
                return;
            }
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                exportJournalToUri(data.getData(), password);
            } else {
                Arrays.fill(password, '\0');
            }
        } else if (requestCode == REQUEST_IMPORT_JOURNAL
                && resultCode == RESULT_OK && data != null && data.getData() != null) {
            promptJournalImportPassword(data.getData());
        }
    }

    private static byte[] readLimited(final InputStream input, final int limit) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final byte[] buffer = new byte[16 * 1024];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (bytes.size() + n > limit) {
                throw new IOException("Backup exceeds size limit");
            }
            bytes.write(buffer, 0, n);
        }
        return bytes.toByteArray();
    }

    private static String sha256Hex(final byte[] data) throws Exception {
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        final char[] hex = "0123456789abcdef".toCharArray();
        final StringBuilder result = new StringBuilder(64);
        for (final byte b : digest) {
            result.append(hex[(b >>> 4) & 0xF]).append(hex[b & 0xF]);
        }
        return result.toString();
    }

    private void exportJournalToUri(final Uri destination, final char[] password) {
        final int scan = scanGeneration.get();
        status.setText(R.string.knowledge_backup_working);
        worker.submit(() -> {
            byte[] plaintext = null;
            try {
                final JSONObject metadata = KnowledgePythonBridge.journalExportInfo(
                        this, workspacePath());
                final File journal = new File(workspacePath(), "data/formal/journal.jsonl");
                try (FileInputStream in = new FileInputStream(journal)) {
                    plaintext = readLimited(in, KnowledgeJournalCrypto.MAX_JOURNAL_BYTES);
                }
                if (!sha256Hex(plaintext).equals(metadata.getString("sha256"))) {
                    throw new IOException("Journal changed while exporting");
                }
                final byte[] ciphertext = KnowledgeJournalCrypto.encrypt(plaintext, password);
                try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                    if (output == null) {
                        throw new IOException("Cannot open user-selected destination");
                    }
                    output.write(ciphertext);
                    output.flush();
                }
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        Toast.makeText(this, R.string.knowledge_backup_exported,
                                Toast.LENGTH_LONG).show();
                        if (syncResult != null && snapshot != null) {
                            showIndexStatus(displayed.size());
                        }
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_backup_failed);
                    }
                });
            } finally {
                Arrays.fill(password, '\0');
                if (plaintext != null) {
                    Arrays.fill(plaintext, (byte) 0);
                }
            }
        });
    }

    private void promptJournalImportPassword(final Uri source) {
        final EditText password = passwordInput(this);
        password.setHint(R.string.knowledge_backup_password);
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_backup_import)
                .setView(password)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_backup_decrypt,
                        (dialog, which) -> importJournalFromUri(source,
                                password.getText().toString().toCharArray()))
                .show();
    }

    private void importJournalFromUri(final Uri source, final char[] password) {
        final int scan = scanGeneration.get();
        status.setText(R.string.knowledge_backup_working);
        worker.submit(() -> {
            byte[] decrypted = null;
            try {
                final byte[] payload;
                try (InputStream input = getContentResolver().openInputStream(source)) {
                    if (input == null) {
                        throw new IOException("Cannot read user-selected backup");
                    }
                    payload = readLimited(input, KnowledgeJournalCrypto.MAX_JOURNAL_BYTES + 128);
                }
                decrypted = KnowledgeJournalCrypto.decrypt(payload, password);
                final String journalText = new String(decrypted,
                        java.nio.charset.StandardCharsets.UTF_8);
                final JSONObject preview = KnowledgePythonBridge.journalRestorePreview(
                        this, workspacePath(), journalText);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        confirmJournalImport(journalText, preview);
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_backup_failed);
                    }
                });
            } finally {
                Arrays.fill(password, '\0');
                if (decrypted != null) {
                    Arrays.fill(decrypted, (byte) 0);
                }
            }
        });
    }

    private void confirmJournalImport(final String journalText, final JSONObject preview) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_backup_import)
                .setMessage(getString(R.string.knowledge_backup_import_details,
                        preview.optInt("count"), preview.optString("head")))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_backup_confirm_import, (dialog, which) -> {
                    final int scan = scanGeneration.get();
                    status.setText(R.string.knowledge_backup_working);
                    worker.submit(() -> {
                        try {
                            KnowledgePythonBridge.journalRestoreCommit(this, workspacePath(),
                                    journalText, preview.getString("sha256"));
                            runOnUiThread(() -> {
                                if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                                    Toast.makeText(this, R.string.knowledge_backup_imported,
                                            Toast.LENGTH_LONG).show();
                                    if (syncResult != null && snapshot != null) {
                                        showIndexStatus(displayed.size());
                                    }
                                }
                            });
                        } catch (Exception ignored) {
                            runOnUiThread(() -> {
                                if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                                    status.setText(R.string.knowledge_backup_failed);
                                }
                            });
                        }
                    });
                })
                .show();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        scanGeneration.incrementAndGet();
        queryGeneration.incrementAndGet();
        analysisGeneration.incrementAndGet();
        auditGeneration.incrementAndGet();
        embeddingGeneration.incrementAndGet();
        if (runningAudit != null) {
            runningAudit.cancel(true);
        }
        if (runningEmbedding != null) {
            runningEmbedding.cancel(true);
        }
        if (runningAnalysis != null) {
            runningAnalysis.cancel(true);
        }
        if (runningScan != null) {
            runningScan.cancel(true);
        }
        if (runningSearch != null) {
            runningSearch.cancel(true);
        }
        if (pendingExportPassword != null) {
            Arrays.fill(pendingExportPassword, '\0');
            pendingExportPassword = null;
        }
        worker.shutdownNow();
        analysisWorker.shutdownNow();
        embeddingWorker.shutdownNow();
        super.onDestroy();
    }
}
