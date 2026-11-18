/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import java.util.ArrayList;
import java.util.List;

/** Deterministic, bounded source excerpts. Long notes are sampled across their length. */
public final class KnowledgeEmbeddingChunks {
    public static final int CHUNK_CHARS = 850;
    public static final int MAX_CHUNKS_PER_NOTE = 3;

    public static final class Chunk {
        public final int start;
        public final String text;

        Chunk(final int offset, final String content) {
            start = offset;
            text = content;
        }
    }

    private KnowledgeEmbeddingChunks() {
    }

    public static List<Chunk> fromNote(final String markdown) {
        final List<Chunk> chunks = new ArrayList<>();
        if (markdown == null || markdown.trim().isEmpty()) {
            return chunks;
        }
        final int length = markdown.length();
        final int count = Math.min(MAX_CHUNKS_PER_NOTE,
                Math.max(1, (length + CHUNK_CHARS - 1) / CHUNK_CHARS));
        for (int i = 0; i < count; i++) {
            final int start = count == 1 ? 0
                    : (int) ((long) (length - Math.min(length, CHUNK_CHARS)) * i / (count - 1));
            final String text = markdown.substring(start,
                    Math.min(length, start + CHUNK_CHARS));
            if (!text.trim().isEmpty()) {
                chunks.add(new Chunk(start, text));
            }
        }
        return chunks;
    }

    public static String atOffset(final String markdown, final int start) {
        if (markdown == null || start < 0 || start >= markdown.length()) {
            return "";
        }
        final String text = markdown.substring(start,
                Math.min(markdown.length(), start + CHUNK_CHARS));
        return (start > 0 ? "…\n" : "") + text
                + (start + CHUNK_CHARS < markdown.length() ? "\n…" : "");
    }
}
