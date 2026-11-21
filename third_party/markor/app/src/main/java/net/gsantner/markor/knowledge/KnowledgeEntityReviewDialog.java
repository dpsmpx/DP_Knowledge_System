/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.gsantner.markor.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Human-only, source-by-source alias review. Suggestions from the disposable
 * index never become identity assertions without two explicit confirmation
 * steps and the Python bridge's fresh SHA + journal-head checks.
 */
final class KnowledgeEntityReviewDialog {
    private final KnowledgeHubActivity activity;
    private final String workspace;
    private final File notebook;
    private final ExecutorService worker;
    private final Consumer<String> openNote;
    private final Runnable onChanged;

    KnowledgeEntityReviewDialog(final KnowledgeHubActivity activity, final String workspace,
                                final File notebook, final ExecutorService worker,
                                final Consumer<String> openNote, final Runnable onChanged) {
        this.activity = activity;
        this.workspace = workspace;
        this.notebook = notebook;
        this.worker = worker;
        this.openNote = openNote;
        this.onChanged = onChanged;
    }

    void show() {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.knowledge_entity_matching)
                .setMessage(R.string.knowledge_entity_explanation)
                .setItems(new String[]{s(R.string.knowledge_entity_new),
                                s(R.string.knowledge_entity_active)}, (dialog, index) -> {
                    if (index == 0) {
                        if (notebook == null) Toast.makeText(activity,
                                R.string.knowledge_no_folder, Toast.LENGTH_LONG).show();
                        else newMatch();
                    }
                    else activeDecisions();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String s(final int id) {
        return activity.getString(id);
    }

    private interface ResultTask {
        JSONObject run() throws Exception;
    }

    private void async(final ResultTask task, final Consumer<JSONObject> done) {
        worker.submit(() -> {
            try {
                final JSONObject value = task.run();
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) done.accept(value);
                });
            } catch (Exception ignored) {
                // No private source text, filenames or Python traces in logs.
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        new MaterialAlertDialogBuilder(activity)
                                .setTitle(R.string.knowledge_entity_error)
                                .setMessage(R.string.knowledge_entity_error_detail)
                                .setPositiveButton(android.R.string.ok, null).show();
                    }
                });
            }
        });
    }

    private void newMatch() {
        final LinearLayout fields = new LinearLayout(activity);
        fields.setOrientation(LinearLayout.VERTICAL);
        final int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        fields.setPadding(pad, pad / 2, pad, pad / 2);
        final EditText alias = new EditText(activity);
        alias.setHint(R.string.knowledge_entity_alias_hint);
        alias.setSingleLine(true);
        alias.setInputType(InputType.TYPE_CLASS_TEXT);
        fields.addView(alias);
        final EditText canonical = new EditText(activity);
        canonical.setHint(R.string.knowledge_entity_canonical_hint);
        canonical.setSingleLine(true);
        canonical.setInputType(InputType.TYPE_CLASS_TEXT);
        fields.addView(canonical);
        final EditText reason = new EditText(activity);
        reason.setHint(R.string.knowledge_entity_reason_hint);
        reason.setSingleLine(false);
        reason.setMaxLines(3);
        fields.addView(reason);
        final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.knowledge_entity_new)
                .setMessage(R.string.knowledge_entity_exact_symbols)
                .setView(fields)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_entity_find_sources, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    final String a = alias.getText().toString().trim();
                    final String c = canonical.getText().toString().trim();
                    final String why = reason.getText().toString().trim();
                    if (a.isEmpty() || c.isEmpty() || a.equals(c) || why.length() > 500) {
                        Toast.makeText(activity, R.string.knowledge_entity_invalid,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog.dismiss();
                    async(() -> KnowledgePythonBridge.entityAliasWorkspace(activity, workspace,
                                    notebook.getAbsolutePath(), a, c),
                            found -> chooseSource(found, true, why, null));
                }));
        dialog.show();
    }

    private void chooseSource(final JSONObject found, final boolean left, final String reason,
                              final JSONObject first) {
        final JSONArray candidates = found.optJSONArray(
                left ? "alias_sources" : "canonical_sources");
        if (candidates == null || candidates.length() == 0) {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.knowledge_entity_no_source)
                    .setMessage(R.string.knowledge_entity_index_needed)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        final String[] rows = new String[candidates.length()];
        for (int i = 0; i < rows.length; i++) {
            final JSONObject item = candidates.optJSONObject(i);
            if (item == null) continue;
            rows[i] = (item.optBoolean("current") ? "" : s(R.string.knowledge_entity_stale) + " · ")
                    + item.optString("path") + " · " + item.optString("locator")
                    + "\n" + item.optString("excerpt");
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle((left ? found.optString("alias") : found.optString("canonical"))
                        + " · " + s(R.string.knowledge_entity_choose_source)
                        + (found.optBoolean("sources_limited")
                        ? " · " + s(R.string.knowledge_entity_limited) : ""))
                .setItems(rows, (dialog, which) -> {
                    final JSONObject selected = candidates.optJSONObject(which);
                    if (selected != null) inspectSource(selected, () -> {
                        if (left) chooseSource(found, false, reason, selected);
                        else previewMatch(found, reason, first, selected);
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void inspectSource(final JSONObject source, final Runnable use) {
        final AlertDialog.Builder dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(source.optString("path") + " · " + source.optString("locator"))
                .setMessage(source.optString("excerpt") + "\n\nSHA-256: "
                        + source.optString("source_sha256") + "\n"
                        + s(source.optBoolean("current") ? R.string.knowledge_entity_verified
                                : R.string.knowledge_entity_stale))
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.knowledge_decision_open_source,
                        (d, w) -> openNote.accept(source.optString("path")));
        if (source.optBoolean("current")) {
            dialog.setPositiveButton(R.string.knowledge_entity_use_source,
                    (d, w) -> use.run());
        }
        dialog.show();
    }

    private void previewMatch(final JSONObject found, final String reason,
                              final JSONObject left, final JSONObject right) {
        async(() -> KnowledgePythonBridge.previewEntityAlias(activity, workspace,
                        notebook.getAbsolutePath(), found.getString("alias"),
                        found.getString("canonical"), left.getString("path"),
                        left.getString("fragment_id"), right.getString("path"),
                        right.getString("fragment_id"), reason), preview -> {
            final JSONArray sources = preview.optJSONArray("evidence");
            final JSONObject a = sources == null ? null : sources.optJSONObject(0);
            final JSONObject c = sources == null ? null : sources.optJSONObject(1);
            if (a == null || c == null) return;
            final String detail = preview.optString("alias") + " → "
                    + preview.optString("canonical") + "\n\n"
                    + s(R.string.knowledge_entity_source_a) + "\n"
                    + sourceDescription(a) + "\n\n"
                    + s(R.string.knowledge_entity_source_b) + "\n"
                    + sourceDescription(c) + "\n\n"
                    + s(R.string.knowledge_entity_reason_hint) + ": "
                    + preview.optString("reason") + "\n\n"
                    + s(R.string.knowledge_entity_confirm_warning) + "\n"
                    + s(R.string.knowledge_entity_journal) + ": "
                    + preview.optString("journal_head", "∅");
            final ScrollView scrolling = new ScrollView(activity);
            final android.widget.TextView content = new android.widget.TextView(activity);
            final int p = (int) (20 * activity.getResources().getDisplayMetrics().density);
            content.setPadding(p, p, p, p);
            content.setText(detail);
            content.setTextIsSelectable(true);
            scrolling.addView(content);
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.knowledge_entity_confirm)
                    .setView(scrolling)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.knowledge_entity_confirm, (d, w) ->
                            async(() -> KnowledgePythonBridge.confirmEntityAlias(
                                            activity, workspace, notebook.getAbsolutePath(),
                                            preview.getString("alias"), preview.getString("canonical"),
                                            a.getString("path"), a.getString("fragment_id"),
                                            c.getString("path"), c.getString("fragment_id"),
                                            preview.getString("reason"),
                                            preview.getString("preview_token"),
                                            preview.isNull("journal_head") ? null
                                                    : preview.getString("journal_head")),
                                    saved -> afterWrite(saved, "alias")))
                    .show();
        });
    }

    private String sourceDescription(final JSONObject source) {
        return source.optString("symbol") + " · " + source.optString("path")
                + " · " + source.optString("locator")
                + "\n" + source.optString("excerpt")
                + "\nSHA-256: " + source.optString("source_sha256");
    }

    private void activeDecisions() {
        async(() -> KnowledgePythonBridge.entityAliasDecisions(activity, workspace), data -> {
            final JSONArray active = data.optJSONArray("active");
            final JSONArray recent = data.optJSONArray("recent");
            final int count = active == null ? 0 : active.length();
            final int history = recent == null ? 0 : recent.length();
            final String[] items = new String[count + history];
            for (int i = 0; i < count; i++) {
                final JSONObject item = active.optJSONObject(i);
                if (item != null) items[i] = s(R.string.knowledge_entity_active)
                        + " · " + item.optString("alias") + " → " + item.optString("canonical");
            }
            for (int i = 0; i < history; i++) {
                final JSONObject event = recent.optJSONObject(i);
                if (event != null) items[count + i] = event.optString("time")
                        + " · " + event.optString("op") + " · "
                        + event.optString("alias") + " → " + event.optString("canonical");
            }
            final AlertDialog.Builder dialog = new MaterialAlertDialogBuilder(activity)
                    .setTitle(s(R.string.knowledge_entity_active)
                            + (data.optBoolean("journal_limited")
                            ? " · " + s(R.string.knowledge_entity_journal_limited) : ""))
                    .setNegativeButton(android.R.string.cancel, null);
            if (items.length == 0) dialog.setMessage(R.string.knowledge_entity_none);
            else dialog.setItems(items, (d, index) -> {
                if (index < count) {
                    final JSONObject item = active.optJSONObject(index);
                    if (item != null) showActiveDecision(item);
                } else {
                    final JSONObject item = recent.optJSONObject(index - count);
                    if (item != null) new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.knowledge_entity_history)
                            .setMessage(item.optString("op") + " · "
                                    + item.optString("alias") + " → "
                                    + item.optString("canonical") + "\n"
                                    + item.optString("time") + "\n"
                                    + item.optString("journal_id"))
                            .setPositiveButton(android.R.string.ok, null).show();
                }
            });
            dialog.show();
        });
    }

    private void showActiveDecision(final JSONObject mapping) {
        final StringBuilder detail = new StringBuilder()
                .append(mapping.optString("alias")).append(" → ")
                .append(mapping.optString("canonical")).append("\n")
                .append(mapping.optString("journal_id")).append("\n")
                .append(mapping.optString("time")).append("\n")
                .append(mapping.optString("reason"));
        final JSONArray sources = mapping.optJSONArray("sources");
        if (sources != null) {
            for (int i = 0; i < sources.length(); i++) {
                final JSONObject source = sources.optJSONObject(i);
                if (source != null) detail.append("\n\n").append(sourceDescription(source));
            }
        }
        detail.append("\n\n").append(s(R.string.knowledge_entity_revoke_warning));
        final AlertDialog.Builder dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.knowledge_entity_active)
                .setMessage(detail.toString())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_entity_revoke, (d, w) ->
                        async(() -> KnowledgePythonBridge.previewEntityUnalias(
                                        activity, workspace, mapping.getString("alias")),
                                this::confirmRevocation));
        if (sources != null && sources.length() > 0) {
            dialog.setNeutralButton(R.string.knowledge_entity_view_source, (d, w) -> {
                final JSONObject src = sources.optJSONObject(0);
                if (src != null) openNote.accept(src.optString("path"));
            });
        }
        dialog.show();
    }

    private void confirmRevocation(final JSONObject preview) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.knowledge_entity_revoke)
                .setMessage(preview.optString("alias") + " → "
                        + preview.optString("canonical") + "\n"
                        + preview.optString("journal_id") + "\n\n"
                        + s(R.string.knowledge_entity_revoke_warning))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_entity_revoke, (d, w) ->
                        async(() -> KnowledgePythonBridge.confirmEntityUnalias(
                                        activity, workspace, preview.getString("alias"),
                                        preview.getString("preview_token"),
                                        preview.isNull("journal_head") ? null
                                                : preview.getString("journal_head")),
                                saved -> afterWrite(saved, "unalias")))
                .show();
    }

    private void afterWrite(final JSONObject response, final String expectedOp) {
        final JSONArray ids = response.optJSONArray("journal_ids");
        if (!expectedOp.equals(response.optString("decision"))
                || ids == null || ids.length() != 1) {
            Toast.makeText(activity, R.string.knowledge_entity_error, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(activity, R.string.knowledge_entity_saved, Toast.LENGTH_LONG).show();
        onChanged.run();
        activeDecisions();
    }
}
