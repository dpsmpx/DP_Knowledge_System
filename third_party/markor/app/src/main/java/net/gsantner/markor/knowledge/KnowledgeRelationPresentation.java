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
                + qualifiers(context, group);
    }

    static String evidenceRelation(Context context, String relation) {
        switch (relation) {
            case "same_polarity": return context.getString(R.string.knowledge_evidence_same);
            case "opposite_polarity": return context.getString(R.string.knowledge_evidence_opposite);
            case "mixed": return context.getString(R.string.knowledge_evidence_mixed);
            default: return context.getString(R.string.knowledge_evidence_unclassified);
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
