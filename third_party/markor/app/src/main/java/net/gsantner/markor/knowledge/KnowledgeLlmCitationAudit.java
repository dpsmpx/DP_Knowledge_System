/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
    private static final Pattern BRACKETS = Pattern.compile(
            "\\[([^\\]\\r\\n\\x{85}\\x{2028}\\x{2029}]{1,24})\\]");
    private static final Pattern REFERENCE_DEFINITION = Pattern.compile(
            "(?m)^ {0,3}\\[[^\\]\\r\\n\\x{85}\\x{2028}\\x{2029}]{1,24}\\]:[^\\r\\n\\x{85}\\x{2028}\\x{2029}]*");
    private static final Pattern AUTOLINK = Pattern.compile(
            "<(?:https?://|mailto:)[^<>\\s\\x{85}\\x{2028}\\x{2029}]{1,2048}>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("[1-9][0-9]?");
    private static final Pattern QUOTE = Pattern.compile(
            "«([^»\\r\\n\\x{85}\\x{2028}\\x{2029}]{4,240})»|"
                    + "“([^”\\r\\n\\x{85}\\x{2028}\\x{2029}]{4,240})”");
    private static final Pattern NEGATED_BOOLEAN = Pattern.compile("(?i)\\bnot\\s+(?:true|false)\\b");
    private static final Pattern NUMERIC_LITERAL = Pattern.compile(
            "(?<![\\p{L}\\p{N}])[0-9]{1,12}(?:[.,][0-9]{1,4})?(?![0-9])");
    /** Generic connective words cannot make an otherwise unrelated source
     * look lexically grounded. This shortlist is intentionally conservative.
     */
    private static final Set<String> NON_ANCHOR_WORDS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "this", "that", "these", "those", "with", "from", "have", "were",
                    "there", "their", "which", "about", "into", "where", "when", "what",
                    "also", "then", "your", "does", "been", "being",
                    "этого", "этому", "этой", "также", "когда", "которые",
                    "который", "которая", "которое", "своего", "этими", "если", "чтобы")));
    private static final int MAX_LOCAL_CITATION_GAP = 180;
    /** Narrow contrastive conjunctions, not a general clause/sentence parser. */
    private static final String[] CONTRASTIVE_CONNECTORS = {
            "but", "however", "однако", "но"
    };
    private static final int MIN_PROSE_LETTERS = 16;
    private static final int MIN_PROSE_WORDS = 3;
    public static final int MAX_FLAG_DETAILS = 8;
    public static final int MAX_FLAG_SNIPPET = 140;

    /** Offsets refer to the original model answer, not private source text. */
    public static final class Flag {
        public enum Kind { WEAK_TEXT, EXPLICIT_POLARITY, NUMERIC_MISMATCH }

        public final Kind kind;
        public final int sourceNumber;
        public final int from;
        public final int to;

        private Flag(final Kind reason, final int source, final int start, final int end) {
            kind = reason;
            sourceNumber = source;
            from = start;
            to = end;
        }
    }

    private static final class ClaimAudit {
        int weak;
        int polarity;
        int numeric;
        final List<Flag> details = new ArrayList<>();

        void add(final Flag.Kind kind, final int source, final int from, final int to) {
            if (kind == Flag.Kind.WEAK_TEXT) weak++;
            else if (kind == Flag.Kind.EXPLICIT_POLARITY) polarity++;
            else numeric++;
            if (details.size() < MAX_FLAG_DETAILS) {
                details.add(new Flag(kind, source + 1, from, to));
            }
        }
    }

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

    /** Reuse the bounded source lexicon across sentences and pair checks.
     * Model answers can cite the same excerpt hundreds of times; repeatedly
     * tokenizing private source text adds work without adding evidence.
     */
    private static final class SourceLexicon {
        final Set<String> terms;
        final Set<String> anchors;
        final Set<String> numbers;
        final int polarity;

        SourceLexicon(final String excerpt) {
            terms = contentWords(excerpt);
            anchors = informativeTerms(terms);
            numbers = numericTokens(excerpt, null, 0);
            polarity = explicitBooleanPolarity(excerpt, terms);
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
        /** Cited spans with weak overlap among distinctive content terms. */
        public final int weakTextAttributions;
        /** Explicit true/false disagreement between a span and its cited text. */
        public final int polarityMismatchedCitations;
        /** Possible differing written numbers in otherwise overlapping text. */
        public final int numericMismatchedCitations;
        /** Pairs of supplied excerpts with opposing explicit true/false labels. */
        public final int potentiallyOpposingSources;
        /** At most eight exact model-answer spans, never vault excerpts. */
        public final List<Flag> flaggedSpans;

        Review(final int count, final int invalid, final int quotes, final int unsupported,
               final int mismatched, final int uncited, final int weak,
               final int polarity, final int numeric, final int opposing,
               final List<Flag> details) {
            referenceCount = count;
            invalidReferences = invalid;
            quotedPassages = quotes;
            unverifiedQuotes = unsupported;
            mismatchedQuoteCitations = mismatched;
            uncitedProseSpans = uncited;
            weakTextAttributions = weak;
            polarityMismatchedCitations = polarity;
            numericMismatchedCitations = numeric;
            potentiallyOpposingSources = opposing;
            flaggedSpans = Collections.unmodifiableList(new ArrayList<>(details));
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
        final List<SourceLexicon> lexicons = new ArrayList<>(sources.size());
        for (final KnowledgeLlmEvidence.Item item : sources) {
            final String excerpt = item == null ? null : item.excerpt;
            if (excerpt != null && excerpt.length() > KnowledgeLlmEvidence.MAX_EXCERPT_CHARS) {
                throw new IllegalArgumentException("Unbounded source excerpt for citation audit");
            }
            lexicons.add(new SourceLexicon(excerpt));
        }
        // The model can echo fake [1] references inside a fenced example or
        // inline code. Preserve offsets while excluding those from evidence.
        final String visible = maskMarkdownNonProse(answer);
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
            if (isEscapedBracket(visible, citations.start())
                    || isMarkdownLinkLabel(visible, citations.start(), citations.end())
                    || citations.group(1).startsWith("^")) {
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
            } else if (containsNumericDigit(label)) {
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
        final ClaimAudit attribution = inspectCitedTextAnchors(visible, markers, lexicons);
        return new Review(count, invalid, quotes.size(), unsupported, mismatched,
                countUncitedProseSpans(visible, markers), attribution.weak, attribution.polarity,
                attribution.numeric, countOpposingSourcePairs(lexicons), attribution.details);
    }

    /** Non-ASCII decimal digits still look like source references to a reader.
     * Reject them as malformed rather than silently ignoring the marker.
     * Iterate code points to include supplementary-plane mathematical digits.
     */
    private static boolean containsNumericDigit(final String label) {
        for (int i = 0; i < label.length();) {
            final int point = label.codePointAt(i);
            if (Character.isDigit(point)) return true;
            i += Character.charCount(point);
        }
        return false;
    }

    /** Differentiate [1] [١] citation clusters from [1] [bibliography]
     * reference-style Markdown links. Allow only numeric separators here;
     * a reference identifier containing letters remains a Markdown link.
     */
    private static boolean isNumericCitationCluster(final String label) {
        boolean hasDigit = false;
        for (int i = 0; i < label.length();) {
            final int point = label.codePointAt(i);
            if (Character.isDigit(point)) {
                hasDigit = true;
            } else if (point != ' ' && point != ',' && point != ';') {
                return false;
            }
            i += Character.charCount(point);
        }
        return hasDigit;
    }

    /** A single backslash escapes a visible bracket; two backslashes do not. */
    private static boolean isEscapedBracket(final String text, final int bracket) {
        return isEscapedAt(text, bracket);
    }

    private static boolean isEscapedAt(final String text, final int position) {
        int backslashes = 0;
        for (int i = position - 1; i >= 0 && text.charAt(i) == '\\'; i--) backslashes++;
        return (backslashes & 1) != 0;
    }

    /** Exclude Markdown link syntax without suppressing the established [1][2]
     * convention for two adjacent numeric source citations.
     */
    private static boolean isMarkdownLinkLabel(final String text, final int start,
                                               final int end) {
        if ((end < text.length() && text.charAt(end) == '(')
                || (start > 0 && text.charAt(start - 1) == '[')
                || (end < text.length() && text.charAt(end) == ']')) return true;
        if (end < text.length() && text.charAt(end) == ':') {
            int lineStart = start;
            while (lineStart > 0 && !isLineBreak(text.charAt(lineStart - 1))) lineStart--;
            if (start - lineStart <= 3) {
                boolean definition = true;
                for (int i = lineStart; i < start; i++) {
                    if (text.charAt(i) != ' ') definition = false;
                }
                if (definition) return true;
            }
        }
        // Full Markdown reference links permit horizontal whitespace and a
        // single soft line break between [label] and [reference]. A literal
        // [1] followed by a named label is not a citation, even with tabs or
        // more than one space. Bound the look-ahead within a 12k answer and
        // never bridge blank lines or a second line break.
        int next = end;
        int breaks = 0;
        final int maximum = Math.min(text.length(), end + 64);
        while (next < maximum) {
            final char character = text.charAt(next);
            if (character == ' ' || character == '\t') {
                next++;
            } else if (isLineBreak(character) && breaks == 0) {
                breaks++;
                next += character == '\r' && next + 1 < text.length()
                        && text.charAt(next + 1) == '\n' ? 2 : 1;
            } else {
                break;
            }
        }
        if (next < text.length() && next <= maximum && text.charAt(next) == '['
                && !isEscapedAt(text, next)) {
            final int close = text.indexOf(']', next + 1);
            if (close > next && close - next <= 25) {
                final String reference = text.substring(next + 1, close);
                // Numeric-looking bracket clusters are source markers, not
                // reference-style Markdown links, even with Unicode digits.
                if (reference.isEmpty() || (!NUMBER.matcher(reference).matches()
                        && !isNumericCitationCluster(reference))) return true;
            }
        }
        return false;
    }

    /** HTML comments/tags, non-rendered raw blocks, link destinations and
     * autolinks are not displayed answer claims. Preserve offsets and
     * newlines before scanning citations, quotes or attributed prose.
     */
    private static String maskMarkdownNonProse(final String answer) {
        final StringBuilder visible = new StringBuilder(maskMarkdownCode(answer));
        String snapshot = visible.toString();
        for (int from = 0; from < snapshot.length();) {
            final int start = snapshot.indexOf("<!--", from);
            if (start < 0) break;
            if (isEscapedAt(snapshot, start)) {
                from = start + 4;
                continue;
            }
            final int close = snapshot.indexOf("-->", start + 4);
            final int end = close < 0 ? snapshot.length() : close + 3;
            maskRange(visible, start, end);
            from = end;
        }
        snapshot = visible.toString();
        final Matcher definitions = REFERENCE_DEFINITION.matcher(snapshot);
        while (definitions.find()) maskRange(visible, definitions.start(), definitions.end());
        snapshot = visible.toString();
        final Matcher autolinks = AUTOLINK.matcher(snapshot);
        while (autolinks.find()) maskRange(visible, autolinks.start(), autolinks.end());
        snapshot = visible.toString();
        for (int at = 0; at + 1 < snapshot.length(); at++) {
            if (snapshot.charAt(at) != ']' || snapshot.charAt(at + 1) != '('
                    || isEscapedAt(snapshot, at + 1)) continue;
            final int end = markdownLinkDestinationEnd(snapshot, at + 1);
            if (end > 0) {
                // Preserve the opening '(' so [1](url) is still recognized
                // as a link label, never as a standalone source citation.
                maskRange(visible, at + 2, end);
                at = end - 1;
            }
        }
        maskHtmlTagsAndRawSections(visible);
        return visible.toString();
    }

    /** HTML attributes, script/style bodies and template content must not
     * donate fake citations or quotations. Keep ordinary inline tag *text*:
     * <span>[1]</span> still has a visible source marker between its tags.
     * The answer is capped at 12k; an individual tag scan is capped at 1024.
     */
    private static void maskHtmlTagsAndRawSections(final StringBuilder visible) {
        final String text = visible.toString();
        for (int at = 0; at < text.length(); at++) {
            if (text.charAt(at) != '<' || isEscapedAt(text, at)) continue;
            final int tagEnd = htmlTagEnd(text, at);
            if (tagEnd < 0) continue;
            int nameStart = at + 1;
            final boolean closing = text.charAt(nameStart) == '/';
            if (closing) nameStart++;
            int nameEnd = nameStart;
            while (nameEnd < tagEnd && isHtmlTagNameChar(text.charAt(nameEnd))) nameEnd++;
            final String name = text.substring(nameStart, nameEnd);
            int last = tagEnd - 2;
            while (last > nameEnd && Character.isWhitespace(text.charAt(last))) last--;
            final boolean raw = !closing && text.charAt(last) != '/'
                    && ("script".equalsIgnoreCase(name) || "style".equalsIgnoreCase(name)
                    || "template".equalsIgnoreCase(name));
            int end = tagEnd;
            if (raw) {
                final int closingEnd = htmlRawClosingEnd(text, tagEnd, name);
                end = closingEnd < 0 ? text.length() : closingEnd;
            }
            maskRange(visible, at, end);
            at = end - 1;
        }
    }

    private static boolean isHtmlTagNameChar(final char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9') || ch == '-' || ch == ':' || ch == '_';
    }

    private static boolean isAsciiHtmlLetter(final char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
    }

    /** Return the exclusive closing '>' offset only for plausible HTML tags.
     * A '>' inside a quoted attribute must not prematurely end the tag.
     */
    private static int htmlTagEnd(final String text, final int opening) {
        int at = opening + 1;
        if (at < text.length() && text.charAt(at) == '/') at++;
        if (at >= text.length() || !isAsciiHtmlLetter(text.charAt(at))) return -1;
        while (at < text.length() && isHtmlTagNameChar(text.charAt(at))) at++;
        if (at >= text.length() || !(Character.isWhitespace(text.charAt(at))
                || text.charAt(at) == '>' || text.charAt(at) == '/')) return -1;
        char quote = 0;
        for (int end = at; end < text.length() && end - opening <= 1024; end++) {
            final char ch = text.charAt(end);
            if (quote != 0) {
                if (ch == quote) quote = 0;
            } else if (ch == '\'' || ch == '"') {
                quote = ch;
            } else if (ch == '>') {
                return end + 1;
            }
        }
        return -1;
    }

    private static int htmlRawClosingEnd(final String text, final int from,
                                         final String name) {
        for (int at = text.indexOf("</", from); at >= 0; at = text.indexOf("</", at + 2)) {
            final int nameStart = at + 2;
            final int afterName = nameStart + name.length();
            if (afterName >= text.length() || !text.regionMatches(true,
                    nameStart, name, 0, name.length())
                    || !(Character.isWhitespace(text.charAt(afterName))
                    || text.charAt(afterName) == '>')) continue;
            final int end = htmlTagEnd(text, at);
            if (end > 0) return end;
        }
        return -1;
    }

    /** Scan balanced parentheses, including escaped closing parentheses.
     * Unclosed or multiline destinations are not masked as valid links.
     */
    private static int markdownLinkDestinationEnd(final String text, final int opening) {
        int depth = 1;
        for (int at = opening + 1; at < text.length(); at++) {
            final char ch = text.charAt(at);
            if (isLineBreak(ch)) return -1;
            if (isEscapedAt(text, at)) continue;
            if (ch == '(') depth++;
            else if (ch == ')' && --depth == 0) return at + 1;
        }
        return -1;
    }

    private static boolean isLineBreak(final char ch) {
        return ch == '\n' || ch == '\r' || ch == 0x0085
                || ch == 0x2028 || ch == 0x2029;
    }

    private static int lineEnd(final String text, final int from) {
        for (int i = from; i < text.length(); i++) {
            if (isLineBreak(text.charAt(i))) return i;
        }
        return text.length();
    }

    private static int nextLineStart(final String text, final int end) {
        if (end >= text.length()) return text.length();
        return end + (text.charAt(end) == '\r' && end + 1 < text.length()
                && text.charAt(end + 1) == '\n' ? 2 : 1);
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
            final int lineEnd = lineEnd(answer, lineStart);
            // Markdown indented code (four columns, including mixed spaces
            // and tabs) is not answer prose. Be conservative about list context.
            int indentColumns = 0;
            for (int at = lineStart; at < lineEnd && indentColumns < 4; at++) {
                final char whitespace = answer.charAt(at);
                if (whitespace == ' ') indentColumns++;
                else if (whitespace == '\t') indentColumns += 4 - indentColumns % 4;
                else break;
            }
            if (fenceChar == 0 && indentColumns >= 4) {
                maskRange(visible, lineStart, lineEnd);
                lineStart = nextLineStart(answer, lineEnd);
                continue;
            }
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
                lineStart = nextLineStart(answer, lineEnd);
                continue;
            }
            if (fenceChar != 0) {
                maskRange(visible, lineStart, lineEnd);
            } else {
                for (int at = lineStart; at < lineEnd;) {
                    if (answer.charAt(at) != '`' || isEscapedAt(answer, at)) {
                        at++;
                        continue;
                    }
                    int close = at + 1;
                    while (close < lineEnd && answer.charAt(close) == '`') close++;
                    final int ticks = close - at;
                    int end = close;
                    while (end < lineEnd) {
                        if (answer.charAt(end) != '`' || isEscapedAt(answer, end)) {
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
            lineStart = nextLineStart(answer, lineEnd);
        }
        return visible.toString();
    }

    private static void maskRange(final StringBuilder text, final int from, final int to) {
        for (int i = from; i < to; i++) {
            if (!isLineBreak(text.charAt(i))) text.setCharAt(i, ' ');
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
            final int lineEnd = lineEnd(visible, lineStart);
            final String trimmed = visible.substring(lineStart, lineEnd).trim();
            if (!isMarkdownAtxHeading(trimmed)) {
                int spanStart = lineStart;
                for (int i = lineStart; i < lineEnd; i++) {
                    if (isProseBoundary(visible, i, lineEnd)) {
                        final int end = sentenceBoundaryEnd(visible, i, lineEnd, markers);
                        if (end > i) {
                            if (uncitedSubstantiveSpan(visible, spanStart, end, markers)) uncited++;
                            spanStart = end;
                            i = end - 1;
                        }
                    }
                }
                if (uncitedSubstantiveSpan(visible, spanStart, lineEnd, markers)) uncited++;
            }
            lineStart = nextLineStart(visible, lineEnd);
        }
        return uncited;
    }

    private static boolean isSentenceTerminator(final char ch) {
        return ch == '.' || ch == '!' || ch == '?' || ch == ';'
                || ch == '。' || ch == '！' || ch == '？' || ch == '；';
    }

    /** A citation before ", but" or ", однако" cannot cover the following
     * independent claim. Require a full connective token after a comma;
     * ordinary lists, decimal commas and "butter" must not split spans.
     * Limit skipped spaces to avoid an unbounded natural-language parser.
     */
    private static boolean isContrastiveClauseSeparator(final String text, final int comma,
                                                         final int end) {
        if (text.charAt(comma) != ',' && text.charAt(comma) != '،') return false;
        int next = comma + 1;
        final int limit = Math.min(end, next + 32);
        while (next < limit && (text.charAt(next) == ' ' || text.charAt(next) == '\t')) next++;
        for (final String connective : CONTRASTIVE_CONNECTORS) {
            final int after = next + connective.length();
            if (after < end && text.regionMatches(true, next, connective, 0,
                    connective.length()) && !Character.isLetterOrDigit(text.charAt(after))
                    && text.charAt(after) != '_') return true;
        }
        return false;
    }

    private static boolean isProseBoundary(final String text, final int at, final int end) {
        return isSentenceTerminator(text.charAt(at))
                || isContrastiveClauseSeparator(text, at, end);
    }

    /** Only # through ###### followed by whitespace are ATX headings. Seven
     * hashes are plain Markdown text and must retain coverage warnings.
     */
    private static boolean isMarkdownAtxHeading(final String trimmed) {
        int count = 0;
        while (count < trimmed.length() && trimmed.charAt(count) == '#') count++;
        return count >= 1 && count <= 6 && (count == trimmed.length()
                || Character.isWhitespace(trimmed.charAt(count)));
    }

    /** Keep trailing citation clusters attached to the sentence BEFORE them.
     * In "Claim. [1] Next claim", [1] must never cover the next claim.
     * Return -1 for punctuation without a sentence boundary.
     */
    private static int sentenceBoundaryEnd(final String text, final int punctuation,
                                           final int lineEnd, final List<Citation> markers) {
        if (isContrastiveClauseSeparator(text, punctuation, lineEnd)) return punctuation + 1;
        int next = punctuation + 1;
        while (next < lineEnd && Character.isWhitespace(text.charAt(next))) next++;
        if (next == lineEnd) return punctuation + 1;
        final Citation first = citationStartingAt(markers, next);
        if (first == null) return next > punctuation + 1 ? punctuation + 1 : -1;
        int lastEnd = first.end;
        while (true) {
            int candidate = lastEnd;
            while (candidate < lineEnd && Character.isWhitespace(text.charAt(candidate))) {
                candidate++;
            }
            if (candidate < lineEnd && text.charAt(candidate) == ',') {
                candidate++;
                while (candidate < lineEnd && Character.isWhitespace(text.charAt(candidate))) {
                    candidate++;
                }
            }
            final Citation more = citationStartingAt(markers, candidate);
            if (more == null) break;
            lastEnd = more.end;
        }
        return lastEnd;
    }

    /** Citation markers are already sorted by regex offset. */
    private static Citation citationStartingAt(final List<Citation> markers, final int start) {
        int low = 0;
        int high = markers.size() - 1;
        while (low <= high) {
            final int middle = low + (high - low) / 2;
            final Citation marker = markers.get(middle);
            if (marker.start == start) return marker;
            if (marker.start < start) low = middle + 1;
            else high = middle - 1;
        }
        return null;
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
    private static ClaimAudit inspectCitedTextAnchors(final String visible,
                                                      final List<Citation> markers,
                                                      final List<SourceLexicon> sources) {
        final ClaimAudit result = new ClaimAudit();
        for (int lineStart = 0; lineStart < visible.length();) {
            final int lineEnd = lineEnd(visible, lineStart);
            final String trimmed = visible.substring(lineStart, lineEnd).trim();
            if (!isMarkdownAtxHeading(trimmed)) {
                int start = lineStart;
                for (int i = lineStart; i < lineEnd; i++) {
                    if (!isProseBoundary(visible, i, lineEnd)) continue;
                    final int end = sentenceBoundaryEnd(visible, i, lineEnd, markers);
                    if (end > i) {
                        inspectOneAttributedSpan(visible, start, end, markers, sources, result);
                        start = end;
                        i = end - 1;
                    }
                }
                inspectOneAttributedSpan(visible, start, lineEnd, markers, sources, result);
            }
            lineStart = nextLineStart(visible, lineEnd);
        }
        return result;
    }

    private static void inspectOneAttributedSpan(final String visible, final int from,
                                                 final int to, final List<Citation> markers,
                                                 final List<SourceLexicon> sources,
                                                 final ClaimAudit counts) {
        if (!substantiveSpan(visible, from, to)) return;
        final String statement = visible.substring(from, to);
        final Set<String> terms = contentWords(statement);
        if (terms.size() < 2) return;
        final Set<String> anchors = informativeTerms(terms);
        final Set<String> numbers = numericTokens(statement, markers, from);
        final int statementPolarity = explicitBooleanPolarity(statement, terms);
        final Set<Integer> checked = new HashSet<>();
        for (final Citation marker : markers) {
            if (marker.source < 0 || marker.start < from || marker.end > to
                    || !checked.add(marker.source)) continue;
            final SourceLexicon source = sources.get(marker.source);
            int overlap = 0;
            for (final String term : anchors) {
                if (source.anchors.contains(term)) overlap++;
            }
            if (overlap < 2 || (anchors.size() >= 8 && overlap * 4 < anchors.size())) {
                counts.add(Flag.Kind.WEAK_TEXT, marker.source, from, to);
            }
            if (statementPolarity != 0 && source.polarity == -statementPolarity) {
                counts.add(Flag.Kind.EXPLICIT_POLARITY, marker.source, from, to);
            }
            // A single shared number must not hide a second changed number.
            // Check every explicit claim number against the cited excerpt;
            // the source may legitimately contain extra numbers.
            if (overlap >= 2 && !numbers.isEmpty() && !source.numbers.isEmpty()
                    && !source.numbers.containsAll(numbers)) {
                counts.add(Flag.Kind.NUMERIC_MISMATCH, marker.source, from, to);
            }
        }
    }

    /** Render a bounded, single-line excerpt from the untrusted MODEL ANSWER,
     * never from a private Markdown note. Keep exact offsets for testability.
     */
    public static String flaggedAnswerSnippet(final String answer, final Flag flag) {
        if (answer == null || flag == null || flag.from < 0 || flag.to > answer.length()
                || flag.from >= flag.to) return "";
        final String raw = answer.substring(flag.from, flag.to).trim();
        final StringBuilder clean = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            final char ch = raw.charAt(i);
            if (Character.isISOControl(ch) || Character.isWhitespace(ch)
                    || Character.getType(ch) == Character.LINE_SEPARATOR
                    || Character.getType(ch) == Character.PARAGRAPH_SEPARATOR
                    || Character.getType(ch) == Character.FORMAT) {
                if (clean.length() > 0 && clean.charAt(clean.length() - 1) != ' ') {
                    clean.append(' ');
                }
            } else {
                clean.append(ch);
            }
            if (clean.length() > MAX_FLAG_SNIPPET) break;
        }
        if (clean.length() > MAX_FLAG_SNIPPET) {
            return clean.substring(0, MAX_FLAG_SNIPPET - 1) + "…";
        }
        return clean.toString().trim();
    }

    /** Identify ONLY simple explicit true/false opposition in otherwise
     * related excerpts. Never infer general contradiction or source truth.
     */
    private static int countOpposingSourcePairs(final List<SourceLexicon> sources) {
        int opposing = 0;
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).polarity == 0) continue;
            for (int j = i + 1; j < sources.size(); j++) {
                if (sources.get(j).polarity != -sources.get(i).polarity) continue;
                int shared = 0;
                for (final String term : sources.get(i).anchors) {
                    if (!"true".equals(term) && !"false".equals(term)
                            && sources.get(j).anchors.contains(term)) shared++;
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

    private static Set<String> informativeTerms(final Set<String> terms) {
        final Set<String> anchors = new HashSet<>(terms);
        anchors.removeAll(NON_ANCHOR_WORDS);
        return anchors;
    }

    /** Only compare simple explicitly written unsigned numbers. Exclude the
     * numbered [1] citation markers themselves from the model claim. This is
     * not quantity extraction, arithmetic verification or unit conversion.
     */
    private static Set<String> numericTokens(final String text,
                                             final List<Citation> markers,
                                             final int answerOffset) {
        final Set<String> values = new HashSet<>();
        if (text == null) return values;
        final Matcher numbers = NUMERIC_LITERAL.matcher(text);
        while (numbers.find()) {
            // A [2](url) link label or [^2] footnote is not a measured value,
            // even if another real citation occurs in the same sentence.
            if (numbers.end() < text.length() && text.charAt(numbers.end()) == ']'
                    && ((numbers.start() > 0 && text.charAt(numbers.start() - 1) == '[')
                    || (numbers.start() > 1 && text.charAt(numbers.start() - 1) == '^'
                    && text.charAt(numbers.start() - 2) == '['))) continue;
            boolean isCitation = false;
            if (markers != null) {
                final int from = answerOffset + numbers.start();
                final int to = answerOffset + numbers.end();
                for (final Citation marker : markers) {
                    if (marker.end <= from) continue;
                    if (marker.start >= to) break;
                    if (marker.start <= from && marker.end >= to) {
                        isCitation = true;
                        break;
                    }
                }
            }
            if (!isCitation) values.add(canonicalNumber(numbers.group()));
        }
        return values;
    }

    private static String canonicalNumber(final String literal) {
        final String formatted = literal.replace(',', '.');
        final int dot = formatted.indexOf('.');
        String integer = dot < 0 ? formatted : formatted.substring(0, dot);
        while (integer.length() > 1 && integer.charAt(0) == '0') {
            integer = integer.substring(1);
        }
        if (dot < 0) return integer;
        String fraction = formatted.substring(dot + 1);
        while (fraction.endsWith("0")) fraction = fraction.substring(0, fraction.length() - 1);
        return fraction.isEmpty() ? integer : integer + "." + fraction;
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
            if (isContrastiveClauseSeparator(text, i, to)) return true;
            switch (text.charAt(i)) {
                case '.': case '!': case '?': case ';': case '\r': case '\n':
                case '。': case '！': case '？': case '；': case 0x0085:
                case 0x2028: case 0x2029:
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
                    || isLineBreak(c)) {
                return false;
            }
        }
        return true;
    }
}
