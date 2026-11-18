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
import android.widget.EditText;
import android.widget.ListView;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
    private static final int REQUEST_EXPORT_JOURNAL = 40021;
    private static final int REQUEST_IMPORT_JOURNAL = 40022;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger scanGeneration = new AtomicInteger();
    private final AtomicInteger queryGeneration = new AtomicInteger();
    private final List<KnowledgeLocalIndex.Hit> displayed = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    private Future<?> runningScan;
    private Future<?> runningSearch;
    private KnowledgeVaultScanner.Snapshot snapshot;
    private KnowledgeLocalIndex.SyncResult syncResult;
    private KnowledgeLocalIndex index;
    private File notebook;
    private ArrayAdapter<String> adapter;
    private TextView status;
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
                                        getString(R.string.knowledge_possible_backlinks)},
                                (dialog, which) -> {
                                    if (which == 0) {
                                        inspectWithCore(path);
                                    } else {
                                        showPossibleBacklinks(path);
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
        findViewById(R.id.knowledge_diagnostics).setOnClickListener(view -> showKnowledgeDiagnostics());
        findViewById(R.id.knowledge_backup_export).setOnClickListener(view -> requestJournalExport());
        findViewById(R.id.knowledge_backup_import).setOnClickListener(view -> requestJournalImport());
        scanNotebook();
    }

    private void scanNotebook() {
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
                || !source.isFile() || source.length() > 300_000) {
            throw new IOException("Note absent, escaped root or oversized");
        }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        try (FileInputStream in = new FileInputStream(source)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted() || bytes.size() + n > 300_000) {
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
        super.onDestroy();
    }
}
