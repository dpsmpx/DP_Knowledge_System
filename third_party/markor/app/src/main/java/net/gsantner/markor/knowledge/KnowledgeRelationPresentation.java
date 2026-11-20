/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import android.content.Context;

import net.gsantner.markor.R;

import org.json.JSONObject;
import org.json.JSONArray;

/** Display-only qualifiers of *unapproved* cross-note assertion matches. */
final class KnowledgeRelationPresentation {
    private KnowledgeRelationPresentation() { }

    /** Counts of distinct files, not independent witnesses or truth scores. */
    static String evidenceSummary(Context context, JSONObject group) {
        if (group == null) return "";
        final StringBuilder text = new StringBuilder(group.optString("predicate"))
                .append(" · ").append(group.optString("source_locator"))
                .append("\n").append(context.getString(R.string.knowledge_evidence_file_counts,
                        group.optInt("same_polarity_files"),
                        group.optInt("opposite_polarity_files"),
                        group.optInt("mixed_polarity_files")));
        text.append("\n").append(context.getString(R.string.knowledge_evidence_source_status,
                evidenceVerification(context, group.optString("source_verification", "not_checked"))));
        text.append("\n").append(context.getString(R.string.knowledge_evidence_checked_counts,
                group.optInt("verified_same_polarity_files"),
                group.optInt("verified_opposite_polarity_files"),
                group.optInt("verified_mixed_polarity_files")));
        text.append("\n").append(context.getString(R.string.knowledge_evidence_other_statuses,
                group.optInt("current_files"), group.optInt("stale_files"),
                group.optInt("unavailable_files"), group.optInt("unchecked_files")));
        if (group.optInt("current_excerpt_files") > 0) {
            text.append("\n").append(context.getString(R.string.knowledge_evidence_excerpt_diversity,
                    group.optInt("current_excerpt_files"),
                    group.optInt("distinct_representative_excerpts")));
        }
        final JSONArray excerptFamilies = group.optJSONArray("excerpt_families");
        final JSONArray documentFamilies = group.optJSONArray("document_families");
        if ((excerptFamilies != null && excerptFamilies.length() > 0)
                || (documentFamilies != null && documentFamilies.length() > 0)) {
            text.append("\n").append(context.getString(R.string.knowledge_evidence_family_counts,
                    excerptFamilies == null ? 0 : excerptFamilies.length(),
                    documentFamilies == null ? 0 : documentFamilies.length()));
        }
        if (group.optBoolean("source_repeated_excerpt")) {
            text.append("\n").append(context.getString(R.string.knowledge_evidence_source_family,
                    group.optString("source_excerpt_family")));
        }
        if (group.optBoolean("source_identical_document")) {
            text.append("\n").append(context.getString(R.string.knowledge_evidence_source_document_family,
                    group.optString("source_document_family")));
        }
        if (group.optInt("repeated_excerpt_files") > 0) {
            text.append("\n").append(context.getString(R.string.knowledge_evidence_repeated_count,
                    group.optInt("repeated_excerpt_files")));
        }
        if (group.optInt("identical_document_files") > 0) {
            text.append("\n").append(context.getString(R.string.knowledge_evidence_identical_count,
                    group.optInt("identical_document_files")));
        }
        if ("mixed".equals(group.optString("source_polarity"))) {
            text.append("\n").append(context.getString(R.string.knowledge_evidence_source_mixed));
        }
        if (group.optBoolean("limited")) {
            text.append("\n").append(context.getString(R.string.knowledge_evidence_limited));
        }
        return text.toString();
    }

    static String evidenceDisclosure(Context context, JSONObject group) {
        return context.getString(R.string.knowledge_evidence_disclaimer)
                + (group.optBoolean("limited") ? "\n"
                + context.getString(R.string.knowledge_evidence_limited) : "")
                + "\n\n" + evidenceSummary(context, group)
                + qualifiers(context, group)
                + evidenceFamilyDetails(context, group.optJSONArray("excerpt_families"),
                        R.string.knowledge_evidence_family_members)
                + evidenceFamilyDetails(context, group.optJSONArray("document_families"),
                        R.string.knowledge_evidence_document_family_members);
    }

    static String evidenceRelation(Context context, String relation) {
        switch (relation) {
            case "same_polarity": return context.getString(R.string.knowledge_evidence_same);
            case "opposite_polarity": return context.getString(R.string.knowledge_evidence_opposite);
            case "mixed": return context.getString(R.string.knowledge_evidence_mixed);
            default: return context.getString(R.string.knowledge_evidence_unclassified);
        }
    }

    static String evidenceSourceLabel(Context context, JSONObject source) {
        final StringBuilder text = new StringBuilder(source.optString("path"))
                .append(" · ").append(source.optString("locator"))
                .append(" · ").append(evidenceRelation(context, source.optString("relation")))
                .append("\n").append(evidenceVerification(context,
                        source.optString("verification", "not_checked")));
        if (source.optBoolean("repeated_excerpt")) {
            text.append(" · ").append(context.getString(R.string.knowledge_evidence_repeated_family,
                    source.optString("excerpt_family")));
        }
        if (source.optBoolean("identical_document")) {
            text.append(" · ").append(context.getString(R.string.knowledge_evidence_duplicate_family,
                    source.optString("document_family")));
        }
        return text.toString();
    }

    private static String evidenceFamilyDetails(Context context, JSONArray families, int label) {
        if (families == null || families.length() == 0) return "";
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < families.length(); i++) {
            final JSONObject family = families.optJSONObject(i);
            if (family == null) continue;
            final JSONArray paths = family.optJSONArray("paths");
            if (paths == null || paths.length() < 2) continue;
            final StringBuilder names = new StringBuilder();
            for (int j = 0; j < paths.length(); j++) {
                if (j > 0) names.append(", ");
                names.append(paths.optString(j));
            }
            text.append("\n").append(context.getString(label,
                    family.optString("label"), names.toString()));
        }
        return text.toString();
    }

    private static String evidenceVerification(Context context, String state) {
        switch (state) {
            case "current": return context.getString(R.string.knowledge_evidence_current);
            case "stale": return context.getString(R.string.knowledge_evidence_stale);
            case "unavailable": return context.getString(R.string.knowledge_evidence_unavailable);
            default: return context.getString(R.string.knowledge_evidence_not_checked);
        }
    }

    static String qualifiers(Context context, JSONObject relation) {
        if (relation == null) return "";
        final StringBuilder text = new StringBuilder();
        if (!relation.isNull("context")) {
            final String scope = relation.optString("context", "").trim();
            if (!scope.isEmpty()) {
                text.append("\n").append(context.getString(R.string.knowledge_editor_structural_context))
                        .append(": ").append(scope);
            }
        }
        appendInterval(text, context, R.string.knowledge_editor_structural_interval,
                relation.optJSONObject("interval"));
        if ("temporal_overlap".equals(relation.optString("kind"))) {
            appendInterval(text, context, R.string.knowledge_relation_other_interval,
                    relation.optJSONObject("other_interval"));
            appendInterval(text, context, R.string.knowledge_relation_overlap_interval,
                    relation.optJSONObject("overlap_interval"));
        }
        if ("alias_candidate".equals(relation.optString("kind"))) {
            final JSONObject evidence = relation.optJSONObject("alias_evidence");
            if (evidence != null) {
                text.append("\n").append(context.getString(R.string.knowledge_relation_alias_source))
                        .append(": ").append(evidence.optString("source_symbol"))
                        .append(" ↔ ").append(evidence.optString("other_symbol"))
                        .append(" → ").append(evidence.optString("canonical_symbol"));
                final JSONArray decisions = evidence.optJSONArray("journal_ids");
                if (decisions != null && decisions.length() > 0) {
                    text.append("\n").append(context.getString(R.string.knowledge_relation_alias_decisions))
                            .append(": ");
                    for (int i = 0; i < decisions.length(); i++) {
                        if (i > 0) text.append(", ");
                        text.append(decisions.optString(i));
                    }
                }
            }
        }
        return text.toString();
    }

    private static void appendInterval(final StringBuilder text, final Context context,
                                       final int label, final JSONObject interval) {
        if (interval != null) {
            final String from = interval.isNull("from") ? "" : interval.optString("from", "");
            final String to = interval.isNull("to") ? "" : interval.optString("to", "");
            if (!from.isEmpty() || !to.isEmpty()) {
                text.append("\n").append(context.getString(label))
                        .append(": ").append(from.isEmpty() ? "−∞" : from)
                        .append(" – ").append(to.isEmpty() ? "+∞" : to);
            }
        }
    }
}
