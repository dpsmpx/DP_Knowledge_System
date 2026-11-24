/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

/**
 * Small deterministic previews from user-selected local Markdown sources.
 * Retrieval is lexical, NOT a semantic proof or authorization to disclose.
 */
public final class KnowledgeLlmEvidence {
    public static final int MAX_EXCERPT_CHARS = 900;
    private static final int EXCERPT_LEADING_CONTEXT = 180;
    private static final int EXCERPT_MIN_TRAILING_CONTEXT = 80;
    private static final int MAX_COVERAGE_SPAN = MAX_EXCERPT_CHARS
            - EXCERPT_LEADING_CONTEXT - EXCERPT_MIN_TRAILING_CONTEXT;

    public static final class Item {
        public final String path;
        public final String excerpt;

        public Item(final String relativePath, final String text) {
            path = relativePath;
            excerpt = text;
        }
    }

    /** Lexical visibility only: term presence in the exact approved excerpts
     * is neither proof of a claim nor a recall/precision estimate.
     */
    public static final class TermCoverage {
        public final int matched;
        public final int total;

        private TermCoverage(final int matchedTerms, final int totalTerms) {
            matched = matchedTerms;
            total = totalTerms;
        }

        public boolean isIncomplete() {
            return total > 0 && matched < total;
        }
    }

    /** Ephemeral copy of what the user actually approved for this one LLM
     * request. Never persisted, logged, or derived from a different scan.
     */
    public static final class ConsentSeal {
        public final String relativePath;
        public final long modifiedAtMillis;
        public final long sizeBytes;
        private final byte[] rawDigest;

        public ConsentSeal(final String path, final long modified, final long size,
                           final byte[] approvedBytes) {
            if (path == null || path.isEmpty() || modified <= 0 || size < 0
                    || approvedBytes == null || approvedBytes.length != size) {
                throw new IllegalArgumentException("Unverified LLM consent source");
            }
            relativePath = path;
            modifiedAtMillis = modified;
            sizeBytes = size;
            rawDigest = rawSha256(approvedBytes);
        }

        /** Distinguishes same-size, same-mtime rewrites after preview. */
        public boolean matchesCurrentBytes(final byte[] currentBytes) {
            return matchesIndexedBytes(rawDigest, currentBytes);
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

    /** Persist only the source-byte digest in the disposable SQLite index. */
    static byte[] rawSha256(final byte[] source) {
        if (source == null || source.length > KnowledgeLocalIndex.MAX_INDEX_BYTES_PER_NOTE) {
            throw new IllegalArgumentException("Source bytes exceed the index bound");
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(source);
        } catch (NoSuchAlgorithmException unsupported) {
            throw new IllegalStateException("SHA-256 is required for source verification", unsupported);
        }
    }

    /** Decoding replacement characters must never collapse distinct raw files. */
    public static boolean matchesIndexedBytes(final byte[] indexedDigest, final byte[] currentBytes) {
        return indexedDigest != null && indexedDigest.length == 32
                && currentBytes != null
                && currentBytes.length <= KnowledgeLocalIndex.MAX_INDEX_BYTES_PER_NOTE
                && MessageDigest.isEqual(indexedDigest, rawSha256(currentBytes));
    }

    public static String excerpt(final String source, final String question) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        final String text = source.replace("\r\n", "\n");
        // Character-wise lowercase preserves UTF-16 offsets into the original
        // text, including uncommon characters whose full case-fold expands.
        final String folded = foldForOffsets(text);
        final List<String> terms = KnowledgeMarkdownMetadata.ftsEvidenceTerms(question);
        // FTS4 ranks a contiguous 2-4-term phrase ahead of isolated terms.
        // Keep the actual excerpt aligned to that phrase, even if a decoy
        // occurrence of one term appears far earlier in the same long note.
        int firstMatch = adjacentPhraseStart(folded, terms);
        // If no adjacent phrase exists (or the question has 5-8 terms),
        // prefer a short window covering more DISTINCT query words. The
        // previous earliest-single-word fallback could omit a later cluster
        // even when the same note matched every term somewhere in FTS4.
        if (firstMatch < 0 && terms.size() >= 2) {
            firstMatch = bestCoverageStart(folded, terms);
        }
        if (firstMatch < 0) {
            for (final String term : terms) {
                final int position = firstTokenPrefixStart(folded, term, 0);
                if (position >= 0 && (firstMatch < 0 || position < firstMatch)) {
                    firstMatch = position;
                }
            }
        }
        final int leading = firstMatch < 0 ? 0
                : Math.max(0, firstMatch - EXCERPT_LEADING_CONTEXT);
        final int length = Math.min(MAX_EXCERPT_CHARS, text.length() - leading);
        final int end = Math.min(text.length(), leading + length);
        final String clipped = text.substring(leading, end);
        return (leading > 0 ? "…\n" : "") + clipped
                + (end < text.length() ? "\n…" : "");
    }

    /** Count distinct normalized query terms visibly present in the approved
     * excerpts, using the same word-prefix rule as the excerpt locator.
     * Source filenames are deliberately excluded: they are labels, not proof.
     */
    public static TermCoverage termCoverage(final String question, final List<Item> excerpts) {
        final List<String> terms = KnowledgeMarkdownMetadata.ftsEvidenceTerms(question);
        if (terms.isEmpty() || excerpts == null || excerpts.isEmpty()) {
            return new TermCoverage(0, terms.size());
        }
        final boolean[] present = new boolean[terms.size()];
        int count = 0;
        for (final Item item : excerpts) {
            if (item == null || item.excerpt == null) continue;
            final String folded = foldForOffsets(item.excerpt);
            // Match a source token to only its longest query prefix. Otherwise
            // one token ("amberlight") could falsely cover two query terms
            // ("amber" and "amberlight") in the consent disclosure.
            for (int at = 0; at < folded.length();) {
                if (!Character.isLetterOrDigit(folded.charAt(at))) {
                    at++;
                    continue;
                }
                final int start = at;
                while (at < folded.length() && Character.isLetterOrDigit(folded.charAt(at))) at++;
                int matching = -1;
                for (int i = 0; i < terms.size(); i++) {
                    if (folded.startsWith(terms.get(i), start)
                            && (matching < 0 || terms.get(i).length() > terms.get(matching).length())) {
                        matching = i;
                    }
                }
                if (matching >= 0 && !present[matching]) {
                    present[matching] = true;
                    count++;
                }
            }
            if (count == terms.size()) break;
        }
        return new TermCoverage(count, terms.size());
    }

    private static String foldForOffsets(final String text) {
        // Do not use String.toLowerCase(Locale): Unicode expansions would
        // invalidate the UTF-16 offsets used for the exact source substring.
        final char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) chars[i] = Character.toLowerCase(chars[i]);
        return new String(chars);
    }

    /** Prefix hits begin at token boundaries, not inside unrelated words. */
    private static int firstTokenPrefixStart(final String text, final String term,
                                            final int from) {
        for (int at = text.indexOf(term, from); at >= 0;
             at = text.indexOf(term, at + 1)) {
            if (at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1))) {
                return at;
            }
        }
        return -1;
    }

    /** Bounded O(source characters × at most 8 terms), O(8) memory. Count each
     * source token once even if it matches overlapping query prefixes. A
     * winning window must cover at least two distinct question terms, leave
     * room for context after the last hit, and favor denser/earlier ties.
     * This is a preview-location heuristic, never semantic entailment.
     */
    private static int bestCoverageStart(final String text, final List<String> terms) {
        final int[] lastSeen = new int[terms.size()];
        Arrays.fill(lastSeen, -1);
        int bestCount = 0;
        int bestSpan = Integer.MAX_VALUE;
        int bestStart = -1;
        for (int at = 0; at < text.length();) {
            if (!Character.isLetterOrDigit(text.charAt(at))) {
                at++;
                continue;
            }
            final int tokenStart = at;
            while (at < text.length() && Character.isLetterOrDigit(text.charAt(at))) {
                at++;
            }
            int matching = -1;
            for (int i = 0; i < terms.size(); i++) {
                if (text.startsWith(terms.get(i), tokenStart)
                        && (matching < 0
                        || terms.get(i).length() > terms.get(matching).length())) {
                    matching = i;
                }
            }
            if (matching < 0) {
                continue;
            }
            lastSeen[matching] = tokenStart;
            int count = 0;
            int earliest = tokenStart;
            for (final int seen : lastSeen) {
                if (seen >= 0 && tokenStart - seen <= MAX_COVERAGE_SPAN) {
                    count++;
                    earliest = Math.min(earliest, seen);
                }
            }
            final int span = tokenStart - earliest;
            if (count < 2) {
                continue;
            }
            if (count > bestCount || (count == bestCount && span < bestSpan)) {
                bestCount = count;
                bestSpan = span;
                bestStart = earliest;
            }
        }
        return bestStart;
    }

    /** A lightweight in-text mirror of the FTS4 2-4-token phrase priority.
     * Word punctuation/whitespace may separate tokens, and each token may
     * have a suffix because the indexed query uses the `prefix*` operator.
     * Reject phrases spanning more than the bounded preview can show; FTS4
     * token adjacency alone may hide arbitrarily long punctuation gaps.
     * This only selects a preview window; it does not certify a quotation.
     */
    private static int adjacentPhraseStart(final String text, final List<String> terms) {
        if (terms.size() < 2 || terms.size() > 4) {
            return -1;
        }
        final String first = terms.get(0);
        for (int start = firstTokenPrefixStart(text, first, 0); start >= 0;
             start = firstTokenPrefixStart(text, first, start + 1)) {
            int at = start;
            boolean matches = true;
            for (int i = 0; i < terms.size(); i++) {
                if (at - start > MAX_COVERAGE_SPAN
                        || !text.startsWith(terms.get(i), at)
                        || at + terms.get(i).length() - start > MAX_COVERAGE_SPAN) {
                    matches = false;
                    break;
                }
                int end = at + terms.get(i).length();
                while (end < text.length() && Character.isLetterOrDigit(text.charAt(end))) {
                    end++;
                }
                if (i + 1 < terms.size()) {
                    int next = end;
                    while (next < text.length() && next - start <= MAX_COVERAGE_SPAN
                            && !Character.isLetterOrDigit(text.charAt(next))) {
                        next++;
                    }
                    if (next == end || next == text.length()
                            || next - start > MAX_COVERAGE_SPAN) {
                        matches = false;
                        break;
                    }
                    at = next;
                }
            }
            if (matches) {
                return start;
            }
        }
        return -1;
    }
}
