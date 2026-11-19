/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Added to the Markor fork for DP Knowledge System.
 */
package net.gsantner.markor.knowledge;

import android.app.Activity;
import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.text.InputType;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import net.gsantner.markor.R;
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
import java.util.Date;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
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
    private static final String EXTRA_EDITOR_REVIEW_PATH = "com.dpsmpx.dpks.editor.review_path";
    private static final String EXTRA_EDITOR_REVIEW_FRAGMENT = "com.dpsmpx.dpks.editor.fragment";
    private static final String EXTRA_EDITOR_SOURCE_HASH = "com.dpsmpx.dpks.editor.source_sha256";

    /** Only the selected source identifier enters the Intent, never note text. */
    public static void reviewFromEditor(final Activity activity, final String relativePath,
                                        final String fragmentId, final String sourceHash) {
        final Intent intent = new Intent(activity, KnowledgeHubActivity.class);
        intent.putExtra(EXTRA_EDITOR_REVIEW_PATH, relativePath);
        intent.putExtra(EXTRA_EDITOR_REVIEW_FRAGMENT, fragmentId);
        intent.putExtra(EXTRA_EDITOR_SOURCE_HASH, sourceHash);
        activity.startActivity(intent);
    }
    private static final String STATE_WORKSPACE_PAGE = "dpks_workspace_page";
    private static final String STATE_DECISION_CATEGORY = "dpks_decision_category";
    private static final String[] DECISION_CATEGORIES = {
            "pending", "active", "facts", "hypotheses", "rejected", "deferred",
            "obsolete", "history"
    };
    private static final int[] DECISION_CATEGORY_LABELS = {
            R.string.knowledge_decisions_pending, R.string.knowledge_decisions_active,
            R.string.knowledge_decisions_facts, R.string.knowledge_decisions_hypotheses,
            R.string.knowledge_decisions_rejected, R.string.knowledge_decisions_deferred,
            R.string.knowledge_decisions_obsolete, R.string.knowledge_decisions_history
    };
    private static final int MAX_EMBED_NOTES_PER_RUN = 120;
    private static final int REQUEST_EXPORT_JOURNAL = 40021;
    private static final int REQUEST_IMPORT_JOURNAL = 40022;
    private static final int REQUEST_KNOWLEDGE_NOTIFICATIONS = 40023;
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
    private final ExecutorService deviceQaWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService decisionWorker = Executors.newSingleThreadExecutor();
    private final AtomicInteger scanGeneration = new AtomicInteger();
    private final AtomicInteger queryGeneration = new AtomicInteger();
    private final AtomicInteger analysisGeneration = new AtomicInteger();
    private final AtomicInteger auditGeneration = new AtomicInteger();
    private final AtomicInteger embeddingGeneration = new AtomicInteger();
    private final AtomicInteger deviceQaGeneration = new AtomicInteger();
    private final AtomicInteger decisionGeneration = new AtomicInteger();
    private final List<KnowledgeLocalIndex.Hit> displayed = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private final List<JSONObject> decisionItems = new ArrayList<>();
    private final List<Map<String, String>> decisionRows = new ArrayList<>();

    private Future<?> runningScan;
    private Future<?> runningSearch;
    private Future<?> runningAnalysis;
    private Future<?> runningAudit;
    private Future<?> runningEmbedding;
    private Future<?> runningDeviceQa;
    private Future<?> runningDecisionLoad;
    private KnowledgeVaultScanner.Snapshot snapshot;
    private KnowledgeLocalIndex.SyncResult syncResult;
    private KnowledgeLocalIndex index;
    private File notebook;
    private ArrayAdapter<String> adapter;
    private TextView status;
    private TextView analysisStatus;
    private TextView embeddingStatus;
    private Button analysisButton;
    private Button auditButton;
    private Button embeddingButton;
    private Button qaQuickButton;
    private Button qaStressButton;
    private TextView qaStatus;
    private ProgressBar qaProgress;
    private boolean qaActive;
    private CheckBox automaticAnalysis;
    private CheckBox backgroundChecks;
    private CheckBox backgroundNotifications;
    private TextView backgroundStatus;
    private boolean analysisActive;
    private boolean auditActive;
    private boolean embeddingActive;
    private ProgressBar progress;
    private EditText filter;
    private BottomNavigationView navigation;
    private SimpleAdapter decisionAdapter;
    private EditText decisionFilter;
    private ChipGroup decisionChips;
    private TextView decisionStatus;
    private String decisionCategory = "pending";
    private int decisionOffset;
    private int decisionNextOffset = -1;
    private boolean decisionWorkspaceReady;
    private boolean decisionDetailShowing;
    private int workspacePage = R.id.knowledge_nav_library;
    private char[] pendingExportPassword;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // This is deliberately scoped to the knowledge workspace: the legacy
        // Markor editor keeps its existing appearance and behavior.
        DynamicColors.applyToActivityIfAvailable(this);
        setContentView(R.layout.knowledge__activity);

        final Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        final ActionBar bar = getSupportActionBar();
        if (bar != null) {
            bar.setTitle(R.string.knowledge_hub_title);
        }
        navigation = findViewById(R.id.knowledge_navigation);
        navigation.setOnItemSelectedListener(item -> {
            showWorkspacePage(item.getItemId());
            return true;
        });
        if (savedInstanceState != null) {
            final String previous = savedInstanceState.getString(STATE_DECISION_CATEGORY, "pending");
            if (Arrays.asList(DECISION_CATEGORIES).contains(previous)) decisionCategory = previous;
        }
        final int initialPage = savedInstanceState == null
                ? R.id.knowledge_nav_library
                : savedInstanceState.getInt(STATE_WORKSPACE_PAGE, R.id.knowledge_nav_library);
        showWorkspacePage(initialPage);
        navigation.setSelectedItemId(workspacePage);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (decisionDetailShowing) {
                    closeDecisionDetail();
                } else if (workspacePage != R.id.knowledge_nav_library) {
                    navigation.setSelectedItemId(R.id.knowledge_nav_library);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        notebook = _appSettings.getNotebookDirectory();
        index = new KnowledgeLocalIndex(this);
        final TextView root = findViewById(R.id.knowledge_root);
        root.setText(notebook == null ? getString(R.string.knowledge_no_folder)
                : getString(R.string.knowledge_root_label, notebook.getAbsolutePath()));

        status = findViewById(R.id.knowledge_status);
        analysisStatus = findViewById(R.id.knowledge_bulk_status);
        embeddingStatus = findViewById(R.id.knowledge_embedding_status);
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
        setupBackgroundControls();
        analysisButton.setOnClickListener(view -> {
            if (analysisActive) {
                cancelBulkAnalysis();
            } else {
                startBulkAnalysis();
            }
        });
        findViewById(R.id.knowledge_bulk_review).setOnClickListener(
                view -> navigateToDecisions("pending"));
        findViewById(R.id.knowledge_active_decisions).setOnClickListener(
                view -> navigateToDecisions("active"));
        progress = findViewById(R.id.knowledge_progress);
        filter = findViewById(R.id.knowledge_filter);

        adapter = new ArrayAdapter<>(this, R.layout.knowledge__note_row, labels);
        final ListView list = findViewById(R.id.knowledge_list);
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.knowledge_empty));
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
        findViewById(R.id.knowledge_diagnostics).setOnClickListener(view ->
                new AlertDialog.Builder(this)
                        .setTitle(R.string.knowledge_diagnostics)
                        .setItems(new String[]{getString(R.string.knowledge_diagnostics),
                                        getString(R.string.knowledge_index_health)},
                                (dialog, which) -> {
                                    if (which == 0) {
                                        showKnowledgeDiagnostics();
                                    } else {
                                        showIndexHealth();
                                    }
                                }).show());
        qaQuickButton = findViewById(R.id.knowledge_qa_quick);
        qaStressButton = findViewById(R.id.knowledge_qa_stress);
        qaStatus = findViewById(R.id.knowledge_qa_status);
        qaProgress = findViewById(R.id.knowledge_qa_progress);
        qaQuickButton.setOnClickListener(view -> runDeviceQa(false));
        qaStressButton.setOnClickListener(view -> runDeviceQa(true));
        findViewById(R.id.knowledge_backup_export).setOnClickListener(view -> requestJournalExport());
        findViewById(R.id.knowledge_backup_import).setOnClickListener(view -> requestJournalImport());
        setupDecisionWorkspace();
        scanNotebook();
        final Intent launch = getIntent();
        if (launch != null && launch.hasExtra(EXTRA_EDITOR_REVIEW_PATH)) {
            final String path = launch.getStringExtra(EXTRA_EDITOR_REVIEW_PATH);
            final String fragmentId = launch.getStringExtra(EXTRA_EDITOR_REVIEW_FRAGMENT);
            final String sourceHash = launch.getStringExtra(EXTRA_EDITOR_SOURCE_HASH);
            if (path != null && fragmentId != null && sourceHash != null
                    && sourceHash.matches("[0-9a-f]{64}")) {
                navigation.setSelectedItemId(R.id.knowledge_nav_decisions);
                previewFragment(path, fragmentId, sourceHash);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState) {
        outState.putInt(STATE_WORKSPACE_PAGE, workspacePage);
        outState.putString(STATE_DECISION_CATEGORY, decisionCategory);
        super.onSaveInstanceState(outState);
    }

    /** Switch destinations without recreating the activity or its active workers. */
    private void showWorkspacePage(final int requested) {
        final int page = requested == R.id.knowledge_nav_processes
                || requested == R.id.knowledge_nav_decisions
                || requested == R.id.knowledge_nav_manage
                ? requested : R.id.knowledge_nav_library;
        workspacePage = page;
        if (page != R.id.knowledge_nav_library && filter != null && filter.hasFocus()) {
            filter.clearFocus();
            final InputMethodManager keyboard = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) {
                keyboard.hideSoftInputFromWindow(filter.getWindowToken(), 0);
            }
        }
        findViewById(R.id.knowledge_page_library).setVisibility(
                page == R.id.knowledge_nav_library ? View.VISIBLE : View.GONE);
        findViewById(R.id.knowledge_page_processes).setVisibility(
                page == R.id.knowledge_nav_processes ? View.VISIBLE : View.GONE);
        findViewById(R.id.knowledge_page_decisions).setVisibility(
                page == R.id.knowledge_nav_decisions ? View.VISIBLE : View.GONE);
        findViewById(R.id.knowledge_page_manage).setVisibility(
                page == R.id.knowledge_nav_manage ? View.VISIBLE : View.GONE);
        final ActionBar bar = getSupportActionBar();
        if (bar != null) {
            bar.setTitle(page == R.id.knowledge_nav_library
                    ? R.string.knowledge_hub_title
                    : page == R.id.knowledge_nav_decisions
                    ? R.string.knowledge_page_decisions
                    : page == R.id.knowledge_nav_processes
                    ? R.string.knowledge_page_processes : R.string.knowledge_page_manage);
        }
        if (page == R.id.knowledge_nav_decisions && decisionWorkspaceReady) {
            loadDecisionPage(0);
        }
    }

    private void setupDecisionWorkspace() {
        decisionChips = findViewById(R.id.knowledge_decision_categories);
        decisionFilter = findViewById(R.id.knowledge_decisions_filter);
        decisionStatus = findViewById(R.id.knowledge_decisions_status);
        for (int i = 0; i < DECISION_CATEGORIES.length; i++) {
            final String category = DECISION_CATEGORIES[i];
            final Chip chip = new Chip(this);
            chip.setCheckable(true);
            chip.setText(DECISION_CATEGORY_LABELS[i]);
            chip.setChecked(category.equals(decisionCategory));
            chip.setOnClickListener(view -> {
                decisionCategory = category;
                loadDecisionPage(0);
            });
            decisionChips.addView(chip);
        }
        decisionAdapter = new SimpleAdapter(this, decisionRows,
                R.layout.knowledge__decision_row,
                new String[]{"heading", "summary"},
                new int[]{android.R.id.text1, android.R.id.text2});
        final ListView list = findViewById(R.id.knowledge_decisions_list);
        list.setAdapter(decisionAdapter);
        list.setEmptyView(findViewById(R.id.knowledge_decisions_empty));
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < decisionItems.size()) {
                openDecisionDetail(decisionItems.get(position));
            }
        });
        decisionFilter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                loadDecisionPage(0);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        findViewById(R.id.knowledge_decisions_reload).setOnClickListener(view ->
                loadDecisionPage(decisionOffset));
        findViewById(R.id.knowledge_decisions_previous).setOnClickListener(view ->
                loadDecisionPage(Math.max(0, decisionOffset - 30)));
        findViewById(R.id.knowledge_decisions_next).setOnClickListener(view -> {
            if (decisionNextOffset >= 0) loadDecisionPage(decisionNextOffset);
        });
        findViewById(R.id.knowledge_decisions_batch).setOnClickListener(view -> showCandidateQueue(0));
        decisionWorkspaceReady = true;
        if (workspacePage == R.id.knowledge_nav_decisions) loadDecisionPage(0);
    }

    private void navigateToDecisions(final String category) {
        decisionCategory = category;
        for (int i = 0; i < DECISION_CATEGORIES.length; i++) {
            ((Chip) decisionChips.getChildAt(i)).setChecked(category.equals(DECISION_CATEGORIES[i]));
        }
        if (workspacePage == R.id.knowledge_nav_decisions) {
            loadDecisionPage(0);
        } else {
            navigation.setSelectedItemId(R.id.knowledge_nav_decisions);
        }
    }

    /** Never block the UI thread while replaying the journal or paging the local index. */
    private void loadDecisionPage(final int offset) {
        if (!decisionWorkspaceReady) return;
        final int generation = decisionGeneration.incrementAndGet();
        if (runningDecisionLoad != null) runningDecisionLoad.cancel(true);
        final String category = decisionCategory;
        final String search = decisionFilter.getText().toString().trim();
        decisionStatus.setText(R.string.knowledge_decisions_loading);
        runningDecisionLoad = decisionWorker.submit(() -> {
            try {
                final JSONObject response = KnowledgePythonBridge.decisionOverview(
                        this, workspacePath(), category, offset, search);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || decisionGeneration.get() != generation) return;
                    decisionOffset = offset;
                    decisionNextOffset = response.isNull("next_offset") ? -1
                            : response.optInt("next_offset", -1);
                    decisionItems.clear();
                    decisionRows.clear();
                    final JSONArray items = response.optJSONArray("items");
                    if (items != null) {
                        for (int i = 0; i < items.length(); i++) {
                            final JSONObject item = items.optJSONObject(i);
                            if (item == null) continue;
                            decisionItems.add(item);
                            final Map<String, String> row = new HashMap<>();
                            row.put("heading", decisionRoleLabel(item.optString("role")) + " · "
                                    + decisionStatusLabel(item.optString("status"))
                                    + (item.optString("locator").isEmpty() ? ""
                                    : " · " + item.optString("locator")));
                            final String path = item.optString("path");
                            row.put("summary", (path.isEmpty() ? "" : path + "\n")
                                    + item.optString("summary"));
                            decisionRows.add(row);
                        }
                    }
                    decisionAdapter.notifyDataSetChanged();
                    final JSONObject counts = response.optJSONObject("counts");
                    for (int i = 0; i < DECISION_CATEGORIES.length; i++) {
                        final Chip chip = (Chip) decisionChips.getChildAt(i);
                        final int count = counts == null ? 0 : counts.optInt(DECISION_CATEGORIES[i]);
                        chip.setText(getString(DECISION_CATEGORY_LABELS[i]) + " · " + count);
                    }
                    final int total = response.optInt("total");
                    decisionStatus.setText(total == 0 ? getString(R.string.knowledge_decisions_empty)
                            : getString(R.string.knowledge_decisions_count, total,
                                    offset + 1, offset + decisionItems.size()));
                    findViewById(R.id.knowledge_decisions_previous).setEnabled(offset > 0);
                    findViewById(R.id.knowledge_decisions_next).setEnabled(decisionNextOffset >= 0);
                    ((TextView) findViewById(R.id.knowledge_decisions_page_number)).setText(
                            total == 0 ? "0" : (offset / 30 + 1) + " / " + ((total + 29) / 30));
                    ((ListView) findViewById(R.id.knowledge_decisions_list))
                            .smoothScrollToPosition(0);
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && decisionGeneration.get() == generation) {
                        decisionStatus.setText(R.string.knowledge_decisions_failed);
                    }
                });
            }
        });
    }

    private void openDecisionDetail(final JSONObject selected) {
        final String kind = selected.optString("kind");
        final String recordId = selected.optString("id");
        if (kind.isEmpty() || recordId.isEmpty()) return;
        decisionDetailShowing = true;
        final int generation = decisionGeneration.incrementAndGet();
        if (runningDecisionLoad != null) runningDecisionLoad.cancel(true);
        findViewById(R.id.knowledge_page_decisions).setVisibility(View.GONE);
        findViewById(R.id.knowledge_decision_detail_screen).setVisibility(View.VISIBLE);
        navigation.setVisibility(View.GONE);
        final Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_white_24dp);
        if (toolbar.getNavigationIcon() != null) {
            toolbar.getNavigationIcon().mutate().setTint(MaterialColors.getColor(toolbar,
                    com.google.android.material.R.attr.colorOnSurface));
        }
        toolbar.setNavigationContentDescription(R.string.knowledge_editor_back);
        toolbar.setNavigationOnClickListener(view -> closeDecisionDetail());
        final ActionBar bar = getSupportActionBar();
        if (bar != null) bar.setTitle(R.string.knowledge_decision_detail_title);
        ((TextView) findViewById(R.id.knowledge_decision_detail_status))
                .setText(R.string.knowledge_decisions_loading);
        for (final int id : new int[]{R.id.knowledge_decision_detail_source,
                R.id.knowledge_decision_detail_interpretation,
                R.id.knowledge_decision_detail_formal,
                R.id.knowledge_decision_detail_provenance,
                R.id.knowledge_decision_detail_conflicts,
                R.id.knowledge_decision_detail_history}) {
            ((TextView) findViewById(id)).setText("");
        }
        findViewById(R.id.knowledge_decision_open_source).setEnabled(false);
        findViewById(R.id.knowledge_decision_review_action).setVisibility(View.GONE);
        findViewById(R.id.knowledge_decision_manage_action).setVisibility(View.GONE);
        runningDecisionLoad = decisionWorker.submit(() -> {
            try {
                final JSONObject detail = KnowledgePythonBridge.decisionDetail(
                        this, workspacePath(), kind, recordId);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && decisionDetailShowing
                            && decisionGeneration.get() == generation) renderDecisionDetail(detail);
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && decisionDetailShowing
                            && decisionGeneration.get() == generation) {
                        ((TextView) findViewById(R.id.knowledge_decision_detail_status))
                                .setText(R.string.knowledge_decision_loading_failed);
                    }
                });
            }
        });
    }

    private void renderDecisionDetail(final JSONObject item) {
        final String kind = item.optString("kind");
        final String role = item.optString("role");
        final String path = item.optString("path");
        ((TextView) findViewById(R.id.knowledge_decision_detail_status)).setText(
                decisionRoleLabel(role) + " · " + decisionStatusLabel(item.optString("status")));
        final String source = item.optString("source_text");
        ((TextView) findViewById(R.id.knowledge_decision_detail_source)).setText(
                source.isEmpty() ? getString(R.string.knowledge_decision_not_available) : source);
        final String interpretation = item.optString("interpretation");
        ((TextView) findViewById(R.id.knowledge_decision_detail_interpretation)).setText(
                interpretation.isEmpty() ? getString(R.string.knowledge_decision_not_available)
                        : interpretation);
        final String formal = item.optString("formalization");
        ((TextView) findViewById(R.id.knowledge_decision_detail_formal)).setText(
                formal.isEmpty() ? getString(R.string.knowledge_decision_not_available) : formal);
        final StringBuilder provenance = new StringBuilder();
        if (!path.isEmpty()) provenance.append(path).append(" · ")
                .append(item.optString("locator")).append('\n');
        if (!item.optString("source_hash").isEmpty()) provenance.append("SHA-256: ")
                .append(item.optString("source_hash")).append('\n');
        if (!item.optString("verifier").isEmpty()) provenance.append(item.optString("verifier"))
                .append(" · ").append(item.optString("verified_at")).append('\n');
        final JSONArray targets = item.optJSONArray("targets");
        if (targets != null && targets.length() > 0) {
            provenance.append(getString(R.string.knowledge_decisions_targets)).append(":\n");
            for (int i = 0; i < targets.length() && i < 10; i++) {
                provenance.append("• ").append(targets.optString(i)).append('\n');
            }
        }
        ((TextView) findViewById(R.id.knowledge_decision_detail_provenance)).setText(
                provenance.length() == 0 ? getString(R.string.knowledge_decision_not_available)
                        : provenance.toString().trim());
        final StringBuilder diagnostics = new StringBuilder();
        if (item.optBoolean("conflicts_available")) {
            final JSONArray conflicts = item.optJSONArray("conflicts");
            if (conflicts == null || conflicts.length() == 0) {
                diagnostics.append(getString(R.string.knowledge_decision_zero_conflicts));
            } else {
                for (int i = 0; i < conflicts.length(); i++) {
                    diagnostics.append("• ").append(conflicts.optString(i)).append('\n');
                }
            }
        } else {
            diagnostics.append(getString(R.string.knowledge_decision_not_audited));
        }
        final JSONArray alerts = item.optJSONArray("diagnostics");
        if (alerts != null && alerts.length() > 0) {
            diagnostics.append("\n");
            for (int i = 0; i < alerts.length(); i++) {
                final JSONObject alert = alerts.optJSONObject(i);
                if (alert != null) diagnostics.append("• ").append(alert.optString("severity"))
                        .append(": ").append(alert.optString("message")).append('\n');
            }
        }
        ((TextView) findViewById(R.id.knowledge_decision_detail_conflicts))
                .setText(diagnostics.toString().trim());
        final JSONArray history = item.optJSONArray("history");
        final StringBuilder historyText = new StringBuilder();
        if (history != null) {
            for (int i = 0; i < history.length(); i++) {
                final JSONObject change = history.optJSONObject(i);
                if (change != null) historyText.append(change.optString("time")).append(" · ")
                        .append(change.optString("op")).append('\n')
                        .append(change.optString("id")).append("\n\n");
            }
        }
        historyText.append(getString(R.string.knowledge_decision_more_history));
        ((TextView) findViewById(R.id.knowledge_decision_detail_history))
                .setText(historyText.toString());
        final View openSource = findViewById(R.id.knowledge_decision_open_source);
        openSource.setEnabled(!path.isEmpty());
        openSource.setOnClickListener(view -> openIndexedNote(path));
        final View review = findViewById(R.id.knowledge_decision_review_action);
        review.setVisibility("candidate".equals(kind) ? View.VISIBLE : View.GONE);
        review.setOnClickListener(view -> previewFragment(path, item.optString("fragment_id")));
        final View manage = findViewById(R.id.knowledge_decision_manage_action);
        manage.setVisibility("record".equals(kind) && !"OBSOLETE".equals(item.optString("status"))
                ? View.VISIBLE : View.GONE);
        manage.setOnClickListener(view -> chooseKnowledgeAction(item.optString("id"), role));
    }

    private void closeDecisionDetail() {
        decisionDetailShowing = false;
        decisionGeneration.incrementAndGet();
        if (runningDecisionLoad != null) runningDecisionLoad.cancel(true);
        findViewById(R.id.knowledge_decision_detail_screen).setVisibility(View.GONE);
        findViewById(R.id.knowledge_page_decisions).setVisibility(View.VISIBLE);
        navigation.setVisibility(View.VISIBLE);
        final Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationIcon(null);
        toolbar.setNavigationOnClickListener(null);
        final ActionBar bar = getSupportActionBar();
        if (bar != null) bar.setTitle(R.string.knowledge_page_decisions);
        loadDecisionPage(decisionOffset);
    }

    private String decisionRoleLabel(final String role) {
        switch (role) {
            case "interpretation": return getString(R.string.knowledge_decisions_role_interpretation);
            case "claim": return getString(R.string.knowledge_decisions_role_claim);
            case "fact": return getString(R.string.knowledge_decisions_role_fact);
            case "hypothesis": return getString(R.string.knowledge_decisions_role_hypothesis);
            case "reject": return getString(R.string.knowledge_decisions_role_reject);
            case "defer": return getString(R.string.knowledge_decisions_role_defer);
            case "retract": return getString(R.string.knowledge_decisions_role_retract);
            case "revise": return getString(R.string.knowledge_decisions_role_revise);
            case "split": return getString(R.string.knowledge_decisions_role_split);
            case "merge": return getString(R.string.knowledge_decisions_role_merge);
            case "accept": return getString(R.string.knowledge_decisions_role_accept);
            case "batch_accept": return getString(R.string.knowledge_decisions_role_batch_accept);
            default: return role;
        }
    }

    private String decisionStatusLabel(final String status) {
        switch (status) {
            case "PENDING": return getString(R.string.knowledge_decisions_status_pending);
            case "HUMAN_ACCEPTED": return getString(R.string.knowledge_decisions_status_accepted);
            case "CLAIMED": return getString(R.string.knowledge_decisions_status_claimed);
            case "UNKNOWN": return getString(R.string.knowledge_decisions_status_unknown);
            case "OBSOLETE": return getString(R.string.knowledge_decisions_status_obsolete);
            case "JOURNAL": return getString(R.string.knowledge_decisions_status_journal);
            default: return status;
        }
    }

    /** Quiet, count-only indicator; long operations never seize navigation. */
    private void updateProcessBadge() {
        if (navigation == null) return;
        final int running = (analysisActive ? 1 : 0) + (auditActive ? 1 : 0)
                + (embeddingActive ? 1 : 0) + (qaActive ? 1 : 0)
                + (progress != null && progress.getVisibility() == View.VISIBLE ? 1 : 0);
        if (running == 0) {
            navigation.removeBadge(R.id.knowledge_nav_processes);
        } else {
            navigation.getOrCreateBadge(R.id.knowledge_nav_processes).setNumber(running);
        }
    }

    private void showEmbeddingStatus(final String text) {
        status.setText(text);
        embeddingStatus.setText(text);
    }

    private void showEmbeddingStatus(final int resource) {
        showEmbeddingStatus(getString(resource));
    }

    @Override
    public void applyActivityBarBackgroundColors() {
        final Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar == null) return;
        final int surface = MaterialColors.getColor(toolbar,
                com.google.android.material.R.attr.colorSurface);
        toolbar.setBackgroundColor(surface);
        getWindow().setStatusBarColor(surface);
        getWindow().setNavigationBarColor(surface);
    }

    /** The foreground UI remains responsive, and every run owns only private cache fixtures. */
    private void runDeviceQa(final boolean stress) {
        if (qaActive) {
            if (runningDeviceQa != null) {
                runningDeviceQa.cancel(true);
            }
            qaStatus.setText(R.string.knowledge_qa_cancelling);
            return;
        }
        final int generation = deviceQaGeneration.incrementAndGet();
        qaActive = true;
        updateProcessBadge();
        qaQuickButton.setText(R.string.knowledge_qa_cancel);
        qaStressButton.setText(R.string.knowledge_qa_cancel);
        qaProgress.setVisibility(View.VISIBLE);
        qaStatus.setText(getString(R.string.knowledge_qa_running_step, "starting"));
        runningDeviceQa = deviceQaWorker.submit(() -> {
            final String report = new KnowledgeDeviceQaRunner(this, notebook, index, stress,
                    (phase, completed, total) -> runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed() || generation != deviceQaGeneration.get()) {
                            return;
                        }
                        qaStatus.setText(total > 0
                                ? getString(R.string.knowledge_qa_running, phase, completed, total)
                                : getString(R.string.knowledge_qa_running_step, phase));
                    }), () -> Thread.currentThread().isInterrupted()).run();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || generation != deviceQaGeneration.get()) {
                    return;
                }
                qaActive = false;
                updateProcessBadge();
                runningDeviceQa = null;
                qaQuickButton.setText(R.string.knowledge_qa_quick);
                qaStressButton.setText(R.string.knowledge_qa_stress);
                qaProgress.setVisibility(View.GONE);
                qaStatus.setText(R.string.knowledge_qa_ready);
                copyDeviceQaReport(report);
                new AlertDialog.Builder(this)
                        .setTitle(R.string.knowledge_qa_report_title)
                        .setMessage(report)
                        .setNegativeButton(android.R.string.ok, null)
                        .setPositiveButton(R.string.knowledge_qa_copy,
                                (dialog, which) -> copyDeviceQaReport(report))
                        .show();
            });
        });
    }

    /** Copies the redacted report, never note names, file paths, excerpts or keys. */
    private void copyDeviceQaReport(final String report) {
        try {
            final ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                throw new IllegalStateException("Clipboard service unavailable");
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("DPKS device QA", report));
            Toast.makeText(this, R.string.knowledge_qa_clipboard, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, R.string.knowledge_qa_clipboard_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void setupBackgroundControls() {
        backgroundChecks = findViewById(R.id.knowledge_background_auto);
        backgroundNotifications = findViewById(R.id.knowledge_background_notifications);
        backgroundStatus = findViewById(R.id.knowledge_background_status);
        backgroundChecks.setChecked(KnowledgeBackgroundScheduler.isEnabled(this));
        backgroundNotifications.setChecked(KnowledgeBackgroundScheduler.notificationsEnabled(this));

        backgroundChecks.setOnCheckedChangeListener((button, enabled) -> {
            if (enabled && !KnowledgeBackgroundScheduler.isEnabled(this)) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.knowledge_background_enable)
                        .setMessage(R.string.knowledge_background_consent)
                        .setOnCancelListener(dialog -> backgroundChecks.setChecked(false))
                        .setNegativeButton(android.R.string.cancel,
                                (dialog, which) -> backgroundChecks.setChecked(false))
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            try {
                                KnowledgeBackgroundScheduler.setEnabled(this, true);
                                backgroundStatus.setText(R.string.knowledge_background_scheduled);
                            } catch (RuntimeException failed) {
                                backgroundChecks.setChecked(false);
                                backgroundStatus.setText(R.string.knowledge_background_failed);
                            }
                        }).show();
            } else if (!enabled && KnowledgeBackgroundScheduler.isEnabled(this)) {
                try {
                    KnowledgeBackgroundScheduler.setEnabled(this, false);
                } catch (RuntimeException failed) {
                    backgroundStatus.setText(R.string.knowledge_background_failed);
                }
            }
        });
        backgroundNotifications.setOnCheckedChangeListener((button, enabled) -> {
            if (enabled && !notificationPermissionGranted()) {
                backgroundNotifications.setChecked(false);
                new AlertDialog.Builder(this)
                        .setMessage(R.string.knowledge_background_notify_permission)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            if (Build.VERSION.SDK_INT >= 33) {
                                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                                        REQUEST_KNOWLEDGE_NOTIFICATIONS);
                            }
                        }).show();
            } else {
                KnowledgeBackgroundScheduler.setNotificationsEnabled(this, enabled);
            }
        });
        findViewById(R.id.knowledge_background_now).setOnClickListener(view -> {
            try {
                KnowledgeBackgroundScheduler.enqueueOnce(this);
                backgroundStatus.setText(R.string.knowledge_background_scheduled);
            } catch (RuntimeException failed) {
                backgroundStatus.setText(R.string.knowledge_background_failed);
            }
        });
        refreshBackgroundStatus();
    }

    private boolean notificationPermissionGranted() {
        return Build.VERSION.SDK_INT < 33 || checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshBackgroundStatus() {
        if (backgroundStatus == null) {
            return;
        }
        final long at = KnowledgeBackgroundScheduler.lastAt(this);
        if (at == 0) {
            backgroundStatus.setText(R.string.knowledge_background_never);
            return;
        }
        final int status = KnowledgeBackgroundScheduler.lastStatus(this);
        final int statusString = status == KnowledgeBackgroundScheduler.LAST_OK
                ? R.string.knowledge_background_last_ok
                : status == KnowledgeBackgroundScheduler.LAST_PARTIAL
                        ? R.string.knowledge_background_last_partial
                        : status == KnowledgeBackgroundScheduler.LAST_UNAVAILABLE
                                ? R.string.knowledge_background_last_unavailable
                                : R.string.knowledge_background_last_error;
        backgroundStatus.setText(getString(R.string.knowledge_background_last,
                java.text.DateFormat.getDateTimeInstance().format(new Date(at)),
                getString(statusString), KnowledgeBackgroundScheduler.lastScanned(this),
                KnowledgeBackgroundScheduler.lastChanged(this)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshBackgroundStatus();
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode,
                                           @NonNull final String[] permissions,
                                           @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_KNOWLEDGE_NOTIFICATIONS && backgroundNotifications != null) {
            final boolean allowed = notificationPermissionGranted();
            KnowledgeBackgroundScheduler.setNotificationsEnabled(this, allowed);
            backgroundNotifications.setChecked(allowed);
        }
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
        updateProcessBadge();
        status.setText(R.string.knowledge_scanning);

        runningScan = worker.submit(() -> {
            try {
                final KnowledgeVaultScanner.Cancellation cancelled =
                        () -> Thread.currentThread().isInterrupted() || scanGeneration.get() != generation;
                final KnowledgeVaultScanner.Snapshot result =
                        KnowledgeVaultScanner.scan(root, KnowledgeVaultScanner.MAX_NOTES,
                                cancelled, (count, directories) -> runOnUiThread(() -> {
                                    if (!isFinishing() && !isDestroyed()
                                            && scanGeneration.get() == generation) {
                                        status.setText(getString(R.string.knowledge_scan_progress,
                                                count, directories));
                                    }
                                }));
                final KnowledgeLocalIndex.SyncResult projection =
                        index.synchronize(root, result, cancelled, (count, total) ->
                                runOnUiThread(() -> {
                                    if (!isFinishing() && !isDestroyed()
                                            && scanGeneration.get() == generation) {
                                        status.setText(getString(R.string.knowledge_index_progress,
                                                count, total));
                                    }
                                }));
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == generation) {
                        snapshot = result;
                        syncResult = projection;
                        progress.setVisibility(View.GONE);
                        updateProcessBadge();
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
                        updateProcessBadge();
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
        updateProcessBadge();
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
                        updateProcessBadge();
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
                        updateProcessBadge();
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
        updateProcessBadge();
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
        updateProcessBadge();
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
                        updateProcessBadge();
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
                        updateProcessBadge();
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
        updateProcessBadge();
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
                    final boolean[] selectedForBatch = new boolean[names.length];
                    final AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.knowledge_bulk_queue_title,
                                    page.optInt("total")))
                            .setMultiChoiceItems(names, selectedForBatch, (view, which, checked) -> {
                                if (checked) {
                                    int count = 0;
                                    for (final boolean value : selectedForBatch) {
                                        if (value) count++;
                                    }
                                    if (count > 8) {
                                        selectedForBatch[which] = false;
                                        ((AlertDialog) view).getListView().setItemChecked(which, false);
                                        Toast.makeText(this, R.string.knowledge_batch_limit,
                                                Toast.LENGTH_LONG).show();
                                    }
                                }
                            })
                            .setPositiveButton(R.string.knowledge_batch_preview,
                                    (view, which) -> previewClaimBatch(candidates, selectedForBatch))
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

    /** The human selects every source/reading; the first bounded batch only accepts claims. */
    private void previewClaimBatch(final JSONArray candidates, final boolean[] selected) {
        int count = 0;
        for (final boolean value : selected) if (value) count++;
        if (count == 1) {
            for (int i = 0; i < selected.length; i++) {
                if (selected[i]) {
                    final JSONObject item = candidates.optJSONObject(i);
                    if (item != null) previewFragment(item.optString("path"),
                            item.optString("fragment_id"));
                    return;
                }
            }
        }
        if (count < 2 || count > 8) {
            Toast.makeText(this, R.string.knowledge_batch_limit, Toast.LENGTH_LONG).show();
            return;
        }
        final int scan = scanGeneration.get();
        status.setText(R.string.knowledge_inspecting);
        worker.submit(() -> {
            try {
                final JSONArray selections = new JSONArray();
                for (int i = 0; i < selected.length; i++) {
                    if (!selected[i]) continue;
                    final JSONObject item = candidates.getJSONObject(i);
                    final String path = item.getString("path");
                    final JSONObject source = new JSONObject();
                    source.put("path", path);
                    source.put("markdown", readSelectedNote(path));
                    source.put("fragment_id", item.getString("fragment_id"));
                    source.put("reading_id", item.getString("reading_id"));
                    selections.put(source);
                }
                final String snapshot = selections.toString();
                final JSONObject preview = KnowledgePythonBridge.previewClaimBatch(
                        this, workspacePath(), snapshot);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || scanGeneration.get() != scan) return;
                    final StringBuilder details = new StringBuilder();
                    details.append(getString(R.string.knowledge_batch_warning)).append("\n\n");
                    final JSONArray items = preview.optJSONArray("items");
                    if (items == null || items.length() != selections.length()) {
                        status.setText(R.string.knowledge_decision_failed);
                        return;
                    }
                    for (int i = 0; i < items.length(); i++) {
                        final JSONObject item = items.optJSONObject(i);
                        if (item != null) {
                            details.append(i + 1).append(". ").append(item.optString("path"))
                                    .append(" · ").append(item.optString("locator"))
                                    .append("\n").append(item.optString("paraphrase"))
                                    .append("\n").append(item.optString("ksf"))
                                    .append("\n\n");
                        }
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.knowledge_batch_confirm)
                            .setMessage(details.toString())
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.knowledge_batch_confirm,
                                    (d, which) -> commitClaimBatch(selections, preview, scan))
                            .show();
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

    private void commitClaimBatch(final JSONArray selections, final JSONObject preview,
                                  final int scan) {
        status.setText(R.string.knowledge_recording_decision);
        worker.submit(() -> {
            try {
                // Re-read every source immediately before committing; a change
                // aborts the entire batch through the preview fingerprint.
                final JSONArray current = new JSONArray();
                for (int i = 0; i < selections.length(); i++) {
                    final JSONObject selected = selections.getJSONObject(i);
                    final JSONObject item = new JSONObject();
                    item.put("path", selected.getString("path"));
                    item.put("markdown", readSelectedNote(selected.getString("path")));
                    item.put("fragment_id", selected.getString("fragment_id"));
                    item.put("reading_id", selected.getString("reading_id"));
                    current.put(item);
                }
                final String head = preview.isNull("journal_head")
                        ? null : preview.getString("journal_head");
                final JSONObject saved = KnowledgePythonBridge.acceptClaimBatch(this,
                        workspacePath(), current.toString(), preview.getString("batch_hash"), head);
                if (!"batch_accept".equals(saved.optString("decision"))
                        || saved.optInt("count") != selections.length()
                        || saved.getJSONArray("journal_ids").length() != 1) {
                    throw new IllegalStateException("No atomic batch commit");
                }
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        Toast.makeText(this, R.string.knowledge_batch_saved, Toast.LENGTH_LONG).show();
                        if (syncResult != null && snapshot != null) showIndexStatus(displayed.size());
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
                            final String label = hit.title.equals(hit.relativePath)
                                    ? hit.relativePath : hit.title + " — " + hit.relativePath;
                            labels.add(hit.confirmedInLastScan ? label
                                    : getString(R.string.knowledge_unverified_source) + " " + label);
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
            summary.append(' ').append(getString(R.string.knowledge_partial_index,
                    syncResult.unverified));
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
                KnowledgeDocumentActivity.open(this, file);
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
        previewFragment(relativePath, fragmentId, null);
    }

    private void previewFragment(final String relativePath, final String fragmentId,
                                 final String expectedSourceHash) {
        final int scan = scanGeneration.get();
        status.setText(R.string.knowledge_inspecting);
        worker.submit(() -> {
            try {
                final String savedMarkdown = readSelectedNote(relativePath);
                if (expectedSourceHash != null && !expectedSourceHash.equals(
                        sha256Hex(savedMarkdown.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
                    throw new IOException("Editor selection source changed before review");
                }
                final JSONObject preview = KnowledgePythonBridge.preview(this, workspacePath(),
                        relativePath, savedMarkdown, fragmentId);
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
                        if (workspacePage == R.id.knowledge_nav_decisions) {
                            ((TextView) findViewById(R.id.knowledge_decisions_status))
                                    .setText(R.string.knowledge_editor_save_first);
                        }
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
            final String reason = preview.optString("reason", "");
            final int guidance = "unsupported punctuation".equals(reason)
                    ? R.string.knowledge_manual_punctuation_help
                    : R.string.knowledge_manual_no_parse_help;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.knowledge_no_interpretation)
                    .setMessage(getString(guidance) + "\n\n"
                            + getString(R.string.knowledge_manual_technical_reason, reason))
                    .setNeutralButton(R.string.knowledge_manual_rephrase,
                            (dialog, which) -> promptManualInterpretation(
                                    relativePath, fragmentId, packet))
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
                .setNeutralButton(R.string.knowledge_manual_rephrase,
                        (dialog, which) -> promptManualInterpretation(
                                relativePath, fragmentId, packet))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * A user-authored alternative, not an edit to the Markdown source.
     * Keep the original fragment and the user assertion visually distinct.
     */
    private void promptManualInterpretation(final String relativePath, final String fragmentId,
                                            final JSONObject sourcePacket) {
        final String original = sourcePacket.optString("source_text");
        final String visibleSource = original.length() > 1000
                ? original.substring(0, 1000) + "…" : original;
        final EditText input = new EditText(this);
        input.setSingleLine(false);
        input.setMaxLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});
        input.setHint(R.string.knowledge_manual_hint);
        input.setText(sourcePacket.optString("manual_text", ""));
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_manual_title)
                .setMessage(getString(R.string.knowledge_manual_source_label)
                        + "\n" + visibleSource + "\n\n"
                        + getString(R.string.knowledge_manual_separate_warning))
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_manual_preview, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    final String statement = input.getText().toString().trim();
                    if (statement.isEmpty() || statement.contains("\n") || statement.contains("\r")) {
                        input.setError(getString(R.string.knowledge_manual_one_statement));
                        return;
                    }
                    dialog.dismiss();
                    previewManualInterpretation(relativePath, fragmentId, sourcePacket, statement);
                }));
        dialog.show();
    }

    private void previewManualInterpretation(final String relativePath, final String fragmentId,
                                              final JSONObject sourcePacket, final String statement) {
        final int scan = scanGeneration.get();
        status.setText(R.string.knowledge_inspecting);
        worker.submit(() -> {
            try {
                final String saved = readSelectedNote(relativePath);
                if (!sourcePacket.optString("source_hash").equals(
                        sha256Hex(saved.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
                    throw new IOException("Source changed since original interpretation");
                }
                final JSONObject packet = KnowledgePythonBridge.previewManual(this,
                        workspacePath(), relativePath, saved, fragmentId, statement);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        chooseReading(relativePath, fragmentId, packet);
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_manual_preview_failed);
                    }
                });
            }
        });
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
                .append(getString(R.string.knowledge_manual_source_label)).append("\n")
                .append(packet.optString("source_text")).append("\n\n");
        if (packet.optBoolean("manual_interpretation")) {
            details.append(getString(R.string.knowledge_manual_statement_label)).append("\n")
                    .append(packet.optString("manual_text")).append("\n\n")
                    .append(getString(R.string.knowledge_manual_separate_warning)).append("\n\n");
        }
        details
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
                final String saved = readSelectedNote(relativePath);
                final JSONObject result = packet.optBoolean("manual_interpretation")
                        ? KnowledgePythonBridge.decideManual(this, workspacePath(),
                                relativePath, saved, fragmentId,
                                packet.getString("manual_text"),
                                packet.getString("manual_text_hash"), readingId,
                                role, packet.getString("source_hash"), head)
                        : KnowledgePythonBridge.decide(this, workspacePath(),
                                relativePath, saved, fragmentId, readingId,
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

    /** Stage 17: human-only, read-only listing. No automatic or bulk retractions. */
    private void showActiveDecisions(final int offset) {
        final int scan = scanGeneration.get();
        worker.submit(() -> {
            try {
                final JSONObject page = KnowledgePythonBridge.activeDecisions(this,
                        workspacePath(), offset);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || scanGeneration.get() != scan) {
                        return;
                    }
                    final JSONArray records = page.optJSONArray("records");
                    if (records == null || records.length() == 0) {
                        Toast.makeText(this, R.string.knowledge_no_active_decisions,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    final int next = page.isNull("next_offset") ? -1
                            : page.optInt("next_offset", -1);
                    final String[] labels = new String[records.length() + (next >= 0 ? 1 : 0)];
                    for (int i = 0; i < records.length(); i++) {
                        final JSONObject record = records.optJSONObject(i);
                        labels[i] = record == null ? "?" : record.optString("role")
                                + " · " + record.optString("summary");
                    }
                    if (next >= 0) {
                        labels[records.length()] = getString(R.string.knowledge_decisions_next);
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.knowledge_active_decisions) + " ("
                                    + page.optInt("total") + ")")
                            .setItems(labels, (dialog, position) -> {
                                if (position == records.length()) {
                                    showActiveDecisions(next);
                                    return;
                                }
                                final JSONObject record = records.optJSONObject(position);
                                if (record != null) {
                                    chooseKnowledgeAction(record.optString("record_id"),
                                            record.optString("role"));
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_retract_failed);
                    }
                });
            }
        });
    }

    private void chooseKnowledgeAction(final String recordId, final String role) {
        final boolean revisable = "claim".equals(role) || "hypothesis".equals(role)
                || "fact".equals(role);
        if (!revisable) {
            showRetractionPreview(recordId);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_decision_actions)
                .setItems(new String[]{getString(R.string.knowledge_revise_action),
                                getString(R.string.knowledge_split_action),
                                getString(R.string.knowledge_merge_action),
                                getString(R.string.knowledge_retract_action)},
                        (dialog, which) -> {
                            if (which == 0) {
                                promptRevision(recordId);
                            } else if (which == 1) {
                                promptSplit(recordId);
                            } else if (which == 2) {
                                chooseMergePartner(recordId, role, 0);
                            } else {
                                showRetractionPreview(recordId);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void promptRevision(final String recordId) {
        final LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(24, 8, 24, 8);
        final EditText statementEdit = new EditText(this);
        statementEdit.setHint(R.string.knowledge_revision_text_hint);
        statementEdit.setSingleLine(false);
        statementEdit.setMinLines(3);
        statementEdit.setMaxLines(5);
        statementEdit.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        final EditText reasonEdit = new EditText(this);
        reasonEdit.setHint(R.string.knowledge_revision_reason_hint);
        reasonEdit.setSingleLine(false);
        reasonEdit.setMaxLines(3);
        fields.addView(statementEdit);
        fields.addView(reasonEdit);
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_revision_title)
                .setView(fields)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_revision_preview,
                        (dialog, which) -> {
                            final String statement = statementEdit.getText().toString();
                            final String reason = reasonEdit.getText().toString();
                            if (statement.trim().isEmpty() || statement.length() > 2000
                                    || reason.length() > 1000) {
                                status.setText(R.string.knowledge_revision_failed);
                            } else {
                                showRevisionPreview(recordId, statement, reason);
                            }
                        })
                .show();
    }

    private void showRevisionPreview(final String recordId, final String statement,
                                     final String reason) {
        final int scan = scanGeneration.get();
        worker.submit(() -> {
            try {
                final JSONObject preview = KnowledgePythonBridge.previewRevision(this,
                        workspacePath(), recordId, statement);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || scanGeneration.get() != scan) {
                        return;
                    }
                    final JSONArray options = preview.optJSONArray("options");
                    if (options == null || options.length() == 0) {
                        status.setText(R.string.knowledge_revision_failed);
                        return;
                    }
                    final String[] labels = new String[options.length()];
                    for (int i = 0; i < options.length(); i++) {
                        final JSONObject option = options.optJSONObject(i);
                        labels[i] = option == null ? "?" : option.optString("reading_id")
                                + " · " + option.optString("paraphrase");
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.knowledge_revision_choose)
                            .setItems(labels, (dialog, which) -> {
                                final JSONObject selected = options.optJSONObject(which);
                                if (selected != null) {
                                    confirmRevision(preview, selected, statement, reason);
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_revision_failed);
                    }
                });
            }
        });
    }

    private void confirmRevision(final JSONObject preview, final JSONObject option,
                                 final String statement, final String reason) {
        final List<KnowledgeDecisionReviewDialog.Section> changes = Arrays.asList(
                new KnowledgeDecisionReviewDialog.Section(R.string.knowledge_diff_before,
                        preview.optString("old_summary"), true),
                new KnowledgeDecisionReviewDialog.Section(R.string.knowledge_diff_after,
                        option.optString("new_summary"), false));
        final StringBuilder details = new StringBuilder()
                .append(getString(R.string.knowledge_diff_proposed)).append("\n")
                .append(statement).append("\n\n")
                .append(preview.optString("role")).append(" · ")
                .append(preview.optString("record_id")).append("\n");
        if (preview.optBoolean("old_summary_truncated")
                || option.optBoolean("new_summary_truncated")) {
            details.append(getString(R.string.knowledge_retract_truncated)).append("\n");
        }
        details.append(getString(R.string.knowledge_revision_conflicts,
                        option.optInt("conflict_count"), option.optInt("diagnostic_count")));
        KnowledgeDecisionReviewDialog.show(this, R.string.knowledge_revision_confirm,
                R.string.knowledge_revision_confirm, changes, details.toString(),
                getString(R.string.knowledge_revision_warning),
                () -> commitRevision(preview, option, statement, reason));
    }

    private void commitRevision(final JSONObject preview, final JSONObject option,
                                final String statement, final String reason) {
        final int scan = scanGeneration.get();
        worker.submit(() -> {
            try {
                final String expectedHead = preview.isNull("journal_head") ? null
                        : preview.getString("journal_head");
                final JSONObject saved = KnowledgePythonBridge.reviseDecision(this,
                        workspacePath(), preview.getString("record_id"), statement,
                        option.getString("reading_id"), reason,
                        preview.getString("statement_hash"), expectedHead);
                if (!"revise".equals(saved.optString("decision"))
                        || saved.optJSONArray("journal_ids") == null) {
                    throw new IllegalStateException("No revision commit");
                }
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        Toast.makeText(this, R.string.knowledge_revision_saved,
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_revision_failed);
                    }
                });
            }
        });
    }

    /** Explicit single-record split: two separately selected readings, one atomic journal append. */
    private void promptSplit(final String recordId) {
        final LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(24, 8, 24, 8);
        final EditText first = new EditText(this);
        first.setHint(R.string.knowledge_split_first_hint);
        first.setMinLines(2);
        first.setMaxLines(4);
        first.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        final EditText second = new EditText(this);
        second.setHint(R.string.knowledge_split_second_hint);
        second.setMinLines(2);
        second.setMaxLines(4);
        second.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        final EditText reason = new EditText(this);
        reason.setHint(R.string.knowledge_revision_reason_hint);
        reason.setMaxLines(3);
        fields.addView(first);
        fields.addView(second);
        fields.addView(reason);
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_split_title)
                .setView(fields)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_revision_preview, (dialog, which) -> {
                    final String firstText = first.getText().toString();
                    final String secondText = second.getText().toString();
                    final String reasonText = reason.getText().toString();
                    if (firstText.trim().isEmpty() || secondText.trim().isEmpty()
                            || firstText.length() > 2000 || secondText.length() > 2000
                            || reasonText.length() > 1000) {
                        status.setText(R.string.knowledge_split_failed);
                    } else {
                        showSplitPreview(recordId, firstText, secondText, reasonText);
                    }
                })
                .show();
    }

    private void showSplitPreview(final String recordId, final String first,
                                  final String second, final String reason) {
        final int scan = scanGeneration.get();
        worker.submit(() -> {
            try {
                final JSONObject preview = KnowledgePythonBridge.previewSplit(this,
                        workspacePath(), recordId, first, second);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || scanGeneration.get() != scan) {
                        return;
                    }
                    chooseSplitReading(preview, first, second, reason, true, null);
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_split_failed);
                    }
                });
            }
        });
    }

    private void chooseSplitReading(final JSONObject preview, final String first,
                                    final String second, final String reason,
                                    final boolean choosingFirst, final JSONObject firstOption) {
        final JSONArray options = preview.optJSONArray(
                choosingFirst ? "first_options" : "second_options");
        if (options == null || options.length() == 0) {
            status.setText(R.string.knowledge_split_failed);
            return;
        }
        final String[] labels = new String[options.length()];
        for (int i = 0; i < options.length(); i++) {
            final JSONObject option = options.optJSONObject(i);
            labels[i] = option == null ? "?" : option.optString("reading_id")
                    + " · " + option.optString("paraphrase");
        }
        new AlertDialog.Builder(this)
                .setTitle(choosingFirst ? R.string.knowledge_split_choose_first
                        : R.string.knowledge_split_choose_second)
                .setItems(labels, (dialog, which) -> {
                    final JSONObject choice = options.optJSONObject(which);
                    if (choice == null) {
                        return;
                    }
                    if (choosingFirst) {
                        chooseSplitReading(preview, first, second, reason, false, choice);
                    } else {
                        confirmSplit(preview, firstOption, choice, first, second, reason);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmSplit(final JSONObject preview, final JSONObject firstOption,
                              final JSONObject secondOption, final String first,
                              final String second, final String reason) {
        if (firstOption == null || secondOption == null) {
            status.setText(R.string.knowledge_split_failed);
            return;
        }
        final List<KnowledgeDecisionReviewDialog.Section> changes = Arrays.asList(
                new KnowledgeDecisionReviewDialog.Section(R.string.knowledge_diff_before,
                        preview.optString("old_summary"), true),
                new KnowledgeDecisionReviewDialog.Section(R.string.knowledge_diff_after_first,
                        firstOption.optString("new_summary"), false),
                new KnowledgeDecisionReviewDialog.Section(R.string.knowledge_diff_after_second,
                        secondOption.optString("new_summary"), false));
        final StringBuilder details = new StringBuilder()
                .append(preview.optString("role")).append("\n")
                .append(getString(R.string.knowledge_diff_proposed)).append("\n1. ")
                .append(first).append("\n2. ").append(second).append("\n")
                .append(getString(R.string.knowledge_split_diagnostics,
                        firstOption.optInt("conflict_count") + secondOption.optInt("conflict_count"),
                        firstOption.optInt("diagnostic_count") + secondOption.optInt("diagnostic_count")));
        if (preview.optBoolean("old_summary_truncated")) {
            details.append("\n").append(getString(R.string.knowledge_retract_truncated));
        }
        KnowledgeDecisionReviewDialog.show(this, R.string.knowledge_split_confirm,
                R.string.knowledge_split_confirm, changes, details.toString(),
                getString(R.string.knowledge_split_warning),
                () -> commitSplit(preview, firstOption, secondOption, first, second, reason));
    }

    private void commitSplit(final JSONObject preview, final JSONObject firstOption,
                             final JSONObject secondOption, final String first,
                             final String second, final String reason) {
        final int scan = scanGeneration.get();
        worker.submit(() -> {
            try {
                final String expectedHead = preview.isNull("journal_head") ? null
                        : preview.getString("journal_head");
                final JSONObject saved = KnowledgePythonBridge.splitDecision(this,
                        workspacePath(), preview.getString("record_id"), first, second,
                        firstOption.getString("reading_id"), secondOption.getString("reading_id"),
                        reason, preview.getString("split_hash"), expectedHead);
                if (!"split".equals(saved.optString("decision"))
                        || saved.getJSONArray("new_record_ids").length() != 2
                        || saved.getJSONArray("journal_ids").length() != 1) {
                    throw new IllegalStateException("No atomic split commit");
                }
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        Toast.makeText(this, R.string.knowledge_split_saved,
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_split_failed);
                    }
                });
            }
        });
    }

    /** Human selects an independently active, same-role second record; never auto-match. */
    private void chooseMergePartner(final String firstId, final String role, final int offset) {
        final int scan = scanGeneration.get();
        worker.submit(() -> {
            try {
                final JSONObject page = KnowledgePythonBridge.activeDecisions(this,
                        workspacePath(), offset);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || scanGeneration.get() != scan) {
                        return;
                    }
                    final JSONArray all = page.optJSONArray("records");
                    final java.util.ArrayList<JSONObject> eligible = new java.util.ArrayList<>();
                    if (all != null) {
                        for (int i = 0; i < all.length(); i++) {
                            final JSONObject item = all.optJSONObject(i);
                            if (item != null && role.equals(item.optString("role"))
                                    && !firstId.equals(item.optString("record_id"))) {
                                eligible.add(item);
                            }
                        }
                    }
                    final int next = page.isNull("next_offset") ? -1
                            : page.optInt("next_offset", -1);
                    if (eligible.isEmpty() && next < 0) {
                        Toast.makeText(this, R.string.knowledge_merge_no_partner,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    final String[] labels = new String[eligible.size() + (next >= 0 ? 1 : 0)];
                    for (int i = 0; i < eligible.size(); i++) {
                        labels[i] = eligible.get(i).optString("role") + " · "
                                + eligible.get(i).optString("summary");
                    }
                    if (next >= 0) {
                        labels[eligible.size()] = getString(R.string.knowledge_decisions_next);
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.knowledge_merge_choose_partner)
                            .setItems(labels, (dialog, which) -> {
                                if (which == eligible.size()) {
                                    chooseMergePartner(firstId, role, next);
                                } else {
                                    promptMerge(firstId, eligible.get(which).optString("record_id"));
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_merge_failed);
                    }
                });
            }
        });
    }

    private void promptMerge(final String firstId, final String secondId) {
        final LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(24, 8, 24, 8);
        final EditText statementEdit = new EditText(this);
        statementEdit.setHint(R.string.knowledge_merge_statement_hint);
        statementEdit.setMinLines(3);
        statementEdit.setMaxLines(5);
        statementEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        final EditText reasonEdit = new EditText(this);
        reasonEdit.setHint(R.string.knowledge_revision_reason_hint);
        reasonEdit.setMaxLines(3);
        fields.addView(statementEdit);
        fields.addView(reasonEdit);
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_merge_title)
                .setView(fields)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_revision_preview, (dialog, which) -> {
                    final String statement = statementEdit.getText().toString();
                    final String reason = reasonEdit.getText().toString();
                    if (statement.trim().isEmpty() || statement.length() > 2000
                            || reason.length() > 1000) {
                        status.setText(R.string.knowledge_merge_failed);
                    } else {
                        showMergePreview(firstId, secondId, statement, reason);
                    }
                })
                .show();
    }

    private void showMergePreview(final String firstId, final String secondId,
                                  final String statement, final String reason) {
        final int scan = scanGeneration.get();
        worker.submit(() -> {
            try {
                final JSONObject preview = KnowledgePythonBridge.previewMerge(this,
                        workspacePath(), firstId, secondId, statement);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || scanGeneration.get() != scan) {
                        return;
                    }
                    final JSONArray options = preview.optJSONArray("options");
                    if (options == null || options.length() == 0) {
                        status.setText(R.string.knowledge_merge_failed);
                        return;
                    }
                    final String[] labels = new String[options.length()];
                    for (int i = 0; i < options.length(); i++) {
                        final JSONObject option = options.optJSONObject(i);
                        labels[i] = option == null ? "?" : option.optString("reading_id")
                                + " · " + option.optString("paraphrase");
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.knowledge_merge_choose_reading)
                            .setItems(labels, (dialog, which) -> {
                                final JSONObject option = options.optJSONObject(which);
                                if (option != null) {
                                    confirmMerge(preview, option, statement, reason);
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_merge_failed);
                    }
                });
            }
        });
    }

    private void confirmMerge(final JSONObject preview, final JSONObject option,
                              final String statement, final String reason) {
        final JSONArray old = preview.optJSONArray("old_summaries");
        if (old == null || old.length() != 2) {
            status.setText(R.string.knowledge_merge_failed);
            return;
        }
        final List<KnowledgeDecisionReviewDialog.Section> changes = Arrays.asList(
                new KnowledgeDecisionReviewDialog.Section(R.string.knowledge_diff_before_first,
                        old.optString(0), true),
                new KnowledgeDecisionReviewDialog.Section(R.string.knowledge_diff_before_second,
                        old.optString(1), true),
                new KnowledgeDecisionReviewDialog.Section(R.string.knowledge_diff_after,
                        option.optString("new_summary"), false));
        final String details = preview.optString("role") + "\n"
                + getString(R.string.knowledge_diff_proposed) + "\n" + statement + "\n\n"
                + getString(R.string.knowledge_revision_conflicts,
                        option.optInt("conflict_count"), option.optInt("diagnostic_count"));
        KnowledgeDecisionReviewDialog.show(this, R.string.knowledge_merge_confirm,
                R.string.knowledge_merge_confirm, changes, details,
                getString(R.string.knowledge_merge_warning),
                () -> commitMerge(preview, option, statement, reason));
    }

    private void commitMerge(final JSONObject preview, final JSONObject option,
                             final String statement, final String reason) {
        final int scan = scanGeneration.get();
        worker.submit(() -> {
            try {
                final JSONArray ids = preview.getJSONArray("record_ids");
                final String expectedHead = preview.isNull("journal_head") ? null
                        : preview.getString("journal_head");
                final JSONObject saved = KnowledgePythonBridge.mergeDecisions(this,
                        workspacePath(), ids.getString(0), ids.getString(1), statement,
                        option.getString("reading_id"), reason,
                        preview.getString("statement_hash"), expectedHead);
                if (!"merge".equals(saved.optString("decision"))
                        || saved.getJSONArray("journal_ids").length() != 1) {
                    throw new IllegalStateException("No atomic merge commit");
                }
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        Toast.makeText(this, R.string.knowledge_merge_saved,
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_merge_failed);
                    }
                });
            }
        });
    }

    private void showRetractionPreview(final String recordId) {
        final int scan = scanGeneration.get();
        worker.submit(() -> {
            try {
                final JSONObject preview = KnowledgePythonBridge.previewRetraction(this,
                        workspacePath(), recordId);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || scanGeneration.get() != scan) {
                        return;
                    }
                    final List<KnowledgeDecisionReviewDialog.Section> changes = Arrays.asList(
                            new KnowledgeDecisionReviewDialog.Section(
                                    R.string.knowledge_diff_before,
                                    preview.optString("summary"), true),
                            new KnowledgeDecisionReviewDialog.Section(
                                    R.string.knowledge_diff_after,
                                    getString(R.string.knowledge_diff_retraction_outcome), false));
                    final StringBuilder details = new StringBuilder()
                            .append(preview.optString("role")).append(" · ")
                            .append(preview.optString("record_id")).append("\n");
                    if (preview.optBoolean("summary_truncated")) {
                        details.append(getString(R.string.knowledge_retract_truncated))
                                .append("\n");
                    }
                    details.append(getString(R.string.knowledge_retract_impact,
                                    preview.optInt("affected_derived"),
                                    preview.optInt("rederived")));
                    KnowledgeDecisionReviewDialog.show(this, R.string.knowledge_retract_preview,
                            R.string.knowledge_retract_confirm, changes, details.toString(),
                            getString(R.string.knowledge_retract_warning),
                            () -> commitRetraction(preview));
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_retract_failed);
                    }
                });
            }
        });
    }

    private void commitRetraction(final JSONObject preview) {
        final int scan = scanGeneration.get();
        worker.submit(() -> {
            try {
                final String expectedHead = preview.isNull("journal_head") ? null
                        : preview.getString("journal_head");
                final JSONObject saved = KnowledgePythonBridge.retractDecision(this,
                        workspacePath(), preview.getString("record_id"), expectedHead);
                if (!"retract".equals(saved.optString("decision"))
                        || saved.optJSONArray("journal_ids") == null) {
                    throw new IllegalStateException("No retraction commit");
                }
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        Toast.makeText(this, R.string.knowledge_retract_success,
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == scan) {
                        status.setText(R.string.knowledge_retract_failed);
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
        updateProcessBadge();
        if (embeddingButton != null) {
            embeddingButton.setText(R.string.knowledge_embedding_build);
        }
        if (status != null) {
            showEmbeddingStatus(R.string.knowledge_embedding_cancelled);
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
        updateProcessBadge();
        embeddingButton.setText(R.string.knowledge_embedding_cancel);
        final int operation = embeddingGeneration.incrementAndGet();
        showEmbeddingStatus(getString(R.string.knowledge_embedding_running, 0, pending.size()));
        runningEmbedding = embeddingWorker.submit(() -> {
            int completed = 0;
            int skipped = 0;
            int incompleteCoverage = 0;
            try {
                for (int i = 0; i < pending.size(); i++) {
                    if (Thread.currentThread().isInterrupted() || scanGeneration.get() != scan
                            || embeddingGeneration.get() != operation) {
                        return;
                    }
                    final KnowledgeVaultScanner.Note note = pending.get(i);
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
                        final boolean truncated = KnowledgeEmbeddingChunks.coverageTruncated(
                                text, chunks);
                        final int from = index.beginEmbedding(note.relativePath,
                                note.modifiedAtMillis, note.sizeBytes, modelKey,
                                chunks.size(), truncated);
                        for (int position = from; position < chunks.size();
                             position += KnowledgeEmbeddingClient.MAX_BATCH) {
                            if (Thread.currentThread().isInterrupted()
                                    || scanGeneration.get() != scan
                                    || embeddingGeneration.get() != operation) {
                                return; // Previously checkpointed chunks remain reusable.
                            }
                            final List<String> inputs = new ArrayList<>();
                            final int until = Math.min(chunks.size(),
                                    position + KnowledgeEmbeddingClient.MAX_BATCH);
                            for (int k = position; k < until; k++) {
                                inputs.add(chunks.get(k).text);
                            }
                            // No text leaves without the batch-level user confirmation.
                            final float[][] vectors;
                            try {
                                vectors = KnowledgeEmbeddingClient.embed(
                                        endpoint, model, apiKey, inputs);
                            } catch (IOException network) {
                                // A failed service must stop this run; retain checkpoints.
                                throw new IllegalStateException("Embedding service unavailable");
                            }
                            if (note.file.lastModified() != note.modifiedAtMillis
                                    || note.file.length() != note.sizeBytes) {
                                throw new IOException("Source changed during embedding");
                            }
                            index.appendEmbeddingBatch(note.relativePath, note.modifiedAtMillis,
                                    note.sizeBytes, modelKey, chunks, position, vectors);
                            final int indexedChunks = until;
                            final int currentNote = i + 1;
                            runOnUiThread(() -> {
                                if (!isFinishing() && !isDestroyed()
                                        && embeddingGeneration.get() == operation) {
                                    showEmbeddingStatus(getString(R.string.knowledge_embedding_chunk_progress,
                                            currentNote, pending.size(),
                                            indexedChunks, chunks.size()));
                                }
                            });
                        }
                        completed++;
                        if (truncated) {
                            incompleteCoverage++;
                        }
                    } catch (IOException inaccessible) {
                        skipped++;
                    }
                    final int progressNow = i + 1;
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()
                                && embeddingGeneration.get() == operation) {
                            showEmbeddingStatus(getString(R.string.knowledge_embedding_running,
                                    progressNow, pending.size()));
                        }
                    });
                }
                final int succeeded = completed;
                final int missing = skipped;
                final int truncated = incompleteCoverage;
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()
                            && embeddingGeneration.get() == operation) {
                        embeddingActive = false;
                        updateProcessBadge();
                        embeddingButton.setText(R.string.knowledge_embedding_build);
                        showEmbeddingStatus(getString(R.string.knowledge_embedding_done,
                                succeeded, missing) + " " + getString(
                                R.string.knowledge_embedding_coverage, truncated));
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()
                            && embeddingGeneration.get() == operation) {
                        embeddingActive = false;
                        updateProcessBadge();
                        embeddingButton.setText(R.string.knowledge_embedding_build);
                        showEmbeddingStatus(R.string.knowledge_embedding_failed);
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
                                content, match.excerptStart, match.excerptLength);
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

    private void showIndexHealth() {
        final int generation = scanGeneration.get();
        status.setText(R.string.knowledge_index_health_checking);
        worker.submit(() -> {
            try {
                final KnowledgeLocalIndex.IndexHealth health = index.healthReport();
                final String lastScan = health.lastScanMillis == 0
                        ? getString(R.string.knowledge_index_never)
                        : java.text.DateFormat.getDateTimeInstance().format(
                                new Date(health.lastScanMillis));
                final String report = getString(health.isHealthy()
                                ? R.string.knowledge_index_health_ok
                                : R.string.knowledge_index_health_issues)
                        + "\n\n" + getString(R.string.knowledge_index_health_last,
                        lastScan, health.lastScanCount,
                        getString(health.lastScanIncomplete
                                ? R.string.knowledge_index_yes : R.string.knowledge_index_no),
                        health.unreadableDirectories)
                        + "\n\n" + getString(R.string.knowledge_index_health_sources,
                        health.quickCheck, health.sources, health.textRows,
                        health.missingTextRows, health.orphanTextRows, health.links)
                        + "\n" + getString(R.string.knowledge_index_unverified,
                        health.unverifiedSources)
                        + "\n\n" + getString(R.string.knowledge_index_health_vectors,
                        health.embeddedNotes, health.embeddedChunks, health.lshRows,
                        health.incompleteEmbeddings, health.truncatedEmbeddings,
                        health.orphanEmbeddingNotes, health.orphanChunks, health.orphanLsh);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || scanGeneration.get() != generation) {
                        return;
                    }
                    status.setText(health.isHealthy()
                            ? R.string.knowledge_index_health_ok
                            : R.string.knowledge_index_health_issues);
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.knowledge_index_health)
                            .setMessage(report)
                            .setNegativeButton(android.R.string.ok, null)
                            .setPositiveButton(R.string.knowledge_index_rebuild,
                                    (dialog, which) -> requestIndexRebuild())
                            .show();
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()
                            && scanGeneration.get() == generation) {
                        status.setText(R.string.knowledge_index_health_issues);
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.knowledge_index_health)
                                .setMessage(R.string.knowledge_index_unavailable)
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(R.string.knowledge_index_rebuild,
                                        (dialog, which) -> requestIndexRebuild())
                                .show();
                    }
                });
            }
        });
    }

    private void requestIndexRebuild() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_index_rebuild)
                .setMessage(R.string.knowledge_index_rebuild_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_index_rebuild, (dialog, which) -> {
                    // Cancel every caller that could still write the disposable index.
                    scanGeneration.incrementAndGet();
                    if (runningScan != null) {
                        runningScan.cancel(true);
                    }
                    if (runningSearch != null) {
                        runningSearch.cancel(true);
                    }
                    auditGeneration.incrementAndGet();
                    if (auditActive) {
                        cancelFullAudit();
                    }
                    if (analysisActive) {
                        cancelBulkAnalysis();
                    }
                    if (embeddingActive) {
                        cancelEmbeddingBuild();
                    }
                    status.setText(R.string.knowledge_index_rebuilding);
                    worker.submit(() -> {
                        try {
                            index.resetDerivedIndex();
                            runOnUiThread(() -> {
                                if (!isFinishing() && !isDestroyed()) {
                                    scanNotebook();
                                }
                            });
                        } catch (IOException failed) {
                            runOnUiThread(() -> {
                                if (!isFinishing() && !isDestroyed()) {
                                    status.setText(R.string.knowledge_index_rebuild_failed);
                                }
                            });
                        }
                    });
                }).show();
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
        decisionGeneration.incrementAndGet();
        if (runningDecisionLoad != null) runningDecisionLoad.cancel(true);
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
        deviceQaWorker.shutdownNow();
        decisionWorker.shutdownNow();
        super.onDestroy();
    }
}
