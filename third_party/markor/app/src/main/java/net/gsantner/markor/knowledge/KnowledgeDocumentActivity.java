/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * DPKS editor chrome around the retained, independently licensed Markor engine.
 */
package net.gsantner.markor.knowledge;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.MaterialColors;

import net.gsantner.markor.R;
import net.gsantner.markor.activity.DocumentActivity;
import net.gsantner.markor.activity.DocumentEditAndViewFragment;
import net.gsantner.markor.model.Document;
import net.gsantner.markor.frontend.textview.HighlightingEditor;
import net.gsantner.opoc.frontend.base.GsFragmentBase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** A note opened from DPKS must return to the library, not destroy its task. */
public final class KnowledgeDocumentActivity extends DocumentActivity {
    private static final int MAX_SOURCE_BYTES = 2_000_000;
    private static final String DOCK_EXPANDED_STATE = "dpks.editor.dock.expanded";
    private static final String JUMP_PATH = "dpks.editor.source.path";
    private static final String JUMP_SHA = "dpks.editor.source.sha";
    private static final String JUMP_TEXT_SHA = "dpks.editor.source.text_sha";
    private static final String JUMP_START = "dpks.editor.source.start";
    private static final String JUMP_END = "dpks.editor.source.end";
    private final ExecutorService knowledgeWorker = Executors.newSingleThreadExecutor();
    private final AtomicInteger operationGeneration = new AtomicInteger();
    private final AtomicInteger dockGeneration = new AtomicInteger();
    private final AtomicInteger highlightGeneration = new AtomicInteger();
    private final List<BackgroundColorSpan> inlineKnowledgeSpans = new ArrayList<>();
    private TextView knowledgeStatus;
    private MaterialButtonToggleGroup modeButtons;
    private HighlightingEditor highlightedEditor;
    private TextWatcher highlightWatcher;
    private boolean highlightsRequested;
    private boolean dockContextInitialized;
    private boolean dockContextStale;
    private boolean dockForeground;
    private int dockInitAttempts;
    private HighlightingEditor dockWatchedEditor;
    private TextWatcher dockEditWatcher;
    private DockPage dockCurrentPage;
    private final List<DockPage> dockPageHistory = new ArrayList<>();
    private boolean incomingJumpStarted;
    private int incomingJumpAttempts;
    private boolean sideDock;

    /** At most eight detached, read-only views; no journal/model data is persisted. */
    private static final class DockPage {
        final String title;
        final LinearLayout content;
        int scrollY;

        DockPage(final String title, final LinearLayout content) {
            this.title = title;
            this.content = content;
        }
    }

    public static void open(final Activity activity, final File file) {
        final Intent intent = new Intent(activity, KnowledgeDocumentActivity.class);
        intent.putExtra(Document.EXTRA_FILE, file);
        intent.putExtra(Document.EXTRA_DO_PREVIEW, false);
        activity.startActivity(intent);
    }

    /** Open a proven journal origin in a DPKS editor, not a generic file viewer. */
    private static void openAtSource(final Activity activity, final File file,
                                     final String relative, final String sha,
                                     final String textSha, final int start, final int end) {
        final Intent intent = new Intent(activity, KnowledgeDocumentActivity.class);
        intent.putExtra(Document.EXTRA_FILE, file);
        intent.putExtra(Document.EXTRA_DO_PREVIEW, false);
        intent.putExtra(JUMP_PATH, relative);
        intent.putExtra(JUMP_SHA, sha);
        intent.putExtra(JUMP_TEXT_SHA, textSha);
        intent.putExtra(JUMP_START, start);
        intent.putExtra(JUMP_END, end);
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
        findViewById(R.id.knowledge_editor_context_expand).setOnClickListener(view -> toggleKnowledgeContext());
        findViewById(R.id.knowledge_editor_context_refresh).setOnClickListener(view -> refreshKnowledgeContext());
        findViewById(R.id.knowledge_editor_context_back).setOnClickListener(view -> navigateDockBack());
        findViewById(R.id.knowledge_editor_highlights).setOnClickListener(view -> toggleEditorHighlights());
        findViewById(R.id.knowledge_editor_source_inspector).setOnClickListener(view -> inspectSourceKnowledge());
        findViewById(R.id.knowledge_editor_review_selection).setOnClickListener(view -> reviewSelection());
        findViewById(R.id.knowledge_editor_backlinks).setOnClickListener(view -> showBacklinks());
        applyResponsiveEditorLayout();
        final View workspace = findViewById(R.id.knowledge_editor_workspace);
        ViewCompat.setOnApplyWindowInsetsListener(workspace, (view, insets) -> {
            // On a phone the IME must not leave a 200dp knowledge pane over
            // the remaining editor. The permanent summary rail stays present.
            if (!sideDock && insets.isVisible(WindowInsetsCompat.Type.ime())
                    && findViewById(R.id.knowledge_editor_context_body)
                    .getVisibility() == View.VISIBLE) {
                setDockExpanded(false);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(workspace);
        if (state != null && state.getBoolean(DOCK_EXPANDED_STATE, false)) {
            setDockExpanded(true);
        }
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
        if (preview) clearEditorHighlights();
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
        if (fragment.isViewModeVisibility() && highlightsRequested) clearEditorHighlights();
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
        dockForeground = true;
        if (modeButtons != null) modeButtons.post(this::updateModeButtons);
        if (modeButtons != null) modeButtons.post(this::selectIncomingSource);
        if (modeButtons != null) modeButtons.post(this::initializeContextDock);
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
        clearEditorHighlights();
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

    /** Inspect verified journal provenance at the edit caret or within one selection. */
    private void inspectSourceKnowledge() {
        final DocumentEditAndViewFragment fragment = editor();
        if (fragment == null) return;
        if (fragment.isViewModeVisibility()) {
            setKnowledgeStatus(R.string.knowledge_editor_highlights_edit_mode);
            return;
        }
        final String snapshot = fragment.getTextString();
        final int a = fragment.getEditor().getSelectionStart();
        final int b = fragment.getEditor().getSelectionEnd();
        if (a < 0 || b < 0 || a > snapshot.length() || b > snapshot.length()
                || cutsSurrogate(snapshot, a) || cutsSurrogate(snapshot, b)) {
            setKnowledgeStatus(R.string.knowledge_editor_source_inspector_select);
            return;
        }
        final int start = snapshot.codePointCount(0, Math.min(a, b));
        final int end = snapshot.codePointCount(0, Math.max(a, b));
        final File file = fragment.getDocument().file;
        final String path;
        try {
            path = relativePath(file);
        } catch (IOException ignored) {
            setKnowledgeStatus(R.string.knowledge_editor_source_invalid);
            return;
        }
        final int generation = operationGeneration.incrementAndGet();
        setKnowledgeStatus(R.string.knowledge_editor_working);
        knowledgeWorker.submit(() -> {
            try {
                if (!path.equals(relativePath(file))) throw new IOException("Vault changed");
                final String saved = readSavedNote(file.getCanonicalFile());
                if (!saved.equals(snapshot)) throw new IOException("Unsaved editor buffer");
                final JSONObject result = KnowledgePythonBridge.editorSourceRecords(this,
                        new File(getNoBackupFilesDir(), "knowledge_workspace_v1").getAbsolutePath(),
                        path, saved, start, end);
                if (!sha256(saved).equals(result.optString("source_hash"))) {
                    throw new IOException("Source inspector snapshot changed");
                }
                runOnUiThread(() -> {
                    if (!current(generation)) return;
                    final DocumentEditAndViewFragment now = editor();
                    if (now == null || now.isViewModeVisibility()
                            || !snapshot.equals(now.getTextString())) {
                        setKnowledgeStatus(R.string.knowledge_editor_source_inspector_changed);
                        return;
                    }
                    showSourceKnowledge(result, path, saved);
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (current(generation))
                        setKnowledgeStatus(R.string.knowledge_editor_source_inspector_changed);
                });
            }
        });
    }

    private void showSourceKnowledge(final JSONObject result, final String path,
                                     final String savedMarkdown) {
        final JSONArray items = result.optJSONArray("records");
        final int count = items == null ? 0 : items.length();
        final String title = getString(R.string.knowledge_editor_source_inspector_summary,
                result.optInt("record_total"), result.optInt("active_total"),
                result.optInt("historical_total"));
        final String suffix = getString(R.string.knowledge_editor_source_inspector_warning,
                result.optInt("unverified_origins"));
        knowledgeStatus.setText(suffix);
        if (count == 0) {
            final LinearLayout page = newDockContent();
            page.addView(contextDetailText(getString(
                    R.string.knowledge_editor_source_inspector_none) + "\n\n" + suffix));
            showDockPage(title, page);
            return;
        }
        if (count == 1) {
            final JSONObject record = items.optJSONObject(0);
            if (record != null) {
                showContextRecord(record, path, result.optString("source_hash"), savedMarkdown);
            }
            return;
        }
        final LinearLayout page = newDockContent();
        page.addView(contextDetailText(suffix));
        for (int i = 0; i < count; i++) {
            final JSONObject record = items.optJSONObject(i);
            if (record == null) continue;
            final String label = editorKnowledgeRole(record.optString("role")) + " · "
                    + getString(record.optBoolean("active")
                    ? R.string.knowledge_editor_source_inspector_active
                    : R.string.knowledge_editor_source_inspector_historical)
                    + " · " + record.optString("statement");
            addContextRow(page, label, view -> showContextRecord(record, path,
                    result.optString("source_hash"), savedMarkdown));
        }
        if (result.optBoolean("truncated")) {
            page.addView(contextDetailText(getString(
                    R.string.knowledge_editor_source_inspector_truncated)));
        }
        showDockPage(title, page);
        if (result.optBoolean("truncated")) {
            Toast.makeText(this, R.string.knowledge_editor_source_inspector_truncated,
                    Toast.LENGTH_LONG).show();
        }
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
                    if (currentEditor == null || !KnowledgeEditorReviewGuard.matchesSavedSource(
                            saved, editorSnapshot, currentEditor.getTextString())) {
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

    /** Highlight verified, approved provenance only. Editable spans are not file content. */
    private void toggleEditorHighlights() {
        if (highlightsRequested) {
            clearEditorHighlights();
            setKnowledgeStatus(R.string.knowledge_editor_highlights_off);
            return;
        }
        final DocumentEditAndViewFragment fragment = editor();
        if (fragment == null) return;
        if (fragment.isViewModeVisibility()) {
            setKnowledgeStatus(R.string.knowledge_editor_highlights_edit_mode);
            return;
        }
        final File file = fragment.getDocument().file;
        final String path;
        try {
            path = relativePath(file);
        } catch (IOException ignored) {
            setKnowledgeStatus(R.string.knowledge_editor_source_invalid);
            return;
        }
        final String editorSnapshot = fragment.getTextString();
        final int generation = highlightGeneration.incrementAndGet();
        highlightsRequested = true;
        ((MaterialButton) findViewById(R.id.knowledge_editor_highlights))
                .setText(R.string.knowledge_editor_highlights_hide);
        setKnowledgeStatus(R.string.knowledge_editor_highlights_loading);
        knowledgeWorker.submit(() -> {
            try {
                if (!path.equals(relativePath(file))) throw new IOException("Selected vault changed");
                final String saved = readSavedNote(file.getCanonicalFile());
                if (!saved.equals(editorSnapshot)) throw new IOException("Unsaved editor buffer");
                final JSONObject result = KnowledgePythonBridge.editorHighlights(this,
                        new File(getNoBackupFilesDir(), "knowledge_workspace_v1").getAbsolutePath(),
                        path, saved);
                if (!sha256(saved).equals(result.optString("source_hash"))) {
                    throw new IOException("Highlights do not match saved source");
                }
                runOnUiThread(() -> {
                    if (highlightGeneration.get() != generation || !highlightsRequested
                            || isFinishing() || isDestroyed()) return;
                    final DocumentEditAndViewFragment current = editor();
                    if (current == null || current.isViewModeVisibility()
                            || !saved.equals(current.getTextString())) {
                        clearEditorHighlights();
                        setKnowledgeStatus(R.string.knowledge_editor_highlights_stale);
                        return;
                    }
                    applyEditorHighlights(current.getEditor(), result, saved);
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (highlightGeneration.get() == generation && highlightsRequested
                            && !isFinishing() && !isDestroyed()) {
                        clearEditorHighlights();
                        setKnowledgeStatus(R.string.knowledge_editor_highlights_stale);
                    }
                });
            }
        });
    }

    private int knowledgeHighlightColor(final View view, final String role) {
        final int attribute;
        switch (role) {
            case "fact": attribute = com.google.android.material.R.attr.colorPrimaryContainer; break;
            case "hypothesis": attribute = com.google.android.material.R.attr.colorTertiaryContainer; break;
            case "claim": attribute = com.google.android.material.R.attr.colorSecondaryContainer; break;
            default: attribute = com.google.android.material.R.attr.colorOutline; break;
        }
        return ColorUtils.setAlphaComponent(MaterialColors.getColor(view, attribute), 100);
    }

    private void applyEditorHighlights(final HighlightingEditor edit,
                                       final JSONObject result, final String snapshot) {
        clearEditorHighlights();
        final Editable text = edit.getText();
        if (text == null || !snapshot.contentEquals(text)) {
            setKnowledgeStatus(R.string.knowledge_editor_highlights_stale);
            return;
        }
        final JSONArray positions = result.optJSONArray("highlights");
        final int totalCodepoints = snapshot.codePointCount(0, snapshot.length());
        final int amount = positions == null ? 0 : Math.min(positions.length(), 128);
        int previousCodepoint = 0;
        int previousUtf16 = 0;
        for (int i = 0; i < amount; i++) {
            final JSONObject item = positions.optJSONObject(i);
            if (item == null) continue;
            final int start = item.optInt("start", -1);
            final int end = item.optInt("end", -1);
            if (start < previousCodepoint || end <= start || end > totalCodepoints) continue;
            // Sorted, non-overlapping Unicode spans: traverse each prefix only
            // once instead of rescanning a large note for every highlight.
            final int startUtf16 = snapshot.offsetByCodePoints(previousUtf16,
                    start - previousCodepoint);
            final int endUtf16 = snapshot.offsetByCodePoints(startUtf16, end - start);
            previousCodepoint = end;
            previousUtf16 = endUtf16;
            final BackgroundColorSpan span = new BackgroundColorSpan(
                    knowledgeHighlightColor(edit, item.optString("role")));
            text.setSpan(span, startUtf16, endUtf16, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            inlineKnowledgeSpans.add(span);
        }
        if (inlineKnowledgeSpans.isEmpty()) {
            setKnowledgeStatus(R.string.knowledge_editor_highlights_empty);
            return;
        }
        highlightedEditor = edit;
        highlightWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(final CharSequence s, final int start,
                                                    final int count, final int after) { }
            @Override public void onTextChanged(final CharSequence s, final int start,
                                               final int before, final int count) { }
            @Override public void afterTextChanged(final Editable editable) {
                clearEditorHighlights();
                setKnowledgeStatus(R.string.knowledge_editor_highlights_stale);
            }
        };
        edit.addTextChangedListener(highlightWatcher);
        highlightsRequested = true;
        ((MaterialButton) findViewById(R.id.knowledge_editor_highlights))
                .setText(R.string.knowledge_editor_highlights_hide);
        final TextView legend = findViewById(R.id.knowledge_editor_highlight_legend);
        final SpannableStringBuilder labels = new SpannableStringBuilder(getString(
                R.string.knowledge_editor_highlights_summary, inlineKnowledgeSpans.size(),
                result.optInt("highlight_total"), result.optInt("unverified_origins")));
        final String[] roles = {"fact", "hypothesis", "claim", "mixed"};
        for (final String role : roles) {
            labels.append("  ");
            final int start = labels.length();
            labels.append(" ").append("mixed".equals(role)
                    ? getString(R.string.knowledge_editor_highlights_mixed)
                    : editorKnowledgeRole(role)).append(" ");
            labels.setSpan(new BackgroundColorSpan(knowledgeHighlightColor(legend, role)),
                    start, labels.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (result.optBoolean("truncated")) {
            labels.append("\n").append(getString(R.string.knowledge_editor_highlights_truncated));
        }
        legend.setText(labels);
        legend.setVisibility(View.VISIBLE);
        setKnowledgeStatus(R.string.knowledge_editor_highlights_active);
    }

    /** Remove only DPKS-owned decoration and watcher; never replace the text. */
    private void clearEditorHighlights() {
        highlightGeneration.incrementAndGet();
        highlightsRequested = false;
        if (highlightedEditor != null) {
            if (highlightWatcher != null) highlightedEditor.removeTextChangedListener(highlightWatcher);
            final Editable buffer = highlightedEditor.getText();
            if (buffer != null) {
                for (final BackgroundColorSpan span : inlineKnowledgeSpans) buffer.removeSpan(span);
            }
        }
        highlightedEditor = null;
        highlightWatcher = null;
        inlineKnowledgeSpans.clear();
        final View legend = findViewById(R.id.knowledge_editor_highlight_legend);
        if (legend != null) legend.setVisibility(View.GONE);
        final MaterialButton button = findViewById(R.id.knowledge_editor_highlights);
        if (button != null) button.setText(R.string.knowledge_editor_highlights);
    }

    /** The summary rail is always present; the bounded detail area is collapsible. */
    private void toggleKnowledgeContext() {
        final View body = findViewById(R.id.knowledge_editor_context_body);
        setDockExpanded(body.getVisibility() != View.VISIBLE);
        if (!dockContextInitialized) refreshKnowledgeContext();
    }

    private void setDockExpanded(final boolean expanded) {
        findViewById(R.id.knowledge_editor_context_body).setVisibility(
                expanded ? View.VISIBLE : View.GONE);
        ((MaterialButton) findViewById(R.id.knowledge_editor_context_expand))
                .setText(expanded ? R.string.knowledge_editor_dock_collapse
                        : R.string.knowledge_editor_dock_expand);
    }

    /** Tablet uses a side rail; phone preserves a bounded top rail above EditText. */
    private void applyResponsiveEditorLayout() {
        final Configuration config = getResources().getConfiguration();
        final KnowledgeEditorLayoutPolicy policy = KnowledgeEditorLayoutPolicy.forWindow(
                config.screenWidthDp, config.screenHeightDp, config.smallestScreenWidthDp);
        sideDock = policy.sideDock;
        final float density = getResources().getDisplayMetrics().density;
        final LinearLayout workspace = findViewById(R.id.knowledge_editor_workspace);
        final LinearLayout panel = findViewById(R.id.knowledge_editor_context_panel);
        final LinearLayout body = findViewById(R.id.knowledge_editor_context_body);
        final View editorSurface = findViewById(R.id.document__placeholder_fragment);
        final ScrollView list = findViewById(R.id.knowledge_editor_context_list_scroll);
        final ScrollView detail = findViewById(R.id.knowledge_editor_context_detail_scroll);
        workspace.setOrientation(sideDock ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        if (sideDock) {
            panel.setLayoutParams(new LinearLayout.LayoutParams(
                    (int) (policy.dockWidthDp * density), ViewGroup.LayoutParams.MATCH_PARENT));
            editorSurface.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            body.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            list.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            detail.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            panel.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            editorSurface.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            body.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            final int scrollPx = (int) (policy.phoneScrollDp * density);
            list.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, scrollPx));
            detail.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, scrollPx));
        }
    }

    @Override
    public void onConfigurationChanged(final Configuration config) {
        super.onConfigurationChanged(config);
        applyResponsiveEditorLayout();
    }

    @Override
    protected void onSaveInstanceState(final Bundle state) {
        state.putBoolean(DOCK_EXPANDED_STATE,
                findViewById(R.id.knowledge_editor_context_body).getVisibility() == View.VISIBLE);
        super.onSaveInstanceState(state);
    }

    private void initializeContextDock() {
        if (!dockForeground || isFinishing() || isDestroyed()) return;
        final DocumentEditAndViewFragment fragment = editor();
        if (fragment == null) {
            if (++dockInitAttempts < 30 && modeButtons != null) {
                modeButtons.postDelayed(this::initializeContextDock, 100);
            }
            return;
        }
        watchDockEdits(fragment.getEditor());
        if (!dockContextInitialized) refreshKnowledgeContext();
    }

    private void watchDockEdits(final HighlightingEditor edit) {
        if (dockWatchedEditor == edit) return;
        stopWatchingDockEdits();
        dockWatchedEditor = edit;
        dockEditWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(final CharSequence s, final int start,
                                                    final int count, final int after) { }
            @Override public void onTextChanged(final CharSequence s, final int start,
                                               final int before, final int count) { }
            @Override public void afterTextChanged(final Editable value) {
                if (!dockContextInitialized || dockContextStale) return;
                dockContextStale = true;
                ((TextView) findViewById(R.id.knowledge_editor_context_summary))
                        .setText(R.string.knowledge_editor_dock_stale);
                ((TextView) findViewById(R.id.knowledge_editor_context_warning))
                        .append("\n\n" + getString(R.string.knowledge_editor_context_unsaved));
            }
        };
        edit.addTextChangedListener(dockEditWatcher);
    }

    private void stopWatchingDockEdits() {
        if (dockWatchedEditor != null && dockEditWatcher != null) {
            dockWatchedEditor.removeTextChangedListener(dockEditWatcher);
        }
        dockWatchedEditor = null;
        dockEditWatcher = null;
    }

    /** Rebuild only from the saved note; never save it or promote index candidates. */
    private void refreshKnowledgeContext() {
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
        final int generation = dockGeneration.incrementAndGet();
        dockContextInitialized = true;
        dockContextStale = false;
        resetDockNavigation();
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
                    if (!currentDock(generation)) return;
                    final DocumentEditAndViewFragment currentEditor = editor();
                    try {
                        if (currentEditor == null || !path.equals(relativePath(
                                currentEditor.getDocument().file))
                                || !_appSettings.getNotebookDirectory().getCanonicalFile()
                                .equals(selectedVault)) {
                            throw new IOException("Vault changed");
                        }
                        final boolean unsaved = !saved.equals(currentEditor.getTextString());
                        dockContextStale = unsaved;
                        renderKnowledgeContext(context, saved, unsaved, selectedVault);
                    } catch (IOException ignored) {
                        ((TextView) findViewById(R.id.knowledge_editor_context_summary))
                                .setText(R.string.knowledge_editor_context_failed);
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (currentDock(generation)) {
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
            final String spanState = item.optString("source_span_state");
            final String title = role + " · " + statement
                    + ("verified".equals(spanState) ? "" : " · " + getString(
                    "stale_source".equals(spanState)
                            ? R.string.knowledge_editor_context_record_stale
                            : R.string.knowledge_editor_context_span_unavailable));
            addContextRow(accepted, title, view ->
                    showContextRecord(item, context.optString("path"),
                            context.optString("source_hash"), savedMarkdown));
        }
        if (recordCount == 0) {
            addContextEmpty(accepted, R.string.knowledge_editor_context_empty_records);
        }

        final LinearLayout related = findViewById(R.id.knowledge_editor_context_relations_list);
        for (int i = 0; i < relationCount; i++) {
            final JSONObject item = relations.optJSONObject(i);
            if (item == null) continue;
            final String kind = item.optString("kind");
            final int label = "temporal_overlap".equals(kind)
                    ? R.string.knowledge_possible_temporal_overlap
                    : "possible_conflict".equals(kind)
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
        if (unsavedBuffer) {
            ((TextView) findViewById(R.id.knowledge_editor_context_summary))
                    .setText(R.string.knowledge_editor_dock_stale);
        }
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
        // The visual row is bounded, while assistive services get a longer
        // locally displayed label without changing the original text.
        item.setContentDescription(label.length() > 512 ? label.substring(0, 512) + "…" : label);
        item.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
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

    private LinearLayout newDockContent() {
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        final int inset = (int) (8 * getResources().getDisplayMetrics().density);
        content.setPadding(inset, inset, inset, inset);
        return content;
    }

    /** At most eight back steps; previous views remain detached and read-only. */
    private void showDockPage(final String title, final LinearLayout content) {
        if (dockCurrentPage != null) {
            dockCurrentPage.scrollY = ((ScrollView) findViewById(
                    R.id.knowledge_editor_context_detail_scroll)).getScrollY();
            if (dockPageHistory.size() >= 8) dockPageHistory.remove(0);
            dockPageHistory.add(dockCurrentPage);
        }
        dockCurrentPage = new DockPage(title, content);
        renderDockPage();
        setDockExpanded(true);
    }

    private void renderDockPage() {
        final ScrollView list = findViewById(R.id.knowledge_editor_context_list_scroll);
        final ScrollView detail = findViewById(R.id.knowledge_editor_context_detail_scroll);
        final LinearLayout container = findViewById(R.id.knowledge_editor_context_detail_content);
        final View navigation = findViewById(R.id.knowledge_editor_context_navigation);
        container.removeAllViews();
        if (dockCurrentPage == null) {
            detail.setVisibility(View.GONE);
            navigation.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
            ViewCompat.setAccessibilityPaneTitle(list,
                    getString(R.string.knowledge_editor_context));
            return;
        }
        list.setVisibility(View.GONE);
        detail.setVisibility(View.VISIBLE);
        navigation.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.knowledge_editor_context_page_title))
                .setText(dockCurrentPage.title);
        ViewCompat.setAccessibilityPaneTitle(detail, dockCurrentPage.title);
        container.addView(dockCurrentPage.content);
        final DockPage displayed = dockCurrentPage;
        detail.post(() -> {
            if (dockCurrentPage == displayed) detail.scrollTo(0, displayed.scrollY);
        });
    }

    private void navigateDockBack() {
        dockCurrentPage = dockPageHistory.isEmpty()
                ? null : dockPageHistory.remove(dockPageHistory.size() - 1);
        renderDockPage();
    }

    private void resetDockNavigation() {
        dockPageHistory.clear();
        dockCurrentPage = null;
        renderDockPage();
    }

    private String editorKnowledgeRole(final String role) {
        switch (role) {
            case "fact": return getString(R.string.knowledge_decision_fact);
            case "hypothesis": return getString(R.string.knowledge_decision_hypothesis);
            case "claim": return getString(R.string.knowledge_decision_claim);
            default: return role;
        }
    }

    /** Only literal differences between two verified representations, never a semantic proof. */
    private void showContextRecord(final JSONObject record, final String path,
                                   final String savedHash, final String savedMarkdown) {
        final boolean verified = "verified".equals(record.optString("source_span_state"));
        final LinearLayout body = newDockContent();
        final String sourceExcerpt = record.optString("source_excerpt");
        final String formalStatement = record.optString("statement");
        final KnowledgeTextDiff.Result comparison = verified
                && !record.optBoolean("source_text_truncated")
                && !record.optBoolean("statement_truncated")
                ? KnowledgeTextDiff.compare(sourceExcerpt, formalStatement)
                : KnowledgeTextDiff.unavailable();
        addComparisonBlock(body, R.string.knowledge_editor_context_source_snapshot,
                sourceExcerpt, comparison.sourceChanges);
        addComparisonBlock(body, R.string.knowledge_editor_context_formal_statement,
                formalStatement, comparison.formalChanges);
        body.addView(contextDetailText(getString(!comparison.available
                ? R.string.knowledge_editor_text_diff_unavailable
                : comparison.hasChanges() ? R.string.knowledge_editor_text_diff_legend
                : R.string.knowledge_editor_text_diff_identical)));
        renderStructuralComparison(body, record.optJSONObject("structured_comparison"));
        final StringBuilder detail = new StringBuilder(getString(
                R.string.knowledge_editor_context_provenance,
                record.optString("provenance_kind"), record.optString("locator")));
        if (verified) {
            detail.append("\n").append(getString(R.string.knowledge_editor_context_exact_location,
                    record.optInt("source_line"), record.optInt("source_column")));
        } else {
            detail.append("\n").append(getString(R.string.knowledge_editor_context_span_unavailable));
        }
        if (record.optBoolean("source_text_truncated")
                || record.optBoolean("statement_truncated")) {
            detail.append("\n").append(getString(
                    R.string.knowledge_editor_context_truncated_excerpt));
        }
        if (record.has("active")) {
            detail.append("\n\n").append(getString(record.optBoolean("active")
                    ? R.string.knowledge_editor_source_inspector_active
                    : R.string.knowledge_editor_source_inspector_historical));
            detail.append(" · ").append(record.optString("status"));
        }
        if (!record.optBoolean("conflicts_available")) {
            detail.append("\n\n")
                    .append(getString(R.string.knowledge_editor_context_conflicts_unavailable));
        } else if (record.optInt("conflict_count") > 0) {
            detail.append("\n\n")
                    .append(getString(R.string.knowledge_editor_context_conflict))
                    .append(" · ").append(record.optInt("conflict_count"));
            final JSONArray conflicts = record.optJSONArray("conflict_records");
            if (conflicts != null) {
                for (int i = 0; i < conflicts.length(); i++) {
                    final JSONObject opposing = conflicts.optJSONObject(i);
                    if (opposing != null) {
                        detail.append("\n• ")
                                .append(editorKnowledgeRole(opposing.optString("role")))
                                .append(" · ").append(opposing.optString("status"))
                                .append(" · ").append(opposing.optString("statement"));
                    }
                }
            }
        }
        final JSONArray history = record.optJSONArray("history");
        if (history != null) {
            detail.append("\n\n").append(getString(R.string.knowledge_editor_source_inspector_history,
                    record.optInt("history_total")));
            for (int i = 0; i < history.length(); i++) {
                final JSONObject event = history.optJSONObject(i);
                if (event != null) {
                    detail.append("\n").append(event.optString("time"))
                            .append(" · ").append(event.optString("op"));
                }
            }
            if (record.optInt("history_total") > history.length()) {
                detail.append("\n").append(getString(
                        R.string.knowledge_editor_source_inspector_history_partial));
            }
        }
        detail.append("\n\n").append(getString(
                R.string.knowledge_editor_context_comparison_disclaimer));
        body.addView(contextDetailText(detail.toString()));
        if (!record.optString("id").isEmpty()) {
            addContextRow(body, getString(R.string.knowledge_editor_evidence_open),
                    view -> inspectRecordEvidence(record.optString("id")));
        }
        if (verified) {
            addContextRow(body, getString(R.string.knowledge_editor_context_jump),
                    view -> jumpToSavedSource(record, path, savedHash, savedMarkdown));
        }
        showDockPage(editorKnowledgeRole(record.optString("role")), body);
    }

    /** The CNL/IR comparison is a bounded machine reading, never a truth verdict. */
    private void renderStructuralComparison(final LinearLayout body, final JSONObject comparison) {
        addEvidenceHeading(body, getString(R.string.knowledge_editor_structural_title));
        if (comparison == null || !comparison.optBoolean("read_only")
                || comparison.optBoolean("proves_truth")
                || !"FORMALIZED".equals(comparison.optString("source_formalization"))) {
            body.addView(contextDetailText(getString(R.string.knowledge_editor_structural_unavailable)));
            return;
        }
        final String state = comparison.optString("status");
        if ("same_structure".equals(state)) {
            body.addView(contextDetailText(getString(R.string.knowledge_editor_structural_same)));
        } else if ("different_structure".equals(state)) {
            final JSONArray changes = comparison.optJSONArray("changed_fields");
            if (changes == null || changes.length() == 0 || changes.length() > 6) {
                body.addView(contextDetailText(getString(
                        R.string.knowledge_editor_structural_unavailable)));
                return;
            }
            for (int i = 0; i < changes.length(); i++) {
                final JSONObject changed = changes.optJSONObject(i);
                if (changed == null) continue;
                body.addView(contextDetailText(getString(R.string.knowledge_editor_structural_change,
                        structuralField(changed.optString("field")),
                        changed.optString("source"), changed.optString("accepted"))));
            }
        } else {
            body.addView(contextDetailText(getString(R.string.knowledge_editor_structural_unavailable)));
            return;
        }
        body.addView(contextDetailText(getString(R.string.knowledge_editor_structural_disclaimer)));
    }

    private String structuralField(final String field) {
        switch (field) {
            case "predicate": return getString(R.string.knowledge_editor_structural_predicate);
            case "arguments": return getString(R.string.knowledge_editor_structural_arguments);
            case "negation": return getString(R.string.knowledge_editor_structural_negation);
            case "modality": return getString(R.string.knowledge_editor_structural_modality);
            case "context": return getString(R.string.knowledge_editor_structural_context);
            case "interval": return getString(R.string.knowledge_editor_structural_interval);
            default: return field;
        }
    }

    /** Navigate only among human-approved journal records; never index candidates. */
    private void inspectRecordEvidence(final String recordId) {
        if (recordId == null || recordId.isEmpty()) return;
        final int generation = operationGeneration.incrementAndGet();
        setKnowledgeStatus(R.string.knowledge_editor_working);
        knowledgeWorker.submit(() -> {
            try {
                final JSONObject evidence = KnowledgePythonBridge.editorRecordEvidence(this,
                        new File(getNoBackupFilesDir(), "knowledge_workspace_v1").getAbsolutePath(),
                        recordId);
                if (!recordId.equals(evidence.optString("id"))
                        || !evidence.optBoolean("only_journal_records")
                        || !evidence.optBoolean("read_only")) {
                    throw new IOException("Untrusted evidence response");
                }
                runOnUiThread(() -> {
                    if (current(generation)) renderRecordEvidence(evidence);
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (current(generation)) setKnowledgeStatus(R.string.knowledge_editor_evidence_failed);
                });
            }
        });
    }

    private void renderRecordEvidence(final JSONObject evidence) {
        final LinearLayout body = newDockContent();
        addComparisonBlock(body, R.string.knowledge_editor_context_formal_statement,
                evidence.optString("statement"));
        body.addView(contextDetailText(editorKnowledgeRole(evidence.optString("role")) + " · "
                + evidence.optString("status") + " · " + getString(evidence.optBoolean("active")
                ? R.string.knowledge_editor_source_inspector_active
                : R.string.knowledge_editor_source_inspector_historical)));

        final JSONArray origins = evidence.optJSONArray("origins");
        addEvidenceHeading(body, getString(R.string.knowledge_editor_evidence_origins,
                evidence.optInt("origins_total")));
        if (origins != null) {
            for (int i = 0; i < origins.length(); i++) {
                final JSONObject origin = origins.optJSONObject(i);
                if (origin == null) continue;
                final String label = getString(R.string.knowledge_editor_evidence_unverified)
                        + "\n" + origin.optString("path") + " · " + origin.optString("locator")
                        + "\n" + origin.optString("source_excerpt");
                addContextRow(body, label, view -> openEvidenceOrigin(origin));
            }
        }
        if (origins == null || origins.length() == 0) {
            addContextEmpty(body, R.string.knowledge_editor_evidence_no_origins);
        }

        final JSONArray links = evidence.optJSONArray("links");
        addEvidenceHeading(body, getString(R.string.knowledge_editor_evidence_links,
                evidence.optInt("links_total")));
        if (links != null) {
            for (int i = 0; i < links.length(); i++) {
                final JSONObject link = links.optJSONObject(i);
                if (link == null) continue;
                final JSONArray kinds = link.optJSONArray("kinds");
                final StringBuilder types = new StringBuilder();
                if (kinds != null) {
                    for (int k = 0; k < kinds.length(); k++) {
                        if (types.length() > 0) types.append(" · ");
                        types.append(evidenceLinkKind(kinds.optString(k)));
                    }
                }
                final String title = types + " · " + editorKnowledgeRole(link.optString("role"))
                        + "\n" + link.optString("statement");
                addContextRow(body, title, view -> inspectRecordEvidence(link.optString("id")));
            }
        }
        if (links == null || links.length() == 0) {
            addContextEmpty(body, R.string.knowledge_editor_evidence_no_links);
        }
        if (!evidence.optBoolean("conflicts_available")) {
            addContextEmpty(body, R.string.knowledge_editor_context_conflicts_unavailable);
        }

        final JSONArray events = evidence.optJSONArray("events");
        addEvidenceHeading(body, getString(R.string.knowledge_editor_source_inspector_history,
                evidence.optInt("events_total")));
        if (events != null) {
            for (int i = 0; i < events.length(); i++) {
                final JSONObject event = events.optJSONObject(i);
                if (event != null) body.addView(contextDetailText("#" + event.optInt("seq")
                        + " · " + event.optString("op") + " · " + event.optString("time")));
            }
        }
        if (evidence.optBoolean("origins_truncated") || evidence.optBoolean("links_truncated")
                || evidence.optBoolean("events_truncated")) {
            body.addView(contextDetailText(getString(R.string.knowledge_editor_evidence_truncated)));
        }
        body.addView(contextDetailText(getString(R.string.knowledge_editor_evidence_disclaimer)));
        showDockPage(getString(R.string.knowledge_editor_evidence_title), body);
    }

    private void addEvidenceHeading(final LinearLayout body, final String title) {
        final TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        heading.setPadding(8, 16, 8, 8);
        body.addView(heading);
    }

    private String evidenceLinkKind(final String kind) {
        switch (kind) {
            case "predecessor": return getString(R.string.knowledge_editor_evidence_predecessor);
            case "successor": return getString(R.string.knowledge_editor_evidence_successor);
            case "possible_conflict": return getString(R.string.knowledge_editor_evidence_conflict);
            default: return getString(R.string.knowledge_editor_evidence_link);
        }
    }

    /** An origin path in the journal is never a trusted file location. */
    private void openEvidenceOrigin(final JSONObject origin) {
        final String relative = origin.optString("path");
        final String expectedSha = origin.optString("document_sha");
        final String textSha = origin.optString("source_text_sha");
        final JSONArray span = origin.optJSONArray("source_span");
        if (span == null || span.length() != 2 || expectedSha.length() != 64
                || textSha.length() != 64) {
            evidenceOriginUnavailable();
            return;
        }
        final int start = span.optInt(0, -1);
        final int end = span.optInt(1, -1);
        final File selectedVault;
        final File target;
        try {
            selectedVault = _appSettings.getNotebookDirectory().getCanonicalFile();
            target = new File(selectedVault, relative).getCanonicalFile();
            if (!relative.equals(relativePath(target))) throw new IOException("Invalid origin path");
        } catch (Exception ignored) {
            evidenceOriginUnavailable();
            return;
        }
        final int generation = operationGeneration.incrementAndGet();
        setKnowledgeStatus(R.string.knowledge_editor_working);
        knowledgeWorker.submit(() -> {
            try {
                if (!relative.equals(relativePath(target))) throw new IOException("Path changed");
                final String disk = readSavedNote(target.getCanonicalFile());
                if (!verifiedEvidenceSpan(disk, expectedSha, textSha, start, end)) {
                    throw new IOException("Origin does not match current saved source");
                }
                runOnUiThread(() -> {
                    if (!current(generation)) return;
                    try {
                        if (!_appSettings.getNotebookDirectory().getCanonicalFile().equals(selectedVault)
                                || !relative.equals(relativePath(target))) {
                            throw new IOException("Vault or path changed");
                        }
                        final DocumentEditAndViewFragment currentEditor = editor();
                        if (currentEditor != null && target.getCanonicalFile().equals(
                                currentEditor.getDocument().file.getCanonicalFile())) {
                            final JSONObject destination = new JSONObject();
                            destination.put("source_span_state", "verified");
                            destination.put("source_span", new JSONArray().put(start).put(end));
                            jumpToSavedSource(destination, relative, expectedSha, disk);
                        } else {
                            setDockExpanded(false);
                            openAtSource(this, target, relative, expectedSha, textSha, start, end);
                        }
                    } catch (Exception ignored) {
                        evidenceOriginUnavailable();
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (current(generation)) evidenceOriginUnavailable();
                });
            }
        });
    }

    private void evidenceOriginUnavailable() {
        setKnowledgeStatus(R.string.knowledge_editor_evidence_unavailable);
        Toast.makeText(this, R.string.knowledge_editor_evidence_unavailable,
                Toast.LENGTH_LONG).show();
    }

    private static boolean verifiedEvidenceSpan(final String disk, final String docSha,
                                                final String sourceSha, final int start,
                                                final int end) throws Exception {
        final int count = disk.codePointCount(0, disk.length());
        if (start < 0 || end <= start || end > count || !docSha.equals(sha256(disk))) return false;
        final int a = disk.offsetByCodePoints(0, start);
        final int b = disk.offsetByCodePoints(a, end - start);
        return sourceSha.equals(sha256(disk.substring(a, b)));
    }

    /** Recheck a cross-note origin after its editor loads, not only before launch. */
    private void selectIncomingSource() {
        final Intent intent = getIntent();
        if (incomingJumpStarted || !intent.hasExtra(JUMP_PATH)) return;
        final DocumentEditAndViewFragment fragment = editor();
        if (fragment == null) {
            if (++incomingJumpAttempts < 12 && modeButtons != null) {
                modeButtons.postDelayed(this::selectIncomingSource, 100);
            } else {
                incomingJumpStarted = true;
                setKnowledgeStatus(R.string.knowledge_editor_evidence_unavailable);
            }
            return;
        }
        incomingJumpStarted = true;
        final String path = intent.getStringExtra(JUMP_PATH);
        final String docSha = intent.getStringExtra(JUMP_SHA);
        final String textSha = intent.getStringExtra(JUMP_TEXT_SHA);
        final int start = intent.getIntExtra(JUMP_START, -1);
        final int end = intent.getIntExtra(JUMP_END, -1);
        final File file = fragment.getDocument().file;
        final int generation = operationGeneration.incrementAndGet();
        knowledgeWorker.submit(() -> {
            try {
                if (path == null || !path.equals(relativePath(file)) || docSha == null
                        || textSha == null) throw new IOException("Different source path");
                final String saved = readSavedNote(file.getCanonicalFile());
                if (!verifiedEvidenceSpan(saved, docSha, textSha, start, end)) {
                    throw new IOException("Source changed before editor opened");
                }
                runOnUiThread(() -> {
                    if (!current(generation)) return;
                    final DocumentEditAndViewFragment current = editor();
                    if (current == null || !saved.equals(current.getTextString())) {
                        setKnowledgeStatus(R.string.knowledge_editor_context_reload);
                        return;
                    }
                    setMode(false);
                    current.getEditor().setSelection(saved.offsetByCodePoints(0, start),
                            saved.offsetByCodePoints(0, end));
                    current.getEditor().requestFocus();
                    setKnowledgeStatus(R.string.knowledge_editor_evidence_verified);
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (current(generation)) setKnowledgeStatus(R.string.knowledge_editor_evidence_unavailable);
                });
            }
        });
    }

    private void addComparisonBlock(final LinearLayout parent, final int label,
                                    final String content) {
        addComparisonBlock(parent, label, content, java.util.Collections.emptyList());
    }

    /** Transient selectable styling only: the source string and journal stay untouched. */
    private void addComparisonBlock(final LinearLayout parent, final int label,
                                    final String content,
                                    final List<KnowledgeTextDiff.Range> changed) {
        final TextView title = new TextView(this);
        title.setText(label);
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        title.setPadding(6, 8, 6, 6);
        parent.addView(title);
        final TextView value = contextDetailText(content);
        value.setBackgroundColor(MaterialColors.getColor(value,
                com.google.android.material.R.attr.colorSurfaceContainer));
        if (!changed.isEmpty()) {
            final SpannableStringBuilder decorated = new SpannableStringBuilder(content);
            final int background = MaterialColors.getColor(value,
                    com.google.android.material.R.attr.colorTertiaryContainer);
            final int foreground = MaterialColors.getColor(value,
                    com.google.android.material.R.attr.colorOnTertiaryContainer);
            for (final KnowledgeTextDiff.Range range : changed) {
                if (range.start < 0 || range.end > content.length() || range.start >= range.end) {
                    continue;
                }
                decorated.setSpan(new BackgroundColorSpan(background), range.start, range.end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                decorated.setSpan(new ForegroundColorSpan(foreground), range.start, range.end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                decorated.setSpan(new StyleSpan(Typeface.BOLD), range.start, range.end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            value.setText(decorated, TextView.BufferType.SPANNABLE);
        }
        parent.addView(value);
    }

    private void showContextRelation(final JSONObject relation, final int title,
                                     final File selectedVault) {
        final String description = getString(R.string.knowledge_editor_context_disclaimer)
                + "\n\n" + getString(R.string.knowledge_editor_context_source)
                + ": " + relation.optString("source_locator")
                + "\n" + relation.optString("path") + " · "
                + relation.optString("other_locator")
                + KnowledgeRelationPresentation.qualifiers(this, relation);
        final LinearLayout body = newDockContent();
        body.addView(contextDetailText(description));
        addContextRow(body, getString(R.string.knowledge_editor_context_open),
                view -> openRelatedNote(relation.optString("path"), selectedVault));
        showDockPage(getString(title), body);
    }

    private TextView contextDetailText(final String text) {
        final TextView label = new TextView(this);
        final int inset = (int) (18 * getResources().getDisplayMetrics().density);
        label.setPadding(inset, inset, inset, inset);
        label.setText(text);
        label.setTextIsSelectable(true);
        return label;
    }

    /** Unicode source offsets from verified provenance; disk is re-read before navigation. */
    private void jumpToSavedSource(final JSONObject record, final String path,
                                   final String expectedHash, final String savedSnapshot) {
        final DocumentEditAndViewFragment initial = editor();
        if (initial == null || !"verified".equals(record.optString("source_span_state"))) return;
        final File file = initial.getDocument().file;
        final JSONArray span = record.optJSONArray("source_span");
        if (span == null || span.length() != 2) return;
        final int start = span.optInt(0, -1);
        final int end = span.optInt(1, -1);
        final int generation = operationGeneration.incrementAndGet();
        setKnowledgeStatus(R.string.knowledge_editor_working);
        knowledgeWorker.submit(() -> {
            try {
                if (!path.equals(relativePath(file))) throw new IOException("Vault changed");
                final String disk = readSavedNote(file.getCanonicalFile());
                if (!savedSnapshot.equals(disk) || !expectedHash.equals(sha256(disk))) {
                    throw new IOException("Source changed on disk");
                }
                runOnUiThread(() -> {
                    if (!current(generation)) return;
                    final DocumentEditAndViewFragment fragment = editor();
                    if (fragment == null || !savedSnapshot.equals(fragment.getTextString())) {
                        setKnowledgeStatus(R.string.knowledge_editor_context_reload);
                        return;
                    }
                    final String buffer = fragment.getTextString();
                    final int count = buffer.codePointCount(0, buffer.length());
                    if (start < 0 || end <= start || end > count) {
                        setKnowledgeStatus(R.string.knowledge_editor_context_reload);
                        return;
                    }
                    setMode(false);
                    fragment.getEditor().setSelection(buffer.offsetByCodePoints(0, start),
                            buffer.offsetByCodePoints(0, end));
                    fragment.getEditor().requestFocus();
                    operationGeneration.incrementAndGet();
                    setDockExpanded(false);
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (current(generation)) setKnowledgeStatus(R.string.knowledge_editor_context_reload);
                });
            }
        });
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

    private boolean currentDock(final int generation) {
        return dockForeground && !isFinishing() && !isDestroyed()
                && dockGeneration.get() == generation;
    }

    @Override
    protected void onStop() {
        // Do not surface stale dialogs over the library or another editor.
        dockForeground = false;
        operationGeneration.incrementAndGet();
        dockGeneration.incrementAndGet();
        clearEditorHighlights();
        stopWatchingDockEdits();
        dockContextInitialized = false;
        dockContextStale = true;
        dockInitAttempts = 0;
        resetDockNavigation();
        ((TextView) findViewById(R.id.knowledge_editor_context_summary))
                .setText(R.string.knowledge_editor_dock_initial);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        operationGeneration.incrementAndGet();
        clearEditorHighlights();
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
        if (dockCurrentPage != null
                && findViewById(R.id.knowledge_editor_context_body).getVisibility() == View.VISIBLE) {
            navigateDockBack();
            return;
        }
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
