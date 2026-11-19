/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * DPKS editor chrome around the retained, independently licensed Markor engine.
 */
package net.gsantner.markor.knowledge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.MaterialColors;

import net.gsantner.markor.R;
import net.gsantner.markor.activity.DocumentActivity;
import net.gsantner.markor.activity.DocumentEditAndViewFragment;
import net.gsantner.markor.model.Document;
import net.gsantner.opoc.frontend.base.GsFragmentBase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** A note opened from DPKS must return to the library, not destroy its task. */
public final class KnowledgeDocumentActivity extends DocumentActivity {
    private static final int MAX_SOURCE_BYTES = 2_000_000;
    private final ExecutorService knowledgeWorker = Executors.newSingleThreadExecutor();
    private final AtomicInteger operationGeneration = new AtomicInteger();
    private TextView knowledgeStatus;
    private MaterialButtonToggleGroup modeButtons;

    public static void open(final Activity activity, final File file) {
        final Intent intent = new Intent(activity, KnowledgeDocumentActivity.class);
        intent.putExtra(Document.EXTRA_FILE, file);
        intent.putExtra(Document.EXTRA_DO_PREVIEW, false);
        activity.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable final Bundle state) {
        super.onCreate(state);
        knowledgeStatus = findViewById(R.id.knowledge_editor_status);
        modeButtons = findViewById(R.id.knowledge_editor_modes);
        findViewById(R.id.knowledge_editor_edit_button).setOnClickListener(view -> setMode(false));
        findViewById(R.id.knowledge_editor_preview_button).setOnClickListener(view -> setMode(true));
        findViewById(R.id.knowledge_editor_save).setOnClickListener(view -> saveExplicitly());
        findViewById(R.id.knowledge_editor_inspect).setOnClickListener(view -> inspectDocument());
        findViewById(R.id.knowledge_editor_context).setOnClickListener(view -> toggleKnowledgeContext());
        findViewById(R.id.knowledge_editor_review_selection).setOnClickListener(view -> reviewSelection());
        findViewById(R.id.knowledge_editor_backlinks).setOnClickListener(view -> showBacklinks());
        final Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back_white_24dp);
            if (toolbar.getNavigationIcon() != null) {
                toolbar.getNavigationIcon().mutate().setTint(
                        MaterialColors.getColor(toolbar,
                                com.google.android.material.R.attr.colorOnSurface));
            }
            toolbar.setNavigationContentDescription(R.string.knowledge_editor_back);
            toolbar.setNavigationOnClickListener(view -> onBackPressed());
            toolbar.post(this::updateModeButtons);
        }
    }

    private DocumentEditAndViewFragment editor() {
        final Fragment fragment = getSupportFragmentManager()
                .findFragmentById(R.id.document__placeholder_fragment);
        return fragment instanceof DocumentEditAndViewFragment
                && ((DocumentEditAndViewFragment) fragment).getEditor() != null
                ? (DocumentEditAndViewFragment) fragment : null;
    }

    private void setMode(final boolean preview) {
        final DocumentEditAndViewFragment fragment = editor();
        if (fragment == null) return;
        if (fragment.isViewModeVisibility() != preview) {
            fragment.setViewModeVisibility(preview);
        }
        updateModeButtons();
    }

    private void updateModeButtons() {
        if (modeButtons == null) return;
        final DocumentEditAndViewFragment fragment = editor();
        if (fragment == null) return;
        final int id = fragment.isViewModeVisibility()
                ? R.id.knowledge_editor_preview_button : R.id.knowledge_editor_edit_button;
        if (modeButtons.getCheckedButtonId() != id) modeButtons.check(id);
    }

    @Override
    public boolean onPrepareOptionsMenu(final Menu menu) {
        final boolean result = super.onPrepareOptionsMenu(menu);
        if (modeButtons != null) modeButtons.post(this::updateModeButtons);
        return result;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (modeButtons != null) modeButtons.post(this::updateModeButtons);
    }

    /** Canonical vault-relative paths only. Never follow symlinks outside the vault. */
    private String relativePath(final File file) throws IOException {
        final File vault = _appSettings.getNotebookDirectory();
        if (vault == null || file == null) throw new IOException("No selected source vault");
        final String root = vault.getCanonicalPath() + File.separator;
        final String path = file.getCanonicalPath();
        if (!path.startsWith(root) || !file.isFile()
                || !(path.toLowerCase(java.util.Locale.ROOT).endsWith(".md")
                || path.toLowerCase(java.util.Locale.ROOT).endsWith(".markdown"))) {
            throw new IOException("Source outside Markdown vault");
        }
        return path.substring(root.length()).replace(File.separatorChar, '/');
    }

    private void setKnowledgeStatus(final int resource) {
        if (knowledgeStatus != null) knowledgeStatus.setText(resource);
    }

    /** A deliberate user save via Markor's existing file writer, never analysis. */
    private void saveExplicitly() {
        final DocumentEditAndViewFragment fragment = editor();
        if (fragment == null) return;
        final File file = fragment.getDocument().file;
        final String path;
        try {
            path = relativePath(file);
        } catch (IOException ignored) {
            setKnowledgeStatus(R.string.knowledge_editor_source_invalid);
            return;
        }
        final String snapshot = fragment.getTextString();
        if (!fragment.saveDocument(false)) {
            setKnowledgeStatus(R.string.knowledge_editor_save_first);
            return;
        }
        final int generation = operationGeneration.incrementAndGet();
        setKnowledgeStatus(R.string.knowledge_editor_working);
        knowledgeWorker.submit(() -> {
            try {
                if (!path.equals(relativePath(file))
                        || !snapshot.equals(readSavedNote(file.getCanonicalFile()))) {
                    throw new IOException("Saved source does not match editor");
                }
                runOnUiThread(() -> {
                    if (current(generation)) setKnowledgeStatus(R.string.knowledge_editor_saved);
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (current(generation)) setKnowledgeStatus(R.string.knowledge_editor_save_first);
                });
            }
        });
    }

    private void inspectDocument() {
        final DocumentEditAndViewFragment fragment = editor();
        if (fragment == null) return;
        final String markdown = fragment.getTextString();
        final String path;
        try {
            path = relativePath(fragment.getDocument().file);
        } catch (IOException ignored) {
            setKnowledgeStatus(R.string.knowledge_editor_source_invalid);
            return;
        }
        final int generation = operationGeneration.incrementAndGet();
        setKnowledgeStatus(R.string.knowledge_editor_working);
        knowledgeWorker.submit(() -> {
            try {
                final JSONObject inspected = KnowledgePythonBridge.inspect(this, path, markdown);
                runOnUiThread(() -> {
                    if (!current(generation)) return;
                    final JSONArray fragments = inspected.optJSONArray("fragments");
                    final int count = fragments == null ? 0 : fragments.length();
                    knowledgeStatus.setText(getString(R.string.knowledge_editor_inspection_summary,
                            inspected.optInt("fragment_count"), inspected.optBoolean("coverage_ok")));
                    final AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                            .setTitle(R.string.knowledge_editor_inspection_title)
                            .setNegativeButton(android.R.string.cancel, null);
                    if (count == 0) {
                        dialog.setMessage(R.string.knowledge_editor_inspection_empty);
                    } else {
                        final String[] labels = new String[count];
                        for (int i = 0; i < count; i++) {
                            final JSONObject row = fragments.optJSONObject(i);
                            labels[i] = row == null ? "?" : row.optString("locator") + " · "
                                    + row.optString("preview") + " (" + row.optInt("candidate_count") + ")";
                        }
                        dialog.setItems(labels, (d, which) -> {
                            final JSONObject chosen = fragments.optJSONObject(which);
                            if (chosen != null) reviewSavedFragment(path,
                                    chosen.optString("id"), markdown);
                        });
                    }
                    dialog.setPositiveButton(android.R.string.ok, null).show();
                    if (inspected.optBoolean("partial_display")) {
                        Toast.makeText(this, R.string.knowledge_editor_inspection_partial,
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (current(generation)) setKnowledgeStatus(R.string.knowledge_editor_inspection_failed);
                });
            }
        });
    }

    /** Offsets are converted from the editor's UTF-16 to Python Unicode code points. */
    private void reviewSelection() {
        final DocumentEditAndViewFragment fragment = editor();
        if (fragment == null) return;
        if (fragment.isViewModeVisibility()) {
            setKnowledgeStatus(R.string.knowledge_editor_selection_required);
            return;
        }
        final String markdown = fragment.getTextString();
        final int start = fragment.getEditor().getSelectionStart();
        final int end = fragment.getEditor().getSelectionEnd();
        if (start < 0 || end <= start || end > markdown.length()
                || cutsSurrogate(markdown, start) || cutsSurrogate(markdown, end)) {
            setKnowledgeStatus(R.string.knowledge_editor_selection_required);
            return;
        }
        final String path;
        try {
            path = relativePath(fragment.getDocument().file);
        } catch (IOException ignored) {
            setKnowledgeStatus(R.string.knowledge_editor_source_invalid);
            return;
        }
        final int codepointStart = markdown.codePointCount(0, start);
        final int codepointEnd = markdown.codePointCount(0, end);
        final int generation = operationGeneration.incrementAndGet();
        setKnowledgeStatus(R.string.knowledge_editor_working);
        knowledgeWorker.submit(() -> {
            try {
                final JSONObject selection = KnowledgePythonBridge.inspectSelection(this,
                        path, markdown, codepointStart, codepointEnd);
                runOnUiThread(() -> {
                    if (!current(generation)) return;
                    if (!selection.optBoolean("matched")) {
                        setKnowledgeStatus(R.string.knowledge_editor_selection_multiple);
                        return;
                    }
                    knowledgeStatus.setText(getString(R.string.knowledge_editor_selection_ready,
                            selection.optString("locator"), selection.optInt("candidate_count")));
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.knowledge_editor_inspection_title)
                            .setMessage(selection.optString("source_text") + "\n\n"
                                    + selection.optString("preview"))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.knowledge_editor_review_continue,
                                    (dialog, which) -> reviewSavedFragment(path,
                                            selection.optString("fragment_id"), markdown))
                            .show();
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (current(generation)) setKnowledgeStatus(R.string.knowledge_editor_inspection_failed);
                });
            }
        });
    }

    private static boolean cutsSurrogate(final String text, final int offset) {
        return offset > 0 && offset < text.length()
                && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset));
    }

    /** Revalidate the *saved* source; analysis of an unsaved buffer is never a commit. */
    private void reviewSavedFragment(final String path, final String fragmentId,
                                     final String editorSnapshot) {
        if (fragmentId.isEmpty()) return;
        final DocumentEditAndViewFragment fragment = editor();
        if (fragment == null) return;
        final File file = fragment.getDocument().file;
        final int generation = operationGeneration.incrementAndGet();
        setKnowledgeStatus(R.string.knowledge_editor_working);
        knowledgeWorker.submit(() -> {
            try {
                // Re-resolve immediately before reading: the target might
                // have been replaced with a symlink since the first check.
                if (!path.equals(relativePath(file))) {
                    throw new IOException("Source was moved outside the selected vault");
                }
                final String saved = readSavedNote(file.getCanonicalFile());
                final String hash = sha256(editorSnapshot);
                runOnUiThread(() -> {
                    if (!current(generation)) return;
                    final DocumentEditAndViewFragment currentEditor = editor();
                    if (!saved.equals(editorSnapshot) || currentEditor == null
                            || !editorSnapshot.equals(currentEditor.getTextString())) {
                        setKnowledgeStatus(R.string.knowledge_editor_save_first);
                        return;
                    }
                    KnowledgeHubActivity.reviewFromEditor(this, path, fragmentId, hash);
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (current(generation)) setKnowledgeStatus(R.string.knowledge_editor_save_first);
                });
            }
        });
    }

    private static String readSavedNote(final File file) throws IOException {
        if (!file.isFile() || file.length() > MAX_SOURCE_BYTES) {
            throw new IOException("Source unavailable or oversized");
        }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        try (FileInputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted() || bytes.size() + n > MAX_SOURCE_BYTES) {
                    throw new IOException("Source read cancelled or too large");
                }
                bytes.write(buffer, 0, n);
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String sha256(final String text) throws Exception {
        final byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
        final StringBuilder hex = new StringBuilder(hash.length * 2);
        for (final byte b : hash) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return hex.toString();
    }

    /**
     * A bounded context panel grounded in the saved note. No journal/index
     * migration, network request, or source mutation is permitted here.
     */
    private void toggleKnowledgeContext() {
        final View panel = findViewById(R.id.knowledge_editor_context_panel);
        if (panel.getVisibility() == View.VISIBLE) {
            operationGeneration.incrementAndGet();
            panel.setVisibility(View.GONE);
            return;
        }
        final DocumentEditAndViewFragment fragment = editor();
        if (fragment == null) return;
        final File file = fragment.getDocument().file;
        final String path;
        final File selectedVault;
        try {
            path = relativePath(file);
            selectedVault = _appSettings.getNotebookDirectory().getCanonicalFile();
        } catch (IOException ignored) {
            setKnowledgeStatus(R.string.knowledge_editor_source_invalid);
            return;
        }
        final String editorSnapshot = fragment.getTextString();
        final int generation = operationGeneration.incrementAndGet();
        panel.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.knowledge_editor_context_summary))
                .setText(R.string.knowledge_editor_context_loading);
        ((LinearLayout) findViewById(R.id.knowledge_editor_context_records)).removeAllViews();
        ((LinearLayout) findViewById(R.id.knowledge_editor_context_relations_list)).removeAllViews();
        ((TextView) findViewById(R.id.knowledge_editor_context_warning))
                .setText(R.string.knowledge_editor_context_disclaimer);
        knowledgeWorker.submit(() -> {
            try {
                if (!path.equals(relativePath(file))) {
                    throw new IOException("Source no longer belongs to the selected vault");
                }
                final String saved = readSavedNote(file.getCanonicalFile());
                final JSONObject context = KnowledgePythonBridge.editorContext(this,
                        new File(getNoBackupFilesDir(), "knowledge_workspace_v1").getAbsolutePath(),
                        path, saved);
                runOnUiThread(() -> {
                    if (current(generation) && panel.getVisibility() == View.VISIBLE) {
                        renderKnowledgeContext(context, saved, !saved.equals(editorSnapshot),
                                selectedVault);
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (current(generation) && panel.getVisibility() == View.VISIBLE) {
                        ((TextView) findViewById(R.id.knowledge_editor_context_summary))
                                .setText(R.string.knowledge_editor_context_failed);
                    }
                });
            }
        });
    }

    private void renderKnowledgeContext(final JSONObject context, final String savedMarkdown,
                                        final boolean unsavedBuffer, final File selectedVault) {
        final JSONArray records = context.optJSONArray("accepted_records");
        final JSONArray relations = context.optJSONArray("candidate_relations");
        final int recordCount = records == null ? 0 : records.length();
        final int relationCount = relations == null ? 0 : relations.length();
        final String countLabel = relationCount
                + (context.optBoolean("relations_truncated") ? "+" : "");
        ((TextView) findViewById(R.id.knowledge_editor_context_summary)).setText(
                getString(R.string.knowledge_editor_context_summary,
                        context.optInt("accepted_total"), countLabel));

        final LinearLayout accepted = findViewById(R.id.knowledge_editor_context_records);
        for (int i = 0; i < recordCount; i++) {
            final JSONObject item = records.optJSONObject(i);
            if (item == null) continue;
            final String role = editorKnowledgeRole(item.optString("role"));
            final String statement = item.optString("statement");
            final String title = role + " · " + statement
                    + (item.optBoolean("source_current") ? ""
                    : " · " + getString(R.string.knowledge_editor_context_record_stale));
            addContextRow(accepted, title, view ->
                    showContextRecord(item, context.optString("source_hash"), savedMarkdown));
        }
        if (recordCount == 0) {
            addContextEmpty(accepted, R.string.knowledge_editor_context_empty_records);
        }

        final LinearLayout related = findViewById(R.id.knowledge_editor_context_relations_list);
        for (int i = 0; i < relationCount; i++) {
            final JSONObject item = relations.optJSONObject(i);
            if (item == null) continue;
            final int label = "possible_conflict".equals(item.optString("kind"))
                    ? R.string.knowledge_editor_context_conflict
                    : R.string.knowledge_editor_context_agreement;
            addContextRow(related, getString(label) + " · "
                    + item.optString("path") + " · " + item.optString("other_locator"),
                    view -> showContextRelation(item, label, selectedVault));
        }
        if (relationCount == 0) {
            addContextEmpty(related, R.string.knowledge_editor_context_empty_relations);
        }

        final StringBuilder warnings = new StringBuilder(
                getString(R.string.knowledge_editor_context_disclaimer));
        if (unsavedBuffer) appendContextWarning(warnings,
                R.string.knowledge_editor_context_unsaved);
        final String indexState = context.optString("relation_index_state");
        if ("absent".equals(indexState)) {
            appendContextWarning(warnings, R.string.knowledge_editor_context_index_absent);
        } else if ("stale".equals(indexState)) {
            appendContextWarning(warnings, R.string.knowledge_editor_context_index_stale);
        } else if (!"current".equals(indexState)) {
            appendContextWarning(warnings, R.string.knowledge_editor_context_index_unavailable);
        }
        if (context.optInt("stale_provenance_count") > 0) {
            warnings.append("\n\n").append(getString(
                    R.string.knowledge_editor_context_stale_count,
                    context.optInt("stale_provenance_count")));
        }
        if (context.optInt("historical_count") > 0) {
            warnings.append("\n\n").append(getString(
                    R.string.knowledge_editor_context_historical_count,
                    context.optInt("historical_count")));
        }
        if (context.optBoolean("accepted_truncated")
                || context.optBoolean("relations_truncated")) {
            appendContextWarning(warnings, R.string.knowledge_editor_context_truncated);
        }
        ((TextView) findViewById(R.id.knowledge_editor_context_warning))
                .setText(warnings.toString());
    }

    private void appendContextWarning(final StringBuilder text, final int message) {
        text.append("\n\n").append(getString(message));
    }

    private void addContextRow(final LinearLayout list, final String label,
                               final View.OnClickListener onClick) {
        final MaterialButton item = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        item.setAllCaps(false);
        item.setSingleLine(false);
        item.setMaxLines(3);
        item.setText(label.length() > 260 ? label.substring(0, 260) + "…" : label);
        item.setOnClickListener(onClick);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = (int) (4 * getResources().getDisplayMetrics().density);
        list.addView(item, params);
    }

    private void addContextEmpty(final LinearLayout list, final int label) {
        final TextView message = new TextView(this);
        message.setText(label);
        message.setTextColor(MaterialColors.getColor(message,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        message.setPadding(12, 4, 12, 8);
        list.addView(message);
    }

    private String editorKnowledgeRole(final String role) {
        switch (role) {
            case "fact": return getString(R.string.knowledge_decision_fact);
            case "hypothesis": return getString(R.string.knowledge_decision_hypothesis);
            case "claim": return getString(R.string.knowledge_decision_claim);
            default: return role;
        }
    }

    private void showContextRecord(final JSONObject record, final String savedHash,
                                   final String savedMarkdown) {
        final String excerpt = record.optString("source_excerpt");
        final StringBuilder detail = new StringBuilder();
        detail.append(getString(R.string.knowledge_editor_context_source)).append("\n")
                .append(excerpt).append("\n\n")
                .append(getString(R.string.knowledge_editor_context_statement)).append("\n")
                .append(record.optString("statement")).append("\n\n")
                .append(getString(R.string.knowledge_editor_context_provenance,
                        record.optString("provenance_kind"), record.optString("locator")));
        if (!record.optBoolean("source_current")) {
            detail.append("\n\n")
                    .append(getString(R.string.knowledge_editor_context_record_stale));
        }
        if (!record.optBoolean("conflicts_available")) {
            detail.append("\n\n")
                    .append(getString(R.string.knowledge_editor_context_conflicts_unavailable));
        } else if (record.optInt("conflict_count") > 0) {
            detail.append("\n\n")
                    .append(getString(R.string.knowledge_editor_context_conflict))
                    .append(" · ").append(record.optInt("conflict_count"));
        }
        final TextView selectable = contextDetailText(detail.toString());
        final AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(editorKnowledgeRole(record.optString("role")))
                .setView(selectable)
                .setNegativeButton(android.R.string.cancel, null);
        if (record.optBoolean("source_current") && !excerpt.isEmpty()) {
            dialog.setPositiveButton(R.string.knowledge_editor_context_jump,
                    (d, which) -> jumpToSavedSource(excerpt, savedHash, savedMarkdown));
        }
        dialog.show();
    }

    private void showContextRelation(final JSONObject relation, final int title,
                                     final File selectedVault) {
        final String description = getString(R.string.knowledge_editor_context_disclaimer)
                + "\n\n" + getString(R.string.knowledge_editor_context_source)
                + ": " + relation.optString("source_locator")
                + "\n" + relation.optString("path") + " · "
                + relation.optString("other_locator");
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(contextDetailText(description))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.knowledge_editor_context_open,
                        (dialog, which) -> openRelatedNote(relation.optString("path"),
                                selectedVault))
                .show();
    }

    private TextView contextDetailText(final String text) {
        final TextView label = new TextView(this);
        final int inset = (int) (18 * getResources().getDisplayMetrics().density);
        label.setPadding(inset, inset, inset, inset);
        label.setText(text);
        label.setTextIsSelectable(true);
        return label;
    }

    private void jumpToSavedSource(final String excerpt, final String expectedHash,
                                   final String savedSnapshot) {
        final DocumentEditAndViewFragment fragment = editor();
        if (fragment == null) return;
        final String currentText = fragment.getTextString();
        try {
            final int start = currentText.indexOf(excerpt);
            if (!savedSnapshot.equals(currentText) || !expectedHash.equals(sha256(currentText))
                    || start < 0 || currentText.indexOf(excerpt, start + 1) >= 0) {
                throw new IOException("Source changed or ambiguous");
            }
            setMode(false);
            fragment.getEditor().setSelection(start, start + excerpt.length());
            fragment.getEditor().requestFocus();
            operationGeneration.incrementAndGet();
            findViewById(R.id.knowledge_editor_context_panel).setVisibility(View.GONE);
        } catch (Exception ignored) {
            setKnowledgeStatus(R.string.knowledge_editor_context_reload);
        }
    }

    private void openRelatedNote(final String relative, final File selectedVault) {
        try {
            final File vault = _appSettings.getNotebookDirectory();
            if (vault == null || !vault.getCanonicalFile().equals(selectedVault)) {
                throw new IOException("Selected vault changed since context was opened");
            }
            final File candidate = new File(vault, relative).getCanonicalFile();
            if (!relative.equals(relativePath(candidate))) {
                throw new IOException("Related path escaped the selected vault");
            }
            open(this, candidate);
        } catch (IOException ignored) {
            Toast.makeText(this, R.string.knowledge_file_missing, Toast.LENGTH_SHORT).show();
        }
    }

    private void showBacklinks() {
        final DocumentEditAndViewFragment fragment = editor();
        if (fragment == null) return;
        final File vault = _appSettings.getNotebookDirectory();
        final String path;
        try {
            path = relativePath(fragment.getDocument().file);
        } catch (IOException ignored) {
            setKnowledgeStatus(R.string.knowledge_editor_source_invalid);
            return;
        }
        final int generation = operationGeneration.incrementAndGet();
        setKnowledgeStatus(R.string.knowledge_editor_working);
        knowledgeWorker.submit(() -> {
            try {
                final List<String> links = new KnowledgeLocalIndex(this)
                        .verifiedPossibleBacklinksReadOnly(vault, path, 50);
                runOnUiThread(() -> {
                    if (!current(generation)) return;
                    setKnowledgeStatus(R.string.knowledge_editor_backlinks_warning);
                    final AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                            .setTitle(R.string.knowledge_editor_backlinks_title)
                            .setNegativeButton(android.R.string.cancel, null);
                    if (links.isEmpty()) {
                        dialog.setMessage(R.string.knowledge_editor_backlinks_none);
                    } else {
                        dialog.setItems(links.toArray(new String[0]), (d, which) -> {
                            try {
                                final File candidate = new File(vault, links.get(which)).getCanonicalFile();
                                final String root = vault.getCanonicalPath() + File.separator;
                                if (!candidate.getPath().startsWith(root) || !candidate.isFile()) {
                                    throw new IOException("Stale link outside vault");
                                }
                                KnowledgeDocumentActivity.open(this, candidate);
                            } catch (IOException ignored) {
                                Toast.makeText(this, R.string.knowledge_file_missing,
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                    dialog.setPositiveButton(android.R.string.ok, null).show();
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (current(generation)) setKnowledgeStatus(R.string.knowledge_editor_backlinks_failed);
                });
            }
        });
    }

    private boolean current(final int generation) {
        return !isFinishing() && !isDestroyed() && operationGeneration.get() == generation;
    }

    @Override
    protected void onStop() {
        // Do not surface stale dialogs over the library or another editor.
        operationGeneration.incrementAndGet();
        final View context = findViewById(R.id.knowledge_editor_context_panel);
        if (context != null) context.setVisibility(View.GONE);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        operationGeneration.incrementAndGet();
        knowledgeWorker.shutdownNow();
        super.onDestroy();
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

    @Override
    public void onBackPressed() {
        // The inherited DocumentActivity calls finishAndRemoveTask(), which
        // would also remove the user's DPKS library from Android recents.
        final Fragment fragment = getSupportFragmentManager()
                .findFragmentById(R.id.document__placeholder_fragment);
        if (fragment instanceof GsFragmentBase
                && !((GsFragmentBase<?, ?>) fragment).onBackPressed()
                && getSupportFragmentManager().getBackStackEntryCount() > 1) {
            getSupportFragmentManager().popBackStack();
        } else {
            finish();
        }
    }
}
