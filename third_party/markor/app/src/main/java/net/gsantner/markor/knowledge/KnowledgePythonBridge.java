/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import android.content.Context;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Thin Android transport to the *actual* Python ksys package, without a
 * separate Java reimplementation of its source or knowledge semantics.
 * Invoke from a worker thread. Only the caller owns the original Markdown.
 */
public final class KnowledgePythonBridge {
    private KnowledgePythonBridge() {
    }

    public interface BulkProgress {
        boolean onProgress(int processed);
    }

    public static JSONObject analyzeVault(final Context context, final String workspace,
                                          final String notebook, final BulkProgress progress)
            throws JSONException {
        return invoke(context, "analyze_vault", workspace, notebook, progress);
    }

    public static JSONObject reviewQueue(final Context context, final String workspace,
                                         final int offset) throws JSONException {
        return invoke(context, "review_queue", workspace, offset);
    }

    public static JSONObject relationships(final Context context, final String workspace,
                                           final String relativePath, final String notebook)
            throws JSONException {
        return invoke(context, "note_relationships", workspace, relativePath, notebook);
    }

    /** Strict manual entity lifecycle: no inferred merges or Markdown changes. */
    public static JSONObject entityAliasWorkspace(final Context context, final String workspace,
                                                 final String notebook, final String alias,
                                                 final String canonical) throws JSONException {
        return invoke(context, "entity_alias_workspace", workspace, notebook, alias, canonical);
    }

    public static JSONObject entityAliasDecisions(final Context context, final String workspace)
            throws JSONException {
        return invoke(context, "entity_alias_decisions", workspace);
    }

    public static JSONObject previewEntityAlias(final Context context, final String workspace,
                                                final String notebook, final String alias,
                                                final String canonical, final String aliasPath,
                                                final String aliasFragment, final String canonicalPath,
                                                final String canonicalFragment, final String reason)
            throws JSONException {
        return invoke(context, "preview_entity_alias", workspace, notebook, alias, canonical,
                aliasPath, aliasFragment, canonicalPath, canonicalFragment, reason);
    }

    public static JSONObject confirmEntityAlias(final Context context, final String workspace,
                                                final String notebook, final String alias,
                                                final String canonical, final String aliasPath,
                                                final String aliasFragment, final String canonicalPath,
                                                final String canonicalFragment, final String reason,
                                                final String token, final String expectedHead)
            throws JSONException {
        return invoke(context, "record_entity_alias", workspace, notebook, alias, canonical,
                aliasPath, aliasFragment, canonicalPath, canonicalFragment, reason,
                token, expectedHead);
    }

    public static JSONObject previewEntityUnalias(final Context context, final String workspace,
                                                  final String alias) throws JSONException {
        return invoke(context, "preview_entity_unalias", workspace, alias);
    }

    public static JSONObject confirmEntityUnalias(final Context context, final String workspace,
                                                  final String alias, final String token,
                                                  final String expectedHead) throws JSONException {
        return invoke(context, "record_entity_unalias", workspace, alias, token, expectedHead);
    }

    /** Explicit, reversible common-upstream source annotations (not truth votes). */
    public static JSONObject sourceLineageDecisions(final Context context, final String workspace)
            throws JSONException {
        return invoke(context, "source_lineage_decisions", workspace);
    }

    public static JSONObject previewSourceLink(final Context context, final String workspace,
                                               final String notebook, final String firstPath,
                                               final String secondPath, final String reason)
            throws JSONException {
        return invoke(context, "preview_source_link", workspace, notebook,
                firstPath, secondPath, reason);
    }

    public static JSONObject confirmSourceLink(final Context context, final String workspace,
                                               final String notebook, final String firstPath,
                                               final String secondPath, final String reason,
                                               final String token, final String expectedHead)
            throws JSONException {
        return invoke(context, "record_source_link", workspace, notebook,
                firstPath, secondPath, reason, token, expectedHead);
    }

    public static JSONObject previewSourceUnlink(final Context context, final String workspace,
                                                 final String decisionId) throws JSONException {
        return invoke(context, "preview_source_unlink", workspace, decisionId);
    }

    public static JSONObject confirmSourceUnlink(final Context context, final String workspace,
                                                 final String decisionId, final String token,
                                                 final String expectedHead) throws JSONException {
        return invoke(context, "record_source_unlink", workspace, decisionId,
                token, expectedHead);
    }

    /** User-authored source *type*, never a reliability score or fact approval. */
    public static JSONObject sourceAssessmentDecisions(final Context context,
                                                       final String workspace) throws JSONException {
        return invoke(context, "source_assessment_decisions", workspace);
    }

    public static JSONObject previewSourceAssessment(final Context context, final String workspace,
                                                     final String notebook, final String path,
                                                     final String kind, final String reason)
            throws JSONException {
        return invoke(context, "preview_source_assessment", workspace, notebook,
                path, kind, reason);
    }

    public static JSONObject confirmSourceAssessment(final Context context, final String workspace,
                                                     final String notebook, final String path,
                                                     final String kind, final String reason,
                                                     final String token, final String expectedHead)
            throws JSONException {
        return invoke(context, "record_source_assessment", workspace, notebook,
                path, kind, reason, token, expectedHead);
    }

    public static JSONObject previewSourceUnassessment(final Context context, final String workspace,
                                                       final String decisionId) throws JSONException {
        return invoke(context, "preview_source_unassessment", workspace, decisionId);
    }

    public static JSONObject confirmSourceUnassessment(final Context context, final String workspace,
                                                       final String decisionId, final String token,
                                                       final String expectedHead) throws JSONException {
        return invoke(context, "record_source_unassessment", workspace, decisionId,
                token, expectedHead);
    }

    /** Descriptive, revocable human interpretation of an exact dated source pair. */
    public static JSONObject versionReviewDecisions(final Context context, final String workspace)
            throws JSONException {
        return invoke(context, "version_review_decisions", workspace);
    }

    public static JSONObject previewVersionReview(final Context context, final String workspace,
                                                  final String notebook, final String sourcePath,
                                                  final String relationJson, final String choice,
                                                  final String reason) throws JSONException {
        return invoke(context, "preview_version_review", workspace, notebook, sourcePath,
                relationJson, choice, reason);
    }

    public static JSONObject confirmVersionReview(final Context context, final String workspace,
                                                  final String notebook, final String sourcePath,
                                                  final String relationJson, final String choice,
                                                  final String reason, final String token,
                                                  final String expectedHead) throws JSONException {
        return invoke(context, "record_version_review", workspace, notebook, sourcePath,
                relationJson, choice, reason, token, expectedHead);
    }

    public static JSONObject previewVersionUnreview(final Context context, final String workspace,
                                                    final String decisionId) throws JSONException {
        return invoke(context, "preview_version_unreview", workspace, decisionId);
    }

    public static JSONObject confirmVersionUnreview(final Context context, final String workspace,
                                                    final String decisionId, final String token,
                                                    final String expectedHead) throws JSONException {
        return invoke(context, "record_version_unreview", workspace, decisionId,
                token, expectedHead);
    }

    public static JSONObject fullAudit(final Context context, final String workspace,
                                       final String notebook, final BulkProgress progress)
            throws JSONException {
        return invoke(context, "full_library_audit", workspace, notebook, progress);
    }

    public static JSONObject auditPage(final Context context, final String workspace,
                                       final String category, final int offset)
            throws JSONException {
        return invoke(context, "full_audit_page", workspace, category, offset);
    }

    public static JSONObject inspect(final Context context, final String relativePath,
                                     final String markdown) throws JSONException {
        return invoke(context, "inspect_note", relativePath, markdown);
    }

    /** Saved-note context: accepted journal records plus tentative, read-only links. */
    public static JSONObject editorContext(final Context context, final String workspace,
                                           final String relativePath, final String savedMarkdown,
                                           final String notebook)
            throws JSONException {
        return invoke(context, "editor_note_context", workspace, relativePath, savedMarkdown,
                notebook);
    }

    /** Ephemeral, approved-only source spans: no index or journal write. */
    public static JSONObject editorHighlights(final Context context, final String workspace,
                                              final String relativePath, final String savedMarkdown)
            throws JSONException {
        return invoke(context, "editor_note_highlights", workspace, relativePath, savedMarkdown);
    }

    /** Read-only accepted and historical journal records covering one source selection. */
    public static JSONObject editorSourceRecords(final Context context, final String workspace,
                                                 final String relativePath, final String savedMarkdown,
                                                 final int codepointStart, final int codepointEnd)
            throws JSONException {
        return invoke(context, "editor_source_records", workspace, relativePath,
                savedMarkdown, codepointStart, codepointEnd);
    }

    /** Bounded journal evidence/lineage. Stored source paths are not trusted until opened. */
    public static JSONObject editorRecordEvidence(final Context context, final String workspace,
                                                  final String recordId) throws JSONException {
        return invoke(context, "editor_record_evidence", workspace, recordId);
    }

    /** Synthetic Stage 22 self-test in app-private cache, never the selected vault. */
    public static JSONObject stage22EditorSelfTest(final Context context,
                                                  final String privateCacheDirectory)
            throws JSONException {
        return invoke(context, "stage22_editor_selftest", privateCacheDirectory);
    }

    /** Locate the single semantic fragment enclosing a manually selected span. */
    public static JSONObject inspectSelection(final Context context, final String relativePath,
                                              final String markdown, final int codepointStart,
                                              final int codepointEnd) throws JSONException {
        return invoke(context, "inspect_selection", relativePath, markdown,
                codepointStart, codepointEnd);
    }

    public static JSONObject preview(final Context context, final String workspace,
                                     final String relativePath, final String markdown,
                                     final String fragmentId) throws JSONException {
        return invoke(context, "preview_fragment", workspace, relativePath, markdown, fragmentId);
    }

    /** A separate, user-authored statement linked to the exact source fragment. */
    public static JSONObject previewManual(final Context context, final String workspace,
                                           final String relativePath, final String markdown,
                                           final String fragmentId, final String manualText)
            throws JSONException {
        return invoke(context, "preview_manual_fragment", workspace, relativePath, markdown,
                fragmentId, manualText);
    }

    public static JSONObject decideManual(final Context context, final String workspace,
                                          final String relativePath, final String markdown,
                                          final String fragmentId, final String manualText,
                                          final String manualTextHash, final String readingId,
                                          final String role, final String sourceHash,
                                          final String journalHead) throws JSONException {
        return invoke(context, "record_manual_decision", workspace, relativePath, markdown,
                fragmentId, manualText, manualTextHash, readingId, role,
                sourceHash, journalHead);
    }

    public static JSONObject decide(final Context context, final String workspace,
                                    final String relativePath, final String markdown,
                                    final String fragmentId, final String readingId,
                                    final String role, final String sourceHash,
                                    final String journalHead) throws JSONException {
        return invoke(context, "record_decision", workspace, relativePath, markdown,
                fragmentId, readingId, role, sourceHash, journalHead);
    }

    public static JSONObject previewClaimBatch(final Context context, final String workspace,
                                               final String selectionJson) throws JSONException {
        return invoke(context, "preview_claim_batch", workspace, selectionJson);
    }

    public static JSONObject acceptClaimBatch(final Context context, final String workspace,
                                              final String selectionJson, final String batchHash,
                                              final String journalHead) throws JSONException {
        return invoke(context, "accept_claim_batch", workspace,
                selectionJson, batchHash, journalHead);
    }

    public static JSONObject activeDecisions(final Context context, final String workspace,
                                             final int offset) throws JSONException {
        return invoke(context, "active_decisions", workspace, offset);
    }

    /** Bounded, read-only decision workspace; never updates the source or journal. */
    public static JSONObject decisionOverview(final Context context, final String workspace,
                                              final String category, final int offset,
                                              final String search) throws JSONException {
        return invoke(context, "decision_overview", workspace, category, offset, search);
    }

    public static JSONObject decisionDetail(final Context context, final String workspace,
                                            final String kind, final String identifier)
            throws JSONException {
        return invoke(context, "decision_detail", workspace, kind, identifier);
    }

    public static JSONObject previewRetraction(final Context context, final String workspace,
                                               final String recordId) throws JSONException {
        return invoke(context, "preview_retraction", workspace, recordId);
    }

    public static JSONObject retractDecision(final Context context, final String workspace,
                                             final String recordId, final String expectedHead)
            throws JSONException {
        return invoke(context, "retract_decision", workspace, recordId, expectedHead);
    }

    public static JSONObject previewRevision(final Context context, final String workspace,
                                             final String recordId, final String statement)
            throws JSONException {
        return invoke(context, "preview_revision", workspace, recordId, statement);
    }

    public static JSONObject reviseDecision(final Context context, final String workspace,
                                            final String recordId, final String statement,
                                            final String readingId, final String reason,
                                            final String sourceHash, final String expectedHead)
            throws JSONException {
        return invoke(context, "revise_decision", workspace, recordId, statement,
                readingId, reason, sourceHash, expectedHead);
    }

    public static JSONObject previewSplit(final Context context, final String workspace,
                                          final String recordId, final String firstStatement,
                                          final String secondStatement) throws JSONException {
        return invoke(context, "preview_split", workspace, recordId,
                firstStatement, secondStatement);
    }

    public static JSONObject splitDecision(final Context context, final String workspace,
                                           final String recordId, final String firstStatement,
                                           final String secondStatement, final String firstReading,
                                           final String secondReading, final String reason,
                                           final String expectedHash, final String expectedHead)
            throws JSONException {
        return invoke(context, "split_decision", workspace, recordId,
                firstStatement, secondStatement, firstReading, secondReading,
                reason, expectedHash, expectedHead);
    }

    public static JSONObject previewMerge(final Context context, final String workspace,
                                          final String firstId, final String secondId,
                                          final String statement) throws JSONException {
        return invoke(context, "preview_merge", workspace, firstId, secondId, statement);
    }

    public static JSONObject mergeDecisions(final Context context, final String workspace,
                                            final String firstId, final String secondId,
                                            final String statement, final String readingId,
                                            final String reason, final String expectedHash,
                                            final String expectedHead) throws JSONException {
        return invoke(context, "merge_decisions", workspace, firstId, secondId,
                statement, readingId, reason, expectedHash, expectedHead);
    }

    public static JSONObject query(final Context context, final String workspace,
                                   final String question) throws JSONException {
        return invoke(context, "ask_knowledge", workspace, question);
    }

    public static JSONObject diagnostics(final Context context, final String workspace)
            throws JSONException {
        return invoke(context, "knowledge_diagnostics", workspace);
    }

    public static JSONObject journalExportInfo(final Context context, final String workspace)
            throws JSONException {
        return invoke(context, "journal_export_info", workspace);
    }

    public static JSONObject journalRestorePreview(final Context context, final String workspace,
                                                   final String text) throws JSONException {
        return invoke(context, "journal_restore_preview", workspace, text);
    }

    public static JSONObject journalRestoreCommit(final Context context, final String workspace,
                                                  final String text, final String expectedSha)
            throws JSONException {
        return invoke(context, "journal_restore_commit", workspace, text, expectedSha);
    }

    private static JSONObject invoke(final Context context, final String method,
                                     final Object... arguments) throws JSONException {
        synchronized (KnowledgePythonBridge.class) {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(context.getApplicationContext()));
            }
        }
        final String output = Python.getInstance().getModule("ksys_android_bridge")
                .callAttr(method, arguments).toString();
        final JSONObject result = new JSONObject(output);
        final String protocol = result.optString("protocol");
        if (!isSupportedProtocol(protocol)) {
            throw new JSONException("Unexpected knowledge core protocol");
        }
        return result;
    }

    /** Keep the Java transport allowlist in step with the Python bridge responses. */
    static boolean isSupportedProtocol(final String protocol) {
        return "dpksys.inspect.v1".equals(protocol)
                || "dpksys.decision.v1".equals(protocol)
                || "dpksys.query.v1".equals(protocol)
                || "dpksys.backup.v1".equals(protocol)
                || "dpksys.bulk.v1".equals(protocol)
                || "dpksys.relations.v1".equals(protocol)
                || "dpksys.entities.v1".equals(protocol)
                || "dpksys.source_lineage.v1".equals(protocol)
                || "dpksys.source_assessment.v1".equals(protocol)
                || "dpksys.version_review.v1".equals(protocol)
                || "dpksys.editor_context.v1".equals(protocol)
                || "dpksys.editor_highlights.v1".equals(protocol)
                || "dpksys.editor_record_evidence.v1".equals(protocol)
                || "dpksys.editor_source_records.v1".equals(protocol)
                || "dpksys.audit.v1".equals(protocol)
                || "dpksys.stage22_editor_selftest.v1".equals(protocol);
    }
}
