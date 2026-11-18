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
import androidx.appcompat.widget.Toolbar;

import net.gsantner.markor.R;
import net.gsantner.markor.activity.DocumentActivity;
import net.gsantner.markor.activity.MarkorBaseActivity;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * First-stage, offline knowledge workspace: discover a local Markdown vault,
 * filter relative filenames, and open an explicitly selected note in Markor.
 * Neither scanning nor filtering reads or modifies note contents.
 */
public final class KnowledgeHubActivity extends MarkorBaseActivity {
    private static final int MAX_VISIBLE = 250;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger scanGeneration = new AtomicInteger();
    private final List<KnowledgeVaultScanner.Note> displayed = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    private Future<?> runningScan;
    private KnowledgeVaultScanner.Snapshot snapshot;
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

        final File notebook = _appSettings.getNotebookDirectory();
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
                final File file = displayed.get(position).file;
                if (file.isFile()) {
                    DocumentActivity.launch(this, file, false, null);
                } else {
                    Toast.makeText(this, R.string.knowledge_file_missing, Toast.LENGTH_SHORT).show();
                }
            }
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
        if (runningScan != null) {
            runningScan.cancel(true);
        }
        final File root = _appSettings.getNotebookDirectory();
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.knowledge_scanning);

        runningScan = worker.submit(() -> {
            try {
                final KnowledgeVaultScanner.Snapshot result =
                        KnowledgeVaultScanner.scan(root, KnowledgeVaultScanner.MAX_NOTES,
                                () -> Thread.currentThread().isInterrupted()
                                        || scanGeneration.get() != generation);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == generation) {
                        snapshot = result;
                        progress.setVisibility(View.GONE);
                        refreshResults();
                    }
                });
            } catch (InterruptedIOException ignored) {
                // An older scan was superseded; never present partial results.
            } catch (IOException | SecurityException failure) {
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
        final String query = filter.getText().toString().trim().toLowerCase(Locale.ROOT);
        displayed.clear();
        labels.clear();
        int matches = 0;
        for (final KnowledgeVaultScanner.Note note : snapshot.notes) {
            if (note.relativePath.toLowerCase(Locale.ROOT).contains(query)) {
                matches++;
                if (displayed.size() < MAX_VISIBLE) {
                    displayed.add(note);
                    labels.add(note.relativePath);
                }
            }
        }
        adapter.notifyDataSetChanged();

        final StringBuilder summary = new StringBuilder(
                getString(R.string.knowledge_count, matches, snapshot.notes.size(), displayed.size()));
        if (snapshot.truncated) {
            summary.append(' ').append(getString(R.string.knowledge_truncated));
        }
        if (snapshot.unreadableDirectories > 0) {
            summary.append(' ').append(getString(R.string.knowledge_unreadable,
                    snapshot.unreadableDirectories));
        }
        status.setText(summary);
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
        if (runningScan != null) {
            runningScan.cancel(true);
        }
        worker.shutdownNow();
        super.onDestroy();
    }
}
