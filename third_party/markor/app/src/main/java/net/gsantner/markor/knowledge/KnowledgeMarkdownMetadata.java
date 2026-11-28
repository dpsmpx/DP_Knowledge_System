/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight lexical metadata, not a formal interpretation of statements.
 * The original Markdown remains authoritative; links are only candidates.
 */
public final class KnowledgeMarkdownMetadata {
    /** Matches the explicit chat-question gate; no unbounded FTS work. */
    public static final int MAX_EVIDENCE_QUESTION_CHARS = 1200;
    private static final Pattern WIKI_LINK = Pattern.compile(
            "!?\\[\\[([^\\]|#^]+)(?:[#^][^\\]|]*)?(?:\\|[^\\]]*)?\\]\\]");
    private static final Pattern MARKDOWN_LINK = Pattern.compile(
            "!?(?:\\[[^\\]]*\\])\\((?:<([^>]+)>|([^\\s)]+))(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+(.+?)\\s*#*\\s*$");

    public static final class Parsed {
        public final String title;
        public final List<String> possibleLinks;
        public final String searchableText;

        private Parsed(final String title, final Set<String> links, final String original) {
            this.title = title;
            this.possibleLinks = Collections.unmodifiableList(new ArrayList<>(links));
            this.searchableText = original;
        }
    }

    private KnowledgeMarkdownMetadata() {
    }

    public static Parsed parse(final String relativePath, final String markdown) {
        final Set<String> links = new LinkedHashSet<>();
        String title = filenameTitle(relativePath);
        boolean foundTitle = false;
        boolean inCode = false;
        boolean inFrontmatter = false;
        final String[] lines = markdown.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            final String trimmed = line.trim();
            if (i == 0 && trimmed.equals("---")) {
                inFrontmatter = true;
                continue;
            }
            if (inFrontmatter) {
                if (trimmed.equals("---") || trimmed.equals("...")) {
                    inFrontmatter = false;
                }
                continue;
            }
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inCode = !inCode;
                continue;
            }
            if (inCode || trimmed.startsWith("%%")) {
                continue;
            }
            if (!foundTitle) {
                final Matcher heading = HEADING.matcher(line);
                if (heading.matches()) {
                    title = heading.group(1).trim();
                    foundTitle = true;
                }
            }
            Matcher match = WIKI_LINK.matcher(line);
            while (match.find()) {
                addTarget(links, match.group(1));
            }
            match = MARKDOWN_LINK.matcher(line);
            while (match.find()) {
                addTarget(links, match.group(1) != null ? match.group(1) : match.group(2));
            }
        }
        return new Parsed(title, links, markdown);
    }

    public static String filenameTitle(final String path) {
        final String filename = path.substring(path.lastIndexOf('/') + 1);
        final int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /** Normalize an unresolved link target, never assert it uniquely resolves. */
    public static String linkKey(final String target) {
        if (target == null) {
            return "";
        }
        String value = target.trim().replace('\\', '/');
        final int anchor = value.indexOf('#');
        if (anchor >= 0) {
            value = value.substring(0, anchor);
        }
        if (value.isEmpty() || value.startsWith("/") || value.contains("://")
                || value.startsWith("mailto:") || value.startsWith("data:")) {
            return "";
        }
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        value = value.toLowerCase(Locale.ROOT);
        if (value.endsWith(".markdown")) {
            value = value.substring(0, value.length() - ".markdown".length());
        } else if (value.endsWith(".md")) {
            value = value.substring(0, value.length() - 3);
        }
        return value;
    }

    /** Safe FTS4 prefix query: only letter/digit tokens, with quoted terms. */
    public static String ftsQuery(final String userInput) {
        final List<String> tokens = new ArrayList<>();
        final StringBuilder token = new StringBuilder();
        for (int i = 0; i < userInput.length(); i++) {
            final char c = userInput.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                token.append(Character.toLowerCase(c));
            } else if (token.length() > 0) {
                tokens.add(token.toString());
                token.setLength(0);
            }
        }
        if (token.length() > 0) {
            tokens.add(token.toString());
        }
        final StringBuilder query = new StringBuilder();
        for (final String part : tokens.subList(0, Math.min(tokens.size(), 8))) {
            if (query.length() > 0) {
                query.append(' ');
            }
            query.append('"').append(part).append('"').append('*');
        }
        return query.toString();
    }

    /** Bounded recall-first OR query for local LLM evidence, never a raw FTS expression. */
    public static String ftsEvidenceQuery(final String question) {
        final List<String> selected = ftsEvidenceTerms(question);
        final StringBuilder query = new StringBuilder();
        for (final String part : selected) {
            if (query.length() > 0) {
                query.append(" OR ");
            }
            // In FTS4 OR expressions, '*' must be inside each quoted term.
            // The "term"* OR "other"* form can silently return no rows.
            query.append('"').append(part).append('*').append('"');
        }
        return query.toString();
    }

    /** Share the same bounded, normalized terms with the excerpt selector.
     * The original question is never sent anywhere by this helper.
     */
    static List<String> ftsEvidenceTerms(final String question) {
        if (question == null || question.isEmpty()
                || question.length() > MAX_EVIDENCE_QUESTION_CHARS) {
            return Collections.emptyList();
        }
        final StringBuilder word = new StringBuilder();
        final List<String> terms = new ArrayList<>();
        for (int i = 0; i <= question.length(); i++) {
            final char ch = i < question.length() ? question.charAt(i) : ' ';
            if (Character.isLetterOrDigit(ch)) {
                word.append(Character.toLowerCase(ch));
            } else if (word.length() > 0) {
                final String term = word.toString();
                if (term.length() >= 4 && term.length() <= 48
                        && !terms.contains(term)) {
                    terms.add(term);
                }
                word.setLength(0);
            }
        }
        // Drop only common question framing when meaningful search terms remain.
        // A one-word question is still searchable; this is not synonym expansion.
        final List<String> informative = new ArrayList<>();
        for (final String term : terms) {
            if (!isQuestionFiller(term)) {
                informative.add(term);
            }
        }
        final List<String> selected = informative.isEmpty() ? terms : informative;
        return new ArrayList<>(selected.subList(0, Math.min(selected.size(), 8)));
    }

    /** Count distinct query-token prefixes at title word boundaries (not substrings).
     * Inspect at most 256 UTF-16 units of title metadata, never source bodies.
     * One complete title word can satisfy at most one query term.
     */
    static int titleEvidenceTermMatches(final String title, final List<String> terms) {
        if (title == null || title.isEmpty() || terms == null || terms.isEmpty()) {
            return 0;
        }
        final boolean[] matched = new boolean[terms.size()];
        final int end = Math.min(title.length(), 256);
        final StringBuilder word = new StringBuilder();
        int score = 0;
        for (int i = 0; i <= end; i++) {
            final boolean atEnd = i == end;
            final char ch = atEnd ? ' ' : title.charAt(i);
            if (!atEnd && Character.isLetterOrDigit(ch)) {
                word.append(Character.toLowerCase(ch));
            } else if (word.length() > 0) {
                // Do not award credit for a word truncated at the 256-char cap.
                if (!atEnd || end == title.length()
                        || !Character.isLetterOrDigit(title.charAt(end))) {
                    int best = -1;
                    for (int j = 0; j < terms.size(); j++) {
                        if (!matched[j] && word.indexOf(terms.get(j)) == 0
                                && (best < 0 || terms.get(j).length() > terms.get(best).length())) {
                            best = j;
                        }
                    }
                    if (best >= 0) {
                        matched[best] = true;
                        score++;
                    }
                }
                word.setLength(0);
            }
        }
        return score;
    }

    /** Boost an adjacent 2–4-term phrase; the ordinary ALL/OR passes remain.
     * Only sanitized FTS4 tokens are concatenated, never raw query syntax.
     */
    public static String ftsEvidencePhraseQuery(final String question) {
        final String safe = ftsEvidenceQuery(question);
        final String[] quoted = safe.split(" OR ");
        if (quoted.length < 2 || quoted.length > 4) {
            return "";
        }
        final StringBuilder phrase = new StringBuilder("\"");
        for (final String token : quoted) {
            if (phrase.length() > 1) {
                phrase.append(' ');
            }
            // Each token is generated above as "letters-or-digits*".
            phrase.append(token, 1, token.length() - 1);
        }
        return phrase.append('"').toString();
    }

    private static boolean isQuestionFiller(final String term) {
        switch (term) {
            case "what":
            case "which":
            case "where":
            case "when":
            case "whose":
            case "please":
            case "could":
            case "would":
            case "should":
            // Frequent introductory wording in natural-language questions.
            // Keep the original terms when *all* of them are fillers.
            case "tell":
            case "about":
            case "know":
            case "explain":
            case "describe":
            case "какие":
            case "какой":
            case "какая":
            case "какое":
            case "когда":
            case "почему":
            case "зачем":
            case "сколько":
            case "такое":
            case "каковы":
            case "расскажи":
            case "расскажите":
            case "известно":
            case "объясни":
            case "объясните":
            case "опиши":
            case "опишите":
                return true;
            default:
                return false;
        }
    }

    private static void addTarget(final Set<String> links, final String raw) {
        final String key = linkKey(raw);
        if (!key.isEmpty()) {
            links.add(key);
        }
    }
}
