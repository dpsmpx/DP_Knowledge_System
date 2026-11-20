/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import android.content.Context;

import net.gsantner.markor.R;

import org.json.JSONObject;

/** Display-only qualifiers of *unapproved* cross-note assertion matches. */
final class KnowledgeRelationPresentation {
    private KnowledgeRelationPresentation() { }

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
        final JSONObject interval = relation.optJSONObject("interval");
        if (interval != null) {
            final String from = interval.isNull("from") ? "" : interval.optString("from", "");
            final String to = interval.isNull("to") ? "" : interval.optString("to", "");
            if (!from.isEmpty() || !to.isEmpty()) {
                text.append("\n").append(context.getString(R.string.knowledge_editor_structural_interval))
                        .append(": ").append(from.isEmpty() ? "−∞" : from)
                        .append(" – ").append(to.isEmpty() ? "+∞" : to);
            }
        }
        return text.toString();
    }
}
