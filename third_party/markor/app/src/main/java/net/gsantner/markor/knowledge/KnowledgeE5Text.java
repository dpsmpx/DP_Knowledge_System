/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

/**
 * Explicit text preparation for the curated multilingual-E5-small family.
 * This does not tokenize text and never reads a note or opens a model.
 */
final class KnowledgeE5Text {
    static final int MAX_TEXT_CHARS = 8192;

    enum Purpose { QUERY, PASSAGE }

    private KnowledgeE5Text() { }

    static String prepare(final Purpose purpose, final String text) {
        if (purpose == null || text == null || text.isEmpty()
                || text.length() > MAX_TEXT_CHARS || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid E5 text");
        }
        // Avoid accidentally doubling a caller-supplied role prefix. The
        // caller must choose QUERY or PASSAGE deliberately.
        if (text.startsWith("query: ") || text.startsWith("passage: ")) {
            throw new IllegalArgumentException("E5 text already has a role prefix");
        }
        for (int i = 0; i < text.length(); i++) {
            final char next = text.charAt(i);
            if (Character.isHighSurrogate(next)) {
                if (i + 1 == text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    throw new IllegalArgumentException("Unpaired UTF-16 surrogate");
                }
                i++;
            } else if (Character.isLowSurrogate(next)) {
                throw new IllegalArgumentException("Unpaired UTF-16 surrogate");
            }
        }
        return (purpose == Purpose.QUERY ? "query: " : "passage: ") + text;
    }
}
