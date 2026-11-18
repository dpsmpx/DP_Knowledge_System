/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Added to the Markor fork for DP Knowledge System.
 */
package net.gsantner.markor.knowledge;

import android.os.Bundle;
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

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
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
                showPossibleBacklinks(displayed.get(position).relativePath);
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
        worker.shutdownNow();
        super.onDestroy();
    }
}
