/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Local, bounded process event log. Never accept arbitrary names, file paths,
 * note content, model identifiers, network responses or exception messages.
 */
final class KnowledgeProcessHistory {
    static final int MAX_EVENTS = 32;

    enum Operation { SCAN, ANALYSIS, AUDIT, EMBEDDING, DEVICE_QA }
    enum Outcome { STARTED, COMPLETED, PARTIAL, CANCELLED, FAILED }

    static final class Entry {
        final Operation operation;
        final Outcome outcome;
        final long timestamp;

        private Entry(final Operation operation, final Outcome outcome, final long timestamp) {
            this.operation = operation;
            this.outcome = outcome;
            this.timestamp = timestamp;
        }
    }

    private final ArrayList<Entry> entries = new ArrayList<>();

    void record(final Operation operation, final Outcome outcome, final long timestamp) {
        if (operation == null || outcome == null || timestamp <= 0) {
            throw new IllegalArgumentException("Invalid process event");
        }
        entries.add(0, new Entry(operation, outcome, timestamp));
        while (entries.size() > MAX_EVENTS) entries.remove(entries.size() - 1);
    }

    List<Entry> recent() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    String serialize() {
        final StringBuilder result = new StringBuilder();
        for (final Entry e : entries) {
            result.append(e.timestamp).append('|').append(e.operation.name()).append('|')
                    .append(e.outcome.name()).append('\n');
        }
        return result.toString();
    }

    static KnowledgeProcessHistory fromStored(final String encoded) {
        final KnowledgeProcessHistory history = new KnowledgeProcessHistory();
        if (encoded == null || encoded.isEmpty()) return history;
        // The preference file is private, yet still treat it as untrusted
        // input. A corrupted/oversized entry cannot become a UI string.
        final String[] lines = encoded.substring(0, Math.min(8192, encoded.length()))
                .split("\\n", MAX_EVENTS + 1);
        for (int i = Math.min(lines.length, MAX_EVENTS) - 1; i >= 0; i--) {
            final String[] fields = lines[i].split("\\|", -1);
            if (fields.length != 3) continue;
            try {
                final long when = Long.parseLong(fields[0]);
                if (when > 0) {
                    history.record(Operation.valueOf(fields[1]), Outcome.valueOf(fields[2]), when);
                }
            } catch (IllegalArgumentException invalid) {
                // Ignore malformed or unknown future versions: no raw text in UI.
            }
        }
        return history;
    }
}
