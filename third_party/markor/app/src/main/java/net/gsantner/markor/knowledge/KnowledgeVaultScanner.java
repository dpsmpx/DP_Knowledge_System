/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Added to the Markor fork for DP Knowledge System.
 */
package net.gsantner.markor.knowledge;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Read-only, bounded discovery of Markdown files in a local notebook folder.
 *
 * No note is opened for content reading, modified, moved, or deleted. Canonical
 * paths ensure that a symlink never escapes the selected notebook root.
 */
public final class KnowledgeVaultScanner {
    public static final int MAX_NOTES = 200_000;

    public interface Cancellation {
        boolean isCancelled();
    }

    public static final class Note {
        public final File file;
        public final String relativePath;
        public final long sizeBytes;
        public final long modifiedAtMillis;

        private Note(final File file, final String relativePath) {
            this.file = file;
            this.relativePath = relativePath;
            this.sizeBytes = file.length();
            this.modifiedAtMillis = file.lastModified();
        }
    }

    public static final class Snapshot {
        public final List<Note> notes;
        public final int unreadableDirectories;
        public final boolean truncated;

        private Snapshot(final List<Note> notes, final int unreadableDirectories, final boolean truncated) {
            this.notes = Collections.unmodifiableList(notes);
            this.unreadableDirectories = unreadableDirectories;
            this.truncated = truncated;
        }
    }

    private static final Comparator<Note> NOTE_ORDER = (left, right) -> {
        final int folded = left.relativePath.compareToIgnoreCase(right.relativePath);
        return folded != 0 ? folded : left.relativePath.compareTo(right.relativePath);
    };

    private KnowledgeVaultScanner() {
    }

    public static Snapshot scan(final File notebook, final int limit, final Cancellation cancellation)
            throws IOException {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (notebook == null) {
            throw new IOException("Notebook is not selected");
        }

        final File root = notebook.getCanonicalFile();
        if (!root.isDirectory()) {
            throw new IOException("Notebook directory is unavailable");
        }
        final String rootPrefix = root.getPath() + File.separator;
        final ArrayDeque<File> queue = new ArrayDeque<>();
        final Set<String> visitedDirectories = new HashSet<>();
        final Set<String> visitedFiles = new HashSet<>();
        final List<Note> notes = new ArrayList<>();
        int unreadable = 0;
        boolean truncated = false;
        queue.add(root);

        while (!queue.isEmpty()) {
            checkCancelled(cancellation);
            final File directory = queue.removeFirst();
            final String canonicalDirectory;
            try {
                canonicalDirectory = directory.getCanonicalPath();
            } catch (IOException ignored) {
                unreadable++;
                continue;
            }
            if (!isInsideRoot(root.getPath(), rootPrefix, canonicalDirectory)
                    || !visitedDirectories.add(canonicalDirectory)) {
                continue;
            }
            final File[] children = directory.listFiles();
            if (children == null) {
                unreadable++;
                continue;
            }

            for (final File child : children) {
                checkCancelled(cancellation);
                if (child.getName().startsWith(".")) {
                    continue; // Internal vault metadata, caches, and hidden files.
                }
                final String childPath;
                try {
                    childPath = child.getCanonicalPath();
                } catch (IOException ignored) {
                    unreadable++;
                    continue;
                }
                if (!childPath.startsWith(rootPrefix)) {
                    continue; // Do not traverse or expose external symlink targets.
                }
                if (child.isDirectory()) {
                    queue.addLast(child);
                } else if (child.isFile() && isMarkdown(child.getName())
                        && visitedFiles.add(childPath)) {
                    notes.add(new Note(new File(childPath),
                            childPath.substring(rootPrefix.length()).replace(File.separatorChar, '/')));
                    if (notes.size() >= limit) {
                        truncated = true;
                        queue.clear();
                        break;
                    }
                }
            }
        }

        Collections.sort(notes, NOTE_ORDER);
        return new Snapshot(notes, unreadable, truncated);
    }

    private static boolean isInsideRoot(final String root, final String prefix, final String path) {
        return path.equals(root) || path.startsWith(prefix);
    }

    private static boolean isMarkdown(final String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    private static void checkCancelled(final Cancellation cancellation) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()
                || (cancellation != null && cancellation.isCancelled())) {
            throw new InterruptedIOException("Vault scan cancelled");
        }
    }
}
