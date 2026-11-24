/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic citation/quotation syntax audit for an untrusted model answer.
 * A matching marker or quotation is NOT proof of a factual claim.
 */
public final class KnowledgeLlmCitationAudit {
    private static final Pattern BRACKETS = Pattern.compile("\\[([^\\]\\r\\n]{1,24})\\]");
    private static final Pattern NUMBER = Pattern.compile("[1-9][0-9]?");
    private static final Pattern QUOTE = Pattern.compile("«([^»\\r\\n]{4,240})»|“([^”\\r\\n]{4,240})”");
    private static final Pattern NEGATED_BOOLEAN = Pattern.compile("(?i)\\bnot\\s+(?:true|false)\\b");
    private static final int MAX_LOCAL_CITATION_GAP = 180;
    private static final int MIN_PROSE_LETTERS = 16;
    private static final int MIN_PROSE_WORDS = 3;

    private static final class Citation {
        final int start;
        final int end;
        /** -1 means a malformed or out-of-range numeric marker. */
        final int source;
        boolean attached;

        Citation(final int start, final int end, final int source) {
            this.start = start;
            this.end = end;
            this.source = source;
        }
    }

    private static final class Quotation {
        final int start;
        final int end;
        final String literal;

        Quotation(final int start, final int end, final String literal) {
            this.start = start;
            this.end = end;
            this.literal = literal;
        }
    }

    public static final class Review {
        public final int referenceCount;
        public final int invalidReferences;
        public final int quotedPassages;
        public final int unverifiedQuotes;
        public final int mismatchedQuoteCitations;
        /** Formatting heuristic only; these spans are not factuality judgments. */
        public final int uncitedProseSpans;
        /** Cited spans with no two shared content words in the cited excerpt. */
        public final int weakTextAttributions;
        /** Explicit true/false disagreement between a span and its cited text. */
        public final int polarityMismatchedCitations;
        /** Pairs of supplied excerpts with opposing explicit true/false labels. */
        public final int potentiallyOpposingSources;

        Review(final int count, final int invalid, final int quotes, final int unsupported,
               final int mismatched, final int uncited, final int weak,
               final int polarity, final int opposing) {
            referenceCount = count;
            invalidReferences = invalid;
            quotedPassages = quotes;
            unverifiedQuotes = unsupported;
            mismatchedQuoteCitations = mismatched;
            uncitedProseSpans = uncited;
            weakTextAttributions = weak;
            polarityMismatchedCitations = polarity;
            potentiallyOpposingSources = opposing;
        }

        public boolean hasValidReferences() {
            return referenceCount > 0 && invalidReferences == 0;
        }
    }

    private KnowledgeLlmCitationAudit() {
    }

    public static Review inspect(final String answer,
                                 final List<KnowledgeLlmEvidence.Item> sources) {
        if (answer == null || sources == null || sources.isEmpty()
                || sources.size() > KnowledgeLlmClient.MAX_EVIDENCE || answer.length() > 12_000) {
            throw new IllegalArgumentException("Unbounded or absent LLM citation input");
        }
        // The model can echo fake [1] references inside a fenced example or
        // inline code. Preserve offsets while excluding those from evidence.
        final String visible = maskMarkdownCode(answer);
        final List<Quotation> quotes = new ArrayList<>();
        final Matcher quotations = QUOTE.matcher(visible);
        while (quotations.find()) {
            quotes.add(new Quotation(quotations.start(), quotations.end(),
                    quotations.group(1) != null ? quotations.group(1) : quotations.group(2)));
        }
        final List<Citation> markers = new ArrayList<>();
        int count = 0;
        int invalid = 0;
        final Matcher citations = BRACKETS.matcher(visible);
        while (citations.find()) {
            boolean quotedLabel = false;
            for (final Quotation quote : quotes) {
                if (citations.start() >= quote.start && citations.end() <= quote.end) {
                    quotedLabel = true;
                    break;
                }
            }
            if (quotedLabel) continue;
            // [1](url) is a Markdown link label, not our standalone [1] source
            // citation. The same applies to a bracket nested inside [[1]].
            if ((citations.end() < visible.length() && visible.charAt(citations.end()) == '(')
                    || (citations.start() > 0 && visible.charAt(citations.start() - 1) == '[')
                    || (citations.end() < visible.length() && visible.charAt(citations.end()) == ']')) {
                continue;
            }
            final String label = citations.group(1);
            if (NUMBER.matcher(label).matches()) {
                final int number = Integer.parseInt(label);
                if (number >= 1 && number <= sources.size()) {
                    count++;
                    markers.add(new Citation(citations.start(), citations.end(), number - 1));
                } else {
                    invalid++;
                    markers.add(new Citation(citations.start(), citations.end(), -1));
                }
            } else if (label.matches(".*[0-9].*")) {
                // Reject malformed numeric citations, including [0], [1, 2],
                // and numbers larger than the supplied source count.
                invalid++;
                markers.add(new Citation(citations.start(), citations.end(), -1));
            }
        }
        int unsupported = 0;
        int mismatched = 0;
        for (int i = 0; i < quotes.size(); i++) {
            final Quotation quote = quotes.get(i);
            final int nextQuote = i + 1 < quotes.size() ? quotes.get(i + 1).start : answer.length();
            final int previousQuote = i > 0 ? quotes.get(i - 1).end : 0;
            final List<Citation> local = nearestLocalCitations(visible, quote, previousQuote,
                    nextQuote, markers);
            boolean found = false;
            for (final Citation marker : local) {
                marker.attached = true; // one citation group cannot certify a later quote
                if (marker.source < 0) {
                    continue;
                }
                final KnowledgeLlmEvidence.Item item = sources.get(marker.source);
                if (item != null && item.excerpt != null
                        && item.excerpt.contains(quote.literal)) {
                    found = true;
                } else {
                    // A second adjacent source must not borrow a verbatim quote
                    // from the first source in the same citation cluster.
                    mismatched++;
                }
            }
            if (!found) unsupported++;
        }
        final int[] attribution = inspectCitedTextAnchors(visible, markers, sources);
        return new Review(count, invalid, quotes.size(), unsupported, mismatched,
                countUncitedProseSpans(visible, markers), attribution[0], attribution[1],
                countOpposingSourcePairs(sources));
    }

    /** Markdown code is not the model's cited prose. Mask without changing
     * character offsets so every other audit can still locate its markers.
     * Unclosed fences and inline backticks are conservatively masked to EOL.
     */
    private static String maskMarkdownCode(final String answer) {
        final StringBuilder visible = new StringBuilder(answer);
        char fenceChar = 0;
        int fenceLength = 0;
        for (int lineStart = 0; lineStart < answer.length();) {
            int lineEnd = answer.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = answer.length();
            int first = lineStart;
            while (first < lineEnd && first - lineStart < 4 && answer.charAt(first) == ' ') {
                first++;
            }
            final char candidate = first < lineEnd ? answer.charAt(first) : 0;
            int runEnd = first;
            if (candidate == '`' || candidate == '~') {
                while (runEnd < lineEnd && answer.charAt(runEnd) == candidate) runEnd++;
            }
            final int run = runEnd - first;
            if (fenceChar == 0 && first - lineStart <= 3 && run >= 3) {
                fenceChar = candidate;
                fenceLength = run;
            } else if (fenceChar != 0 && candidate == fenceChar && run >= fenceLength
                    && answer.substring(runEnd, lineEnd).trim().isEmpty()) {
                fenceChar = 0;
                fenceLength = 0;
                maskRange(visible, lineStart, lineEnd);
                lineStart = lineEnd + 1;
                continue;
            }
            if (fenceChar != 0) {
                maskRange(visible, lineStart, lineEnd);
            } else {
                for (int at = lineStart; at < lineEnd;) {
                    if (answer.charAt(at) != '`') {
                        at++;
                        continue;
                    }
                    int close = at + 1;
                    while (close < lineEnd && answer.charAt(close) == '`') close++;
                    final int ticks = close - at;
                    int end = close;
                    while (end < lineEnd) {
                        if (answer.charAt(end) != '`') {
                            end++;
                            continue;
                        }
                        int tail = end + 1;
                        while (tail < lineEnd && answer.charAt(tail) == '`') tail++;
                        if (tail - end == ticks) {
                            end = tail;
                            break;
                        }
                        end = tail;
                    }
                    maskRange(visible, at, Math.min(end, lineEnd));
                    at = Math.min(end, lineEnd);
                }
            }
            lineStart = lineEnd + 1;
        }
        return visible.toString();
    }

    private static void maskRange(final StringBuilder text, final int from, final int to) {
        for (int i = from; i < to; i++) {
            if (text.charAt(i) != '\r' && text.charAt(i) != '\n') text.setCharAt(i, ' ');
        }
    }

    /** Warn only on substantive uncited written spans. A citation in another
     * sentence or bullet cannot confer even syntactic coverage here. This
     * intentionally makes no semantic/factuality determination; uncertainty
     * and short labels can be skipped while some prose disclaimers may count.
     */
    private static int countUncitedProseSpans(final String visible,
                                             final List<Citation> markers) {
        int uncited = 0;
        for (int lineStart = 0; lineStart < visible.length();) {
            int lineEnd = visible.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = visible.length();
            final String trimmed = visible.substring(lineStart, lineEnd).trim();
            if (!trimmed.startsWith("# ") && !trimmed.startsWith("## ")
                    && !trimmed.startsWith("### ")) {
                int spanStart = lineStart;
                for (int i = lineStart; i < lineEnd; i++) {
                    if (isSentenceTerminator(visible.charAt(i))) {
                        int next = i + 1;
                        while (next < lineEnd && Character.isWhitespace(visible.charAt(next))) next++;
                        if ((next == lineEnd || next > i + 1)
                                && (next == lineEnd || visible.charAt(next) != '[')) {
                            if (uncitedSubstantiveSpan(visible, spanStart, i + 1, markers)) {
                                uncited++;
                            }
                            spanStart = i + 1;
                        }
                    }
                }
                if (uncitedSubstantiveSpan(visible, spanStart, lineEnd, markers)) uncited++;
            }
            lineStart = lineEnd + 1;
        }
        return uncited;
    }

    private static boolean isSentenceTerminator(final char ch) {
        return ch == '.' || ch == '!' || ch == '?' || ch == ';'
                || ch == '。' || ch == '！' || ch == '？' || ch == '；';
    }

    private static boolean uncitedSubstantiveSpan(final String visible, final int from,
                                                  final int to, final List<Citation> markers) {
        for (final Citation marker : markers) {
            if (marker.start >= from && marker.end <= to && marker.source >= 0) return false;
        }
        return substantiveSpan(visible, from, to);
    }

    private static boolean substantiveSpan(final String visible, final int from, final int to) {
        int letters = 0;
        int words = 0;
        boolean inWord = false;
        for (int i = from; i < to; i++) {
            if (Character.isLetter(visible.charAt(i))) {
                letters++;
                if (!inWord) words++;
                inWord = true;
            } else {
                inWord = false;
            }
        }
        return letters >= MIN_PROSE_LETTERS && words >= MIN_PROSE_WORDS;
    }

    /** Check only the text immediately attributed to an individual source.
     * No shared words is a useful warning, but synonyms, paraphrases and
     * inference make this a weak diagnostic, never semantic verification.
     * A narrow explicit true/false mismatch gets its own warning. Neither
     * check changes the answer or any authoritative journal state.
     */
    private static int[] inspectCitedTextAnchors(final String visible,
                                                 final List<Citation> markers,
                                                 final List<KnowledgeLlmEvidence.Item> sources) {
        final int[] result = new int[2];
        for (int lineStart = 0; lineStart < visible.length();) {
            int lineEnd = visible.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = visible.length();
            final String trimmed = visible.substring(lineStart, lineEnd).trim();
            if (!trimmed.startsWith("# ") && !trimmed.startsWith("## ")
                    && !trimmed.startsWith("### ")) {
                int start = lineStart;
                for (int i = lineStart; i < lineEnd; i++) {
                    if (!isSentenceTerminator(visible.charAt(i))) continue;
                    int next = i + 1;
                    while (next < lineEnd && Character.isWhitespace(visible.charAt(next))) next++;
                    if ((next == lineEnd || next > i + 1)
                            && (next == lineEnd || visible.charAt(next) != '[')) {
                        inspectOneAttributedSpan(visible, start, i + 1, markers, sources, result);
                        start = i + 1;
                    }
                }
                inspectOneAttributedSpan(visible, start, lineEnd, markers, sources, result);
            }
            lineStart = lineEnd + 1;
        }
        return result;
    }

    private static void inspectOneAttributedSpan(final String visible, final int from,
                                                 final int to, final List<Citation> markers,
                                                 final List<KnowledgeLlmEvidence.Item> sources,
                                                 final int[] counts) {
        if (!substantiveSpan(visible, from, to)) return;
        final String statement = visible.substring(from, to);
        final Set<String> terms = contentWords(statement);
        if (terms.size() < 2) return;
        final int statementPolarity = explicitBooleanPolarity(statement, terms);
        final Set<Integer> checked = new HashSet<>();
        for (final Citation marker : markers) {
            if (marker.source < 0 || marker.start < from || marker.end > to
                    || !checked.add(marker.source)) continue;
            final KnowledgeLlmEvidence.Item source = sources.get(marker.source);
            final String excerpt = source == null ? null : source.excerpt;
            final Set<String> sourceTerms = contentWords(excerpt);
            int overlap = 0;
            for (final String term : terms) {
                if (sourceTerms.contains(term)) overlap++;
            }
            if (overlap < 2) counts[0]++;
            final int sourcePolarity = explicitBooleanPolarity(excerpt, sourceTerms);
            if (statementPolarity != 0 && sourcePolarity == -statementPolarity) {
                counts[1]++;
            }
        }
    }

    /** Identify ONLY simple explicit true/false opposition in otherwise
     * related excerpts. Never infer general contradiction or source truth.
     */
    private static int countOpposingSourcePairs(final List<KnowledgeLlmEvidence.Item> sources) {
        final List<Set<String>> words = new ArrayList<>();
        final int[] polarity = new int[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            final KnowledgeLlmEvidence.Item source = sources.get(i);
            final String excerpt = source == null ? null : source.excerpt;
            final Set<String> terms = contentWords(excerpt);
            words.add(terms);
            polarity[i] = explicitBooleanPolarity(excerpt, terms);
        }
        int opposing = 0;
        for (int i = 0; i < sources.size(); i++) {
            if (polarity[i] == 0) continue;
            for (int j = i + 1; j < sources.size(); j++) {
                if (polarity[j] != -polarity[i]) continue;
                int shared = 0;
                for (final String term : words.get(i)) {
                    if (!"true".equals(term) && !"false".equals(term)
                            && words.get(j).contains(term)) shared++;
                }
                if (shared >= 2) opposing++;
            }
        }
        return opposing;
    }

    private static int explicitBooleanPolarity(final String text, final Set<String> words) {
        if (text == null || NEGATED_BOOLEAN.matcher(text).find()) return 0;
        final boolean positive = words.contains("true");
        final boolean negative = words.contains("false");
        if (positive == negative) return 0;
        return positive ? 1 : -1;
    }

    private static Set<String> contentWords(final String text) {
        final Set<String> result = new HashSet<>();
        if (text == null) return result;
        final StringBuilder token = new StringBuilder();
        for (int i = 0; i <= text.length(); i++) {
            final char ch = i < text.length() ? Character.toLowerCase(text.charAt(i)) : ' ';
            if (Character.isLetterOrDigit(ch)) {
                token.append(ch);
            } else if (token.length() > 0) {
                if (token.length() >= 4) result.add(token.toString());
                token.setLength(0);
            }
        }
        return result;
    }

    /** Prefer the citation cluster after a quote in the same clause. Fall back
     * to immediately preceding markers, but never borrow a previous quote's
     * group. This is a conservative syntactic check, not entailment proof.
     */
    private static List<Citation> nearestLocalCitations(final String answer,
                                                        final Quotation quote,
                                                        final int previousQuote,
                                                        final int nextQuote,
                                                        final List<Citation> markers) {
        final List<Citation> after = new ArrayList<>();
        int previousEnd = -1;
        for (final Citation marker : markers) {
            if (marker.start < quote.end) {
                continue;
            }
            if (marker.start >= nextQuote
                    || marker.start - quote.end > MAX_LOCAL_CITATION_GAP) {
                break;
            }
            if (after.isEmpty()) {
                if (containsSentenceBreak(answer, quote.end, marker.start)) {
                    break;
                }
            } else if (!onlyCitationSeparators(answer, previousEnd, marker.start)) {
                break;
            }
            after.add(marker);
            previousEnd = marker.end;
        }
        if (!after.isEmpty()) {
            return after;
        }
        final List<Citation> before = new ArrayList<>();
        int nextStart = quote.start;
        for (int i = markers.size() - 1; i >= 0; i--) {
            final Citation marker = markers.get(i);
            if (marker.end > quote.start || marker.attached) {
                continue;
            }
            if (marker.end <= previousQuote
                    || quote.start - marker.end > MAX_LOCAL_CITATION_GAP) {
                break;
            }
            if (before.isEmpty()) {
                if (containsSentenceBreak(answer, marker.end, quote.start)) {
                    break;
                }
            } else if (!onlyCitationSeparators(answer, marker.end, nextStart)) {
                break;
            }
            before.add(marker);
            nextStart = marker.start;
        }
        return before;
    }

    private static boolean containsSentenceBreak(final String text, final int from, final int to) {
        for (int i = from; i < to; i++) {
            switch (text.charAt(i)) {
                case '.': case '!': case '?': case ';': case '\r': case '\n':
                case '。': case '！': case '？': case '；': case 0x2028: case 0x2029:
                    return true;
                default:
                    break;
            }
        }
        return false;
    }

    private static boolean onlyCitationSeparators(final String text, final int from,
                                                  final int to) {
        for (int i = from; i < to; i++) {
            final char c = text.charAt(i);
            if ((!Character.isWhitespace(c) && c != ',' && c != '،')
                    || c == '\r' || c == '\n' || c == 0x2028 || c == 0x2029) {
                return false;
            }
        }
        return true;
    }
}
