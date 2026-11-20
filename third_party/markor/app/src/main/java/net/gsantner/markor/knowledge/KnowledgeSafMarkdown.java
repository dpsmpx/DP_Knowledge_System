/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.Locale;

/** Explicit one-file SAF transport; stages only privately, never rewrites a vault note. */
final class KnowledgeSafMarkdown {
    private KnowledgeSafMarkdown() { }

    static String safeName(final String raw) throws IOException {
        final String normalized = Normalizer.normalize(raw == null ? "imported.md" : raw,
                Normalizer.Form.NFC);
        if (normalized.length() < 4 || normalized.length() > 120
                || normalized.startsWith(".") || normalized.endsWith(" ")
                || normalized.contains("..") || normalized.contains("/")
                || normalized.contains("\\") || normalized.indexOf('\0') >= 0) {
            throw new IOException("Unsafe Markdown filename");
        }
        final String lower = normalized.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".md") || lower.endsWith(".markdown")))
            throw new IOException("Only original Markdown files can be imported");
        for (int i = 0; i < normalized.length(); i++) {
            final char c = normalized.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '.' && c != '_' && c != '-'
                    && c != ' ' && c != '(' && c != ')')
                throw new IOException("Unsafe character in Markdown filename");
        }
        return normalized;
    }

    static File stage(final InputStream source, final File privateCache) throws IOException {
        if (source == null || privateCache == null || !privateCache.isDirectory())
            throw new IOException("No selected Markdown source");
        final File staged = File.createTempFile(".dpks-md-", ".tmp", privateCache);
        boolean complete = false;
        try (FileOutputStream output = new FileOutputStream(staged)) {
            final byte[] bytes = new byte[32 * 1024];
            long length = 0;
            int n;
            while ((n = source.read(bytes)) != -1) {
                if (Thread.currentThread().isInterrupted())
                    throw new IOException("Markdown transfer cancelled");
                length += n;
                if (length > KnowledgePortableArchive.MAX_FILE_BYTES)
                    throw new IOException("Markdown transfer exceeds safety bound");
                output.write(bytes, 0, n);
            }
            output.getFD().sync();
            complete = true;
            return staged;
        } finally {
            if (!complete) staged.delete();
        }
    }
}
