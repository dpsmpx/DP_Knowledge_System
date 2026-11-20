/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import android.app.Activity;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.gsantner.markor.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** Manual dated-pair annotations; never auto-approve facts or rewrite notes. */
final class KnowledgeVersionReviewDialog {
    private static final String[] CHOICES = {"unresolved", "possibly_changed",
            "different_contexts", "consistent_readings"};
    private static final int[] LABELS = {
            R.string.knowledge_version_unresolved, R.string.knowledge_version_possible_change,
            R.string.knowledge_version_different_contexts, R.string.knowledge_version_consistent};

    private final Activity activity;
    private final String workspace;
    private final File notebook;
    private final ExecutorService worker;
    private final String sourcePath;
    private final JSONObject relation;
    private final Runnable onChanged;

    KnowledgeVersionReviewDialog(final Activity activity, final String workspace,
                                 final File notebook, final ExecutorService worker,
                                 final String sourcePath, final JSONObject relation,
                                 final Runnable onChanged) {
        this.activity = activity;
        this.workspace = workspace;
        this.notebook = notebook;
        this.worker = worker;
        this.sourcePath = sourcePath;
        this.relation = relation;
        this.onChanged = onChanged;
    }

    private interface Task {
        JSONObject run() throws Exception;
    }

    private void async(final Task task, final Consumer<JSONObject> onSuccess) {
        worker.submit(() -> {
            try {
                final JSONObject result = task.run();
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        onSuccess.accept(result);
                    }
                });
            } catch (Exception ignored) {
                // Never put original note text, paths or journal content in logs.
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        new MaterialAlertDialogBuilder(activity)
                                .setTitle(R.string.knowledge_version_error)
                                .setMessage(R.string.knowledge_version_error_detail)
                                .setPositiveButton(android.R.string.ok, null).show();
                    }
                });
            }
        });
    }

    private String label(final String choice) {
        for (int i = 0; i < CHOICES.length; i++) {
            if (CHOICES[i].equals(choice)) return activity.getString(LABELS[i]);
        }
        return activity.getString(R.string.knowledge_version_unresolved);
    }

    void show() {
        if (relation == null) {
            history();
            return;
        }
        final String decisionId = relation.optString("version_review_id", "");
        if (decisionId.isEmpty()) {
            choose();
        } else {
            async(() -> KnowledgePythonBridge.versionReviewDecisions(activity, workspace), data -> {
                final JSONArray active = data.optJSONArray("active");
                if (active != null) for (int i = 0; i < active.length(); i++) {
                    final JSONObject item = active.optJSONObject(i);
                    if (item != null && decisionId.equals(item.optString("journal_id"))) {
                        showActive(item);
                        return;
                    }
                }
                choose(); // Snapshot changed; backend will revalidate on preview.
            });
        }
    }

    private void choose() {
        if (notebook == null || relation == null || sourcePath == null) {
            Toast.makeText(activity, R.string.knowledge_no_folder, Toast.LENGTH_LONG).show();
            return;
        }
        final String[] items = new String[CHOICES.length];
        for (int i = 0; i < CHOICES.length; i++) items[i] = activity.getString(LABELS[i]);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.knowledge_version_title)
                .setMessage(R.string.knowledge_version_scope)
                .setItems(items, (dialog, index) -> enterReason(CHOICES[index]))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void enterReason(final String choice) {
        final EditText reason = new EditText(activity);
        final int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        reason.setPadding(pad, pad / 2, pad, pad / 2);
        reason.setSingleLine(false);
        reason.setMaxLines(4);
        reason.setHint(R.string.knowledge_version_reason);
        final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(label(choice))
                .setView(reason)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_version_preview, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    final String explanation = reason.getText().toString().trim();
                    if (explanation.isEmpty() || explanation.length() > 500) {
                        Toast.makeText(activity, R.string.knowledge_version_invalid,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog.dismiss();
                    final String selected = relation.toString();
                    async(() -> KnowledgePythonBridge.previewVersionReview(activity, workspace,
                            notebook.getAbsolutePath(), sourcePath, selected, choice, explanation),
                            preview -> confirm(preview, selected, choice, explanation));
                }));
        dialog.show();
    }

    private void confirm(final JSONObject preview, final String selected,
                         final String choice, final String reason) {
        final JSONArray sources = preview.optJSONArray("sources");
        final StringBuilder summary = new StringBuilder(activity.getString(
                R.string.knowledge_version_warning));
        if (sources != null) for (int i = 0; i < sources.length(); i++) {
            final JSONObject source = sources.optJSONObject(i);
            if (source == null) continue;
            summary.append("\n\n").append(source.optString("path"))
                    .append(" · ").append(source.optString("locator"))
                    .append("\nSHA-256: ").append(source.optString("source_sha256"))
                    .append("\n").append(source.optString("excerpt"));
        }
        summary.append("\n\n").append(label(choice)).append(" · ").append(reason);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.knowledge_version_confirm)
                .setMessage(summary.toString())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_version_confirm, (dialog, which) ->
                        async(() -> KnowledgePythonBridge.confirmVersionReview(activity,
                                workspace, notebook.getAbsolutePath(), sourcePath, selected,
                                choice, reason, preview.optString("preview_token"),
                                preview.optString("journal_head")), result -> {
                            Toast.makeText(activity, R.string.knowledge_version_recorded,
                                    Toast.LENGTH_LONG).show();
                            if (onChanged != null) onChanged.run();
                        })).show();
    }

    /** Works with notebook disconnected; revocation references exact decision ID. */
    void history() {
        async(() -> KnowledgePythonBridge.versionReviewDecisions(activity, workspace), data -> {
            final JSONArray active = data.optJSONArray("active");
            if (active == null || active.length() == 0) {
                new MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.knowledge_version_history)
                        .setMessage(R.string.knowledge_version_empty)
                        .setPositiveButton(android.R.string.ok, null).show();
                return;
            }
            final String[] labels = new String[active.length()];
            for (int i = 0; i < labels.length; i++) {
                final JSONObject record = active.optJSONObject(i);
                final JSONArray sources = record == null ? null : record.optJSONArray("sources");
                final JSONObject first = sources == null ? null : sources.optJSONObject(0);
                final JSONObject second = sources == null ? null : sources.optJSONObject(1);
                labels[i] = label(record == null ? "" : record.optString("choice"))
                        + " · " + (first == null ? "?" : first.optString("path"))
                        + " ↔ " + (second == null ? "?" : second.optString("path"));
            }
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.knowledge_version_history)
                    .setMessage(R.string.knowledge_version_offline_warning)
                    .setItems(labels, (dialog, index) -> {
                        final JSONObject item = active.optJSONObject(index);
                        if (item != null) showActive(item);
                    }).setNegativeButton(android.R.string.cancel, null).show();
        });
    }

    private void showActive(final JSONObject record) {
        final String decision = record.optString("journal_id");
        final String details = label(record.optString("choice")) + "\n"
                + record.optString("reason") + "\n" + decision + "\n\n"
                + activity.getString(R.string.knowledge_version_offline_warning);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.knowledge_version_history)
                .setMessage(details)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_version_revoke, (dialog, which) ->
                        async(() -> KnowledgePythonBridge.previewVersionUnreview(
                                        activity, workspace, decision),
                                preview -> revoke(preview, decision))).show();
    }

    private void revoke(final JSONObject preview, final String decision) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.knowledge_version_revoke)
                .setMessage(R.string.knowledge_version_revoke_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_version_revoke, (dialog, which) ->
                        async(() -> KnowledgePythonBridge.confirmVersionUnreview(activity,
                                        workspace, decision, preview.optString("preview_token"),
                                        preview.optString("journal_head")), result -> {
                                    Toast.makeText(activity, R.string.knowledge_version_withdrawn,
                                            Toast.LENGTH_LONG).show();
                                    if (onChanged != null) onChanged.run();
                                })).show();
    }
}
