/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

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

    public static final class Review {
        public final int referenceCount;
        public final int invalidReferences;
        public final int quotedPassages;
        public final int unverifiedQuotes;

        Review(final int count, final int invalid, final int quotes, final int unsupported) {
            referenceCount = count;
            invalidReferences = invalid;
            quotedPassages = quotes;
            unverifiedQuotes = unsupported;
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
        final Set<Integer> referenced = new HashSet<>();
        int count = 0;
        int invalid = 0;
        final Matcher citations = BRACKETS.matcher(answer);
        while (citations.find()) {
            final String label = citations.group(1);
            if (NUMBER.matcher(label).matches()) {
                final int number = Integer.parseInt(label);
                if (number >= 1 && number <= sources.size()) {
                    count++;
                    referenced.add(number - 1);
                } else {
                    invalid++;
                }
            } else if (label.matches(".*[0-9].*")) {
                // Reject malformed numeric citations, including [0], [1, 2],
                // and numbers larger than the supplied source count.
                invalid++;
            }
        }
        int quotes = 0;
        int unsupported = 0;
        final Matcher quotations = QUOTE.matcher(answer);
        while (quotations.find()) {
            quotes++;
            final String literal = quotations.group(1) != null
                    ? quotations.group(1) : quotations.group(2);
            boolean found = false;
            for (final Integer source : referenced) {
                final KnowledgeLlmEvidence.Item item = sources.get(source);
                if (item != null && item.excerpt != null && item.excerpt.contains(literal)) {
                    found = true;
                    break;
                }
            }
            if (!found) unsupported++;
        }
        return new Review(count, invalid, quotes, unsupported);
    }
}
