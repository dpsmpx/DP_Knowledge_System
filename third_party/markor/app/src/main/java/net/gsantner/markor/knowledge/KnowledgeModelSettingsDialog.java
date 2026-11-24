/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

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

    private static final String PREFS = "dpks_local_model_choices_v1";
    private final Context context;
    private final KnowledgeModelFiles files;
    private final ImportRequest importer;
    private final SharedPreferences preferences;

    KnowledgeModelSettingsDialog(final Context activity, final KnowledgeModelFiles modelFiles,
                                 final ImportRequest request) {
        context = activity;
        files = modelFiles;
        importer = request;
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
        final String[] labels = new String[entries.size() + 1];
        labels[0] = (selected.isEmpty() ? "✓ " : "")
                + context.getString(R.string.knowledge_models_disabled);
        for (int i = 0; i < entries.size(); i++) {
            final KnowledgeModelFiles.Entry model = entries.get(i);
            labels[i + 1] = (selected.equals(model.sha256) ? "✓ " : "")
                    + model.name + " · " + formatSize(model.bytes);
        }
        new AlertDialog.Builder(context)
                .setTitle(roleName(role))
                .setMessage(R.string.knowledge_models_imported_not_runnable)
                .setItems(labels, (dialog, which) -> {
                    if (which == 0) {
                        preferences.edit().remove(role.name()).apply();
                        showRole(role);
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

    private void showDetails(final KnowledgeModelFiles.Entry entry) {
        final String summary = context.getString(R.string.knowledge_models_details,
                entry.name, formatSize(entry.bytes), entry.bytes, entry.format.name(),
                entry.sha256);
        new AlertDialog.Builder(context)
                .setTitle(roleName(entry.role))
                .setMessage(summary + "\n\n"
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
        final List<KnowledgeModelPolicy.Candidate> candidates =
                KnowledgeModelPolicy.candidates(role);
        final String[] labels = new String[candidates.size()];
        for (int i = 0; i < labels.length; i++) {
            final KnowledgeModelPolicy.Candidate candidate = candidates.get(i);
            labels[i] = candidate.label + " · " + candidate.note;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.knowledge_models_catalog)
                .setMessage(R.string.knowledge_models_catalog_info)
                .setItems(labels, (dialog, which) -> {
                    final String url = candidates.get(which).url;
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

    static String formatSize(final long bytes) {
        return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
    }
}
