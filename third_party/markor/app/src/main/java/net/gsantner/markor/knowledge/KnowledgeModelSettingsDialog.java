/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;

import net.gsantner.markor.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * First MODEL-M1/M2 UI slice: local file shelf and declared model choices.
 * It deliberately does NOT start inference or claim compatibility from a
 * GGUF header/ONNX filename; that requires the later runtime integration.
 */
final class KnowledgeModelSettingsDialog {
    interface ImportRequest { void pick(KnowledgeModelPolicy.Role role); }
    interface DownloadRequest {
        void start(KnowledgeModelPolicy.Role role, String url, String expectedSha256);
    }

    private static final String PREFS = "dpks_local_model_choices_v1";
    private final Context context;
    private final KnowledgeModelFiles files;
    private final ImportRequest importer;
    private final DownloadRequest downloader;
    private final SharedPreferences preferences;

    KnowledgeModelSettingsDialog(final Context activity, final KnowledgeModelFiles modelFiles,
                                 final ImportRequest request, final DownloadRequest download) {
        context = activity;
        files = modelFiles;
        importer = request;
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
        new AlertDialog.Builder(context)
                .setTitle(R.string.knowledge_models_title)
                .setMessage(R.string.knowledge_models_four_optional)
                .setItems(titles, (dialog, which) -> showRole(roles[which]))
                .setNegativeButton(android.R.string.cancel, null)
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
        final String[] labels = new String[entries.size() + 2];
        labels[0] = (selected.isEmpty() ? "✓ " : "")
                + context.getString(R.string.knowledge_models_disabled);
        for (int i = 0; i < entries.size(); i++) {
            final KnowledgeModelFiles.Entry model = entries.get(i);
            labels[i + 1] = (selected.equals(model.sha256) ? "✓ " : "")
                    + model.name + " · " + formatSize(model.bytes);
        }
        labels[labels.length - 1] = context.getString(R.string.knowledge_models_download_url);
        new AlertDialog.Builder(context)
                .setTitle(roleName(role))
                .setMessage(R.string.knowledge_models_imported_not_runnable)
                .setItems(labels, (dialog, which) -> {
                    if (which == 0) {
                        preferences.edit().remove(role.name()).apply();
                        showRole(role);
                    } else if (which == labels.length - 1) {
                        showDownloadPrompt(role);
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
        final KnowledgeModelPackageContract.Report packageReport =
                KnowledgeModelPackageContract.inspect(entry);
        final StringBuilder missing = new StringBuilder();
        for (final KnowledgeModelPackageContract.Requirement requirement : packageReport.missing) {
            missing.append("\n• ").append(packageRequirementName(requirement));
        }
        final String packageSummary = context.getString(R.string.knowledge_models_package_report,
                packageReport.schemaVersion, packageReport.sourceId,
                packageReport.license, packageReport.engineCandidate, missing.toString());
        new AlertDialog.Builder(context)
                .setTitle(roleName(entry.role))
                .setMessage(summary + provenance + "\n\n" + packageSummary + "\n\n"
                        + context.getString(R.string.knowledge_models_imported_not_runnable))
                .setPositiveButton(R.string.knowledge_models_select, (dialog, which) -> {
                    preferences.edit().putString(entry.role.name(), entry.sha256).apply();
                    showRole(entry.role);
                })
                .setNeutralButton(R.string.knowledge_models_delete, (dialog, which) ->
                        confirmDelete(entry))
                .setNegativeButton(R.string.knowledge_models_back, (dialog, which) ->
                        showRole(entry.role))
                .show();
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
        final List<KnowledgeModelCatalog.Artifact> artifacts =
                KnowledgeModelCatalog.forRole(role);
        final List<KnowledgeModelPolicy.Candidate> candidates =
                KnowledgeModelPolicy.candidates(role);
        final String[] labels = new String[artifacts.size() + candidates.size()];
        for (int i = 0; i < artifacts.size(); i++) {
            final KnowledgeModelCatalog.Artifact artifact = artifacts.get(i);
            labels[i] = context.getString(R.string.knowledge_models_catalog_file_label,
                    artifact.title, artifact.approximateMegabytes);
        }
        for (int i = 0; i < candidates.size(); i++) {
            final KnowledgeModelPolicy.Candidate candidate = candidates.get(i);
            labels[artifacts.size() + i] = candidate.label + " · " + candidate.note;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.knowledge_models_catalog)
                .setMessage(R.string.knowledge_models_catalog_info)
                .setItems(labels, (dialog, which) -> {
                    if (which < artifacts.size()) {
                        showArtifact(artifacts.get(which));
                        return;
                    }
                    final String url = candidates.get(which - artifacts.size()).url;
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

    private void showArtifact(final KnowledgeModelCatalog.Artifact artifact) {
        final long freeBytes = context.getFilesDir().getUsableSpace();
        final boolean lowSpace = freeBytes > 0
                && freeBytes < (artifact.approximateMegabytes + 16L) * 1000000L;
        final String message = context.getString(R.string.knowledge_models_catalog_artifact_details,
                artifact.id, artifact.approximateMegabytes, artifact.license,
                artifact.sourcePage, artifact.expectedSha256,
                freeBytes <= 0 ? context.getString(R.string.knowledge_models_space_unknown)
                        : formatSize(freeBytes))
                + (lowSpace ? "\n\n" + context.getString(R.string.knowledge_models_space_warning) : "")
                + "\n\n" + context.getString(R.string.knowledge_models_imported_not_runnable);
        new AlertDialog.Builder(context)
                .setTitle(artifact.title)
                .setMessage(message)
                .setPositiveButton(R.string.knowledge_models_download_confirm_action,
                        (dialog, which) -> beginTrustedDownload(artifact.role,
                                artifact.downloadUrl, artifact.expectedSha256,
                                artifact.filename))
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
