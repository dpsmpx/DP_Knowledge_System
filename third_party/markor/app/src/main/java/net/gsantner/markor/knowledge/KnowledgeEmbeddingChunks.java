/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Versioned, Markdown-boundary-aware passage segmentation.
 * Offsets always address the unchanged original Markdown, never normalized text.
 */
public final class KnowledgeEmbeddingChunks {
    public static final int CHUNKER_VERSION = 2;
    public static final int CHUNK_CHARS = 850;
    public static final int CHUNK_OVERLAP = 80;
    public static final int MAX_CHUNKS_PER_NOTE = 128;
    private static final int MIN_CHUNK_CHARS = 350;

    public static final class Chunk {
        public final int start;
        public final String text;

        Chunk(final int offset, final String content) {
            start = offset;
            text = content;
        }

        public int end() {
            return start + text.length();
        }
    }

    private static final class Boundaries {
        final List<Integer> headings = new ArrayList<>();
        final List<Integer> paragraphs = new ArrayList<>();
        final List<Integer> lines = new ArrayList<>();
    }

    private KnowledgeEmbeddingChunks() {
    }

    /** Traverse from beginning to end, preserving headings and paragraphs where possible. */
    public static List<Chunk> fromNote(final String markdown) {
        final List<Chunk> chunks = new ArrayList<>();
        if (markdown == null || markdown.trim().isEmpty()) {
            return chunks;
        }
        final Boundaries boundaries = scanBoundaries(markdown);
        int offset = 0;
        while (offset < markdown.length() && chunks.size() < MAX_CHUNKS_PER_NOTE) {
            final int maximum = Math.min(markdown.length(), offset + CHUNK_CHARS);
            final int end = maximum == markdown.length() ? maximum
                    : chooseBoundary(boundaries, offset + MIN_CHUNK_CHARS, maximum);
            final int actualEnd = end > offset ? end : maximum;
            final String text = markdown.substring(offset, actualEnd);
            if (!text.trim().isEmpty()) {
                chunks.add(new Chunk(offset, text));
            }
            if (actualEnd == markdown.length()) {
                break;
            }
            offset = Math.max(offset + 1, actualEnd - CHUNK_OVERLAP);
        }
        return chunks;
    }

    /** Explicit signal for text that needs a future higher-capacity indexing stage. */
    public static boolean coverageTruncated(final String original, final List<Chunk> chunks) {
        return original != null && !original.trim().isEmpty()
                && (chunks.isEmpty() || chunks.get(chunks.size() - 1).end() < original.length());
    }

    public static String atOffset(final String markdown, final int start) {
        return atOffset(markdown, start, CHUNK_CHARS);
    }

    public static String atOffset(final String markdown, final int start, final int length) {
        if (markdown == null || start < 0 || start >= markdown.length()
                || length < 1 || length > CHUNK_CHARS) {
            return "";
        }
        final int end = Math.min(markdown.length(), start + length);
        return (start > 0 ? "…\n" : "") + markdown.substring(start, end)
                + (end < markdown.length() ? "\n…" : "");
    }

    private static int chooseBoundary(final Boundaries breaks, final int lower,
                                      final int upper) {
        final int heading = lastCandidate(breaks.headings, lower, upper);
        if (heading >= 0) {
            return heading;
        }
        final int paragraph = lastCandidate(breaks.paragraphs, lower, upper);
        if (paragraph >= 0) {
            return paragraph;
        }
        final int line = lastCandidate(breaks.lines, lower, upper);
        return line >= 0 ? line : upper; // Long fenced blocks still advance.
    }

    private static int lastCandidate(final List<Integer> values,
                                     final int lower, final int upper) {
        final int position = Collections.binarySearch(values, upper);
        final int index = position >= 0 ? position : -position - 2;
        if (index >= 0) {
            final int candidate = values.get(index);
            if (candidate >= lower && candidate <= upper) {
                return candidate;
            }
        }
        return -1;
    }

    /** Do not treat newlines inside fenced code blocks as semantic boundaries. */
    private static Boundaries scanBoundaries(final String markdown) {
        final Boundaries result = new Boundaries();
        char activeFence = 0;
        int fenceWidth = 0;
        int start = 0;
        while (start < markdown.length()) {
            final int newline = markdown.indexOf('\n', start);
            final int end = newline < 0 ? markdown.length() : newline;
            final int next = newline < 0 ? end : end + 1;
            final String line = markdown.substring(start, end).trim();
            if (!line.isEmpty() && (line.charAt(0) == '`' || line.charAt(0) == '~')) {
                int count = 0;
                while (count < line.length() && line.charAt(count) == line.charAt(0)) {
                    count++;
                }
                if (count >= 3) {
                    if (activeFence == 0) {
                        activeFence = line.charAt(0);
                        fenceWidth = count;
                    } else if (activeFence == line.charAt(0) && count >= fenceWidth) {
                        activeFence = 0;
                        fenceWidth = 0;
                    }
                }
            } else if (activeFence == 0) {
                if (line.startsWith("#") && line.length() > 1
                        && Character.isWhitespace(line.charAt(1)) && start > 0) {
                    result.headings.add(start);
                }
                if (line.isEmpty() && next < markdown.length()) {
                    result.paragraphs.add(next);
                }
                if (next < markdown.length()) {
                    result.lines.add(next);
                }
            }
            if (next >= markdown.length()) {
                break;
            }
            start = next;
        }
        return result;
    }
}
