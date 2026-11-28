/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explicit public HTTPS model-file transfer, never a provider for note text.
 * The user must provide a direct weight-file URL (not a catalog/repository page).
 * Redirects are validated individually; authentication headers are never used.
 */
final class KnowledgeModelDownload {
    private static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 25000;
    private static final Pattern CONTENT_RANGE =
            Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");

    interface Progress { void onBytes(long copied, long announcedTotal); }
    interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }
    interface AddressResolver { InetAddress[] resolve(String host) throws IOException; }

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

    /**
     * Refuse a public-looking DNS name that currently resolves to a private or
     * reserved destination. All answers must pass, including dual-stack and
     * round-robin answers. This is a preconnect guard, not DNS pinning: a DNS
     * rebinding between this lookup and HttpURLConnection remains possible.
     */
    private static void requirePublicAddresses(final URI uri, final AddressResolver resolver)
            throws IOException {
        final InetAddress[] resolved = resolver.resolve(uri.getHost());
        if (resolved == null || resolved.length == 0) {
            throw new IOException("Model download host has no public DNS addresses");
        }
        for (final InetAddress address : resolved) {
            if (!isPublicAddress(address)) {
                throw new IOException("Model download host resolves to a non-public address");
            }
        }
    }

    static boolean isPublicAddress(final InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return false;
        final byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            final int a = bytes[0] & 255;
            final int b = bytes[1] & 255;
            final int c = bytes[2] & 255;
            // Also exclude CGNAT, protocol assignments, benchmark and example
            // ranges which java.net's site-local test does not cover.
            return a != 0 && a != 10 && a != 127 && a < 224
                    && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 169 && b == 254)
                    && !(a == 172 && b >= 16 && b <= 31)
                    && !(a == 192 && (b == 168 || (b == 0 && (c == 0 || c == 2))
                            || (b == 88 && c == 99)))
                    && !(a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                    && !(a == 203 && b == 0 && c == 113);
        }
        if (address instanceof Inet6Address) {
            final int a = bytes[0] & 255;
            final int b = bytes[1] & 255;
            return (a & 0xfe) != 0xfc // IPv6 unique-local fc00::/7
                    && !(a == 0xfe && (b & 0xc0) == 0x80) // link-local fe80::/10
                    && !(a == 0x20 && b == 0x01 && bytes[2] == 0
                            && bytes[3] == 0) // Teredo 2001:0000::/32
                    && !(a == 0x20 && b == 0x01 && (bytes[2] & 255) == 0x0d
                            && (bytes[3] & 255) == 0xb8) // docs 2001:db8::/32
                    && !(a == 0x20 && b == 0x02); // 6to4 2002::/16
        }
        return false;
    }

    static KnowledgeModelFiles.Entry download(final String rawUrl,
                                               final KnowledgeModelPolicy.Role role,
                                               final String expectedSha256,
                                               final KnowledgeModelFiles files,
                                               final Progress progress) throws IOException {
        return download(rawUrl, role, expectedSha256, files, progress,
                url -> (HttpURLConnection) url.openConnection(), InetAddress::getAllByName);
    }

    static KnowledgeModelFiles.Entry download(final String rawUrl,
                                               final KnowledgeModelPolicy.Role role,
                                               final String expectedSha256,
                                               final KnowledgeModelFiles files,
                                               final Progress progress,
                                               final ConnectionFactory factory) throws IOException {
        return download(rawUrl, role, expectedSha256, files, progress, factory,
                InetAddress::getAllByName);
    }

    /** The injected resolver is used by offline JVM tests only. */
    static KnowledgeModelFiles.Entry download(final String rawUrl,
                                               final KnowledgeModelPolicy.Role role,
                                               final String expectedSha256,
                                               final KnowledgeModelFiles files,
                                               final Progress progress,
                                               final ConnectionFactory factory,
                                               final AddressResolver resolver) throws IOException {
        final Source source = parseSource(rawUrl);
        final boolean trustedResume = expectedSha256 != null && !expectedSha256.isEmpty();
        final long checkpoint = trustedResume
                ? files.resumeBytes(expectedSha256, source.filename) : 0;
        URI current = source.uri;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Model download cancelled");
            }
            requirePublicAddresses(current, resolver);
            final HttpURLConnection connection = factory.open(current.toURL());
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestProperty("Accept-Encoding", "identity");
                if (checkpoint > 0) {
                    connection.setRequestProperty("Range", "bytes=" + checkpoint + "-");
                }
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
                if (status != HttpURLConnection.HTTP_OK
                        && status != HttpURLConnection.HTTP_PARTIAL) {
                    throw new IOException("Model download HTTP " + status);
                }
                final String contentType = connection.getContentType();
                if (contentType != null && (contentType.toLowerCase(Locale.ROOT).startsWith("text/html")
                        || contentType.toLowerCase(Locale.ROOT).startsWith("application/json")
                        || contentType.toLowerCase(Locale.ROOT).startsWith("application/xml"))) {
                    throw new IOException("URL returned a web page instead of weights");
                }
                final long responseBytes = connection.getContentLengthLong();
                final long offset;
                final long total;
                if (status == HttpURLConnection.HTTP_PARTIAL) {
                    if (checkpoint == 0) {
                        throw new IOException("Unrequested partial model response");
                    }
                    final long[] range = checkedContentRange(
                            connection.getHeaderField("Content-Range"), checkpoint);
                    offset = checkpoint;
                    total = range[1];
                    if (responseBytes != range[0]) {
                        throw new IOException("Partial response length does not match range");
                    }
                } else {
                    // A server may ignore Range. A full 200 response explicitly
                    // restarts from byte zero, never appends to old content.
                    offset = 0;
                    total = responseBytes;
                }
                if (responseBytes == 0) throw new IOException("Empty model download");
                try (InputStream remote = new LengthCheckedStream(
                        connection.getInputStream(), responseBytes)) {
                    final KnowledgeModelFiles.Progress imported = copied -> {
                        if (progress != null) progress.onBytes(copied, total);
                    };
                    return trustedResume
                            ? files.importResumed(remote, source.filename, role,
                                    expectedSha256, offset, imported)
                            : files.importFile(remote, source.filename, role,
                                    expectedSha256, imported);
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many download redirects");
    }

    /** [segment bytes, complete bytes]; never accept an ambiguous Range reply. */
    private static long[] checkedContentRange(final String header, final long offset)
            throws IOException {
        final Matcher match = CONTENT_RANGE.matcher(header == null ? "" : header.trim());
        if (!match.matches()) throw new IOException("Missing exact Content-Range");
        try {
            final long first = Long.parseLong(match.group(1));
            final long last = Long.parseLong(match.group(2));
            final long total = Long.parseLong(match.group(3));
            if (first != offset || last < first || last != total - 1 || total < 16) {
                throw new IOException("Mismatched model Content-Range");
            }
            return new long[]{last - first + 1, total};
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid model Content-Range", invalid);
        }
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
