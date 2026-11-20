/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Streaming ZIP payload for a portable, separately encrypted DPKS backup.
 * The ZIP must never be written directly to a public/SAF destination: callers
 * wrap the output in authenticated encryption before calling write().
 */
final class KnowledgePortableArchive {
    static final String MANIFEST = "meta/manifest.dpks";
    static final String JOURNAL = "meta/journal.jsonl";
    static final String SETTINGS = "meta/settings.json";
    static final String PEER = "meta/peer.json";
    static final int MAX_FILES = 300_000;
    static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    static final long MAX_ARCHIVE_BYTES = 12L * 1024 * 1024 * 1024;
    private static final byte[] MAGIC = "DPKSZIP1\n".getBytes(StandardCharsets.US_ASCII);

    interface Cancellation { boolean cancelled(); }

    static final class Entry {
        final String name;
        final long bytes;
        final String sha256;

        Entry(final String name, final long bytes, final String sha256) {
            this.name = name;
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    static final class Summary {
        final int sources;
        final long bytes;
        final boolean journal;
        final boolean settings;

        Summary(final int sources, final long bytes, final boolean journal, final boolean settings) {
            this.sources = sources;
            this.bytes = bytes;
            this.journal = journal;
            this.settings = settings;
        }
    }

    private KnowledgePortableArchive() { }

    static Summary write(final File selectedVault, final File journal,
                         final byte[] settingsJson, final OutputStream encryptedOutput,
                         final Cancellation cancelled) throws Exception {
        return write(selectedVault, journal, settingsJson, null, encryptedOutput, cancelled);
    }

    static Summary write(final File selectedVault, final File journal,
                         final byte[] settingsJson, final byte[] peerProof,
                         final OutputStream encryptedOutput,
                         final Cancellation cancelled) throws Exception {
        if (selectedVault == null || !selectedVault.isDirectory() || encryptedOutput == null) {
            throw new IOException("Selected source library unavailable");
        }
        final File root = selectedVault.getCanonicalFile();
        final ArrayList<File> sources = new ArrayList<>();
        enumerate(root, root, sources, cancelled);
        sources.sort(Comparator.comparing(f -> relative(root, f)));
        final ArrayList<Entry> inventory = new ArrayList<>();
        long sum = 0;
        try (ZipOutputStream zip = new ZipOutputStream(encryptedOutput)) {
            for (final File file : sources) {
                final Entry e = appendFile(zip, "notes/" + relative(root, file), file, cancelled);
                inventory.add(e);
                sum = plusBounded(sum, e.bytes);
            }
            if (journal != null && journal.isFile()) {
                // Only the selected, app-private authoritative journal path.
                if (journal.length() > KnowledgeJournalCrypto.MAX_JOURNAL_BYTES) {
                    throw new IOException("Authoritative journal exceeds backup bound");
                }
                final Entry e = appendFile(zip, JOURNAL, journal, cancelled);
                inventory.add(e);
                sum = plusBounded(sum, e.bytes);
            }
            if (settingsJson != null) {
                if (settingsJson.length > 256_000) throw new IOException("Settings exceed bound");
                final Entry e = appendBytes(zip, SETTINGS, settingsJson);
                inventory.add(e);
                sum = plusBounded(sum, e.bytes);
            }
            if (peerProof != null) {
                if (peerProof.length > 4096 || journal == null || !journal.isFile())
                    throw new IOException("Invalid peer proof");
                final Entry e = appendBytes(zip, PEER, peerProof);
                inventory.add(e);
                sum = plusBounded(sum, e.bytes);
            }
            final ByteArrayOutputStream manifest = new ByteArrayOutputStream();
            manifest.write(MAGIC);
            for (final Entry e : inventory) {
                final String name = hex(e.name.getBytes(StandardCharsets.UTF_8));
                manifest.write((name + "\t" + e.bytes + "\t" + e.sha256 + "\n")
                        .getBytes(StandardCharsets.US_ASCII));
            }
            if (manifest.size() > 50_000_000) throw new IOException("Manifest exceeds bound");
            zip.putNextEntry(new ZipEntry(MANIFEST));
            zip.write(manifest.toByteArray());
            zip.closeEntry();
        }
        return new Summary(sources.size(), sum,
                journal != null && journal.isFile(), settingsJson != null);
    }

    /** Validate the whole, fully decrypted and authenticated staging ZIP. No writes. */
    static Summary verify(final File decryptedStaging, final Cancellation cancelled)
            throws Exception {
        if (decryptedStaging == null || !decryptedStaging.isFile()
                || decryptedStaging.length() > MAX_ARCHIVE_BYTES) {
            throw new IOException("Archive unavailable or too large");
        }
        final Map<String, Entry> actual = new HashMap<>();
        final Set<String> folded = new HashSet<>();
        byte[] manifest = null;
        int sources = 0;
        long bytes = 0;
        try (ZipFile zip = new ZipFile(decryptedStaging)) {
            final java.util.Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                if (cancelled.cancelled()) throw new IOException("Backup verification cancelled");
                final ZipEntry z = all.nextElement();
                final String name = z.getName();
                if (z.isDirectory() || !validName(name) || actual.containsKey(name)
                        || !folded.add(name.toLowerCase(Locale.ROOT))) {
                    throw new IOException("Unsafe or colliding archive entry");
                }
                if (MANIFEST.equals(name)) {
                    manifest = readBounded(zip.getInputStream(z), 50_000_000, cancelled);
                    actual.put(name, new Entry(name, manifest.length, hash(manifest)));
                    continue;
                }
                if (actual.size() >= MAX_FILES + 3) throw new IOException("Too many entries");
                final MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long size = 0;
                try (InputStream in = zip.getInputStream(z)) {
                    final byte[] block = new byte[65536];
                    int n;
                    while ((n = in.read(block)) != -1) {
                        if (cancelled.cancelled()) throw new IOException("Verification cancelled");
                        size += n;
                        if (size > MAX_FILE_BYTES) throw new IOException("Entry too large");
                        digest.update(block, 0, n);
                    }
                }
                actual.put(name, new Entry(name, size, hex(digest.digest())));
                bytes = plusBounded(bytes, size);
                if (name.startsWith("notes/")) sources++;
            }
        }
        if (manifest == null) throw new IOException("Backup manifest missing");
        final String[] lines = new String(manifest, StandardCharsets.US_ASCII).split("\n", -1);
        if (lines.length < 2 || !"DPKSZIP1".equals(lines[0])) {
            throw new IOException("Unsupported backup manifest");
        }
        int recorded = 0;
        final Set<String> names = new HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            final String[] fields = lines[i].split("\t", -1);
            if (fields.length != 3 || !fields[2].matches("[0-9a-f]{64}")) {
                throw new IOException("Malformed manifest item");
            }
            final String name = new String(unhex(fields[0]), StandardCharsets.UTF_8);
            final Entry e = actual.get(name);
            if (e == null || MANIFEST.equals(name) || !names.add(name)
                    || Long.parseLong(fields[1]) != e.bytes || !fields[2].equals(e.sha256)) {
                throw new IOException("Archive payload/manifest mismatch");
            }
            recorded++;
        }
        if (recorded != actual.size() - 1) throw new IOException("Unlisted backup payload");
        return new Summary(sources, bytes, actual.containsKey(JOURNAL), actual.containsKey(SETTINGS));
    }

    private static byte[] readBounded(final InputStream input, final int max,
                                      final Cancellation cancelled) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (cancelled.cancelled() || out.size() + n > max)
                    throw new IOException("Manifest too large/cancelled");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void enumerate(final File root, final File dir, final List<File> result,
                                  final Cancellation cancelled) throws IOException {
        if (cancelled.cancelled()) throw new IOException("Backup cancelled");
        final File[] list = dir.listFiles();
        if (list == null) throw new IOException("Unreadable directory");
        for (final File f : list) {
            final File canonical = f.getCanonicalFile();
            // No symlinks or traversal, even to another location within vault.
            if (!canonical.getPath().equals(f.getAbsolutePath())
                    || !canonical.getPath().startsWith(root.getPath() + File.separator)) {
                throw new IOException("Unsafe vault entry");
            }
            if (f.isDirectory()) enumerate(root, f, result, cancelled);
            else if (f.isFile()) {
                if (f.length() > MAX_FILE_BYTES || result.size() >= MAX_FILES)
                    throw new IOException("Vault backup size/count bound exceeded");
                result.add(f);
            } else throw new IOException("Unsupported vault entry");
        }
    }

    private static String relative(final File root, final File file) {
        return file.getAbsolutePath().substring(root.getPath().length() + 1)
                .replace(File.separatorChar, '/');
    }

    private static Entry appendFile(final ZipOutputStream zip, final String name,
                                    final File file, final Cancellation cancelled) throws Exception {
        final long beforeLength = file.length();
        final long beforeTime = file.lastModified();
        zip.putNextEntry(new ZipEntry(name));
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        long size = 0;
        try (InputStream input = new FileInputStream(file)) {
            final byte[] buf = new byte[65536];
            int n;
            while ((n = input.read(buf)) != -1) {
                if (cancelled.cancelled()) throw new IOException("Backup cancelled");
                size += n;
                if (size > MAX_FILE_BYTES) throw new IOException("File too large");
                md.update(buf, 0, n);
                zip.write(buf, 0, n);
            }
        }
        zip.closeEntry();
        if (size != beforeLength || file.length() != beforeLength
                || file.lastModified() != beforeTime) throw new IOException("Source changed during backup");
        return new Entry(name, size, hex(md.digest()));
    }

    private static Entry appendBytes(final ZipOutputStream zip, final String name,
                                     final byte[] payload) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(payload);
        zip.closeEntry();
        return new Entry(name, payload.length, hash(payload));
    }

    private static boolean validName(final String name) {
        if (name == null || name.length() > 1000 || name.isEmpty() || name.startsWith("/")
                || name.contains("\\") || name.indexOf('\0') >= 0 || name.contains("//")) return false;
        if (!name.startsWith("notes/") && !JOURNAL.equals(name)
                && !SETTINGS.equals(name) && !PEER.equals(name)
                && !MANIFEST.equals(name)) return false;
        for (final String component : name.split("/", -1)) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)) return false;
        }
        return true;
    }

    private static long plusBounded(final long a, final long b) throws IOException {
        if (b < 0 || a > MAX_ARCHIVE_BYTES - b) throw new IOException("Backup exceeds bound");
        return a + b;
    }

    private static String hash(final byte[] data) throws NoSuchAlgorithmException {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String hex(final byte[] bytes) {
        final char[] alphabet = "0123456789abcdef".toCharArray();
        final char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = alphabet[(bytes[i] >>> 4) & 15];
            out[2 * i + 1] = alphabet[bytes[i] & 15];
        }
        return new String(out);
    }

    private static byte[] unhex(final String text) throws IOException {
        if (text.isEmpty() || (text.length() & 1) != 0 || text.length() > 2000) {
            throw new IOException("Invalid entry encoding");
        }
        final byte[] result = new byte[text.length() / 2];
        for (int i = 0; i < result.length; i++) {
            final int high = Character.digit(text.charAt(i * 2), 16);
            final int low = Character.digit(text.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IOException("Invalid entry encoding");
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }
}
