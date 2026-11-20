/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.gsantner.markor.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** Human-authored document provenance class, not a statement of reliability. */
final class KnowledgeSourceAssessmentDialog {
    private static final String[] KINDS = {"primary_record", "secondary_summary",
            "republication", "unclassified"};
    private static final int[] KIND_LABELS = {
            R.string.knowledge_source_assessment_primary,
            R.string.knowledge_source_assessment_secondary,
            R.string.knowledge_source_assessment_republication,
            R.string.knowledge_source_assessment_unclassified};

    private final KnowledgeHubActivity activity;
    private final String workspace;
    private final File notebook;
    private final ExecutorService worker;
    private final Consumer<String> openNote;
    private final Runnable onChanged;

    KnowledgeSourceAssessmentDialog(final KnowledgeHubActivity activity, final String workspace,
                                    final File notebook, final ExecutorService worker,
                                    final Consumer<String> openNote, final Runnable onChanged) {
        this.activity = activity;
        this.workspace = workspace;
        this.notebook = notebook;
        this.worker = worker;
        this.openNote = openNote;
        this.onChanged = onChanged;
    }

    private String s(final int id) {
        return activity.getString(id);
    }

    private String kindLabel(final String value) {
        for (int i = 0; i < KINDS.length; i++) {
            if (KINDS[i].equals(value)) return s(KIND_LABELS[i]);
        }
        return s(R.string.knowledge_source_assessment_unclassified);
    }

    private interface Task {
        JSONObject run() throws Exception;
    }

    private void async(final Task task, final Consumer<JSONObject> done) {
        worker.submit(() -> {
            try {
                final JSONObject result = task.run();
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) done.accept(result);
                });
            } catch (Exception ignored) {
                // No private excerpts, source paths or journal data in logs.
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        new MaterialAlertDialogBuilder(activity)
                                .setTitle(R.string.knowledge_source_assessment_error)
                                .setMessage(R.string.knowledge_source_assessment_error_detail)
                                .setPositiveButton(android.R.string.ok, null).show();
                    }
                });
            }
        });
    }

    void show() {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.knowledge_source_assessment_title)
                .setMessage(R.string.knowledge_source_assessment_explanation)
                .setItems(new String[]{s(R.string.knowledge_source_assessment_new),
                        s(R.string.knowledge_source_assessment_history)}, (d, index) -> {
                    if (index == 0) chooseKind();
                    else history();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseKind() {
        if (notebook == null) {
            Toast.makeText(activity, R.string.knowledge_no_folder, Toast.LENGTH_LONG).show();
            return;
        }
        final String[] labels = new String[KINDS.length];
        for (int i = 0; i < KINDS.length; i++) labels[i] = s(KIND_LABELS[i]);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.knowledge_source_assessment_choose_kind)
                .setItems(labels, (d, which) -> showFields(KINDS[which]))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void showFields(final String kind) {
        final LinearLayout fields = new LinearLayout(activity);
        fields.setOrientation(LinearLayout.VERTICAL);
        final int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        fields.setPadding(pad, pad / 2, pad, pad / 2);
        final EditText path = new EditText(activity);
        path.setSingleLine(true);
        path.setHint(R.string.knowledge_source_assessment_path);
        fields.addView(path);
        final EditText reason = new EditText(activity);
        reason.setSingleLine(false);
        reason.setMaxLines(4);
        reason.setHint(R.string.knowledge_source_assessment_reason);
        fields.addView(reason);
        final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(kindLabel(kind))
                .setMessage(R.string.knowledge_source_assessment_exact_path)
                .setView(fields)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_source_assessment_preview, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    final String file = path.getText().toString().trim();
                    final String explanation = reason.getText().toString().trim();
                    if (file.isEmpty() || explanation.isEmpty() || explanation.length() > 500) {
                        Toast.makeText(activity, R.string.knowledge_source_assessment_invalid,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog.dismiss();
                    async(() -> KnowledgePythonBridge.previewSourceAssessment(activity, workspace,
                            notebook.getAbsolutePath(), file, kind, explanation),
                            this::showPreview);
                }));
        dialog.show();
    }

    private ScrollView detailView(final String message) {
        final ScrollView scroll = new ScrollView(activity);
        final TextView text = new TextView(activity);
        final int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);
        text.setText(message);
        text.setTextIsSelectable(true);
        scroll.addView(text);
        return scroll;
    }

    private String sourceDetail(final JSONObject source) {
        return source.optString("path") + " · " + source.optString("locator")
                + "\n" + source.optString("excerpt") + "\nSHA-256: "
                + source.optString("source_sha256");
    }

    private void showPreview(final JSONObject preview) {
        final JSONObject source = preview.optJSONObject("source");
        if (source == null) return;
        final String message = s(R.string.knowledge_source_assessment_preview_warning)
                + "\n\n" + kindLabel(preview.optString("kind"))
                + "\n" + sourceDetail(source)
                + "\n\n" + s(R.string.knowledge_source_assessment_reason) + ": "
                + preview.optString("reason")
                + "\n" + s(R.string.knowledge_entity_journal) + ": "
                + preview.optString("journal_head", "∅");
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.knowledge_source_assessment_preview)
                .setView(detailView(message))
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.knowledge_entity_view_source,
                        (d, w) -> openNote.accept(source.optString("path")))
                .setPositiveButton(R.string.knowledge_source_assessment_confirm,
                        (d, w) -> async(() -> KnowledgePythonBridge.confirmSourceAssessment(
                                activity, workspace, notebook.getAbsolutePath(),
                                preview.getString("path"), preview.getString("kind"),
                                preview.getString("reason"), preview.getString("preview_token"),
                                preview.isNull("journal_head") ? null
                                        : preview.getString("journal_head")),
                                response -> afterWrite(response, "source_assess")))
                .show();
    }

    private void history() {
        async(() -> KnowledgePythonBridge.sourceAssessmentDecisions(activity, workspace), data -> {
            final JSONArray active = data.optJSONArray("active");
            final JSONArray recent = data.optJSONArray("recent");
            final int count = active == null ? 0 : active.length();
            final int history = recent == null ? 0 : recent.length();
            final String[] labels = new String[count + history];
            for (int i = 0; i < count; i++) {
                final JSONObject record = active.optJSONObject(i);
                if (record != null) labels[i] = kindLabel(record.optString("kind"))
                        + " · " + record.optString("path");
            }
            for (int i = 0; i < history; i++) {
                final JSONObject record = recent.optJSONObject(i);
                if (record != null) labels[count + i] = record.optString("time")
                        + " · " + record.optString("op") + " · " + record.optString("path");
            }
            final AlertDialog.Builder dialog = new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.knowledge_source_assessment_history)
                    .setNegativeButton(android.R.string.cancel, null);
            if (labels.length == 0) dialog.setMessage(R.string.knowledge_source_assessment_none);
            else dialog.setItems(labels, (d, i) -> {
                if (i < count) {
                    final JSONObject record = active.optJSONObject(i);
                    if (record != null) showActive(record);
                } else {
                    final JSONObject event = recent.optJSONObject(i - count);
                    if (event != null) new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.knowledge_source_assessment_history)
                            .setMessage(event.optString("op") + " · " + event.optString("path")
                                    + "\n" + event.optString("time")
                                    + "\n" + event.optString("journal_id"))
                            .setPositiveButton(android.R.string.ok, null).show();
                }
            });
            dialog.show();
        });
    }

    private void showActive(final JSONObject record) {
        final JSONObject source = record.optJSONObject("source");
        final String message = kindLabel(record.optString("kind"))
                + "\n" + record.optString("path")
                + "\n" + record.optString("journal_id")
                + "\n" + record.optString("time")
                + "\n" + record.optString("reason")
                + (source == null ? "" : "\n\n" + sourceDetail(source))
                + "\n\n" + s(R.string.knowledge_source_lineage_historical_warning)
                + "\n\n" + s(R.string.knowledge_source_assessment_revoke_warning);
        final AlertDialog.Builder dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.knowledge_source_assessment_history)
                .setView(detailView(message))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_source_assessment_revoke,
                        (d, w) -> async(() -> KnowledgePythonBridge.previewSourceUnassessment(
                                        activity, workspace, record.getString("journal_id")),
                                this::confirmRevocation));
        if (source != null) dialog.setNeutralButton(R.string.knowledge_entity_view_source,
                (d, w) -> openNote.accept(source.optString("path")));
        dialog.show();
    }

    private void confirmRevocation(final JSONObject preview) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.knowledge_source_assessment_revoke)
                .setMessage(preview.optString("path") + "\n"
                        + preview.optString("decision_id") + "\n\n"
                        + s(R.string.knowledge_source_assessment_revoke_warning))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_source_assessment_revoke,
                        (d, w) -> async(() -> KnowledgePythonBridge.confirmSourceUnassessment(
                                        activity, workspace, preview.getString("decision_id"),
                                        preview.getString("preview_token"),
                                        preview.isNull("journal_head") ? null
                                                : preview.getString("journal_head")),
                                response -> afterWrite(response, "source_unassess")))
                .show();
    }

    private void afterWrite(final JSONObject response, final String expected) {
        final JSONArray ids = response.optJSONArray("journal_ids");
        if (!expected.equals(response.optString("decision"))
                || ids == null || ids.length() != 1) {
            Toast.makeText(activity, R.string.knowledge_source_assessment_error,
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(activity, R.string.knowledge_source_assessment_saved,
                Toast.LENGTH_LONG).show();
        onChanged.run();
        history();
    }
}
