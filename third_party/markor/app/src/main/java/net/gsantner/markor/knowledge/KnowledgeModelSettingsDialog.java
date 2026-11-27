/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import net.gsantner.markor.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * First MODEL-M1/M2 UI slice: local file shelf and declared model choices.
 * Installed GGUF/ONNX weights are not executed or declared compatible.
 * The separate, explicitly approved CPU smoke only runs a synthetic graph.
 */
final class KnowledgeModelSettingsDialog {
    interface ImportRequest { void pick(KnowledgeModelPolicy.Role role); }
    interface PackageRequest { void pick(KnowledgeModelFiles.Entry entry); }
    interface AuditRequest { void verify(KnowledgeModelFiles.Entry entry); }
    interface PrepareRequest { void prepare(KnowledgeModelFiles.Entry entry); }
    interface SessionRequest { void inspect(KnowledgeModelFiles.Entry entry); }
    interface E5QaRequest { void run(KnowledgeModelFiles.Entry entry); }
    interface E5GraphRequest { void inspect(KnowledgeModelFiles.Entry entry); }
    interface E5BuildRequest { void pick(KnowledgeModelFiles.Entry entry); }
    interface SmokeRequest { void run(); }
    interface FdSmokeRequest { void run(); }
    interface BenchmarkRequest { void run(KnowledgeModelPolicy.Role role); }
    interface DownloadRequest {
        void start(KnowledgeModelPolicy.Role role, String url, String expectedSha256);
    }

    private static final String PREFS = "dpks_local_model_choices_v1";
    private final Context context;
    private final KnowledgeModelFiles files;
    private final KnowledgeModelPackageStore packages;
    private final ImportRequest importer;
    private final PackageRequest packageImporter;
    private final AuditRequest auditor;
    private final PrepareRequest preparer;
    private final SessionRequest sessionInspector;
    private final E5QaRequest e5QaRunner;
    private final E5GraphRequest e5GraphInspector;
    private final E5BuildRequest e5PackageBuilder;
    private final SmokeRequest smokeTester;
    private final FdSmokeRequest fdSmokeTester;
    private final BenchmarkRequest benchmarkRunner;
    private final DownloadRequest downloader;
    private final SharedPreferences preferences;

    KnowledgeModelSettingsDialog(final Context activity, final KnowledgeModelFiles modelFiles,
                                 final KnowledgeModelPackageStore modelPackages,
                                 final ImportRequest request, final PackageRequest packageRequest,
                                 final DownloadRequest download, final AuditRequest audit,
                                 final PrepareRequest prepare, final SmokeRequest smoke,
                                 final FdSmokeRequest fdSmoke,
                                 final BenchmarkRequest benchmark,
                                 final SessionRequest sessionInspect,
                                 final E5QaRequest e5Qa,
                                 final E5GraphRequest e5Graph,
                                 final E5BuildRequest e5Build) {
        context = activity;
        files = modelFiles;
        packages = modelPackages;
        importer = request;
        packageImporter = packageRequest;
        auditor = audit;
        preparer = prepare;
        sessionInspector = sessionInspect;
        e5QaRunner = e5Qa;
        e5GraphInspector = e5Graph;
        e5PackageBuilder = e5Build;
        smokeTester = smoke;
        fdSmokeTester = fdSmoke;
        benchmarkRunner = benchmark;
        downloader = download;
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String roleName(final KnowledgeModelPolicy.Role role) {
        switch (role) {
            case EMBEDDING: return context.getString(R.string.knowledge_models_embeddings);
            case GENERATION: return context.getString(R.string.knowledge_models_generation);
            case RERANKING: return context.getString(R.string.knowledge_models_reranking);
            case PERSONALIZATION: return context.getString(R.string.knowledge_models_personalization);
            default: throw new IllegalArgumentException("Unknown model category");
        }
    }

    void show() {
        final KnowledgeModelPolicy.Role[] roles = KnowledgeModelPolicy.Role.values();
        final String[] titles = new String[roles.length];
        for (int i = 0; i < titles.length; i++) {
            titles[i] = roleName(roles[i]);
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(R.string.knowledge_models_title)
                .setMessage(R.string.knowledge_models_four_optional)
                .setItems(titles, (dialog, which) -> showRole(roles[which]))
                .setNegativeButton(android.R.string.cancel, null);
        if (KnowledgeOnnxCpuBackend.available()) {
            builder.setNeutralButton(R.string.knowledge_models_onnx_diagnostics,
                    (dialog, which) -> showOnnxDiagnostics());
        }
        builder.show();
    }

    private void showOnnxDiagnostics() {
        new AlertDialog.Builder(context)
                .setTitle(R.string.knowledge_models_onnx_diagnostics)
                .setItems(new String[]{
                        context.getString(R.string.knowledge_models_onnx_smoke),
                        context.getString(R.string.knowledge_models_onnx_fd_smoke)
                }, (dialog, which) -> {
                    if (which == 0) confirmOnnxSmoke();
                    else if (which == 1) confirmOnnxFdSmoke();
                })
                .setNegativeButton(R.string.knowledge_models_back, (dialog, which) -> show())
                .show();
    }

    private void confirmOnnxFdSmoke() {
        new AlertDialog.Builder(context)
                .setTitle(R.string.knowledge_models_onnx_fd_smoke)
                .setMessage(R.string.knowledge_models_onnx_fd_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_models_onnx_fd_smoke,
                        (dialog, which) -> fdSmokeTester.run())
                .show();
    }

    void showRole(final KnowledgeModelPolicy.Role role) {
        final List<KnowledgeModelFiles.Entry> entries = new ArrayList<>();
        try {
            for (final KnowledgeModelFiles.Entry model : files.installed()) {
                if (model.role == role) entries.add(model);
            }
        } catch (IOException unavailable) {
            new AlertDialog.Builder(context).setMessage(R.string.knowledge_models_store_failed)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        final String selected = preferences.getString(role.name(), "");
        final String[] labels = new String[entries.size() + 3];
        labels[0] = (selected.isEmpty() ? "✓ " : "")
                + context.getString(R.string.knowledge_models_disabled);
        for (int i = 0; i < entries.size(); i++) {
            final KnowledgeModelFiles.Entry model = entries.get(i);
            labels[i + 1] = (selected.equals(model.sha256) ? "✓ " : "")
                    + model.name + " · " + formatSize(model.bytes);
        }
        labels[labels.length - 2] = context.getString(R.string.knowledge_models_download_url);
        labels[labels.length - 1] = context.getString(R.string.knowledge_models_synthetic_start);
        new AlertDialog.Builder(context)
                .setTitle(roleName(role))
                .setMessage(R.string.knowledge_models_imported_not_runnable)
                .setItems(labels, (dialog, which) -> {
                    if (which == 0) {
                        preferences.edit().remove(role.name()).apply();
                        showRole(role);
                    } else if (which == labels.length - 2) {
                        showDownloadPrompt(role);
                    } else if (which == labels.length - 1) {
                        confirmSyntheticBenchmark(role);
                    } else {
                        showDetails(entries.get(which - 1));
                    }
                })
                .setPositiveButton(R.string.knowledge_models_import, (dialog, which) ->
                        importer.pick(role))
                .setNeutralButton(R.string.knowledge_models_catalog, (dialog, which) ->
                        showCatalog(role))
                .setNegativeButton(R.string.knowledge_models_back, (dialog, which) -> show())
                .show();
    }

    private void confirmSyntheticBenchmark(final KnowledgeModelPolicy.Role role) {
        new AlertDialog.Builder(context)
                .setTitle(roleName(role))
                .setMessage(R.string.knowledge_models_synthetic_warning)
                .setPositiveButton(R.string.knowledge_models_synthetic_confirm,
                        (choice, which) -> benchmarkRunner.run(role))
                .setNegativeButton(R.string.knowledge_models_back,
                        (choice, which) -> showRole(role))
                .show();
    }

    private void showDownloadPrompt(final KnowledgeModelPolicy.Role role) {
        final LinearLayout fields = new LinearLayout(context);
        fields.setOrientation(LinearLayout.VERTICAL);
        final int pad = (int) (20 * context.getResources().getDisplayMetrics().density);
        fields.setPadding(pad, 0, pad, 0);
        final EditText link = new EditText(context);
        link.setSingleLine(true);
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        link.setHint(R.string.knowledge_models_download_url_hint);
        fields.addView(link);
        final EditText hash = new EditText(context);
        hash.setSingleLine(true);
        hash.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hash.setHint(R.string.knowledge_models_download_sha_hint);
        fields.addView(hash);
        new AlertDialog.Builder(context)
                .setTitle(R.string.knowledge_models_download_url)
                .setMessage(R.string.knowledge_models_download_url_info)
                .setView(fields)
                .setPositiveButton(R.string.knowledge_models_download_review, (dialog, which) -> {
                    final String url = link.getText().toString().trim();
                    final String digest = hash.getText().toString().trim().toLowerCase(Locale.ROOT);
                    try {
                        final KnowledgeModelDownload.Source source = KnowledgeModelDownload.parseSource(url);
                        if (!digest.isEmpty() && !digest.matches("[0-9a-f]{64}")) {
                            throw new IOException("Invalid checksum");
                        }
                        new AlertDialog.Builder(context)
                                .setTitle(R.string.knowledge_models_download_review)
                                .setMessage(context.getString(R.string.knowledge_models_download_confirm,
                                        source.uri.getHost(), source.filename,
                                        digest.isEmpty()
                                                ? context.getString(R.string.knowledge_models_sha_not_provided)
                                                : digest))
                                .setPositiveButton(R.string.knowledge_models_download_confirm_action,
                                        (confirm, button) -> beginTrustedDownload(
                                                role, url, digest, source.filename))
                                .setNegativeButton(android.R.string.cancel, (confirm, button) ->
                                        showRole(role))
                                .show();
                    } catch (IOException invalid) {
                        new AlertDialog.Builder(context)
                                .setMessage(R.string.knowledge_models_download_bad_url)
                                .setPositiveButton(android.R.string.ok, (error, button) ->
                                        showDownloadPrompt(role))
                                .show();
                    }
                })
                .setNegativeButton(R.string.knowledge_models_back, (dialog, which) -> showRole(role))
                .show();
    }

    /** A stored partial is only reused by a new explicit user action. */
    private void beginTrustedDownload(final KnowledgeModelPolicy.Role role, final String url,
                                      final String expectedSha, final String filename) {
        if (expectedSha == null || expectedSha.isEmpty()) {
            downloader.start(role, url, ""); // No digest means no resume checkpoint.
            return;
        }
        try {
            final long saved = files.resumeBytes(expectedSha, filename);
            if (saved == 0) {
                downloader.start(role, url, expectedSha);
                return;
            }
            new AlertDialog.Builder(context)
                    .setTitle(R.string.knowledge_models_resume_title)
                    .setMessage(context.getString(R.string.knowledge_models_resume_message,
                            filename, formatSize(saved)))
                    .setPositiveButton(R.string.knowledge_models_resume_continue,
                            (dialog, which) -> downloader.start(role, url, expectedSha))
                    .setNeutralButton(R.string.knowledge_models_resume_restart,
                            (dialog, which) -> {
                                try {
                                    files.discardResume(expectedSha, filename);
                                    downloader.start(role, url, expectedSha);
                                } catch (IOException invalid) {
                                    new AlertDialog.Builder(context)
                                            .setMessage(R.string.knowledge_models_resume_failed)
                                            .setPositiveButton(android.R.string.ok, null)
                                            .show();
                                }
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } catch (IOException invalid) {
            new AlertDialog.Builder(context)
                    .setMessage(R.string.knowledge_models_resume_failed)
                    .setPositiveButton(android.R.string.ok, null).show();
        }
    }

    private void showDetails(final KnowledgeModelFiles.Entry entry) {
        final String summary = context.getString(R.string.knowledge_models_details,
                entry.name, formatSize(entry.bytes), entry.bytes, entry.format.name(),
                entry.sha256);
        final KnowledgeModelCatalog.Artifact known =
                KnowledgeModelCatalog.findInstalled(entry.role, entry.sha256);
        final String provenance = known == null ? "" : "\n\n"
                + context.getString(R.string.knowledge_models_catalog_provenance,
                        known.id, known.license, known.sourcePage);
        KnowledgeModelPackageContract.Report packageReport =
                KnowledgeModelPackageContract.inspect(entry);
        KnowledgeModelPackageManifest installed = null;
        boolean packageCorrupt = false;
        try {
            installed = packages.installed(entry);
            if (installed != null) {
                packageReport = KnowledgeModelPackageContract.inspect(entry, installed);
            }
        } catch (IOException invalid) {
            packageCorrupt = true;
        }
        final String packageFiles = packageCorrupt
                ? context.getString(R.string.knowledge_models_package_invalid)
                : installed == null ? context.getString(R.string.knowledge_models_package_none)
                : context.getString(R.string.knowledge_models_package_present,
                        packageReport.companionFileCount, packageReport.assertedRevision,
                        installed.assertedLicense);
        final StringBuilder missing = new StringBuilder();
        for (final KnowledgeModelPackageContract.Requirement requirement : packageReport.missing) {
            missing.append("\n• ").append(packageRequirementName(requirement));
        }
        final String packageSummary = context.getString(R.string.knowledge_models_package_report,
                packageReport.schemaVersion, packageReport.sourceId,
                packageReport.license, packageReport.engineCandidate, missing.toString());
        final String resources = resourceReport(entry, installed);
        new AlertDialog.Builder(context)
                .setTitle(roleName(entry.role))
                .setMessage(summary + provenance + "\n\n" + packageFiles + "\n\n"
                        + packageSummary + "\n\n" + resources + "\n\n"
                        + context.getString(R.string.knowledge_models_imported_not_runnable))
                .setPositiveButton(R.string.knowledge_models_select, (dialog, which) -> {
                    preferences.edit().putString(entry.role.name(), entry.sha256).apply();
                    showRole(entry.role);
                })
                .setNeutralButton(R.string.knowledge_models_package_manage,
                        (dialog, which) -> showPackageActions(entry))
                .setNegativeButton(R.string.knowledge_models_back, (dialog, which) ->
                        showRole(entry.role))
                .show();
    }

    /** No file scan, inference or model download occurs when opening details. */
    private String resourceReport(final KnowledgeModelFiles.Entry entry,
                                  final KnowledgeModelPackageManifest manifest) {
        try {
            final ActivityManager.MemoryInfo memory = memorySnapshot();
            final long osAvailableRam = memory == null ? 0 : Math.max(0, memory.availMem);
            final KnowledgeModelResourcePlan.Report report = KnowledgeModelResourcePlan.plan(
                    entry, manifest, osAvailableRam, context.getFilesDir().getUsableSpace());
            final String general = context.getString(R.string.knowledge_models_resource_report,
                    formatSize(report.weightBytes), formatSize(report.externalBytes),
                    report.externalFiles, formatSize(report.otherCompanionBytes),
                    formatSize(report.totalDeclaredBytes),
                    report.availableRamBytes == 0
                            ? context.getString(R.string.knowledge_models_container_unknown)
                            : formatSize(report.availableRamBytes),
                    report.freeStorageBytes == 0
                            ? context.getString(R.string.knowledge_models_container_unknown)
                            : formatSize(report.freeStorageBytes),
                    context.getString(resourceGateLabel(report.gate)));
            if (manifest == null || entry.role != KnowledgeModelPolicy.Role.EMBEDDING
                    || entry.format != KnowledgeModelFiles.Format.ONNX_UNVERIFIED
                    || KnowledgeModelCatalog.e5Companions(entry.role, entry.sha256).isEmpty()) {
                return general;
            }
            final KnowledgeE5ResourceEstimate.Plan e5 = KnowledgeE5ResourceEstimate.assess(
                    entry.bytes, report.externalBytes,
                    KnowledgeEmbeddingTensorContract.MAX_SEQUENCE_LENGTH, true,
                    osAvailableRam, memory == null || memory.lowMemory);
            final int status = memory == null || osAvailableRam == 0
                    ? R.string.knowledge_models_e5_resource_unknown
                    : e5.memoryDenied ? R.string.knowledge_models_e5_resource_denied
                    : e5.memoryWarning ? R.string.knowledge_models_e5_resource_warning
                    : R.string.knowledge_models_e5_resource_prefilter;
            return general + "\n\n" + context.getString(
                    R.string.knowledge_models_e5_resource_report,
                    formatSize(e5.estimatedWorkingSetBytes),
                    osAvailableRam == 0
                            ? context.getString(R.string.knowledge_models_container_unknown)
                            : formatSize(osAvailableRam),
                    formatSize(e5.inputTensorBytes), formatSize(e5.hiddenTensorBytes),
                    context.getString(status));
        } catch (IOException | RuntimeException invalid) {
            return context.getString(R.string.knowledge_models_resource_unavailable);
        }
    }

    private static int resourceGateLabel(final KnowledgeModelResourcePlan.Gate gate) {
        switch (gate) {
            case GGUF_BACKEND_PENDING: return R.string.knowledge_models_resource_gguf;
            case MISSING_PACKAGE: return R.string.knowledge_models_resource_no_package;
            case EXTERNAL_DATA_PENDING: return R.string.knowledge_models_resource_external;
            case MODEL_TOO_LARGE: return R.string.knowledge_models_resource_weight_limit;
            case PACKAGE_TOO_LARGE: return R.string.knowledge_models_resource_package_limit;
            case RAM_UNKNOWN: return R.string.knowledge_models_resource_ram_unknown;
            case RAM_TOO_LOW: return R.string.knowledge_models_resource_ram_low;
            case RESOURCES_ONLY_PASSED: return R.string.knowledge_models_resource_prefilter;
            default: throw new IllegalArgumentException("Unknown resource gate");
        }
    }

    private void showPackageActions(final KnowledgeModelFiles.Entry entry) {
        final boolean onnx = entry.format == KnowledgeModelFiles.Format.ONNX_UNVERIFIED;
        final boolean cpuSession = onnx && KnowledgeOnnxCpuBackend.available();
        final boolean e5Qa = cpuSession && entry.role == KnowledgeModelPolicy.Role.EMBEDDING;
        final boolean e5Graph = onnx && entry.role == KnowledgeModelPolicy.Role.EMBEDDING;
        final List<String> menu = new ArrayList<>();
        menu.add(context.getString(R.string.knowledge_models_package_import));
        menu.add(context.getString(R.string.knowledge_models_package_audit));
        final int prepareIndex = onnx ? menu.size() : -1;
        if (onnx) menu.add(context.getString(R.string.knowledge_models_onnx_prepare));
        final int sessionIndex = cpuSession ? menu.size() : -1;
        if (cpuSession) menu.add(context.getString(R.string.knowledge_models_onnx_session));
        final int e5QaIndex = e5Qa ? menu.size() : -1;
        if (e5Qa) menu.add(context.getString(R.string.knowledge_models_e5_qa));
        final int e5GraphIndex = e5Graph ? menu.size() : -1;
        if (e5Graph) menu.add(context.getString(R.string.knowledge_models_e5_graph));
        // Show only for an exact known upstream E5 weight SHA. Unknown/custom
        // E5 exports continue through the standard manual package workflow.
        final int companionsIndex = KnowledgeModelCatalog.e5Companions(
                entry.role, entry.sha256).isEmpty() ? -1 : menu.size();
        if (companionsIndex >= 0) {
            menu.add(context.getString(R.string.knowledge_models_e5_companions));
        }
        final int buildE5Index = companionsIndex >= 0 ? menu.size() : -1;
        if (buildE5Index >= 0) {
            menu.add(context.getString(R.string.knowledge_models_e5_build_package));
        }
        final int removeIndex = menu.size();
        menu.add(context.getString(R.string.knowledge_models_package_remove));
        menu.add(context.getString(R.string.knowledge_models_delete));
        final String[] actions = menu.toArray(new String[0]);
        new AlertDialog.Builder(context)
                .setTitle(R.string.knowledge_models_package_manage)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) packageImporter.pick(entry);
                    else if (which == 1) auditor.verify(entry);
                    else if (which == prepareIndex) confirmPrepareOnnx(entry);
                    else if (which == sessionIndex) confirmOnnxSession(entry);
                    else if (which == e5QaIndex) confirmE5Qa(entry);
                    else if (which == e5GraphIndex) confirmE5Graph(entry);
                    else if (which == companionsIndex) showE5Companions(entry);
                    else if (which == buildE5Index) confirmE5Build(entry);
                    else if (which == removeIndex) confirmRemovePackage(entry);
                    else confirmDelete(entry);
                })
                .setNeutralButton(R.string.knowledge_models_package_help, (dialog, which) ->
                        new AlertDialog.Builder(context)
                                .setMessage(R.string.knowledge_models_package_import_help)
                                .setPositiveButton(android.R.string.ok, (help, close) ->
                                        showPackageActions(entry)).show())
                .setNegativeButton(R.string.knowledge_models_back, (dialog, which) ->
                        showDetails(entry))
                .show();
    }

    /** Three explicit SAF documents; no implicit download or model execution. */
    private void confirmE5Build(final KnowledgeModelFiles.Entry entry) {
        try {
            if (packages.installed(entry) != null) {
                new AlertDialog.Builder(context)
                        .setMessage(R.string.knowledge_models_e5_package_exists)
                        .setPositiveButton(android.R.string.ok, null).show();
                return;
            }
        } catch (IOException damaged) {
            new AlertDialog.Builder(context)
                    .setMessage(R.string.knowledge_models_package_invalid)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.knowledge_models_e5_build_package)
                .setMessage(R.string.knowledge_models_e5_build_warning)
                .setPositiveButton(R.string.knowledge_models_e5_build_package,
                        (dialog, which) -> e5PackageBuilder.pick(entry))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Purely manual browser guidance; no HTTP request or background job here. */
    private void showE5Companions(final KnowledgeModelFiles.Entry entry) {
        final List<KnowledgeModelCatalog.Companion> companions =
                KnowledgeModelCatalog.e5Companions(entry.role, entry.sha256);
        if (companions.isEmpty()) return;
        final String[] names = new String[companions.size()];
        for (int i = 0; i < names.length; i++) names[i] = companions.get(i).filename;
        new AlertDialog.Builder(context)
                .setTitle(R.string.knowledge_models_e5_companions)
                .setMessage(R.string.knowledge_models_e5_companions_help)
                .setItems(names, (dialog, which) ->
                        showE5CompanionDetails(entry, companions.get(which)))
                .setNegativeButton(R.string.knowledge_models_back,
                        (dialog, which) -> showPackageActions(entry))
                .show();
    }

    private void showE5CompanionDetails(final KnowledgeModelFiles.Entry entry,
                                        final KnowledgeModelCatalog.Companion companion) {
        new AlertDialog.Builder(context)
                .setTitle(companion.filename)
                .setMessage(context.getString(R.string.knowledge_models_e5_companion_details,
                        companion.sourcePage, companion.expectedSha256))
                .setPositiveButton(R.string.knowledge_models_catalog_open_source,
                        (dialog, which) -> {
                            try {
                                context.startActivity(new Intent(Intent.ACTION_VIEW,
                                        Uri.parse(companion.sourcePage)));
                            } catch (RuntimeException unavailable) {
                                new AlertDialog.Builder(context)
                                        .setMessage(R.string.knowledge_models_no_browser)
                                        .setPositiveButton(android.R.string.ok, null).show();
                            }
                        })
                .setNeutralButton(R.string.knowledge_models_e5_copy_sha,
                        (dialog, which) -> {
                            final ClipboardManager clipboard = (ClipboardManager)
                                    context.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (clipboard != null) {
                                clipboard.setPrimaryClip(ClipData.newPlainText(
                                        "E5 SHA-256", companion.expectedSha256));
                                Toast.makeText(context, R.string.knowledge_models_e5_sha_copied,
                                        Toast.LENGTH_SHORT).show();
                            }
                        })
                .setNegativeButton(R.string.knowledge_models_back,
                        (dialog, which) -> showE5Companions(entry))
                .show();
    }

    private void confirmE5Qa(final KnowledgeModelFiles.Entry entry) {
        try {
            // Lightweight manifest lookup only. Hashing and native loading are
            // deferred to the user-approved background task.
            packages.e5Tokenizer(entry);
        } catch (IOException invalid) {
            new AlertDialog.Builder(context)
                    .setMessage(R.string.knowledge_models_e5_qa_missing)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.knowledge_models_e5_qa)
                .setMessage(R.string.knowledge_models_e5_qa_warning)
                .setPositiveButton(R.string.knowledge_models_e5_qa,
                        (dialog, which) -> e5QaRunner.run(entry))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmE5Graph(final KnowledgeModelFiles.Entry entry) {
        try {
            // The synchronous UI path only checks the installed package identity.
            // The user-approved background action rehashes every byte.
            packages.e5Tokenizer(entry);
        } catch (IOException invalid) {
            new AlertDialog.Builder(context)
                    .setMessage(R.string.knowledge_models_e5_qa_missing)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.knowledge_models_e5_graph)
                .setMessage(R.string.knowledge_models_e5_graph_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_models_e5_graph,
                        (dialog, which) -> e5GraphInspector.inspect(entry))
                .show();
    }

    private void confirmOnnxSmoke() {
        new AlertDialog.Builder(context)
                .setTitle(R.string.knowledge_models_onnx_smoke)
                .setMessage(R.string.knowledge_models_onnx_smoke_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_models_onnx_smoke,
                        (dialog, which) -> smokeTester.run())
                .show();
    }

    private void confirmOnnxSession(final KnowledgeModelFiles.Entry entry) {
        try {
            if (packages.installed(entry) == null) {
                new AlertDialog.Builder(context)
                        .setMessage(R.string.knowledge_models_onnx_session_requires_package)
                        .setPositiveButton(android.R.string.ok, null).show();
                return;
            }
        } catch (IOException invalid) {
            new AlertDialog.Builder(context)
                    .setMessage(R.string.knowledge_models_onnx_session_requires_package)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.knowledge_models_onnx_session)
                .setMessage(context.getString(R.string.knowledge_models_onnx_session_warning,
                        entry.name, formatSize(entry.bytes)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_models_onnx_session,
                        (dialog, which) -> sessionInspector.inspect(entry))
                .show();
    }

    private void confirmPrepareOnnx(final KnowledgeModelFiles.Entry entry) {
        try {
            final KnowledgeModelPackageManifest manifest = packages.installed(entry);
            if (manifest == null) throw new IOException("No installed package");
            long bytes = entry.bytes;
            for (final KnowledgeModelPackageManifest.Component part : manifest.components) {
                if (part.kind == KnowledgeModelPackageManifest.Kind.WEIGHTS) continue;
                bytes = Math.addExact(bytes, part.bytes);
            }
            final String required = formatSize(bytes);
            final String available = formatSize(context.getCacheDir().getUsableSpace());
            new AlertDialog.Builder(context)
                    .setTitle(R.string.knowledge_models_onnx_prepare)
                    .setMessage(context.getString(R.string.knowledge_models_onnx_prepare_warning,
                            required, available))
                    .setNegativeButton(android.R.string.cancel, (dialog, which) ->
                            showPackageActions(entry))
                    .setPositiveButton(R.string.knowledge_models_onnx_prepare,
                            (dialog, which) -> preparer.prepare(entry))
                    .show();
        } catch (IOException | ArithmeticException invalid) {
            new AlertDialog.Builder(context)
                    .setMessage(R.string.knowledge_models_package_invalid)
                    .setPositiveButton(android.R.string.ok, null).show();
        }
    }

    private void confirmRemovePackage(final KnowledgeModelFiles.Entry entry) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.knowledge_models_package_remove)
                .setMessage(R.string.knowledge_models_package_remove_warning)
                .setNegativeButton(android.R.string.cancel, (dialog, which) ->
                        showPackageActions(entry))
                .setPositiveButton(R.string.knowledge_models_package_remove, (dialog, which) -> {
                    try {
                        packages.forget(entry);
                        showDetails(entry);
                    } catch (IOException invalid) {
                        new AlertDialog.Builder(context)
                                .setMessage(R.string.knowledge_models_package_invalid)
                                .setPositiveButton(android.R.string.ok, null).show();
                    }
                }).show();
    }

    private String packageRequirementName(final KnowledgeModelPackageContract.Requirement item) {
        switch (item) {
            case PINNED_REVISION: return context.getString(R.string.knowledge_models_req_revision);
            case LICENSE_REVIEW: return context.getString(R.string.knowledge_models_req_license);
            case TOKENIZER: return context.getString(R.string.knowledge_models_req_tokenizer);
            case MODEL_CONFIG: return context.getString(R.string.knowledge_models_req_config);
            case CHAT_TEMPLATE: return context.getString(R.string.knowledge_models_req_chat);
            case EMBEDDING_POOLING: return context.getString(R.string.knowledge_models_req_pooling);
            case RERANKER_SCORE: return context.getString(R.string.knowledge_models_req_reranker);
            case FEATURE_LABEL_SCHEMA: return context.getString(R.string.knowledge_models_req_features);
            case VALIDATED_ANDROID_BACKEND: return context.getString(R.string.knowledge_models_req_backend);
            case UNSUPPORTED_ROLE_FORMAT: return context.getString(R.string.knowledge_models_req_format);
            default: throw new IllegalArgumentException("Unknown package requirement");
        }
    }

    private void confirmDelete(final KnowledgeModelFiles.Entry entry) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.knowledge_models_delete)
                .setMessage(R.string.knowledge_models_delete_warning)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> showDetails(entry))
                .setPositiveButton(R.string.knowledge_models_delete, (dialog, which) -> {
                    try {
                        if (files.delete(entry)
                                && entry.sha256.equals(preferences.getString(entry.role.name(), ""))) {
                            preferences.edit().remove(entry.role.name()).apply();
                        }
                        showRole(entry.role);
                    } catch (IOException error) {
                        new AlertDialog.Builder(context)
                                .setMessage(R.string.knowledge_models_delete_failed)
                                .setPositiveButton(android.R.string.ok, null).show();
                    }
                })
                .show();
    }

    private void showCatalog(final KnowledgeModelPolicy.Role role) {
        final List<KnowledgeModelComparison.Row> compared;
        final List<KnowledgeModelCatalog.Artifact> curated = KnowledgeModelCatalog.forRole(role);
        final long freeBytes = context.getFilesDir().getUsableSpace();
        final long availableRam = availableRamBytes();
        try {
            compared = KnowledgeModelComparison.forRole(role, files.installed(),
                    preferences.getString(role.name(), ""), freeBytes, availableRam);
        } catch (IOException unavailable) {
            new AlertDialog.Builder(context).setMessage(R.string.knowledge_models_store_failed)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        final List<KnowledgeModelPolicy.Candidate> candidates =
                KnowledgeModelPolicy.candidates(role);
        final int compareOffset = compared.isEmpty() ? 0 : 1;
        final String[] labels = new String[compareOffset + curated.size() + candidates.size()];
        if (compareOffset == 1) labels[0] = context.getString(R.string.knowledge_models_compare);
        for (int i = 0; i < curated.size(); i++) {
            final KnowledgeModelComparison.Row row = compared.get(i);
            labels[compareOffset + i] = (row.installed
                    ? context.getString(R.string.knowledge_models_catalog_installed_prefix) + " " : "")
                    + context.getString(R.string.knowledge_models_catalog_file_label,
                    row.artifact.title, row.artifact.approximateMegabytes);
        }
        for (int i = 0; i < candidates.size(); i++) {
            final KnowledgeModelPolicy.Candidate candidate = candidates.get(i);
            labels[compareOffset + curated.size() + i] = candidate.label + " · " + candidate.note;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.knowledge_models_catalog)
                .setMessage(R.string.knowledge_models_catalog_info)
                .setItems(labels, (dialog, which) -> {
                    if (compareOffset == 1 && which == 0) {
                        showComparison(role, compared, freeBytes, availableRam);
                        return;
                    }
                    final int index = which - compareOffset;
                    if (index < curated.size()) {
                        showArtifact(compared.get(index));
                        return;
                    }
                    final String url = candidates.get(index - curated.size()).url;
                    if (!url.isEmpty()) {
                        try {
                            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        } catch (RuntimeException unavailable) {
                            new AlertDialog.Builder(context)
                                    .setMessage(R.string.knowledge_models_no_browser)
                                    .setPositiveButton(android.R.string.ok, null).show();
                        }
                    } else {
                        new AlertDialog.Builder(context)
                                .setMessage(R.string.knowledge_models_personalizer_pending)
                                .setPositiveButton(android.R.string.ok, null).show();
                    }
                })
                .setNegativeButton(R.string.knowledge_models_back, (dialog, which) -> showRole(role))
                .show();
    }

    private long availableRamBytes() {
        final ActivityManager.MemoryInfo memory = memorySnapshot();
        return memory == null ? 0 : Math.max(0, memory.availMem);
    }

    private ActivityManager.MemoryInfo memorySnapshot() {
        try {
            final ActivityManager manager = (ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return null;
            final ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(memory);
            return memory;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private String storageComparison(final KnowledgeModelComparison.Storage status) {
        switch (status) {
            case INSTALLED: return context.getString(R.string.knowledge_models_compare_installed);
            case ESTIMATED_FITS: return context.getString(R.string.knowledge_models_compare_fits);
            case MAY_NOT_FIT: return context.getString(R.string.knowledge_models_compare_low);
            case UNKNOWN: return context.getString(R.string.knowledge_models_compare_unknown);
            default: throw new IllegalArgumentException("Unknown model storage comparison");
        }
    }

    private String ramComparison(final KnowledgeModelComparison.Row row) {
        switch (row.ram) {
            case WEIGHT_ABOVE_SNAPSHOT:
                return context.getString(R.string.knowledge_models_compare_ram_over,
                        row.weightPercentOfAvailableRam);
            case WEIGHT_WITHIN_SNAPSHOT:
                return context.getString(R.string.knowledge_models_compare_ram_within,
                        row.weightPercentOfAvailableRam);
            case UNKNOWN: return context.getString(R.string.knowledge_models_compare_ram_unknown);
            default: throw new IllegalArgumentException("Unknown model RAM comparison");
        }
    }

    private void showComparison(final KnowledgeModelPolicy.Role role,
                                final List<KnowledgeModelComparison.Row> artifacts,
                                final long freeBytes, final long availableRam) {
        final StringBuilder report = new StringBuilder(context.getString(
                R.string.knowledge_models_compare_intro,
                freeBytes <= 0 ? context.getString(R.string.knowledge_models_space_unknown)
                        : formatSize(freeBytes),
                availableRam <= 0 ? context.getString(R.string.knowledge_models_space_unknown)
                        : formatSize(availableRam)));
        for (final KnowledgeModelComparison.Row row : artifacts) {
            report.append("\n\n").append(context.getString(R.string.knowledge_models_compare_row,
                    row.title
                            + (row.preferred ? context.getString(R.string.knowledge_models_compare_preferred) : ""),
                    formatSize(row.weightBytes), storageComparison(row.storage), ramComparison(row)));
        }
        new AlertDialog.Builder(context)
                .setTitle(roleName(role))
                .setMessage(report.toString())
                .setPositiveButton(R.string.knowledge_models_back,
                        (dialog, which) -> showCatalog(role))
                .show();
    }

    private void showArtifact(final KnowledgeModelComparison.Row row) {
        final KnowledgeModelCatalog.Artifact artifact = row.artifact;
        final long freeBytes = context.getFilesDir().getUsableSpace();
        final boolean lowSpace = row.storage == KnowledgeModelComparison.Storage.MAY_NOT_FIT;
        final String message = context.getString(R.string.knowledge_models_catalog_artifact_details,
                artifact.id, artifact.approximateMegabytes, artifact.license,
                artifact.sourcePage, artifact.expectedSha256,
                freeBytes <= 0 ? context.getString(R.string.knowledge_models_space_unknown)
                        : formatSize(freeBytes))
                + "\n\n" + context.getString(R.string.knowledge_models_compare_row,
                        artifact.title, formatSize(row.weightBytes),
                        storageComparison(row.storage), ramComparison(row))
                + (lowSpace ? "\n\n" + context.getString(R.string.knowledge_models_space_warning) : "")
                + "\n\n" + context.getString(R.string.knowledge_models_imported_not_runnable);
        final AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(artifact.title)
                .setMessage(message);
        if (row.installedEntry != null) {
            builder.setPositiveButton(R.string.knowledge_models_compare_manage_installed,
                    (choice, which) -> showDetails(row.installedEntry));
        } else {
            builder.setPositiveButton(R.string.knowledge_models_download_confirm_action,
                    (choice, which) -> beginTrustedDownload(artifact.role,
                            artifact.downloadUrl, artifact.expectedSha256, artifact.filename));
        }
        builder
                .setNeutralButton(R.string.knowledge_models_catalog_open_source,
                        (dialog, which) -> {
                            try {
                                context.startActivity(new Intent(Intent.ACTION_VIEW,
                                        Uri.parse(artifact.sourcePage)));
                            } catch (RuntimeException unavailable) {
                                new AlertDialog.Builder(context)
                                        .setMessage(R.string.knowledge_models_no_browser)
                                        .setPositiveButton(android.R.string.ok, null).show();
                            }
                        })
                .setNegativeButton(R.string.knowledge_models_back,
                        (dialog, which) -> showCatalog(artifact.role))
                .show();
    }

    static String formatSize(final long bytes) {
        return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
    }
}
