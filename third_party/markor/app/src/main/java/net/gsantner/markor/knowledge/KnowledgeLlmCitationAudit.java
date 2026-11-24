/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import java.util.ArrayList;
import java.util.List;
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
    private static final int MAX_LOCAL_CITATION_GAP = 180;

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

        Review(final int count, final int invalid, final int quotes, final int unsupported,
               final int mismatched) {
            referenceCount = count;
            invalidReferences = invalid;
            quotedPassages = quotes;
            unverifiedQuotes = unsupported;
            mismatchedQuoteCitations = mismatched;
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
        final List<Citation> markers = new ArrayList<>();
        int count = 0;
        int invalid = 0;
        final Matcher citations = BRACKETS.matcher(answer);
        while (citations.find()) {
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
        final List<Quotation> quotes = new ArrayList<>();
        final Matcher quotations = QUOTE.matcher(answer);
        while (quotations.find()) {
            quotes.add(new Quotation(quotations.start(), quotations.end(),
                    quotations.group(1) != null ? quotations.group(1) : quotations.group(2)));
        }
        int unsupported = 0;
        int mismatched = 0;
        for (int i = 0; i < quotes.size(); i++) {
            final Quotation quote = quotes.get(i);
            final int nextQuote = i + 1 < quotes.size() ? quotes.get(i + 1).start : answer.length();
            final int previousQuote = i > 0 ? quotes.get(i - 1).end : 0;
            final List<Citation> local = nearestLocalCitations(answer, quote, previousQuote,
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
        return new Review(count, invalid, quotes.size(), unsupported, mismatched);
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
