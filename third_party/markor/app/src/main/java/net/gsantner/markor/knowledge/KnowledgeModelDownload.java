/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Locale;

/**
 * Explicit public HTTPS model-file transfer, never a provider for note text.
 * The user must provide a direct weight-file URL (not a catalog/repository page).
 * Redirects are validated individually; authentication headers are never used.
 */
final class KnowledgeModelDownload {
    private static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 25000;

    interface Progress { void onBytes(long copied, long announcedTotal); }
    interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }

    static final class Source {
        final URI uri;
        final String filename;

        Source(final URI link, final String name) {
            uri = link;
            filename = name;
        }
    }

    private KnowledgeModelDownload() { }

    static Source parseSource(final String supplied) throws IOException {
        final URI uri = secureUri(supplied);
        final String path = uri.getRawPath();
        final String basename = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        if (basename.indexOf('%') != -1 || basename.isEmpty()) {
            // Encoded suffixes and path traversal are not needed for direct weight artifacts.
            throw new IOException("Supply a direct .gguf or .onnx HTTPS file URL");
        }
        KnowledgeModelFiles.requestedFormat(basename);
        return new Source(uri, KnowledgeModelFiles.safeName(basename));
    }

    private static URI secureUri(final String raw) throws IOException {
        try {
            final URI uri = new URI(raw == null ? "" : raw.trim());
            final String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || host == null || !publicHostname(host)) {
                throw new IOException("Only public HTTPS URLs without credentials are allowed");
            }
            return uri;
        } catch (URISyntaxException invalid) {
            throw new IOException("Invalid model URL", invalid);
        }
    }

    private static boolean publicHostname(final String supplied) {
        final String host = supplied.toLowerCase(Locale.ROOT);
        if (host.length() > 253 || host.endsWith(".") || host.indexOf(':') >= 0
                || host.indexOf('.') < 1 || host.startsWith(".") || host.contains("..")
                || host.matches("[0-9.]+") || host.equals("localhost")
                || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal") || host.endsWith(".test")
                || host.endsWith(".invalid")) return false;
        final String[] labels = host.split("\\.");
        for (final String label : labels) {
            if (!label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) return false;
        }
        return labels[labels.length - 1].length() >= 2;
    }

    static KnowledgeModelFiles.Entry download(final String rawUrl,
                                               final KnowledgeModelPolicy.Role role,
                                               final String expectedSha256,
                                               final KnowledgeModelFiles files,
                                               final Progress progress) throws IOException {
        return download(rawUrl, role, expectedSha256, files, progress,
                url -> (HttpURLConnection) url.openConnection());
    }

    static KnowledgeModelFiles.Entry download(final String rawUrl,
                                               final KnowledgeModelPolicy.Role role,
                                               final String expectedSha256,
                                               final KnowledgeModelFiles files,
                                               final Progress progress,
                                               final ConnectionFactory factory) throws IOException {
        final Source source = parseSource(rawUrl);
        URI current = source.uri;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Model download cancelled");
            }
            final HttpURLConnection connection = factory.open(current.toURL());
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestProperty("Accept-Encoding", "identity");
                final int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_MOVED_PERM
                        || status == HttpURLConnection.HTTP_MOVED_TEMP
                        || status == HttpURLConnection.HTTP_SEE_OTHER
                        || status == 307 || status == 308) {
                    if (redirect == MAX_REDIRECTS) throw new IOException("Too many download redirects");
                    final String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirect without Location");
                    // Redirects may use signed CDN query parameters, but must remain HTTPS.
                    current = secureUri(current.resolve(location).toString());
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Model download HTTP " + status);
                }
                final String contentType = connection.getContentType();
                if (contentType != null && (contentType.toLowerCase(Locale.ROOT).startsWith("text/html")
                        || contentType.toLowerCase(Locale.ROOT).startsWith("application/json")
                        || contentType.toLowerCase(Locale.ROOT).startsWith("application/xml"))) {
                    throw new IOException("URL returned a web page instead of weights");
                }
                final long total = connection.getContentLengthLong();
                if (total == 0) throw new IOException("Empty model download");
                try (InputStream remote = new LengthCheckedStream(connection.getInputStream(), total)) {
                    return files.importFile(remote, source.filename, role, expectedSha256,
                            copied -> {
                                if (progress != null) progress.onBytes(copied, total);
                            });
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many download redirects");
    }

    private static final class LengthCheckedStream extends FilterInputStream {
        private final long expected;
        private long transferred;

        LengthCheckedStream(final InputStream source, final long length) {
            super(source);
            expected = length;
        }

        @Override public int read(final byte[] bytes, final int off, final int len) throws IOException {
            final int count = in.read(bytes, off, len);
            if (count == -1) {
                if (expected >= 0 && transferred != expected) {
                    throw new IOException("Incomplete HTTP download");
                }
            } else if (count > 0) {
                transferred += count;
                if (expected >= 0 && transferred > expected) {
                    throw new IOException("HTTP download exceeds declared length");
                }
            }
            return count;
        }
    }
}
