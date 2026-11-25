/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Added to the Markor fork for DP Knowledge System.
 */
package net.gsantner.markor.knowledge;

import android.app.Activity;
import android.app.ActivityManager;
import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.SystemClock;
import android.provider.OpenableColumns;
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
import android.widget.ScrollView;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.ViewCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Offline knowledge workspace: discover files without changing them, maintain
 * an app-private disposable text index, and open a chosen file in Markor.
 */
public final class KnowledgeHubActivity extends MarkorBaseActivity {
    private static final int LIBRARY_PAGE_SIZE = 60;
    private static final String GUIDE_PREFERENCES = "dpks_knowledge_guide";
    private static final String GUIDE_SEEN = "stage22_guide_seen_v1";
    private static final String PROCESS_HISTORY_PREFERENCES = "dpks_process_events";
    private static final String PROCESS_HISTORY_KEY = "redacted_events_v1";
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
    private static final String STATE_LIBRARY_OFFSET = "dpks_library_offset";
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
    /** A whole reviewable transfer manifest, never a truncated preview. */
    private static final int MAX_EMBED_PREVIEW_CHARS = 128_000;
    private static final int REQUEST_EXPORT_JOURNAL = 40021;
    private static final int REQUEST_IMPORT_JOURNAL = 40022;
    private static final int REQUEST_KNOWLEDGE_NOTIFICATIONS = 40023;
    private static final int REQUEST_IMPORT_MODEL = 40024;
    private static final int REQUEST_IMPORT_MODEL_PACKAGE = 40025;
    private static final String STATE_PENDING_MODEL_IMPORT = "dpks_pending_model_import";
    private static final String STATE_PENDING_MODEL_PACKAGE = "dpks_pending_model_package";
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
    private final ExecutorService modelWorker = Executors.newSingleThreadExecutor();
    private final AtomicInteger scanGeneration = new AtomicInteger();
    private final AtomicInteger queryGeneration = new AtomicInteger();
    private final AtomicInteger analysisGeneration = new AtomicInteger();
    private final AtomicInteger auditGeneration = new AtomicInteger();
    private final AtomicInteger embeddingGeneration = new AtomicInteger();
    private final AtomicInteger deviceQaGeneration = new AtomicInteger();
    private final AtomicInteger decisionGeneration = new AtomicInteger();
    private final AtomicInteger modelGeneration = new AtomicInteger();
    private final List<KnowledgeLocalIndex.Hit> displayed = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private final List<JSONObject> decisionItems = new ArrayList<>();
    private final List<Map<String, String>> decisionRows = new ArrayList<>();

    private Future<?> runningScan;
    private Future<?> runningSearch;
    private Future<?> runningAnalysis;
    private Future<?> runningAudit;
    private Future<?> runningEmbedding;
    private KnowledgeNetworkApproval.Permit activeIndexApproval;
    private Future<?> runningDeviceQa;
    private Future<?> runningDecisionLoad;
    private Future<?> runningModelImport;
    private KnowledgeVaultScanner.Snapshot snapshot;
    private KnowledgeLocalIndex.SyncResult syncResult;
    private KnowledgeLocalIndex index;
    private File notebook;
    private ArrayAdapter<String> adapter;
    private TextView status;
    private TextView analysisStatus;
    private TextView embeddingStatus;
    private TextView modelStatus;
    private KnowledgeModelFiles modelFiles;
    private KnowledgeModelPackageStore modelPackages;
    private KnowledgeOnnxIsolatedClient onnxSmokeClient;
    private KnowledgeOnnxIsolatedSessionClient onnxSessionClient;
    private KnowledgeModelPolicy.Role pendingModelImportRole;
    private String pendingModelPackageKey;
    private boolean modelImportActive;
    private Button analysisButton;
    private Button auditButton;
    private Button embeddingButton;
    private Button qaQuickButton;
    private Button qaStressButton;
    private Button qaEditorButton;
    private TextView qaStatus;
    private ProgressBar qaProgress;
    private boolean qaActive;
    private boolean qaCancellationRequested;
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
    private int libraryOffset;
    private int libraryNextOffset = -1;
    private boolean decisionWorkspaceReady;
    private final KnowledgeWorkspaceNavigation workspaceNavigation = new KnowledgeWorkspaceNavigation(
            R.id.knowledge_nav_library, R.id.knowledge_nav_decisions,
            R.id.knowledge_nav_processes, R.id.knowledge_nav_manage);
    private KnowledgeProcessHistory processHistory;
    // Prepared only after verifying and encrypting the existing journal.
    // No SAF destination is created for an empty or unreadable journal.
    private byte[] pendingExportEnvelope;

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
            libraryOffset = Math.max(0, savedInstanceState.getInt(STATE_LIBRARY_OFFSET, 0));
            final String pending = savedInstanceState.getString(STATE_PENDING_MODEL_IMPORT, "");
            pendingModelPackageKey = savedInstanceState.getString(STATE_PENDING_MODEL_PACKAGE, "");
            try {
                if (!pending.isEmpty()) {
                    pendingModelImportRole = KnowledgeModelPolicy.Role.valueOf(pending);
                }
            } catch (IllegalArgumentException unknown) {
                pendingModelImportRole = null;
            }
        }
        final int initialPage = savedInstanceState == null
                ? R.id.knowledge_nav_library
                : savedInstanceState.getInt(STATE_WORKSPACE_PAGE, R.id.knowledge_nav_library);
        showWorkspacePage(initialPage);
        navigation.setSelectedItemId(workspaceNavigation.page());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                switch (workspaceNavigation.backAction()) {
                    case CLOSE_DETAIL:
                        closeDecisionDetail();
                        break;
                    case LIBRARY:
                        navigation.setSelectedItemId(R.id.knowledge_nav_library);
                        break;
                    case FINISH:
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                        break;
                }
            }
        });

        notebook = _appSettings.getNotebookDirectory();
        index = new KnowledgeLocalIndex(this);
        modelFiles = new KnowledgeModelFiles(new File(getFilesDir(), "dpks-models-v1"));
        modelPackages = new KnowledgeModelPackageStore(
                new File(getFilesDir(), "dpks-models-v1"), modelFiles);
        modelStatus = findViewById(R.id.knowledge_models_status);
        onnxSmokeClient = new KnowledgeOnnxIsolatedClient(this);
        onnxSessionClient = new KnowledgeOnnxIsolatedSessionClient(this);
        findViewById(R.id.knowledge_models_open).setOnClickListener(view -> {
            if (modelImportActive) {
                cancelModelImport();
            } else {
                new KnowledgeModelSettingsDialog(this, modelFiles, modelPackages,
                        this::pickModelFile, this::pickModelPackage,
                        this::downloadModel, this::auditModelPackage,
                        this::prepareOnnxFiles, this::runOnnxCpuSmoke,
                        this::runOnnxFdSmoke,
                        this::inspectOnnxCpuSession).show();
            }
        });
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
        findViewById(R.id.knowledge_entity_matching).setOnClickListener(view ->
                new KnowledgeEntityReviewDialog(this, workspacePath(), notebook,
                        decisionWorker, this::openIndexedNote, () -> loadDecisionPage(0)).show());
        findViewById(R.id.knowledge_source_lineage).setOnClickListener(view ->
                new KnowledgeSourceReviewDialog(this, workspacePath(), notebook,
                        decisionWorker, this::openIndexedNote, () -> loadDecisionPage(0)).show());
        findViewById(R.id.knowledge_source_assessment).setOnClickListener(view ->
                new KnowledgeSourceAssessmentDialog(this, workspacePath(), notebook,
                        decisionWorker, this::openIndexedNote, () -> loadDecisionPage(0)).show());
        findViewById(R.id.knowledge_version_reviews).setOnClickListener(view ->
                new KnowledgeVersionReviewDialog(this, workspacePath(), notebook,
                        decisionWorker, null, null, () -> loadDecisionPage(0)).history());
        progress = findViewById(R.id.knowledge_progress);
        filter = findViewById(R.id.knowledge_filter);

        adapter = new ArrayAdapter<>(this, R.layout.knowledge__note_row, labels);
        final ListView list = findViewById(R.id.knowledge_list);
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.knowledge_empty));
        findViewById(R.id.knowledge_library_previous).setEnabled(false);
        findViewById(R.id.knowledge_library_next).setEnabled(false);
        findViewById(R.id.knowledge_library_previous).setOnClickListener(view ->
                loadLibraryPage(Math.max(0, libraryOffset - LIBRARY_PAGE_SIZE)));
        findViewById(R.id.knowledge_library_next).setOnClickListener(view -> {
            if (libraryNextOffset >= 0) loadLibraryPage(libraryNextOffset);
        });
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
        findViewById(R.id.knowledge_library_relations).setOnClickListener(
                view -> showLibraryRelationsPicker());

        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                libraryOffset = 0;
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
        qaEditorButton = findViewById(R.id.knowledge_qa_editor);
        qaStatus = findViewById(R.id.knowledge_qa_status);
        qaProgress = findViewById(R.id.knowledge_qa_progress);
        qaQuickButton.setOnClickListener(view -> runDeviceQa(false, false));
        qaStressButton.setOnClickListener(view -> runDeviceQa(true, false));
        qaEditorButton.setOnClickListener(view -> runDeviceQa(false, true));
        findViewById(R.id.knowledge_process_jump_qa).setOnClickListener(view -> {
            final ScrollView processes = findViewById(R.id.knowledge_page_processes);
            final View qaCard = findViewById(R.id.knowledge_process_qa_card);
            processes.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    processes.smoothScrollTo(0, qaCard.getTop());
                }
            });
        });
        findViewById(R.id.knowledge_backup_export).setOnClickListener(view -> requestJournalExport());
        findViewById(R.id.knowledge_backup_import).setOnClickListener(view -> requestJournalImport());
        findViewById(R.id.knowledge_open_guide).setOnClickListener(view -> showKnowledgeGuide());
        processHistory = KnowledgeProcessHistory.fromStored(
                getSharedPreferences(PROCESS_HISTORY_PREFERENCES, MODE_PRIVATE)
                        .getString(PROCESS_HISTORY_KEY, ""));
        renderProcessHistory();
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
        if (savedInstanceState == null
                && (launch == null || !launch.hasExtra(EXTRA_EDITOR_REVIEW_PATH))
                && !getSharedPreferences(GUIDE_PREFERENCES, MODE_PRIVATE)
                .getBoolean(GUIDE_SEEN, false)) {
            // Never inspect a vault or journal for onboarding. Posting also avoids
            // showing a dialog before the four-destination shell is laid out.
            findViewById(R.id.knowledge_open_guide).post(() -> {
                if (!isFinishing() && !isDestroyed()) showKnowledgeGuide();
            });
        }
    }

    private void showKnowledgeGuide() {
        final View guide = getLayoutInflater().inflate(R.layout.knowledge__guide, null);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.knowledge_guide_title)
                .setView(guide)
                .setPositiveButton(R.string.knowledge_guide_understood, (dialog, which) ->
                        markGuideSeen())
                .setNegativeButton(R.string.knowledge_guide_later, (dialog, which) ->
                        markGuideSeen())
                .show();
    }

    private void markGuideSeen() {
        getSharedPreferences(GUIDE_PREFERENCES, MODE_PRIVATE).edit()
                .putBoolean(GUIDE_SEEN, true).apply();
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState) {
        outState.putInt(STATE_WORKSPACE_PAGE, workspaceNavigation.page());
        outState.putString(STATE_DECISION_CATEGORY, decisionCategory);
        outState.putInt(STATE_LIBRARY_OFFSET, libraryOffset);
        if (pendingModelImportRole != null) {
            outState.putString(STATE_PENDING_MODEL_IMPORT, pendingModelImportRole.name());
        }
        if (pendingModelPackageKey != null) {
            outState.putString(STATE_PENDING_MODEL_PACKAGE, pendingModelPackageKey);
        }
        super.onSaveInstanceState(outState);
    }

    /** Android SAF is only a source of bytes. It is never granted vault access. */
    private void pickModelFile(final KnowledgeModelPolicy.Role role) {
        if (modelImportActive || role == null) return;
        pendingModelImportRole = role;
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_IMPORT_MODEL);
        } catch (RuntimeException unavailable) {
            pendingModelImportRole = null;
            modelStatus.setText(R.string.knowledge_models_picker_failed);
        }
    }

    /** A package picker contains only an already-installed weight's sidecars and manifest. */
    private void pickModelPackage(final KnowledgeModelFiles.Entry entry) {
        if (modelImportActive || entry == null) return;
        pendingModelPackageKey = entry.role.name() + ":" + entry.sha256;
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQUEST_IMPORT_MODEL_PACKAGE);
        } catch (RuntimeException unavailable) {
            pendingModelPackageKey = null;
            modelStatus.setText(R.string.knowledge_models_picker_failed);
        }
    }

    private void importModelPackageFromPicker(final Intent picker,
                                              final KnowledgeModelFiles.Entry entry) {
        if (modelImportActive || entry == null) return;
        final List<Uri> chosen = new ArrayList<>();
        final ClipData clip = picker.getClipData();
        if (clip != null) {
            if (clip.getItemCount() > 17) {
                modelStatus.setText(R.string.knowledge_models_package_failed);
                return;
            }
            for (int i = 0; i < clip.getItemCount(); i++) {
                chosen.add(clip.getItemAt(i).getUri());
            }
        } else if (picker.getData() != null) {
            chosen.add(picker.getData());
        }
        if (chosen.size() < 2 || chosen.size() > 17) {
            modelStatus.setText(R.string.knowledge_models_package_failed);
            return;
        }
        Uri manifestUri = null;
        final List<KnowledgeModelPackageStore.Source> sidecars = new ArrayList<>();
        for (final Uri source : chosen) {
            if (source == null || !"content".equals(source.getScheme())) {
                modelStatus.setText(R.string.knowledge_models_package_failed);
                return;
            }
            final String name = modelDisplayName(source);
            if (KnowledgeModelPackageManifest.FILENAME.equals(name)) {
                if (manifestUri != null) {
                    modelStatus.setText(R.string.knowledge_models_package_failed);
                    return;
                }
                manifestUri = source;
            } else {
                final Uri selectedUri = source;
                sidecars.add(new KnowledgeModelPackageStore.Source(name,
                        () -> getContentResolver().openInputStream(selectedUri)));
            }
        }
        if (manifestUri == null) {
            modelStatus.setText(R.string.knowledge_models_package_failed);
            return;
        }
        modelImportActive = true;
        ((Button) findViewById(R.id.knowledge_models_open))
                .setText(R.string.knowledge_models_cancel_transfer);
        final int operation = modelGeneration.incrementAndGet();
        modelStatus.setText(R.string.knowledge_models_package_importing);
        final Uri selectedManifest = manifestUri;
        runningModelImport = modelWorker.submit(() -> {
            try (InputStream input = getContentResolver().openInputStream(selectedManifest)) {
                final byte[] json = KnowledgeModelPackageManifest.readBounded(input);
                final KnowledgeModelPackageManifest result =
                        modelPackages.install(json, sidecars, entry);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && modelGeneration.get() == operation) {
                        modelImportActive = false;
                        restoreModelButton();
                        modelStatus.setText(getString(R.string.knowledge_models_package_imported,
                                result.components.size() - 1));
                    }
                });
            } catch (IOException | RuntimeException failure) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && modelGeneration.get() == operation) {
                        modelImportActive = false;
                        restoreModelButton();
                        modelStatus.setText(R.string.knowledge_models_package_failed);
                    }
                });
            }
        });
    }

    /** Explicit integrity/JSON-shape check. NOT an AI speed/quality benchmark. */
    private void auditModelPackage(final KnowledgeModelFiles.Entry entry) {
        if (modelImportActive || entry == null) return;
        modelImportActive = true;
        ((Button) findViewById(R.id.knowledge_models_open))
                .setText(R.string.knowledge_models_cancel_transfer);
        final int operation = modelGeneration.incrementAndGet();
        final AtomicLong lastReport = new AtomicLong();
        modelStatus.setText(R.string.knowledge_models_package_audit_starting);
        runningModelImport = modelWorker.submit(() -> {
            try {
                final KnowledgeModelPackageStore.Audit result = modelPackages.audit(
                        entry, (verified, total) -> {
                            final long now = SystemClock.elapsedRealtime();
                            final long last = lastReport.get();
                            if (now - last < 500 || !lastReport.compareAndSet(last, now)) return;
                            runOnUiThread(() -> {
                                if (!isFinishing() && !isDestroyed()
                                        && modelGeneration.get() == operation) {
                                    modelStatus.setText(getString(
                                            R.string.knowledge_models_package_audit_progress,
                                            KnowledgeModelSettingsDialog.formatSize(verified),
                                            KnowledgeModelSettingsDialog.formatSize(total)));
                                }
                            });
                        });
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()
                            && modelGeneration.get() == operation) {
                        modelImportActive = false;
                        restoreModelButton();
                        modelStatus.setText(result.container.state
                                == KnowledgeModelContainerProbe.State.INVALID
                                ? R.string.knowledge_models_container_invalid
                                : !result.jsonPreflightPassed()
                                        ? R.string.knowledge_models_package_audit_json_warning
                                        : result.container.externalDataTensorsDeclared > 0
                                                && !result.externalDataPreflightPassed()
                                                ? R.string.knowledge_models_package_audit_external_warning
                                        : result.container.state
                                                == KnowledgeModelContainerProbe.State.PARTIAL
                                                ? R.string.knowledge_models_container_partial
                                                : R.string.knowledge_models_package_audit_passed);
                        new MaterialAlertDialogBuilder(this)
                                .setTitle(R.string.knowledge_models_package_audit)
                                .setMessage(getString(R.string.knowledge_models_package_audit_report,
                                        KnowledgeModelSettingsDialog.formatSize(result.verifiedBytes),
                                        result.elapsedMillis, result.companionFiles,
                                        result.jsonObjectsChecked, result.jsonInvalid,
                                        result.jsonNotInspected) + "\n\n"
                                        + getString(R.string.knowledge_models_package_audit_external_report,
                                                result.externalReferencesMatched,
                                                result.externalReferencesUnresolved,
                                                result.externalRangesOutOfBounds,
                                                result.externalComponentsUnused,
                                                result.container.invalidExternalDataRanges)
                                        + "\n\n"
                                        + containerPreflightLabel(result.container))
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    }
                });
            } catch (IOException | RuntimeException invalid) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()
                            && modelGeneration.get() == operation) {
                        modelImportActive = false;
                        restoreModelButton();
                        modelStatus.setText(R.string.knowledge_models_package_audit_failed);
                    }
                });
            }
        });
    }

    /** User-confirmed dry-run of the file layout; never creates an inference session. */
    private void prepareOnnxFiles(final KnowledgeModelFiles.Entry entry) {
        if (modelImportActive || entry == null
                || entry.format != KnowledgeModelFiles.Format.ONNX_UNVERIFIED) return;
        modelImportActive = true;
        ((Button) findViewById(R.id.knowledge_models_open))
                .setText(R.string.knowledge_models_cancel_transfer);
        final int operation = modelGeneration.incrementAndGet();
        modelStatus.setText(R.string.knowledge_models_onnx_preparing);
        runningModelImport = modelWorker.submit(() -> {
            try (KnowledgeModelPackageStore.PreparedOnnx prepared = modelPackages.prepareOnnx(
                    entry, new File(getCacheDir(), "dpks-onnx-runtime-prep"))) {
                if (!prepared.modelFile.isFile()) {
                    throw new IOException("ONNX preparation did not produce a model file");
                }
                // Closing the session immediately removes the private copies.
                // A future explicit inference action may hold it until its session ends.
            } catch (IOException | RuntimeException failed) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()
                            && modelGeneration.get() == operation) {
                        modelImportActive = false;
                        restoreModelButton();
                        modelStatus.setText(R.string.knowledge_models_onnx_prepare_failed);
                    }
                });
                return;
            }
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()
                        && modelGeneration.get() == operation) {
                    modelImportActive = false;
                    restoreModelButton();
                    modelStatus.setText(R.string.knowledge_models_onnx_prepare_success);
                }
            });
        });
    }

    /** One manually approved, synthetic CPU run in a separate isolated UID. */
    private void runOnnxCpuSmoke() {
        if (modelImportActive || !KnowledgeOnnxCpuBackend.available()
                || onnxSmokeClient == null) return;
        modelImportActive = true;
        ((Button) findViewById(R.id.knowledge_models_open))
                .setText(R.string.knowledge_models_cancel_transfer);
        final int operation = modelGeneration.incrementAndGet();
        modelStatus.setText(R.string.knowledge_models_onnx_smoke_running);
        if (!onnxSmokeClient.start(new KnowledgeOnnxIsolatedClient.Callback() {
            @Override public void success(final KnowledgeOnnxSmoke.Report report) {
                if (!isFinishing() && !isDestroyed() && modelGeneration.get() == operation) {
                    modelImportActive = false;
                    restoreModelButton();
                    modelStatus.setText(getString(R.string.knowledge_models_onnx_smoke_passed,
                            report.runtimeVersion, report.sessionInitMillis,
                            report.inferenceMicros, report.nativeHeapDeltaBytes,
                            formatWorkerMemory(report.workerMemory, report.workerRssHighWaterKiB)));
                }
            }

            @Override public void failure() {
                if (!isFinishing() && !isDestroyed() && modelGeneration.get() == operation) {
                    modelImportActive = false;
                    restoreModelButton();
                    modelStatus.setText(R.string.knowledge_models_onnx_smoke_failed);
                }
            }
        })) {
            modelImportActive = false;
            restoreModelButton();
            modelStatus.setText(R.string.knowledge_models_onnx_smoke_failed);
        }
    }

    /** Manual synthetic end-to-end FD transport + isolated session, never a user model. */
    private void runOnnxFdSmoke() {
        if (modelImportActive || !KnowledgeOnnxCpuBackend.available()
                || onnxSessionClient == null) return;
        modelImportActive = true;
        ((Button) findViewById(R.id.knowledge_models_open))
                .setText(R.string.knowledge_models_cancel_transfer);
        final int operation = modelGeneration.incrementAndGet();
        modelStatus.setText(R.string.knowledge_models_onnx_fd_running);
        final byte[] fixture = KnowledgeOnnxSmoke.identityModel();
        try {
            final String digest = KnowledgeOnnxSessionPipe.sha256Hex(fixture);
            if (onnxSessionClient.start(fixture, digest,
                    new KnowledgeOnnxIsolatedSessionClient.Callback() {
                @Override public void success(final KnowledgeOnnxSessionGate.Report report) {
                    if (!isFinishing() && !isDestroyed()
                            && modelGeneration.get() == operation) {
                        modelImportActive = false;
                        restoreModelButton();
                        if (report.inputs != 1 || report.outputs != 1) {
                            modelStatus.setText(R.string.knowledge_models_onnx_fd_failed);
                        } else {
                            modelStatus.setText(getString(R.string.knowledge_models_onnx_fd_passed,
                                    report.runtimeVersion, report.inputs, report.outputs,
                                    report.sessionInitMillis, report.nativeHeapDeltaBytes,
                                    formatSessionDiagnostics(report)));
                        }
                    }
                }

                @Override public void failure() {
                    if (!isFinishing() && !isDestroyed()
                            && modelGeneration.get() == operation) {
                        modelImportActive = false;
                        restoreModelButton();
                        modelStatus.setText(R.string.knowledge_models_onnx_fd_failed);
                    }
                }
            })) {
                modelImportActive = false;
                restoreModelButton();
                modelStatus.setText(R.string.knowledge_models_onnx_fd_failed);
            }
        } catch (IOException failed) {
            Arrays.fill(fixture, (byte) 0);
            modelImportActive = false;
            restoreModelButton();
            modelStatus.setText(R.string.knowledge_models_onnx_fd_failed);
        }
    }

    /** PSS samples and kernel self RSS high-water are distinct memory measurements. */
    private String formatWorkerMemory(final KnowledgeOnnxMemoryTrace memory,
                                      final long rssHighWaterKiB) {
        final String pss;
        if (memory == null || memory.beforeKiB < 0 || memory.maxObservedKiB < 0
                || memory.afterCloseKiB < 0) {
            pss = getString(R.string.knowledge_models_worker_pss_unavailable);
        } else {
            pss = getString(R.string.knowledge_models_worker_pss_samples,
                    memory.beforeKiB, memory.maxObservedKiB, memory.afterCloseKiB);
        }
        final String rss = rssHighWaterKiB > 0 && KnowledgeOnnxRssHighWater.valid(rssHighWaterKiB)
                ? getString(R.string.knowledge_models_worker_rss_high_water, rssHighWaterKiB)
                : getString(R.string.knowledge_models_worker_rss_high_water_unavailable);
        return pss + "\n" + rss;
    }

    /** Numeric I/O aggregates only; node names, symbolic labels and bytes stay isolated. */
    private String formatSessionDiagnostics(final KnowledgeOnnxSessionGate.Report report) {
        final KnowledgeOnnxIoSummary io = report.ioSummary;
        return formatWorkerMemory(report.workerMemory, report.workerRssHighWaterKiB)
                + "\n" + getString(R.string.knowledge_models_onnx_io_summary,
                io.inputTensors, io.outputTensors, io.floatInputs, io.floatOutputs,
                io.int64Inputs, io.int64Outputs, io.dynamicInputAxes, io.dynamicOutputAxes,
                io.maxInputRank, io.maxOutputRank);
    }

    /** Fail closed on an unavailable/low OS RAM snapshot; this reserves no memory. */
    private void requireAvailableOnnxMemory() throws IOException {
        // Read a current system-wide snapshot. Neither this nor the isolated
        // process guarantees that a later native allocation will succeed.
        try {
            final ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) throw new IOException("No Android memory info");
            final ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(memory);
            KnowledgeOnnxSessionGate.requireAvailableMemory(memory.availMem, memory.lowMemory);
        } catch (RuntimeException unavailable) {
            throw new IOException("Cannot confirm available RAM for optional CPU check", unavailable);
        }
    }

    /** Audit model bytes in the app, then create a bounded session in an isolated UID. */
    private void inspectOnnxCpuSession(final KnowledgeModelFiles.Entry entry) {
        if (modelImportActive || entry == null || !KnowledgeOnnxCpuBackend.available()
                || onnxSessionClient == null
                || entry.format != KnowledgeModelFiles.Format.ONNX_UNVERIFIED) return;
        modelImportActive = true;
        ((Button) findViewById(R.id.knowledge_models_open))
                .setText(R.string.knowledge_models_cancel_transfer);
        final int operation = modelGeneration.incrementAndGet();
        modelStatus.setText(R.string.knowledge_models_onnx_session_running);
        runningModelImport = modelWorker.submit(() -> {
            try {
                requireAvailableOnnxMemory();
                final byte[] bytes = modelPackages.readForCpuSession(entry);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || modelGeneration.get() != operation) {
                        Arrays.fill(bytes, (byte) 0);
                        return;
                    }
                    // SHA audit/file IO may take time: do not send the now
                    // resident bytes if Android reports new memory pressure.
                    try {
                        requireAvailableOnnxMemory();
                    } catch (IOException unavailable) {
                        Arrays.fill(bytes, (byte) 0);
                        modelImportActive = false;
                        restoreModelButton();
                        modelStatus.setText(R.string.knowledge_models_onnx_session_failed);
                        return;
                    }
                    if (!onnxSessionClient.start(bytes, entry.sha256,
                            new KnowledgeOnnxIsolatedSessionClient.Callback() {
                        @Override public void success(final KnowledgeOnnxSessionGate.Report report) {
                            if (!isFinishing() && !isDestroyed()
                                    && modelGeneration.get() == operation) {
                                modelImportActive = false;
                                restoreModelButton();
                                modelStatus.setText(getString(
                                        R.string.knowledge_models_onnx_session_passed,
                                        report.runtimeVersion, report.inputs, report.outputs,
                                        report.sessionInitMillis, report.nativeHeapDeltaBytes,
                                        formatSessionDiagnostics(report)));
                            }
                        }

                        @Override public void failure() {
                            if (!isFinishing() && !isDestroyed()
                                    && modelGeneration.get() == operation) {
                                modelImportActive = false;
                                restoreModelButton();
                                modelStatus.setText(R.string.knowledge_models_onnx_session_failed);
                            }
                        }
                    })) {
                        modelImportActive = false;
                        restoreModelButton();
                        modelStatus.setText(R.string.knowledge_models_onnx_session_failed);
                    }
                });
            } catch (Exception | LinkageError failed) {
                // Native errors, model names/paths and user data never enter UI/logs.
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()
                            && modelGeneration.get() == operation) {
                        modelImportActive = false;
                        restoreModelButton();
                        modelStatus.setText(R.string.knowledge_models_onnx_session_failed);
                    }
                });
            }
        });
    }

    private String containerPreflightLabel(final KnowledgeModelContainerProbe.Result result) {
        final String status = result.state == KnowledgeModelContainerProbe.State.METADATA_HINTS
                ? getString(R.string.knowledge_models_container_metadata)
                : result.state == KnowledgeModelContainerProbe.State.PARTIAL
                        ? getString(R.string.knowledge_models_container_partial_label)
                        : getString(R.string.knowledge_models_container_invalid_label);
        final String detailStatus = result.detailState
                == KnowledgeModelContainerProbe.DetailState.DECLARATIONS_READ
                ? getString(R.string.knowledge_models_container_detail_read)
                : result.detailState == KnowledgeModelContainerProbe.DetailState.INVALID
                        ? getString(R.string.knowledge_models_container_invalid_label)
                        : result.detailState == KnowledgeModelContainerProbe.DetailState.PARTIAL
                                ? getString(R.string.knowledge_models_container_partial_label)
                                : getString(R.string.knowledge_models_container_unknown);
        if (result.format == KnowledgeModelFiles.Format.GGUF) {
            return getString(R.string.knowledge_models_container_gguf, status, result.version,
                    result.tensorCount, result.metadataCount,
                    result.architecture.isEmpty()
                            ? getString(R.string.knowledge_models_container_unknown)
                            : result.architecture,
                    result.tokenizer.isEmpty()
                            ? getString(R.string.knowledge_models_container_unknown)
                            : result.tokenizer) + "\n"
                    + getString(R.string.knowledge_models_container_gguf_descriptors,
                            detailStatus, result.tensorDescriptors) + "\n"
                    + getString(R.string.knowledge_models_container_gguf_extents,
                            result.tensorExtentsChecked, result.tensorExtentsUnknown);
        }
        return getString(R.string.knowledge_models_container_onnx, status,
                result.version, result.opsetDeclarations,
                result.graphDeclared ? getString(R.string.knowledge_models_container_yes)
                        : getString(R.string.knowledge_models_container_no)) + "\n"
                + getString(R.string.knowledge_models_container_onnx_graph,
                        detailStatus, result.graphNodes, result.graphInputs,
                        result.graphOutputs, result.operatorHints.isEmpty()
                                ? getString(R.string.knowledge_models_container_unknown)
                                : result.operatorHints) + "\n"
                + getString(R.string.knowledge_models_container_onnx_signatures,
                        result.typedInputs, result.typedOutputs,
                        result.signatureHints.isEmpty()
                                ? getString(R.string.knowledge_models_container_unknown)
                                : result.signatureHints) + "\n"
                + getString(R.string.knowledge_models_container_onnx_opset_domains,
                        result.opsetHints.isEmpty()
                                ? getString(R.string.knowledge_models_container_unknown)
                                : result.opsetHints, result.customDomainNodes,
                        result.customNodesMissingImport, result.duplicateOpsetDomains) + "\n"
                + getString(R.string.knowledge_models_container_onnx_inventory,
                        result.distinctOperatorKinds, result.undecodedOperatorNodes,
                        result.operatorInventoryHints.isEmpty()
                                ? getString(R.string.knowledge_models_container_unknown)
                                : result.operatorInventoryHints) + "\n"
                + getString(R.string.knowledge_models_container_onnx_schema_snapshot,
                        result.schemaMatchedKinds, result.schemaBeforeIntroductionKinds,
                        result.schemaUnlistedKinds, result.schemaUnresolvedKinds,
                        result.schemaBeyondSnapshotKinds,
                        result.schemaSnapshotHints.isEmpty()
                                ? getString(R.string.knowledge_models_container_unknown)
                                : result.schemaSnapshotHints) + "\n"
                + getString(R.string.knowledge_models_container_onnx_local_functions,
                        result.localFunctionDeclarations, result.matchedGraphFunctionNodes,
                        result.duplicateLocalFunctions, result.undecodedLocalFunctions,
                        result.undecodedFunctionOverloads,
                        result.localFunctionHints.isEmpty()
                                ? getString(R.string.knowledge_models_container_unknown)
                                : result.localFunctionHints) + "\n"
                + getString(R.string.knowledge_models_container_onnx_function_imports,
                        result.localFunctionBodyNodes, result.localFunctionImportDeclarations,
                        result.localFunctionImportVersionDifferences,
                        result.localFunctionImportMissingModel,
                        result.localFunctionImportAmbiguousModel,
                        result.duplicateLocalFunctionImportDomains,
                        result.invalidLocalFunctionImportVersions) + "\n"
                + getString(R.string.knowledge_models_container_onnx_function_operators,
                        result.localFunctionOperatorKinds,
                        result.undecodedLocalFunctionOperatorNodes,
                        result.localFunctionCustomNodesMissingImport,
                        result.localFunctionOperatorHints.isEmpty()
                                ? getString(R.string.knowledge_models_container_unknown)
                                : result.localFunctionOperatorHints) + "\n"
                + getString(R.string.knowledge_models_container_onnx_external_data,
                        result.denseInitializersScanned, result.externalDataTensorsDeclared,
                        result.externalDataRelativeLocations,
                        result.externalDataMissingLocations,
                        result.externalDataUntrustedLocations,
                        result.externalDataDuplicateLocations,
                        result.invalidExternalDataLocationEnums,
                        result.sparseInitializersSkipped);
    }

    private String modelDisplayName(final Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                final int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (RuntimeException unavailable) {
            // Do not copy path or URI into UI or logs.
        }
        return "";
    }

    private void importModelFromUri(final Uri source, final KnowledgeModelPolicy.Role role) {
        if (modelImportActive) return;
        final String name = modelDisplayName(source);
        modelImportActive = true;
        findViewById(R.id.knowledge_models_open).setContentDescription(
                getString(R.string.knowledge_models_cancel_transfer));
        ((Button) findViewById(R.id.knowledge_models_open))
                .setText(R.string.knowledge_models_cancel_transfer);
        final int operation = modelGeneration.incrementAndGet();
        modelStatus.setText(R.string.knowledge_models_importing);
        runningModelImport = modelWorker.submit(() -> {
            try (InputStream input = getContentResolver().openInputStream(source)) {
                final KnowledgeModelFiles.Entry imported = modelFiles.importFile(input, name, role);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()
                            && modelGeneration.get() == operation) {
                        modelImportActive = false;
                        restoreModelButton();
                        modelStatus.setText(getString(R.string.knowledge_models_imported,
                                imported.name, KnowledgeModelSettingsDialog.formatSize(imported.bytes)));
                    }
                });
            } catch (IOException | RuntimeException failure) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()
                            && modelGeneration.get() == operation) {
                        modelImportActive = false;
                        restoreModelButton();
                        modelStatus.setText(R.string.knowledge_models_import_failed);
                    }
                });
            }
        });
    }

    /** User-initiated transfer of public model bytes only. No vault reads or API requests. */
    private void downloadModel(final KnowledgeModelPolicy.Role role, final String url,
                               final String expectedSha256) {
        if (modelImportActive || role == null) return;
        modelImportActive = true;
        final int operation = modelGeneration.incrementAndGet();
        ((Button) findViewById(R.id.knowledge_models_open))
                .setText(R.string.knowledge_models_cancel_transfer);
        modelStatus.setText(R.string.knowledge_models_download_starting);
        final AtomicLong lastReport = new AtomicLong();
        runningModelImport = modelWorker.submit(() -> {
            try {
                final KnowledgeModelFiles.Entry result = KnowledgeModelDownload.download(
                        url, role, expectedSha256, modelFiles, (copied, total) -> {
                            final long now = SystemClock.elapsedRealtime();
                            final long last = lastReport.get();
                            if (now - last < 500 || !lastReport.compareAndSet(last, now)) return;
                            runOnUiThread(() -> {
                                if (!isFinishing() && !isDestroyed()
                                        && modelGeneration.get() == operation) {
                                    modelStatus.setText(total > 0
                                            ? getString(R.string.knowledge_models_download_progress,
                                                    KnowledgeModelSettingsDialog.formatSize(copied),
                                                    KnowledgeModelSettingsDialog.formatSize(total))
                                            : getString(R.string.knowledge_models_download_progress_unknown,
                                                    KnowledgeModelSettingsDialog.formatSize(copied)));
                                }
                            });
                        });
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()
                            && modelGeneration.get() == operation) {
                        modelImportActive = false;
                        restoreModelButton();
                        modelStatus.setText(getString(R.string.knowledge_models_download_done,
                                result.name, KnowledgeModelSettingsDialog.formatSize(result.bytes)));
                    }
                });
            } catch (IOException | RuntimeException failure) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()
                            && modelGeneration.get() == operation) {
                        modelImportActive = false;
                        restoreModelButton();
                        modelStatus.setText(R.string.knowledge_models_download_failed);
                    }
                });
            }
        });
    }

    private void restoreModelButton() {
        final Button button = findViewById(R.id.knowledge_models_open);
        button.setText(R.string.knowledge_models_open);
        button.setContentDescription(getString(R.string.knowledge_models_open));
    }

    private void cancelModelImport() {
        modelGeneration.incrementAndGet();
        if (onnxSmokeClient != null) onnxSmokeClient.cancel();
        if (onnxSessionClient != null) onnxSessionClient.cancel();
        if (runningModelImport != null) runningModelImport.cancel(true);
        modelImportActive = false;
        restoreModelButton();
        modelStatus.setText(R.string.knowledge_models_import_cancelled);
    }

    /** Switch destinations without recreating the activity or its active workers. */
    private void showWorkspacePage(final int requested) {
        final int page = workspaceNavigation.select(requested);
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
        if (workspaceNavigation.page() == R.id.knowledge_nav_decisions) loadDecisionPage(0);
    }

    private void navigateToDecisions(final String category) {
        decisionCategory = category;
        for (int i = 0; i < DECISION_CATEGORIES.length; i++) {
            ((Chip) decisionChips.getChildAt(i)).setChecked(category.equals(DECISION_CATEGORIES[i]));
        }
        if (workspaceNavigation.page() == R.id.knowledge_nav_decisions) {
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
                            .setSelection(0);
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
        if (!workspaceNavigation.openDecisionDetail()) return;
        final int generation = decisionGeneration.incrementAndGet();
        if (runningDecisionLoad != null) runningDecisionLoad.cancel(true);
        findViewById(R.id.knowledge_page_decisions).setVisibility(View.GONE);
        findViewById(R.id.knowledge_decision_detail_screen).setVisibility(View.VISIBLE);
        ViewCompat.setAccessibilityPaneTitle(findViewById(R.id.knowledge_decision_detail_screen),
                getString(R.string.knowledge_decision_detail_title));
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
                    if (!isFinishing() && !isDestroyed() && workspaceNavigation.detailOpen()
                            && decisionGeneration.get() == generation) renderDecisionDetail(detail);
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && workspaceNavigation.detailOpen()
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
        workspaceNavigation.closeDecisionDetail();
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

    /** UI-thread only: never persist paths, counts, note titles or arbitrary error text. */
    private void recordProcessEvent(final KnowledgeProcessHistory.Operation operation,
                                    final KnowledgeProcessHistory.Outcome outcome) {
        if (processHistory == null) return;
        processHistory.record(operation, outcome, System.currentTimeMillis());
        getSharedPreferences(PROCESS_HISTORY_PREFERENCES, MODE_PRIVATE).edit()
                .putString(PROCESS_HISTORY_KEY, processHistory.serialize()).apply();
        renderProcessHistory();
    }

    private void renderProcessHistory() {
        if (processHistory == null) return;
        final List<KnowledgeProcessHistory.Entry> entries = processHistory.recent();
        final TextView view = findViewById(R.id.knowledge_process_history);
        if (entries.isEmpty()) {
            view.setText(R.string.knowledge_process_history_empty);
            return;
        }
        final DateFormat date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        final StringBuilder lines = new StringBuilder();
        for (final KnowledgeProcessHistory.Entry entry : entries) {
            final int operation;
            switch (entry.operation) {
                case SCAN: operation = R.string.knowledge_history_scan; break;
                case ANALYSIS: operation = R.string.knowledge_history_analysis; break;
                case AUDIT: operation = R.string.knowledge_history_audit; break;
                case EMBEDDING: operation = R.string.knowledge_history_embedding; break;
                case DEVICE_QA: operation = R.string.knowledge_history_qa; break;
                default: throw new IllegalStateException("Unknown process category");
            }
            final int outcome;
            switch (entry.outcome) {
                case STARTED: outcome = R.string.knowledge_history_started; break;
                case COMPLETED: outcome = R.string.knowledge_history_completed; break;
                case PARTIAL: outcome = R.string.knowledge_history_partial; break;
                case CANCELLED: outcome = R.string.knowledge_history_cancelled; break;
                case FAILED: outcome = R.string.knowledge_history_failed; break;
                default: throw new IllegalStateException("Unknown process outcome");
            }
            if (lines.length() != 0) lines.append('\n');
            lines.append(date.format(new Date(entry.timestamp))).append(" · ")
                    .append(getString(operation)).append(" · ").append(getString(outcome));
        }
        view.setText(lines.toString());
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
    private void runDeviceQa(final boolean stress, final boolean editorStage22) {
        if (qaActive) {
            if (runningDeviceQa != null) {
                runningDeviceQa.cancel(true);
            }
            if (!qaCancellationRequested) {
                qaCancellationRequested = true;
                recordProcessEvent(KnowledgeProcessHistory.Operation.DEVICE_QA,
                        KnowledgeProcessHistory.Outcome.CANCELLED);
            }
            qaStatus.setText(R.string.knowledge_qa_cancelling);
            return;
        }
        final int generation = deviceQaGeneration.incrementAndGet();
        qaActive = true;
        qaCancellationRequested = false;
        recordProcessEvent(KnowledgeProcessHistory.Operation.DEVICE_QA,
                KnowledgeProcessHistory.Outcome.STARTED);
        updateProcessBadge();
        qaQuickButton.setText(R.string.knowledge_qa_cancel);
        qaStressButton.setText(R.string.knowledge_qa_cancel);
        qaEditorButton.setText(R.string.knowledge_qa_cancel);
        qaProgress.setVisibility(View.VISIBLE);
        qaStatus.setText(getString(R.string.knowledge_qa_running_step, "starting"));
        runningDeviceQa = deviceQaWorker.submit(() -> {
            final String report = new KnowledgeDeviceQaRunner(this, notebook, index, stress,
                    editorStage22,
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
                qaEditorButton.setText(R.string.knowledge_qa_editor);
                qaProgress.setVisibility(View.GONE);
                qaStatus.setText(R.string.knowledge_qa_ready);
                if (!qaCancellationRequested) {
                    final KnowledgeProcessHistory.Outcome outcome = !report.contains(" FAIL=0 ")
                            ? KnowledgeProcessHistory.Outcome.FAILED
                            : !report.contains(" WARN=0 ")
                            ? KnowledgeProcessHistory.Outcome.PARTIAL
                            : KnowledgeProcessHistory.Outcome.COMPLETED;
                    recordProcessEvent(KnowledgeProcessHistory.Operation.DEVICE_QA, outcome);
                }
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
            if (!runningScan.isDone()) {
                recordProcessEvent(KnowledgeProcessHistory.Operation.SCAN,
                        KnowledgeProcessHistory.Outcome.CANCELLED);
            }
            runningScan.cancel(true);
        }
        if (runningSearch != null) {
            runningSearch.cancel(true);
        }
        final File root = notebook;
        progress.setVisibility(View.VISIBLE);
        recordProcessEvent(KnowledgeProcessHistory.Operation.SCAN,
                KnowledgeProcessHistory.Outcome.STARTED);
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
                        recordProcessEvent(KnowledgeProcessHistory.Operation.SCAN,
                                result.truncated || result.unreadableDirectories > 0
                                        ? KnowledgeProcessHistory.Outcome.PARTIAL
                                        : KnowledgeProcessHistory.Outcome.COMPLETED);
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
                        recordProcessEvent(KnowledgeProcessHistory.Operation.SCAN,
                                KnowledgeProcessHistory.Outcome.FAILED);
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
        recordProcessEvent(KnowledgeProcessHistory.Operation.ANALYSIS,
                KnowledgeProcessHistory.Outcome.STARTED);
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
                        recordProcessEvent(KnowledgeProcessHistory.Operation.ANALYSIS,
                                partial ? KnowledgeProcessHistory.Outcome.PARTIAL
                                        : KnowledgeProcessHistory.Outcome.COMPLETED);
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
                        recordProcessEvent(KnowledgeProcessHistory.Operation.ANALYSIS,
                                KnowledgeProcessHistory.Outcome.FAILED);
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
        recordProcessEvent(KnowledgeProcessHistory.Operation.ANALYSIS,
                KnowledgeProcessHistory.Outcome.CANCELLED);
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
        recordProcessEvent(KnowledgeProcessHistory.Operation.AUDIT,
                KnowledgeProcessHistory.Outcome.STARTED);
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
                        recordProcessEvent(KnowledgeProcessHistory.Operation.AUDIT,
                                KnowledgeProcessHistory.Outcome.COMPLETED);
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
                        recordProcessEvent(KnowledgeProcessHistory.Operation.AUDIT,
                                KnowledgeProcessHistory.Outcome.FAILED);
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
        recordProcessEvent(KnowledgeProcessHistory.Operation.AUDIT,
                KnowledgeProcessHistory.Outcome.CANCELLED);
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
                                    .append("\n").append(getString(R.string.knowledge_batch_source))
                                    .append("\n").append(item.optString("source_excerpt"));
                            if (item.optBoolean("source_truncated")) {
                                details.append(getString(R.string.knowledge_batch_source_truncated));
                            }
                            details.append("\n")
                                    .append(getString(R.string.knowledge_batch_interpretation))
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
                        new AlertDialog.Builder(this)
                                .setMessage(R.string.knowledge_batch_rejected)
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
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
        loadLibraryPage(libraryOffset);
    }

    private void loadLibraryPage(final int requestedOffset) {
        if (snapshot == null) {
            return;
        }
        final String query = filter.getText().toString().trim();
        final int generation = queryGeneration.incrementAndGet();
        findViewById(R.id.knowledge_library_previous).setEnabled(false);
        findViewById(R.id.knowledge_library_next).setEnabled(false);
        ((TextView) findViewById(R.id.knowledge_library_page_number)).setText(
                R.string.knowledge_library_page_loading);
        if (runningSearch != null) {
            runningSearch.cancel(true);
        }
        runningSearch = worker.submit(() -> {
            try {
                final KnowledgeLocalIndex.SearchPage page = index.searchPage(
                        query, requestedOffset, LIBRARY_PAGE_SIZE);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && queryGeneration.get() == generation) {
                        libraryOffset = page.offset;
                        libraryNextOffset = page.nextOffset;
                        displayed.clear();
                        labels.clear();
                        for (final KnowledgeLocalIndex.Hit hit : page.hits) {
                            displayed.add(hit);
                            final String label = hit.title.equals(hit.relativePath)
                                    ? hit.relativePath : hit.title + " — " + hit.relativePath;
                            labels.add(hit.confirmedInLastScan ? label
                                    : getString(R.string.knowledge_unverified_source) + " " + label);
                        }
                        adapter.notifyDataSetChanged();
                        ((ListView) findViewById(R.id.knowledge_list)).setSelection(0);
                        findViewById(R.id.knowledge_library_previous).setEnabled(page.offset > 0);
                        findViewById(R.id.knowledge_library_next).setEnabled(page.nextOffset >= 0);
                        ((TextView) findViewById(R.id.knowledge_library_page_number)).setText(
                                page.total == 0 ? getString(R.string.knowledge_library_page_empty)
                                : getString(R.string.knowledge_library_page_range,
                                        page.offset + 1, page.offset + page.hits.size(), page.total));
                        showIndexStatus(page.hits.size());
                    }
                });
            } catch (RuntimeException ignored) {
                runOnUiThread(() -> {
                    if (queryGeneration.get() == generation && !isFinishing()) {
                        status.setText(R.string.knowledge_index_failed);
                        ((TextView) findViewById(R.id.knowledge_library_page_number)).setText(
                                R.string.knowledge_library_page_unavailable);
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

    /** Visible discovery path; long-press on a row remains an optional shortcut. */
    private void showLibraryRelationsPicker() {
        if (displayed.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.knowledge_semantic_relations)
                    .setMessage(R.string.knowledge_relations_empty_page)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        // Keep labels and paths from the same search-page snapshot even if a
        // background scan refreshes the library while the picker is open.
        final String[] paths = new String[displayed.size()];
        final String[] choices = new String[displayed.size()];
        for (int i = 0; i < paths.length; i++) {
            paths[i] = displayed.get(i).relativePath;
            choices[i] = labels.get(i);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.knowledge_relations_choose_note)
                .setItems(choices, (dialog, which) -> {
                    if (which >= 0 && which < paths.length) {
                        showSemanticRelations(paths[which]);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void showSemanticRelations(final String relativePath) {
        final int scan = scanGeneration.get();
        status.setText(R.string.knowledge_semantic_working);
        worker.submit(() -> {
            try {
                final JSONObject result = KnowledgePythonBridge.relationships(
                        this, workspacePath(), relativePath,
                        notebook == null ? "" : notebook.getAbsolutePath());
                final JSONArray matches = result.getJSONArray("matches");
                final JSONArray evidence = result.optJSONArray("evidence_groups");
                final String[] labels = new String[matches.length()];
                final int[] titles = new int[matches.length()];
                for (int i = 0; i < labels.length; i++) {
                    final JSONObject match = matches.getJSONObject(i);
                    final String kind = match.optString("kind");
                    final int label = "alias_candidate".equals(kind)
                            ? R.string.knowledge_possible_alias
                            : "temporal_overlap".equals(kind)
                            ? R.string.knowledge_possible_temporal_overlap
                            : "temporal_separation".equals(kind)
                            ? R.string.knowledge_possible_temporal_separation
                            : "temporal_uncertainty".equals(kind)
                            ? R.string.knowledge_possible_temporal_uncertainty
                            : "modal_contrast".equals(kind)
                            ? R.string.knowledge_possible_modal_contrast
                            : "possible_conflict".equals(kind)
                                    ? R.string.knowledge_possible_conflict
                                    : R.string.knowledge_possible_agreement;
                    titles[i] = label;
                    labels[i] = getString(label)
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
                                    ? " · " + getString(R.string.knowledge_semantic_limited) : "")
                                    + (result.optBoolean("alias_search_limited")
                                    ? " · " + getString(R.string.knowledge_alias_search_limited) : "")
                                    + (result.optBoolean("modal_search_limited")
                                    ? " · " + getString(R.string.knowledge_modal_limited) : "")
                                    + (result.optBoolean("modal_checks_limited")
                                    ? " · " + getString(R.string.knowledge_modal_checks_limited) : "")
                                    + (result.optBoolean("temporal_checks_limited")
                                    ? " · " + getString(R.string.knowledge_temporal_checks_limited) : "")
                                    + (result.optBoolean("relation_checks_limited")
                                    ? " · " + getString(R.string.knowledge_relation_checks_limited) : "")
                                    + (result.optBoolean("version_review_limited")
                                    ? " · " + getString(R.string.knowledge_version_limited) : ""))
                            .setPositiveButton(android.R.string.ok, null);
                    if (evidence != null && evidence.length() > 0) {
                        dialog.setNeutralButton(R.string.knowledge_evidence_title,
                                (d, w) -> showEvidenceGroups(evidence,
                                        result.optBoolean("evidence_limited"),
                                        result.optBoolean("evidence_checks_limited"),
                                        result.optBoolean("source_lineage_limited"),
                                        result.optBoolean("source_assessment_limited"),
                                        relativePath));
                    }
                    if (labels.length == 0) {
                        dialog.setMessage(R.string.knowledge_no_semantic_relations);
                    } else {
                        dialog.setItems(labels, (d, position) -> {
                            final JSONObject match = matches.optJSONObject(position);
                            if (match != null) {
                                showSemanticRelationDetail(relativePath, match, titles[position]);
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

    private void showSemanticRelationDetail(final String sourcePath, final JSONObject relation,
                                            final int title) {
        final boolean dated = "temporal_overlap".equals(relation.optString("kind"))
                || "temporal_separation".equals(relation.optString("kind"))
                || "temporal_uncertainty".equals(relation.optString("kind"));
        final AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(KnowledgeRelationPresentation.relationComparison(
                        this, relation, sourcePath))
                .setPositiveButton(R.string.knowledge_relation_open_other,
                        (d, which) -> openIndexedNote(relation.optString("path")));
        if (dated) {
            dialog.setNeutralButton(R.string.knowledge_version_action,
                    (d, which) -> new KnowledgeVersionReviewDialog(this, workspacePath(),
                            notebook, decisionWorker, sourcePath, relation,
                            () -> showSemanticRelations(sourcePath)).show());
            dialog.setNegativeButton(R.string.knowledge_relation_open_inspected,
                    (d, which) -> openIndexedNote(sourcePath));
        } else {
            dialog.setNeutralButton(R.string.knowledge_relation_open_inspected,
                    (d, which) -> openIndexedNote(sourcePath));
            dialog.setNegativeButton(android.R.string.cancel, null);
        }
        dialog.show();
    }

    private void showEvidenceGroups(final JSONArray groups, final boolean limited,
                                    final boolean checksLimited, final boolean lineageLimited,
                                    final boolean assessmentLimited,
                                    final String sourcePath) {
        final String[] rows = new String[groups.length()];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = KnowledgeRelationPresentation.evidenceSummary(
                    this, groups.optJSONObject(i));
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.knowledge_evidence_title)
                        + (limited ? " · " + getString(R.string.knowledge_evidence_limited) : "")
                        + (checksLimited ? " · " + getString(R.string.knowledge_evidence_checks_limited) : "")
                        + (lineageLimited ? " · " + getString(R.string.knowledge_source_lineage_evidence_limited) : "")
                        + (assessmentLimited ? " · " + getString(R.string.knowledge_source_assessment_evidence_limited) : ""))
                .setItems(rows, (d, which) -> {
                    final JSONObject group = groups.optJSONObject(which);
                    if (group != null) showEvidenceDetail(group, sourcePath);
                })
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private void showEvidenceDetail(final JSONObject group, final String sourcePath) {
        // The disclosure is shown before the user can inspect another file.
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_evidence_title)
                .setMessage(KnowledgeRelationPresentation.evidenceDisclosure(this, group))
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.knowledge_source_lineage_new, (d, w) ->
                        chooseLineagePartner(group, sourcePath))
                .setPositiveButton(R.string.knowledge_evidence_show_sources, (d, w) -> {
                    final JSONArray sources = group.optJSONArray("other_sources");
                    final int count = sources == null ? 0 : sources.length();
                    final String[] rows = new String[count + 1];
                    rows[0] = getString(R.string.knowledge_source_assessment_evidence_inspected)
                            + "\n" + sourcePath;
                    for (int i = 0; i < count; i++) {
                        final JSONObject source = sources.optJSONObject(i);
                        rows[i + 1] = source == null ? "" :
                                KnowledgeRelationPresentation.evidenceSourceLabel(this, source);
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.knowledge_evidence_title)
                            .setItems(rows, (d2, selected) -> {
                                if (selected == 0) {
                                    evidenceSourceActions(group, null, sourcePath);
                                } else {
                                    final JSONObject source = sources.optJSONObject(selected - 1);
                                    if (source != null) evidenceSourceActions(group, source, sourcePath);
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null).show();
                }).show();
    }

    private void evidenceSourceActions(final JSONObject group, final JSONObject selected,
                                       final String sourcePath) {
        final String path = selected == null ? sourcePath : selected.optString("path");
        final String verification = selected == null
                ? group.optString("source_verification") : selected.optString("verification");
        final boolean alreadyReviewed = selected == null
                ? group.has("source_assessment_kind") : selected.has("source_assessment_kind");
        final AlertDialog.Builder comparison = new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_evidence_title)
                .setMessage(KnowledgeRelationPresentation.evidenceExcerptComparison(
                        this, group, selected, sourcePath))
                .setNegativeButton(android.R.string.cancel, null);
        if (!"current".equals(verification)) {
            // Snapshot text may be stale, so offer only inspection, not a
            // preverified classification shortcut. The editor reopens source.
            comparison.setPositiveButton(R.string.knowledge_entity_view_source,
                    (d, w) -> openIndexedNote(path)).show();
            return;
        }
        comparison.setNeutralButton(R.string.knowledge_entity_view_source,
                        (d, w) -> openIndexedNote(path))
                .setPositiveButton(alreadyReviewed
                        ? R.string.knowledge_source_assessment_history
                        : R.string.knowledge_source_assessment_new, (d, w) -> {
                    final KnowledgeSourceAssessmentDialog dialog =
                            new KnowledgeSourceAssessmentDialog(this, workspacePath(), notebook,
                                    decisionWorker, this::openIndexedNote,
                                    () -> loadDecisionPage(0));
                    if (alreadyReviewed) dialog.showReviewed(path);
                    else dialog.showNew(path);
                }).show();
    }

    private void chooseLineagePartner(final JSONObject group, final String sourcePath) {
        final JSONArray sources = group.optJSONArray("other_sources");
        final ArrayList<String> paths = new ArrayList<>();
        final ArrayList<String> labels = new ArrayList<>();
        if (sources != null) for (int i = 0; i < sources.length(); i++) {
            final JSONObject source = sources.optJSONObject(i);
            if (source == null || !"current".equals(source.optString("verification"))
                    || sourcePath.equals(source.optString("path"))) continue;
            paths.add(source.optString("path"));
            labels.add(source.optString("path") + " · "
                    + getString(R.string.knowledge_evidence_current));
        }
        labels.add(getString(R.string.knowledge_source_lineage_manual_path));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.knowledge_source_lineage_choose_candidate)
                .setItems(labels.toArray(new String[0]), (d, index) ->
                        new KnowledgeSourceReviewDialog(this, workspacePath(), notebook,
                                decisionWorker, this::openIndexedNote,
                                () -> loadDecisionPage(0)).showNew(
                                        sourcePath, index < paths.size() ? paths.get(index) : ""))
                .setNegativeButton(android.R.string.cancel, null).show();
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

    private static final class SelectedSource {
        final byte[] bytes;
        final String text;

        SelectedSource(final byte[] sourceBytes) {
            bytes = sourceBytes;
            text = new String(sourceBytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private String readSelectedNote(final String relativePath) throws IOException {
        return readSelectedSource(relativePath).text;
    }

    private SelectedSource readSelectedSource(final String relativePath) throws IOException {
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
        return new SelectedSource(bytes.toByteArray());
    }

    /** Re-read user-selected bytes around each external embedding request.
     * File metadata alone cannot detect same-size rewrites with restored mtime.
     */
    private boolean embeddingSourceUnchanged(final KnowledgeVaultScanner.Note note,
                                             final byte[] originalSha256) throws IOException {
        if (!KnowledgeLlmEvidence.matchesIndexedSnapshot(note.file,
                note.modifiedAtMillis, note.sizeBytes)) {
            return false;
        }
        final SelectedSource current = readSelectedSource(note.relativePath);
        return KnowledgeLlmEvidence.matchesIndexedSnapshot(note.file,
                note.modifiedAtMillis, note.sizeBytes)
                && java.security.MessageDigest.isEqual(originalSha256,
                        KnowledgeLlmEvidence.rawSha256(current.bytes));
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
                        if (workspaceNavigation.page() == R.id.knowledge_nav_decisions) {
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

    private static final class PreparedEmbedding {
        final KnowledgeVaultScanner.Note note;
        final byte[] sourceHash;
        final List<KnowledgeEmbeddingChunks.Chunk> chunks;
        final int resumeFrom;
        final boolean truncated;

        PreparedEmbedding(final KnowledgeVaultScanner.Note source, final byte[] sha256,
                          final List<KnowledgeEmbeddingChunks.Chunk> fragments,
                          final int offset, final boolean partial) {
            note = source;
            sourceHash = sha256.clone();
            chunks = fragments;
            resumeFrom = offset;
            truncated = partial;
        }
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
                final List<PreparedEmbedding> ready = new ArrayList<>();
                final List<List<String>> reviewedBatches = new ArrayList<>();
                final StringBuilder exactPreview = new StringBuilder();
                int outgoingChars = 0;
                int outgoingFragments = 0;
                int rejected = 0;
                for (final KnowledgeVaultScanner.Note note : pending) {
                    if (scanGeneration.get() != scan || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    try {
                        if (note.file.lastModified() != note.modifiedAtMillis
                                || note.file.length() != note.sizeBytes) {
                            rejected++;
                            continue;
                        }
                        final SelectedSource source = readSelectedSource(note.relativePath);
                        if (note.file.lastModified() != note.modifiedAtMillis
                                || note.file.length() != note.sizeBytes
                                || !index.matchesIndexedEvidenceSource(note.relativePath,
                                        note.modifiedAtMillis, note.sizeBytes, source.bytes)) {
                            rejected++;
                            continue;
                        }
                        final byte[] hash = KnowledgeLlmEvidence.rawSha256(source.bytes);
                        final List<KnowledgeEmbeddingChunks.Chunk> chunks =
                                KnowledgeEmbeddingChunks.fromNote(source.text);
                        if (chunks.isEmpty()) {
                            rejected++;
                            continue;
                        }
                        final boolean truncated = KnowledgeEmbeddingChunks.coverageTruncated(
                                source.text, chunks);
                        final int from = index.previewEmbeddingResumeOffset(
                                note.relativePath, note.modifiedAtMillis, note.sizeBytes,
                                key, chunks.size(), truncated, hash);
                        int noteChars = 0;
                        for (int j = from; j < chunks.size(); j++) {
                            noteChars += chunks.get(j).text.length();
                        }
                        if (noteChars == 0) {
                            continue;
                        }
                        if (outgoingChars + noteChars > MAX_EMBED_PREVIEW_CHARS) {
                            // A smaller operation can be reviewed next time; never hide text.
                            break;
                        }
                        ready.add(new PreparedEmbedding(note, hash, chunks, from, truncated));
                        outgoingChars += noteChars;
                        for (int position = from; position < chunks.size();
                             position += KnowledgeEmbeddingClient.MAX_BATCH) {
                            final List<String> inputs = new ArrayList<>();
                            final int until = Math.min(chunks.size(),
                                    position + KnowledgeEmbeddingClient.MAX_BATCH);
                            for (int j = position; j < until; j++) {
                                final KnowledgeEmbeddingChunks.Chunk chunk = chunks.get(j);
                                exactPreview.append("\n\n[ ").append(note.relativePath)
                                        .append(" · ").append(j + 1).append('/')
                                        .append(chunks.size()).append(" ]\n")
                                        .append(chunk.text);
                                inputs.add(chunk.text);
                                outgoingFragments++;
                            }
                            reviewedBatches.add(inputs);
                        }
                    } catch (Exception invalid) {
                        rejected++;
                    }
                }
                final int fragments = outgoingFragments;
                final int skipped = rejected;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || scanGeneration.get() != scan) {
                        return;
                    }
                    if (ready.isEmpty()) {
                        status.setText(R.string.knowledge_embedding_up_to_date);
                        return;
                    }
                    final String plainWarning = "http".equals(endpoint.getProtocol())
                            ? "\n\n" + getString(R.string.knowledge_llm_plaintext_warning) : "";
                    final TextView outgoing = new TextView(this);
                    outgoing.setPadding(24, 12, 24, 12);
                    outgoing.setTextIsSelectable(true);
                    outgoing.setText(exactPreview.toString());
                    final ScrollView scroll = new ScrollView(this);
                    scroll.addView(outgoing);
                    scroll.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (int) (340 * getResources().getDisplayMetrics().density)));
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.knowledge_embedding_consent_title)
                            .setMessage(getString(R.string.knowledge_embedding_consent,
                                    ready.size(), endpoint.getHost(), model,
                                    getString(R.string.knowledge_embedding_partial_warning))
                                    + plainWarning + "\n\n" + getString(
                                    R.string.knowledge_embedding_exact_preview_help,
                                    fragments, reviewedBatches.size(), skipped))
                            .setView(scroll)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.knowledge_llm_send,
                                    (dialog, which) -> startEmbeddingBuild(endpoint, model,
                                            apiKey, key, ready, scan,
                                            KnowledgeNetworkApproval.forIndex(endpoint, model,
                                                    reviewedBatches)))
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
        if (activeIndexApproval != null) {
            activeIndexApproval.revoke();
            activeIndexApproval = null;
        }
        if (runningEmbedding != null) {
            runningEmbedding.cancel(true);
        }
        embeddingActive = false;
        recordProcessEvent(KnowledgeProcessHistory.Operation.EMBEDDING,
                KnowledgeProcessHistory.Outcome.CANCELLED);
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
                                     final List<PreparedEmbedding> pending,
                                     final int scan,
                                     final KnowledgeNetworkApproval.Permit approval) {
        if (embeddingActive || scanGeneration.get() != scan) {
            approval.revoke();
            return;
        }
        embeddingActive = true;
        activeIndexApproval = approval;
        recordProcessEvent(KnowledgeProcessHistory.Operation.EMBEDDING,
                KnowledgeProcessHistory.Outcome.STARTED);
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
                    final PreparedEmbedding prepared = pending.get(i);
                    final KnowledgeVaultScanner.Note note = prepared.note;
                    try {
                        final byte[] sourceSha256 = prepared.sourceHash;
                        if (!embeddingSourceUnchanged(note, sourceSha256)) {
                            throw new IOException("Reviewed source changed before upload");
                        }
                        final List<KnowledgeEmbeddingChunks.Chunk> chunks = prepared.chunks;
                        final boolean truncated = prepared.truncated;
                        final int from = index.beginEmbedding(note.relativePath,
                                note.modifiedAtMillis, note.sizeBytes, modelKey,
                                chunks.size(), truncated, sourceSha256);
                        if (from != prepared.resumeFrom) {
                            throw new IOException("Embedding resume offset changed after consent");
                        }
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
                            if (!embeddingSourceUnchanged(note, sourceSha256)) {
                                throw new IOException("Source changed before embedding upload");
                            }
                            // Exact batch content and order were reviewed in full by the user.
                            final float[][] vectors;
                            try {
                                vectors = KnowledgeEmbeddingClient.embed(
                                        endpoint, model, apiKey, inputs, approval,
                                        KnowledgeNetworkApproval.Purpose.EMBEDDING_INDEX);
                            } catch (IOException network) {
                                // A failed service must stop this run; retain checkpoints.
                                throw new IllegalStateException("Embedding service unavailable");
                            }
                            if (!embeddingSourceUnchanged(note, sourceSha256)) {
                                throw new IOException("Source changed during embedding");
                            }
                            index.appendEmbeddingBatch(note.relativePath, note.modifiedAtMillis,
                                    note.sizeBytes, modelKey, chunks, position, vectors,
                                    sourceSha256);
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
                        // Fail closed: skipping a planned batch would desynchronize
                        // the hash-bound permit from the approved upload sequence.
                        throw new IllegalStateException("Reviewed embedding plan changed",
                                inaccessible);
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
                        recordProcessEvent(KnowledgeProcessHistory.Operation.EMBEDDING,
                                missing > 0 || truncated > 0
                                        ? KnowledgeProcessHistory.Outcome.PARTIAL
                                        : KnowledgeProcessHistory.Outcome.COMPLETED);
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
                        recordProcessEvent(KnowledgeProcessHistory.Operation.EMBEDDING,
                                KnowledgeProcessHistory.Outcome.FAILED);
                        updateProcessBadge();
                        embeddingButton.setText(R.string.knowledge_embedding_build);
                        showEmbeddingStatus(R.string.knowledge_embedding_failed);
                    }
                });
            } finally {
                approval.revoke();
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
                                question, endpoint, model, apiKey, modelKey, callback,
                                KnowledgeNetworkApproval.forQuery(endpoint, model, question)))
                .show();
    }

    private void performSemanticQuery(final String question, final URL endpoint,
                                      final String model, final String apiKey,
                                      final String modelKey, final SemanticMatches callback,
                                      final KnowledgeNetworkApproval.Permit approval) {
        final int scan = scanGeneration.get();
        status.setText(R.string.knowledge_embedding_querying);
        embeddingWorker.submit(() -> {
            try {
                final float[] query = KnowledgeEmbeddingClient.embed(endpoint, model, apiKey,
                        Collections.singletonList(question), approval,
                        KnowledgeNetworkApproval.Purpose.EMBEDDING_QUERY)[0];
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
                                                                prepareHybridLlmEvidence(question,
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

    private void prepareHybridLlmEvidence(final String question, final URL endpoint,
                                            final String model, final String apiKey,
                                            final List<KnowledgeLocalIndex.SemanticHit> ranked) {
        final int scan = scanGeneration.get();
        status.setText(R.string.knowledge_llm_retrieving);
        worker.submit(() -> {
            try {
                final List<KnowledgeLlmEvidence.Item> candidates = new ArrayList<>();
                final List<KnowledgeLlmEvidence.ConsentSeal> candidateSeals = new ArrayList<>();
                final List<String> verifiedPaths = new ArrayList<>();
                final Map<String, KnowledgeLocalIndex.SemanticHit> semantic = new HashMap<>();
                final List<String> semanticPaths = new ArrayList<>();
                for (final KnowledgeLocalIndex.SemanticHit match : ranked) {
                    if (!semantic.containsKey(match.relativePath)) {
                        semantic.put(match.relativePath, match);
                        semanticPaths.add(match.relativePath);
                    }
                }
                final Map<String, KnowledgeLocalIndex.Hit> lexical = new HashMap<>();
                final List<String> lexicalPaths = new ArrayList<>();
                for (final KnowledgeLocalIndex.Hit match : index.evidenceSearch(question, 24)) {
                    if (!lexical.containsKey(match.relativePath)) {
                        lexical.put(match.relativePath, match);
                        lexicalPaths.add(match.relativePath);
                    }
                }
                final List<String> combined = KnowledgeHybridRanking.combine(
                        lexicalPaths, semanticPaths, 24);
                for (final String relativePath : combined) {
                    if (Thread.currentThread().isInterrupted() || scanGeneration.get() != scan) {
                        return;
                    }
                    if (candidates.size() >= KnowledgeHybridRanking.MAX_VERIFIED_PREVIEW_CANDIDATES) {
                        break;
                    }
                    try {
                        final KnowledgeLocalIndex.SemanticHit vector = semantic.get(relativePath);
                        final KnowledgeLocalIndex.Hit text = lexical.get(relativePath);
                        final long modified = vector != null ? vector.modifiedAtMillis
                                : text.modifiedAtMillis;
                        final long size = vector != null ? vector.sizeBytes : text.sizeBytes;
                        final File file = new File(notebook, relativePath);
                        if (!KnowledgeLlmEvidence.matchesIndexedSnapshot(file,
                                modified, size)) {
                            continue;
                        }
                        final SelectedSource source = readSelectedSource(relativePath);
                        if (!KnowledgeLlmEvidence.matchesIndexedSnapshot(file,
                                modified, size)
                                || !index.matchesIndexedEvidenceSource(relativePath,
                                        modified, size, source.bytes)) {
                            continue;
                        }
                        final String content = source.text;
                        final String excerpt = vector == null
                                ? KnowledgeLlmEvidence.excerpt(content, question)
                                : KnowledgeEmbeddingChunks.atOffset(
                                        content, vector.excerptStart, vector.excerptLength);
                        if (!excerpt.trim().isEmpty()) {
                            candidates.add(new KnowledgeLlmEvidence.Item(relativePath, excerpt));
                            candidateSeals.add(new KnowledgeLlmEvidence.ConsentSeal(
                                    relativePath, modified, size, source.bytes));
                            verifiedPaths.add(relativePath);
                        }
                    } catch (IOException ignored) {
                        // Changed or inaccessible indexed files are never disclosed.
                    }
                }
                final List<KnowledgeLlmEvidence.Item> sources = new ArrayList<>();
                final List<KnowledgeLlmEvidence.ConsentSeal> seals = new ArrayList<>();
                for (final int selected : KnowledgeHybridRanking.selectBalancedIndices(
                        verifiedPaths, lexicalPaths, semanticPaths,
                        KnowledgeLlmClient.MAX_EVIDENCE)) {
                    sources.add(candidates.get(selected));
                    seals.add(candidateSeals.get(selected));
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || scanGeneration.get() != scan) {
                        return;
                    }
                    if (sources.isEmpty()) {
                        status.setText(R.string.knowledge_llm_no_sources);
                    } else {
                        requestLlmConsent(endpoint, model, apiKey, question,
                                KnowledgeLlmClient.requestPreview(question, sources), sources,
                                seals, scan);
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
                final List<KnowledgeLlmEvidence.Item> candidates = new ArrayList<>();
                final List<KnowledgeLlmEvidence.ConsentSeal> candidateSeals = new ArrayList<>();
                for (final KnowledgeLocalIndex.Hit match : matches) {
                    if (Thread.currentThread().isInterrupted() || scanGeneration.get() != generation) {
                        return;
                    }
                    if (candidates.size() >= KnowledgeLlmEvidence.MAX_LEXICAL_CANDIDATES) {
                        break;
                    }
                    try {
                        final File file = new File(notebook, match.relativePath);
                        if (!KnowledgeLlmEvidence.matchesIndexedSnapshot(file,
                                match.modifiedAtMillis, match.sizeBytes)) {
                            continue;
                        }
                        final SelectedSource source = readSelectedSource(match.relativePath);
                        if (!KnowledgeLlmEvidence.matchesIndexedSnapshot(file,
                                match.modifiedAtMillis, match.sizeBytes)
                                || !index.matchesIndexedEvidenceSource(match.relativePath,
                                        match.modifiedAtMillis, match.sizeBytes, source.bytes)) {
                            continue;
                        }
                        final String content = source.text;
                        final String excerpt = KnowledgeLlmEvidence.excerpt(content, question);
                        if (!excerpt.trim().isEmpty()) {
                            candidates.add(new KnowledgeLlmEvidence.Item(match.relativePath, excerpt));
                            candidateSeals.add(new KnowledgeLlmEvidence.ConsentSeal(
                                    match.relativePath, match.modifiedAtMillis,
                                    match.sizeBytes, source.bytes));
                        }
                    } catch (IOException ignored) {
                        // An inaccessible or stale indexed note must not be sent.
                    }
                }
                if (candidates.isEmpty()) {
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed() && scanGeneration.get() == generation) {
                            status.setText(R.string.knowledge_llm_no_sources);
                        }
                    });
                    return;
                }
                // Select only byte-verified candidates. Keep the two strongest
                // lexical hits and use remaining slots for additional visible
                // question terms, without altering any source or journal.
                final List<KnowledgeLlmEvidence.Item> excerpts = new ArrayList<>();
                final List<KnowledgeLlmEvidence.ConsentSeal> seals = new ArrayList<>();
                for (final int selected : KnowledgeLlmEvidence.selectLexicalIndices(
                        question, candidates, KnowledgeLlmClient.MAX_EVIDENCE)) {
                    excerpts.add(candidates.get(selected));
                    seals.add(candidateSeals.get(selected));
                }
                final String exactPreview = KnowledgeLlmClient.requestPreview(question, excerpts);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == generation) {
                        requestLlmConsent(endpoint, model, apiKey, question, exactPreview, excerpts,
                                seals, generation);
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
                                   final String apiKey, final String question,
                                   final String exactPreview,
                                   final List<KnowledgeLlmEvidence.Item> excerpts,
                                   final List<KnowledgeLlmEvidence.ConsentSeal> seals,
                                   final int consentGeneration) {
        final String privacy = getString(R.string.knowledge_llm_privacy,
                endpoint.getHost(), model, excerpts.size());
        final String warning = "http".equals(endpoint.getProtocol())
                ? "\n" + getString(R.string.knowledge_llm_plaintext_warning) : "";
        final KnowledgeLlmEvidence.TermCoverage coverage =
                KnowledgeLlmEvidence.termCoverage(question, excerpts);
        final String lexicalCoverage = coverage.total == 0
                ? getString(R.string.knowledge_llm_coverage_unavailable)
                : getString(R.string.knowledge_llm_coverage, coverage.matched, coverage.total);
        final String missingCoverage = coverage.isIncomplete()
                ? "\n" + getString(R.string.knowledge_llm_coverage_incomplete) : "";
        new AlertDialog.Builder(this)
                .setTitle(R.string.knowledge_llm_consent_title)
                .setMessage(privacy + warning + "\n\n" + lexicalCoverage
                        + missingCoverage + "\n\n" + exactPreview)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_llm_send,
                        (dialog, which) -> sendLlmQuestion(endpoint, model, apiKey,
                                exactPreview, excerpts, seals, coverage, consentGeneration,
                                KnowledgeNetworkApproval.forAnswer(endpoint, model, exactPreview)))
                .show();
    }

    /** A dialog may sit open while another app edits the selected Markdown.
     * Revalidate every approved source immediately before network I/O; never
     * auto-rebuild, re-consent, or substitute different excerpts here.
     */
    private boolean consentEvidenceStillCurrent(
            final List<KnowledgeLlmEvidence.ConsentSeal> seals,
            final int consentGeneration) {
        if (notebook == null || seals == null || seals.isEmpty()
                || seals.size() > KnowledgeLlmClient.MAX_EVIDENCE
                || scanGeneration.get() != consentGeneration) {
            return false;
        }
        for (final KnowledgeLlmEvidence.ConsentSeal seal : seals) {
            if (Thread.currentThread().isInterrupted()
                    || scanGeneration.get() != consentGeneration) return false;
            final File file = new File(notebook, seal.relativePath);
            if (!KnowledgeLlmEvidence.matchesIndexedSnapshot(file,
                    seal.modifiedAtMillis, seal.sizeBytes)) return false;
            try {
                final SelectedSource current = readSelectedSource(seal.relativePath);
                if (!KnowledgeLlmEvidence.matchesIndexedSnapshot(file,
                        seal.modifiedAtMillis, seal.sizeBytes)
                        || !seal.matchesCurrentBytes(current.bytes)
                        || !index.matchesIndexedEvidenceSource(seal.relativePath,
                                seal.modifiedAtMillis, seal.sizeBytes, current.bytes)) {
                    return false;
                }
            } catch (IOException unavailable) {
                return false;
            }
        }
        return scanGeneration.get() == consentGeneration;
    }

    private void sendLlmQuestion(final URL endpoint, final String model,
                                 final String apiKey, final String exactPreview,
                                 final List<KnowledgeLlmEvidence.Item> excerpts,
                                 final List<KnowledgeLlmEvidence.ConsentSeal> seals,
                                 final KnowledgeLlmEvidence.TermCoverage coverage,
                                 final int generation,
                                 final KnowledgeNetworkApproval.Permit approval) {
        status.setText(R.string.knowledge_llm_requesting);
        worker.submit(() -> {
            try {
                if (!consentEvidenceStillCurrent(seals, generation)) {
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()
                                && scanGeneration.get() == generation) {
                            status.setText(R.string.knowledge_llm_sources_changed_before_send);
                        }
                    });
                    return;
                }
                final String answer = KnowledgeLlmClient.ask(endpoint, model, apiKey,
                        exactPreview, approval);
                // A user or another app may edit Markdown while the provider
                // generates its response. References still describe only the
                // bytes actually sent, not necessarily the current vault.
                final boolean sourceStillCurrentAfterReply =
                        consentEvidenceStillCurrent(seals, generation);
                final KnowledgeLlmCitationAudit.Review review =
                        KnowledgeLlmCitationAudit.inspect(answer, excerpts);
                final StringBuilder display = new StringBuilder(
                        getString(R.string.knowledge_llm_unverified));
                if (coverage.isIncomplete()) {
                    display.append("\n").append(getString(
                            R.string.knowledge_llm_coverage_incomplete));
                }
                if (!sourceStillCurrentAfterReply) {
                    display.append("\n").append(getString(
                            R.string.knowledge_llm_sources_changed_after_reply));
                }
                if (!review.hasValidReferences()) {
                    display.append("\n").append(getString(R.string.knowledge_llm_no_citations));
                }
                if (review.invalidReferences > 0) {
                    display.append("\n").append(getString(R.string.knowledge_llm_invalid_citations,
                            review.invalidReferences));
                }
                if (review.unverifiedQuotes > 0) {
                    display.append("\n").append(getString(R.string.knowledge_llm_unverified_quotes,
                            review.unverifiedQuotes));
                }
                if (review.mismatchedQuoteCitations > 0) {
                    display.append("\n").append(getString(
                            R.string.knowledge_llm_mismatched_quote_citations,
                            review.mismatchedQuoteCitations));
                }
                if (review.uncitedProseSpans > 0) {
                    display.append("\n").append(getString(
                            R.string.knowledge_llm_uncited_prose_spans,
                            review.uncitedProseSpans));
                }
                if (review.weakTextAttributions > 0) {
                    display.append("\n").append(getString(
                            R.string.knowledge_llm_weak_text_attributions,
                            review.weakTextAttributions));
                }
                if (review.polarityMismatchedCitations > 0) {
                    display.append("\n").append(getString(
                            R.string.knowledge_llm_polarity_mismatch,
                            review.polarityMismatchedCitations));
                }
                if (review.potentiallyOpposingSources > 0) {
                    display.append("\n").append(getString(
                            R.string.knowledge_llm_possible_source_conflicts,
                            review.potentiallyOpposingSources));
                }
                if (!review.flaggedSpans.isEmpty()) {
                    display.append("\n\n").append(getString(
                            R.string.knowledge_llm_flagged_spans_title));
                    for (final KnowledgeLlmCitationAudit.Flag flagged : review.flaggedSpans) {
                        final int label = flagged.kind
                                == KnowledgeLlmCitationAudit.Flag.Kind.WEAK_TEXT
                                ? R.string.knowledge_llm_flagged_weak
                                : R.string.knowledge_llm_flagged_polarity;
                        display.append("\n• ").append(getString(label, flagged.sourceNumber,
                                KnowledgeLlmCitationAudit.flaggedAnswerSnippet(answer, flagged)));
                    }
                    final int hidden = review.weakTextAttributions
                            + review.polarityMismatchedCitations - review.flaggedSpans.size();
                    if (hidden > 0) {
                        display.append("\n").append(getString(
                                R.string.knowledge_llm_flagged_more, hidden));
                    }
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
            } catch (KnowledgeLlmClient.IncompleteResponseException incomplete) {
                // Do not display a possibly missing conclusion or citations;
                // never retry or resend private excerpts automatically.
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && scanGeneration.get() == generation) {
                        status.setText(R.string.knowledge_llm_incomplete_response);
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
        final File journal = new File(workspacePath(), "data/formal/journal.jsonl");
        if (!journal.isFile() || journal.length() == 0L) {
            status.setText(R.string.knowledge_backup_export_empty);
            Toast.makeText(this, R.string.knowledge_backup_export_empty,
                    Toast.LENGTH_LONG).show();
            return;
        }
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
                    prepareJournalExport(first.toCharArray());
                })
                .show();
    }

    private void prepareJournalExport(final char[] password) {
        status.setText(R.string.knowledge_backup_working);
        worker.submit(() -> {
            byte[] plaintext = null;
            byte[] envelope = null;
            try {
                final File journal = new File(workspacePath(), "data/formal/journal.jsonl");
                if (!journal.isFile() || journal.length() == 0L) {
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            status.setText(R.string.knowledge_backup_export_empty);
                            Toast.makeText(this, R.string.knowledge_backup_export_empty,
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }
                final JSONObject metadata = KnowledgePythonBridge.journalExportInfo(
                        this, workspacePath());
                if (metadata.optInt("count", 0) < 1) {
                    throw new IOException("No journal decisions to export");
                }
                try (FileInputStream in = new FileInputStream(journal)) {
                    plaintext = readLimited(in, KnowledgeJournalCrypto.MAX_JOURNAL_BYTES);
                }
                if (!sha256Hex(plaintext).equals(metadata.getString("sha256"))) {
                    throw new IOException("Journal changed while preparing backup");
                }
                envelope = KnowledgeJournalCrypto.encrypt(plaintext, password);
                final byte[] prepared = envelope;
                envelope = null;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        Arrays.fill(prepared, (byte) 0);
                        return;
                    }
                    if (pendingExportEnvelope != null) {
                        Arrays.fill(pendingExportEnvelope, (byte) 0);
                    }
                    pendingExportEnvelope = prepared;
                    final Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/octet-stream");
                    intent.putExtra(Intent.EXTRA_TITLE, "DP_Knowledge_Journal.dpks");
                    try {
                        startActivityForResult(intent, REQUEST_EXPORT_JOURNAL);
                    } catch (RuntimeException unavailable) {
                        Arrays.fill(prepared, (byte) 0);
                        pendingExportEnvelope = null;
                        status.setText(R.string.knowledge_backup_failed);
                    }
                });
            } catch (Exception failed) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        status.setText(R.string.knowledge_backup_failed);
                        Toast.makeText(this, R.string.knowledge_backup_failed,
                                Toast.LENGTH_LONG).show();
                    }
                });
            } finally {
                Arrays.fill(password, '\0');
                if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
                if (envelope != null) Arrays.fill(envelope, (byte) 0);
            }
        });
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
        if (requestCode == REQUEST_IMPORT_MODEL) {
            final KnowledgeModelPolicy.Role role = pendingModelImportRole;
            pendingModelImportRole = null;
            if (role != null && resultCode == RESULT_OK && data != null
                    && data.getData() != null) {
                importModelFromUri(data.getData(), role);
            }
        } else if (requestCode == REQUEST_IMPORT_MODEL_PACKAGE) {
            final String key = pendingModelPackageKey;
            pendingModelPackageKey = null;
            if (key != null && resultCode == RESULT_OK && data != null) {
                try {
                    for (final KnowledgeModelFiles.Entry entry : modelFiles.installed()) {
                        if (key.equals(entry.role.name() + ":" + entry.sha256)) {
                            importModelPackageFromPicker(data, entry);
                            break;
                        }
                    }
                } catch (IOException unavailable) {
                    modelStatus.setText(R.string.knowledge_models_package_failed);
                }
            }
        } else if (requestCode == REQUEST_EXPORT_JOURNAL) {
            final byte[] envelope = pendingExportEnvelope;
            pendingExportEnvelope = null;
            if (envelope == null) {
                status.setText(R.string.knowledge_backup_export_incomplete);
                return;
            }
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                exportJournalToUri(data.getData(), envelope);
            } else {
                Arrays.fill(envelope, (byte) 0);
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

    private void exportJournalToUri(final Uri destination, final byte[] envelope) {
        status.setText(R.string.knowledge_backup_working);
        worker.submit(() -> {
            try {
                KnowledgeJournalExportWriter.writeAndVerify(
                        new KnowledgeJournalExportWriter.Destination() {
                            @Override public OutputStream openWrite() throws IOException {
                                return getContentResolver().openOutputStream(destination, "wt");
                            }
                            @Override public InputStream openRead() throws IOException {
                                return getContentResolver().openInputStream(destination);
                            }
                        }, envelope);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        status.setText(R.string.knowledge_backup_exported);
                        Toast.makeText(this, R.string.knowledge_backup_exported,
                                Toast.LENGTH_LONG).show();
                        if (syncResult != null && snapshot != null) {
                            showIndexStatus(displayed.size());
                        }
                    }
                });
            } catch (Exception failed) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        status.setText(R.string.knowledge_backup_export_incomplete);
                        Toast.makeText(this, R.string.knowledge_backup_export_incomplete,
                                Toast.LENGTH_LONG).show();
                    }
                });
            } finally {
                Arrays.fill(envelope, (byte) 0);
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
        modelGeneration.incrementAndGet();
        if (onnxSmokeClient != null) onnxSmokeClient.cancel();
        if (onnxSessionClient != null) onnxSessionClient.cancel();
        if (runningModelImport != null) runningModelImport.cancel(true);
        scanGeneration.incrementAndGet();
        queryGeneration.incrementAndGet();
        analysisGeneration.incrementAndGet();
        auditGeneration.incrementAndGet();
        embeddingGeneration.incrementAndGet();
        if (activeIndexApproval != null) activeIndexApproval.revoke();
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
        if (pendingExportEnvelope != null) {
            Arrays.fill(pendingExportEnvelope, (byte) 0);
            pendingExportEnvelope = null;
        }
        worker.shutdownNow();
        analysisWorker.shutdownNow();
        embeddingWorker.shutdownNow();
        deviceQaWorker.shutdownNow();
        decisionWorker.shutdownNow();
        modelWorker.shutdownNow();
        super.onDestroy();
    }
}
