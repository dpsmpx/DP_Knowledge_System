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
        if (tokens.size() > 8) {
            tokens = tokens.subList(0, 8);
        }
        final StringBuilder query = new StringBuilder();
        for (final String part : tokens) {
            if (query.length() > 0) {
                query.append(' ');
            }
            query.append('"').append(part).append('"').append('*');
        }
        return query.toString();
    }

    private static void addTarget(final Set<String> links, final String raw) {
        final String key = linkKey(raw);
        if (!key.isEmpty()) {
            links.add(key);
        }
    }
}
