/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bounded, literal token comparison for two read-only representations.
 * This does not establish semantic equivalence between Markdown and KSF.
 * All offsets are Java UTF-16 indices, suitable for Spannable text spans.
 */
final class KnowledgeTextDiff {
    private static final int MAX_LENGTH = 1024;
    private static final int MAX_TOKENS = 128;

    static final class Range {
        final int start;
        final int end;

        Range(final int start, final int end) {
            this.start = start;
            this.end = end;
        }
    }

    static final class Result {
        final boolean available;
        final List<Range> sourceChanges;
        final List<Range> formalChanges;

        Result(final boolean available, final List<Range> sourceChanges,
               final List<Range> formalChanges) {
            this.available = available;
            this.sourceChanges = sourceChanges;
            this.formalChanges = formalChanges;
        }

        boolean hasChanges() {
            return !sourceChanges.isEmpty() || !formalChanges.isEmpty();
        }
    }

    private static final class Token {
        final String text;
        final int start;
        final int end;

        Token(final String text, final int start, final int end) {
            this.text = text;
            this.start = start;
            this.end = end;
        }
    }

    private KnowledgeTextDiff() { }

    static Result unavailable() {
        return new Result(false, Collections.emptyList(), Collections.emptyList());
    }

    static Result compare(final String source, final String formal) {
        if (source == null || formal == null || source.isEmpty() || formal.isEmpty()
                || source.length() > MAX_LENGTH || formal.length() > MAX_LENGTH) {
            return unavailable();
        }
        final List<Token> from = tokens(source);
        final List<Token> to = tokens(formal);
        if (from.isEmpty() || to.isEmpty() || from.size() > MAX_TOKENS
                || to.size() > MAX_TOKENS) {
            return unavailable();
        }
        final int[][] suffix = new int[from.size() + 1][to.size() + 1];
        for (int i = from.size() - 1; i >= 0; i--) {
            for (int j = to.size() - 1; j >= 0; j--) {
                suffix[i][j] = from.get(i).text.equals(to.get(j).text)
                        ? suffix[i + 1][j + 1] + 1
                        : Math.max(suffix[i + 1][j], suffix[i][j + 1]);
            }
        }
        final boolean[] keptSource = new boolean[from.size()];
        final boolean[] keptFormal = new boolean[to.size()];
        int i = 0;
        int j = 0;
        while (i < from.size() && j < to.size()) {
            if (from.get(i).text.equals(to.get(j).text)) {
                keptSource[i++] = true;
                keptFormal[j++] = true;
            } else if (suffix[i + 1][j] >= suffix[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return new Result(true, changed(from, keptSource), changed(to, keptFormal));
    }

    private static List<Token> tokens(final String text) {
        final List<Token> result = new ArrayList<>();
        for (int offset = 0; offset < text.length();) {
            final int cp = text.codePointAt(offset);
            final int length = Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                offset += length;
                continue;
            }
            final int start = offset;
            offset += length;
            if (Character.isLetterOrDigit(cp) || cp == '_') {
                while (offset < text.length()) {
                    final int next = text.codePointAt(offset);
                    if (!Character.isLetterOrDigit(next) && next != '_') break;
                    offset += Character.charCount(next);
                }
            }
            result.add(new Token(text.substring(start, offset), start, offset));
            if (result.size() > MAX_TOKENS) break;
        }
        return result;
    }

    private static List<Range> changed(final List<Token> tokens, final boolean[] kept) {
        final List<Range> ranges = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (kept[i]) continue;
            final Token token = tokens.get(i);
            if (!ranges.isEmpty() && ranges.get(ranges.size() - 1).end == token.start) {
                final Range previous = ranges.remove(ranges.size() - 1);
                ranges.add(new Range(previous.start, token.end));
            } else {
                ranges.add(new Range(token.start, token.end));
            }
        }
        return ranges;
    }
}
