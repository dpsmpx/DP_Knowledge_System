/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import android.text.InputType;
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

/** A two-step owner decision that annotates provenance, never factual authority. */
final class KnowledgeSourceReviewDialog {
    private final KnowledgeHubActivity activity;
    private final String workspace;
    private final File notebook;
    private final ExecutorService worker;
    private final Consumer<String> openNote;
    private final Runnable onChanged;

    KnowledgeSourceReviewDialog(final KnowledgeHubActivity activity, final String workspace,
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
                // Never log private note paths, source excerpts or journal data.
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        new MaterialAlertDialogBuilder(activity)
                                .setTitle(R.string.knowledge_source_lineage_error)
                                .setMessage(R.string.knowledge_source_lineage_error_detail)
                                .setPositiveButton(android.R.string.ok, null).show();
                    }
                });
            }
        });
    }

    void show() {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.knowledge_source_lineage_title)
                .setMessage(R.string.knowledge_source_lineage_explanation)
                .setItems(new String[]{s(R.string.knowledge_source_lineage_new),
                        s(R.string.knowledge_source_lineage_history)}, (d, index) -> {
                    if (index == 0) showNew("", "");
                    else history();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    void showNew(final String firstPath, final String secondPath) {
        if (notebook == null) {
            Toast.makeText(activity, R.string.knowledge_no_folder, Toast.LENGTH_LONG).show();
            return;
        }
        final LinearLayout fields = new LinearLayout(activity);
        fields.setOrientation(LinearLayout.VERTICAL);
        final int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        fields.setPadding(pad, pad / 2, pad, pad / 2);
        final EditText first = new EditText(activity);
        first.setHint(R.string.knowledge_source_lineage_first);
        first.setSingleLine(true);
        first.setInputType(InputType.TYPE_CLASS_TEXT);
        first.setText(firstPath);
        fields.addView(first);
        final EditText second = new EditText(activity);
        second.setHint(R.string.knowledge_source_lineage_second);
        second.setSingleLine(true);
        second.setInputType(InputType.TYPE_CLASS_TEXT);
        second.setText(secondPath);
        fields.addView(second);
        final EditText reason = new EditText(activity);
        reason.setHint(R.string.knowledge_source_lineage_reason);
        reason.setSingleLine(false);
        reason.setMaxLines(4);
        fields.addView(reason);
        final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.knowledge_source_lineage_new)
                .setMessage(R.string.knowledge_source_lineage_exact_paths)
                .setView(fields)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_source_lineage_preview, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    final String a = first.getText().toString().trim();
                    final String b = second.getText().toString().trim();
                    final String why = reason.getText().toString().trim();
                    if (a.isEmpty() || b.isEmpty() || a.equals(b) || why.isEmpty()
                            || why.length() > 500) {
                        Toast.makeText(activity, R.string.knowledge_source_lineage_invalid,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog.dismiss();
                    async(() -> KnowledgePythonBridge.previewSourceLink(activity, workspace,
                            notebook.getAbsolutePath(), a, b, why), this::showPreview);
                }));
        dialog.show();
    }

    private String detail(final JSONObject source) {
        return source.optString("path") + " · " + source.optString("locator")
                + "\n" + source.optString("excerpt") + "\nSHA-256: "
                + source.optString("source_sha256");
    }

    private ScrollView scrollDetail(final String message) {
        final ScrollView scroller = new ScrollView(activity);
        final TextView text = new TextView(activity);
        final int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);
        text.setText(message);
        text.setTextIsSelectable(true);
        scroller.addView(text);
        return scroller;
    }

    private void showPreview(final JSONObject preview) {
        final JSONArray sources = preview.optJSONArray("sources");
        if (sources == null || sources.length() != 2) return;
        final JSONObject first = sources.optJSONObject(0);
        final JSONObject second = sources.optJSONObject(1);
        if (first == null || second == null) return;
        final String message = s(R.string.knowledge_source_lineage_preview_explanation)
                + "\n\n" + detail(first) + "\n\n" + detail(second)
                + "\n\n" + s(R.string.knowledge_source_lineage_reason) + ": "
                + preview.optString("reason") + "\n\n"
                + s(R.string.knowledge_source_lineage_confirm_warning) + "\n"
                + s(R.string.knowledge_entity_journal) + ": "
                + preview.optString("journal_head", "∅");
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.knowledge_source_lineage_preview)
                .setView(scrollDetail(message))
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.knowledge_source_lineage_open_source,
                        (d, w) -> chooseSource(sources))
                .setPositiveButton(R.string.knowledge_source_lineage_confirm,
                        (d, w) -> async(() -> KnowledgePythonBridge.confirmSourceLink(
                                activity, workspace, notebook.getAbsolutePath(),
                                first.getString("path"), second.getString("path"),
                                preview.getString("reason"), preview.getString("preview_token"),
                                preview.isNull("journal_head") ? null
                                        : preview.getString("journal_head")),
                                response -> afterWrite(response, "source_link")))
                .show();
    }

    private void chooseSource(final JSONArray sources) {
        final String[] labels = new String[sources.length()];
        for (int i = 0; i < labels.length; i++) {
            final JSONObject item = sources.optJSONObject(i);
            labels[i] = item == null ? "?" : item.optString("path");
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.knowledge_source_lineage_open_source)
                .setItems(labels, (d, i) -> {
                    final JSONObject source = sources.optJSONObject(i);
                    if (source != null) openNote.accept(source.optString("path"));
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void history() {
        async(() -> KnowledgePythonBridge.sourceLineageDecisions(activity, workspace), result -> {
            final JSONArray active = result.optJSONArray("active");
            final JSONArray recent = result.optJSONArray("recent");
            final int count = active == null ? 0 : active.length();
            final int history = recent == null ? 0 : recent.length();
            final String[] labels = new String[count + history];
            for (int i = 0; i < count; i++) {
                final JSONObject record = active.optJSONObject(i);
                if (record != null) labels[i] = s(R.string.knowledge_source_lineage_active)
                        + " · " + pair(record.optJSONArray("paths"));
            }
            for (int i = 0; i < history; i++) {
                final JSONObject record = recent.optJSONObject(i);
                if (record != null) labels[count + i] = record.optString("time")
                        + " · " + record.optString("op") + " · "
                        + pair(record.optJSONArray("paths"));
            }
            final AlertDialog.Builder dialog = new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.knowledge_source_lineage_history)
                    .setNegativeButton(android.R.string.cancel, null);
            if (labels.length == 0) dialog.setMessage(R.string.knowledge_source_lineage_none);
            else dialog.setItems(labels, (d, i) -> {
                if (i < count) {
                    final JSONObject record = active.optJSONObject(i);
                    if (record != null) showActive(record);
                } else {
                    final JSONObject record = recent.optJSONObject(i - count);
                    if (record != null) new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.knowledge_source_lineage_history)
                            .setMessage(record.optString("op") + " · "
                                    + pair(record.optJSONArray("paths")) + "\n"
                                    + record.optString("time") + "\n"
                                    + record.optString("journal_id"))
                            .setPositiveButton(android.R.string.ok, null).show();
                }
            });
            dialog.show();
        });
    }

    private String pair(final JSONArray paths) {
        return paths == null ? "?" : paths.optString(0) + " ↔ " + paths.optString(1);
    }

    private void showActive(final JSONObject record) {
        final JSONArray sources = record.optJSONArray("sources");
        final StringBuilder message = new StringBuilder(pair(record.optJSONArray("paths")))
                .append("\n").append(record.optString("journal_id"))
                .append("\n").append(record.optString("time"))
                .append("\n").append(record.optString("reason"));
        if (sources != null) for (int i = 0; i < sources.length(); i++) {
            final JSONObject source = sources.optJSONObject(i);
            if (source != null) message.append("\n\n").append(detail(source));
        }
        message.append("\n\n").append(s(R.string.knowledge_source_lineage_historical_warning));
        message.append("\n\n").append(s(R.string.knowledge_source_lineage_revoke_warning));
        final AlertDialog.Builder dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.knowledge_source_lineage_active)
                .setView(scrollDetail(message.toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_source_lineage_revoke, (d, w) ->
                        async(() -> KnowledgePythonBridge.previewSourceUnlink(
                                        activity, workspace, record.getString("journal_id")),
                                this::confirmRevocation));
        if (sources != null && sources.length() > 0) {
            dialog.setNeutralButton(R.string.knowledge_source_lineage_open_source,
                    (d, w) -> chooseSource(sources));
        }
        dialog.show();
    }

    private void confirmRevocation(final JSONObject preview) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.knowledge_source_lineage_revoke)
                .setMessage(pair(preview.optJSONArray("paths")) + "\n"
                        + preview.optString("decision_id") + "\n\n"
                        + s(R.string.knowledge_source_lineage_revoke_warning))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_source_lineage_revoke, (d, w) ->
                        async(() -> KnowledgePythonBridge.confirmSourceUnlink(activity,
                                        workspace, preview.getString("decision_id"),
                                        preview.getString("preview_token"),
                                        preview.isNull("journal_head") ? null
                                                : preview.getString("journal_head")),
                                response -> afterWrite(response, "source_unlink")))
                .show();
    }

    private void afterWrite(final JSONObject response, final String expectedOp) {
        final JSONArray ids = response.optJSONArray("journal_ids");
        if (!expectedOp.equals(response.optString("decision"))
                || ids == null || ids.length() != 1) {
            Toast.makeText(activity, R.string.knowledge_source_lineage_error,
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(activity, R.string.knowledge_source_lineage_saved,
                Toast.LENGTH_LONG).show();
        onChanged.run();
        history();
    }
}
