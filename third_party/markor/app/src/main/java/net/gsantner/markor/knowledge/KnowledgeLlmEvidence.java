/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Small deterministic previews from user-selected local Markdown sources.
 * Retrieval is lexical, NOT a semantic proof or authorization to disclose.
 */
public final class KnowledgeLlmEvidence {
    public static final int MAX_EXCERPT_CHARS = 900;

    public static final class Item {
        public final String path;
        public final String excerpt;

        public Item(final String relativePath, final String text) {
            path = relativePath;
            excerpt = text;
        }
    }

    private KnowledgeLlmEvidence() {
    }

    /** Reject evidence that no longer matches the generation of the local index.
     * This is an mtime/size check, not a cryptographic source revision proof.
     */
    public static boolean matchesIndexedSnapshot(final File file, final long modified,
                                                  final long size) {
        return file != null && modified > 0 && size >= 0 && file.isFile()
                && file.lastModified() == modified && file.length() == size;
    }

    /**
     * Match the exact UTF-8 text supplied to the model against the text kept
     * in the disposable FTS4 projection. This catches a same-size rewrite
     * with a restored mtime. The index stores decoded text, not raw file
     * bytes, so this is a decoded-source identity check, not a raw-byte seal.
     */
    public static boolean matchesIndexedText(final String indexed, final String current) {
        if (indexed == null || current == null
                || indexed.length() > KnowledgeLocalIndex.MAX_INDEX_BYTES_PER_NOTE
                || current.length() > KnowledgeLocalIndex.MAX_INDEX_BYTES_PER_NOTE) {
            return false;
        }
        final byte[] before = indexed.getBytes(StandardCharsets.UTF_8);
        final byte[] after = current.getBytes(StandardCharsets.UTF_8);
        if (before.length > KnowledgeLocalIndex.MAX_INDEX_BYTES_PER_NOTE
                || after.length > KnowledgeLocalIndex.MAX_INDEX_BYTES_PER_NOTE) {
            return false;
        }
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] indexedHash = digest.digest(before);
            final byte[] currentHash = digest.digest(after);
            return MessageDigest.isEqual(indexedHash, currentHash);
        } catch (NoSuchAlgorithmException unsupported) {
            throw new IllegalStateException("SHA-256 is required for source verification", unsupported);
        }
    }

    public static String excerpt(final String source, final String question) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        final String text = source.replace("\r\n", "\n");
        final String folded = text.toLowerCase(Locale.ROOT);
        final List<String> terms = queryTerms(question);
        int firstMatch = -1;
        for (final String term : terms) {
            final int position = folded.indexOf(term);
            if (position >= 0 && (firstMatch < 0 || position < firstMatch)) {
                firstMatch = position;
            }
        }
        final int leading = firstMatch < 0 ? 0 : Math.max(0, firstMatch - 180);
        final int length = Math.min(MAX_EXCERPT_CHARS, text.length() - leading);
        final int end = Math.min(text.length(), leading + length);
        final String clipped = text.substring(leading, end);
        return (leading > 0 ? "…\n" : "") + clipped
                + (end < text.length() ? "\n…" : "");
    }

    private static List<String> queryTerms(final String question) {
        final List<String> result = new ArrayList<>();
        if (question == null) {
            return result;
        }
        final StringBuilder word = new StringBuilder();
        final String folded = question.toLowerCase(Locale.ROOT);
        for (int i = 0; i <= folded.length(); i++) {
            final char c = i < folded.length() ? folded.charAt(i) : ' ';
            if (Character.isLetterOrDigit(c)) {
                word.append(c);
            } else if (word.length() > 0) {
                final String term = word.toString();
                if (term.length() >= 4 && !"which".equals(term)
                        && !"what".equals(term) && !"where".equals(term)
                        && !"когда".equals(term) && !"какие".equals(term)
                        && !"почему".equals(term) && !"зачем".equals(term)) {
                    result.add(term);
                }
                word.setLength(0);
            }
        }
        return result;
    }
}
