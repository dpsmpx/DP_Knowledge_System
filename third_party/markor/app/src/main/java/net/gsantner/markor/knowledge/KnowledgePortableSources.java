/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Strict manual Markdown compare/keep-both after full authenticated backup verification. */
final class KnowledgePortableSources {
    static final int PAGE = 40;
    private static final int PREVIEW_BYTES = 16_384;

    static final class Item {
        final String path;
        final String status;
        final long remoteBytes;
        final String remoteSha;
        final String localSha;
        final String localExcerpt;
        final String remoteExcerpt;
        final boolean remoteTruncated;
        final boolean localTruncated;

        Item(final String path, final String status, final long remoteBytes,
             final String remoteSha, final String localSha,
             final String localExcerpt, final String remoteExcerpt,
             final boolean localTruncated, final boolean remoteTruncated) {
            this.path = path;
            this.status = status;
            this.remoteBytes = remoteBytes;
            this.remoteSha = remoteSha;
            this.localSha = localSha;
            this.localExcerpt = localExcerpt;
            this.remoteExcerpt = remoteExcerpt;
            this.localTruncated = localTruncated;
            this.remoteTruncated = remoteTruncated;
        }
    }

    static final class Page {
        final int total;
        final List<String> paths;

        Page(final int total, final List<String> paths) {
            this.total = total;
            this.paths = paths;
        }
    }

    private KnowledgePortableSources() { }

    /** Zip names were already validated by KnowledgePortableArchive.verify(). */
    private static boolean markdown(final String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        return name.startsWith("notes/") && name.length() > 6
                && (lower.endsWith(".md") || lower.endsWith(".markdown"));
    }

    private static File local(final File vault, final String path) throws IOException {
        if (vault == null || !vault.isDirectory() || path.isEmpty()
                || path.startsWith("/") || path.contains("\\") || path.contains("//"))
            throw new IOException("Invalid local vault or Markdown path");
        for (final String component : path.split("/", -1)) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component))
                throw new IOException("Unsafe Markdown path");
        }
        final File root = vault.getCanonicalFile();
        final File target = new File(root, path).getCanonicalFile();
        if (!target.getPath().startsWith(root.getPath() + File.separator))
            throw new IOException("Markdown path escapes selected library");
        return target;
    }

    static Page page(final File verifiedZip, final int offset) throws Exception {
        if (offset < 0) throw new IOException("Invalid Markdown page");
        final List<String> paths = new ArrayList<>();
        try (ZipFile zip = new ZipFile(verifiedZip)) {
            final java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                final String name = entries.nextElement().getName();
                if (markdown(name)) paths.add(name.substring(6));
            }
        }
        paths.sort(Comparator.naturalOrder());
        final int total = paths.size();
        return new Page(total, new ArrayList<>(paths.subList(
                Math.min(offset, total), (int) Math.min((long) total, (long) offset + PAGE))));
    }

    private static String hash(final InputStream input) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        final byte[] bytes = new byte[32 * 1024];
        int n;
        while ((n = input.read(bytes)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Compare cancelled");
            digest.update(bytes, 0, n);
        }
        return hex(digest.digest());
    }

    private static String hex(final byte[] bytes) {
        final char[] digits = "0123456789abcdef".toCharArray();
        final char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            chars[2 * i] = digits[(bytes[i] >>> 4) & 15];
            chars[2 * i + 1] = digits[bytes[i] & 15];
        }
        return new String(chars);
    }

    private static String excerpt(final InputStream input) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] bytes = new byte[4096];
        int n;
        while (output.size() < PREVIEW_BYTES && (n = input.read(bytes, 0,
                Math.min(bytes.length, PREVIEW_BYTES - output.size()))) != -1) {
            output.write(bytes, 0, n);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    static Item inspect(final File verifiedZip, final File vault,
                        final String path) throws Exception {
        if (!markdown("notes/" + path)) throw new IOException("Not a Markdown file");
        final File target = local(vault, path);
        try (ZipFile zip = new ZipFile(verifiedZip)) {
            final ZipEntry entry = zip.getEntry("notes/" + path);
            if (entry == null || entry.isDirectory() || entry.getSize() < 0
                    || entry.getSize() > KnowledgePortableArchive.MAX_FILE_BYTES)
                throw new IOException("Markdown source unavailable");
            final String remoteSha;
            try (InputStream input = zip.getInputStream(entry)) { remoteSha = hash(input); }
            final String remoteExcerpt;
            try (InputStream input = zip.getInputStream(entry)) {
                remoteExcerpt = excerpt(input);
            }
            if (!target.exists()) {
                return new Item(path, "missing", entry.getSize(), remoteSha, "",
                        "", remoteExcerpt, false, entry.getSize() > PREVIEW_BYTES);
            }
            if (!target.isFile() || target.length() > KnowledgePortableArchive.MAX_FILE_BYTES)
                throw new IOException("Local Markdown path is not a supported file");
            final String localSha;
            try (InputStream input = new FileInputStream(target)) { localSha = hash(input); }
            final String localExcerpt;
            try (InputStream input = new FileInputStream(target)) {
                localExcerpt = excerpt(input);
            }
            return new Item(path, remoteSha.equals(localSha) ? "identical" : "different",
                    entry.getSize(), remoteSha, localSha, localExcerpt, remoteExcerpt,
                    target.length() > PREVIEW_BYTES, entry.getSize() > PREVIEW_BYTES);
        }
    }

    /** Direct SAF Markdown is staged privately before comparison; no remote URL fetch. */
    static Item inspectPlain(final File staging, final File vault,
                             final String filename) throws Exception {
        if (filename.contains("/") || !markdown("notes/" + filename)
                || !staging.isFile() || staging.length() > KnowledgePortableArchive.MAX_FILE_BYTES)
            throw new IOException("Invalid staged Markdown");
        final File target = local(vault, filename);
        final String remoteSha;
        final String remoteExcerpt;
        try (InputStream input = new FileInputStream(staging)) { remoteSha = hash(input); }
        try (InputStream input = new FileInputStream(staging)) { remoteExcerpt = excerpt(input); }
        if (!target.exists()) return new Item(filename, "missing", staging.length(), remoteSha,
                "", "", remoteExcerpt, false, staging.length() > PREVIEW_BYTES);
        if (!target.isFile() || target.length() > KnowledgePortableArchive.MAX_FILE_BYTES)
            throw new IOException("Unsupported local Markdown destination");
        final String localSha;
        final String localExcerpt;
        try (InputStream input = new FileInputStream(target)) { localSha = hash(input); }
        try (InputStream input = new FileInputStream(target)) { localExcerpt = excerpt(input); }
        return new Item(filename, remoteSha.equals(localSha) ? "identical" : "different",
                staging.length(), remoteSha, localSha, localExcerpt, remoteExcerpt,
                target.length() > PREVIEW_BYTES, staging.length() > PREVIEW_BYTES);
    }

    /** Exclusive create is required even on API 24–25 (Files API was added in 26). */
    private static OutputStream createExclusive(final File candidate) throws IOException {
        if (android.os.Build.VERSION.SDK_INT >= 26 || android.os.Build.VERSION.SDK_INT == 0) {
            return Files.newOutputStream(candidate.toPath(),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        try {
            return new FileOutputStream(android.system.Os.open(candidate.getAbsolutePath(),
                    android.system.OsConstants.O_WRONLY | android.system.OsConstants.O_CREAT
                            | android.system.OsConstants.O_EXCL, 0600));
        } catch (android.system.ErrnoException failed) {
            if (failed.errno == android.system.OsConstants.EEXIST)
                throw new java.nio.file.FileAlreadyExistsException(candidate.getPath());
            throw new IOException("Cannot exclusively create Markdown copy", failed);
        }
    }

    static File saveCopy(final File verifiedZip, final File vault,
                         final Item reviewed) throws Exception {
        final Item now = inspect(verifiedZip, vault, reviewed.path);
        try (ZipFile zip = new ZipFile(verifiedZip)) {
            final ZipEntry entry = zip.getEntry("notes/" + reviewed.path);
            if (entry == null) throw new IOException("Missing reviewed source");
            return saveReviewed(vault, reviewed, now, () -> zip.getInputStream(entry));
        }
    }

    static File savePlainCopy(final File staging, final File vault,
                              final Item reviewed) throws Exception {
        final Item now = inspectPlain(staging, vault, reviewed.path);
        return saveReviewed(vault, reviewed, now, () -> new FileInputStream(staging));
    }

    private interface Source { InputStream open() throws Exception; }

    /** Creates one new Markdown file, never overwrites or merges existing source. */
    private static File saveReviewed(final File vault, final Item reviewed,
                                     final Item now, final Source source) throws Exception {
        if (reviewed == null || "identical".equals(reviewed.status))
            throw new IOException("No new copy required");
        if (!now.remoteSha.equals(reviewed.remoteSha)
                || !now.localSha.equals(reviewed.localSha)
                || !now.status.equals(reviewed.status))
            throw new IOException("Markdown changed since preview; review again");
        final File original = local(vault, reviewed.path);
        final File parent = original.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Cannot create destination folders");
        final String canonicalParent = parent.getCanonicalPath();
        final String canonicalVault = vault.getCanonicalPath();
        if (!canonicalParent.equals(canonicalVault)
                && !canonicalParent.startsWith(canonicalVault + File.separator))
            throw new IOException("Destination folder escaped selected library");
        final String filename = original.getName();
        final int dot = filename.lastIndexOf('.');
        if (dot < 1) throw new IOException("Unsafe Markdown extension");
        final String stem = filename.substring(0, dot);
        final String suffix = filename.substring(dot);
        File destination = null;
        for (int n = 0; n < 1000; n++) {
            final String filenameCandidate = "different".equals(reviewed.status)
                    ? stem + ".peer-" + reviewed.remoteSha.substring(0, 12)
                    + (n == 0 ? "" : "-" + n) + suffix
                    : n == 0 ? filename : stem + ".import-" + n + suffix;
            final File candidate = local(vault,
                    reviewed.path.substring(0, reviewed.path.length() - filename.length())
                            + filenameCandidate);
            // Atomic exclusive create prevents overwrites even after a concurrent write.
            try (OutputStream output = createExclusive(candidate)) {
                destination = candidate;
                final MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long length = 0;
                try (InputStream input = source.open()) {
                    final byte[] bytes = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(bytes)) != -1) {
                        if (Thread.currentThread().isInterrupted())
                            throw new IOException("Copy cancelled");
                        length += count;
                        if (length > KnowledgePortableArchive.MAX_FILE_BYTES)
                            throw new IOException("Copy exceeds size limit");
                        output.write(bytes, 0, count);
                        digest.update(bytes, 0, count);
                    }
                }
                if (length != reviewed.remoteBytes
                        || !hex(digest.digest()).equals(reviewed.remoteSha))
                    throw new IOException("Reviewed Markdown content changed");
                return destination;
            } catch (java.nio.file.FileAlreadyExistsException collision) {
                continue;
            } catch (Exception failed) {
                if (destination != null) destination.delete();
                throw failed;
            }
        }
        throw new IOException("Too many colliding Markdown copies");
    }
}
