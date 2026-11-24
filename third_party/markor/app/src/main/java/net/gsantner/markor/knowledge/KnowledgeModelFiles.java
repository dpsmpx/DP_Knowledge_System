/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * MODEL-M1: a private, content-addressed file shelf for manually imported
 * models. A stored blob is NOT an executable/compatible model: inference
 * requires a separate architecture/tokenizer/backend validation step.
 * No vault path, note text, network or user-controlled filesystem path is used.
 */
final class KnowledgeModelFiles {
    private static final int MAGIC = 0x44504d31; // DPM1
    private static final long MIN_FREE_BYTES = 16L * 1024L * 1024L;
    private static final int BUFFER_BYTES = 64 * 1024;
    private final File directory;

    enum Format { GGUF, ONNX_UNVERIFIED }

    interface Progress { void onBytes(long written); }

    static final class Entry {
        final KnowledgeModelPolicy.Role role;
        final Format format;
        final String sha256;
        final String name;
        final long bytes;

        Entry(final KnowledgeModelPolicy.Role kind, final Format fileFormat,
              final String hash, final String label, final long length) {
            role = kind;
            format = fileFormat;
            sha256 = hash;
            name = label;
            bytes = length;
        }
    }

    KnowledgeModelFiles(final File privateDirectory) {
        directory = privateDirectory;
    }

    static String safeName(final String supplied) {
        final String value = supplied == null ? "" : supplied;
        final StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < value.length() && cleaned.length() < 72; i++) {
            final char ch = value.charAt(i);
            if (ch == '/' || ch == '\\' || Character.isISOControl(ch)
                    || Character.getType(ch) == Character.FORMAT) continue;
            cleaned.append(ch);
        }
        final String label = cleaned.toString().trim();
        return label.isEmpty() ? "Imported model" : label;
    }

    static Format requestedFormat(final String filename) throws IOException {
        final String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".gguf")) return Format.GGUF;
        if (lower.endsWith(".onnx")) return Format.ONNX_UNVERIFIED;
        throw new IOException("Unsupported model file extension");
    }

    private static String hex(final byte[] digest) {
        final char[] alphabet = "0123456789abcdef".toCharArray();
        final StringBuilder builder = new StringBuilder(64);
        for (final byte b : digest) {
            builder.append(alphabet[(b >>> 4) & 15]).append(alphabet[b & 15]);
        }
        return builder.toString();
    }

    private static boolean validHash(final String hash) {
        return hash != null && hash.matches("[0-9a-f]{64}");
    }

    private static String extension(final Format format) {
        return format == Format.GGUF ? ".gguf" : ".onnx";
    }

    private File modelFile(final String hash, final Format format) throws IOException {
        if (!validHash(hash) || format == null) throw new IOException("Invalid model identity");
        return privateFile(hash + extension(format));
    }

    private File metadataFile(final String hash, final KnowledgeModelPolicy.Role role)
            throws IOException {
        if (!validHash(hash) || role == null) throw new IOException("Invalid model identity");
        return privateFile(hash + "-" + role.name() + ".meta");
    }

    private File privateFile(final String name) throws IOException {
        final File child = new File(directory, name);
        if (!child.getCanonicalFile().equals(new File(directory.getCanonicalFile(), name))) {
            throw new IOException("Model file is not private");
        }
        return child;
    }

    private void prepareDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create private model store");
        }
    }

    synchronized Entry importFile(final InputStream input, final String originalName,
                                  final KnowledgeModelPolicy.Role role) throws IOException {
        return importFile(input, originalName, role, "", null);
    }

    synchronized Entry importFile(final InputStream input, final String originalName,
                                  final KnowledgeModelPolicy.Role role, final String expectedSha256,
                                  final Progress progress) throws IOException {
        if (input == null || role == null) throw new IOException("Missing model input");
        if (expectedSha256 == null || (!expectedSha256.isEmpty()
                && !validHash(expectedSha256))) {
            throw new IOException("Invalid expected SHA-256");
        }
        final Format format = requestedFormat(originalName);
        prepareDirectory();
        final File pending = File.createTempFile("model-", ".part", directory);
        File createdBlob = null;
        boolean recorded = false;
        try {
            final MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IOException("SHA-256 unavailable", impossible);
            }
            final byte[] buffer = new byte[BUFFER_BYTES];
            final byte[] beginning = new byte[8];
            long written = 0;
            try (OutputStream output = new FileOutputStream(pending)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("Model import cancelled");
                    }
                    if (count == 0) continue;
                    final int capture = (int) Math.min(count, Math.max(0L, 8L - written));
                    if (capture > 0) System.arraycopy(buffer, 0, beginning, (int) written, capture);
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    written += count;
                    if (progress != null) progress.onBytes(written);
                    if (directory.getUsableSpace() < MIN_FREE_BYTES) {
                        throw new IOException("Insufficient space for safe import");
                    }
                }
            }
            if (written < 16) throw new IOException("Model file is too small");
            if (format == Format.GGUF) {
                final int version = (beginning[4] & 255) | ((beginning[5] & 255) << 8)
                        | ((beginning[6] & 255) << 16) | ((beginning[7] & 255) << 24);
                if (beginning[0] != 'G' || beginning[1] != 'G'
                        || beginning[2] != 'U' || beginning[3] != 'F'
                        || (version != 2 && version != 3)) {
                    throw new IOException("Invalid GGUF container header");
                }
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Model import cancelled before installation");
            }
            final String sha = hex(digest.digest());
            if (!expectedSha256.isEmpty() && !expectedSha256.equals(sha)) {
                throw new IOException("SHA-256 does not match expected digest");
            }
            final File target = modelFile(sha, format);
            if (target.exists()) {
                if (!target.isFile() || target.length() != written) {
                    throw new IOException("Conflicting existing model blob");
                }
            } else if (!pending.renameTo(target)) {
                throw new IOException("Could not commit imported model");
            } else {
                createdBlob = target;
            }
            final Entry entry = new Entry(role, format, sha, safeName(originalName), written);
            final File metadata = metadataFile(sha, role);
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Model import cancelled before registration");
            }
            if (!metadata.exists()) writeMetadata(metadata, entry);
            recorded = true;
            return entry;
        } finally {
            // An interrupted/invalid import has no visible metadata entry.
            if (pending.exists()) pending.delete();
            if (!recorded && createdBlob != null) createdBlob.delete();
        }
    }

    private void writeMetadata(final File destination, final Entry entry) throws IOException {
        final File temporary = File.createTempFile("entry-", ".part", directory);
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary);
                 DataOutputStream output = new DataOutputStream(stream)) {
                output.writeInt(MAGIC);
                output.writeUTF(entry.role.name());
                output.writeUTF(entry.format.name());
                output.writeUTF(entry.sha256);
                output.writeUTF(entry.name);
                output.writeLong(entry.bytes);
                output.flush();
                stream.getFD().sync();
            }
            if (!temporary.renameTo(destination)) throw new IOException("Could not install metadata");
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    synchronized List<Entry> installed() throws IOException {
        if (!directory.isDirectory()) return Collections.emptyList();
        final File[] files = directory.listFiles();
        if (files == null) throw new IOException("Could not inspect private model store");
        final List<Entry> result = new ArrayList<>();
        for (final File file : files) {
            final String name = file.getName();
            if ((name.startsWith("model-") || name.startsWith("entry-"))
                    && name.endsWith(".part") && file.isFile()) {
                // Only our disposable transfer/metadata files. A process kill
                // bypasses finally; never leave their space allocated forever.
                file.delete();
                continue;
            }
            if (!name.matches("[0-9a-f]{64}-(EMBEDDING|GENERATION|RERANKING|PERSONALIZATION)\\.meta")
                    || !file.isFile() || file.length() > 4096) continue;
            try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
                if (input.readInt() != MAGIC) continue;
                final KnowledgeModelPolicy.Role role =
                        KnowledgeModelPolicy.Role.valueOf(input.readUTF());
                final Format format = Format.valueOf(input.readUTF());
                final String sha = input.readUTF();
                final String label = safeName(input.readUTF());
                final long length = input.readLong();
                final File model = modelFile(sha, format);
                if (name.equals(sha + "-" + role.name() + ".meta") && length >= 16
                        && model.isFile() && model.length() == length && input.read() == -1) {
                    result.add(new Entry(role, format, sha, label, length));
                }
            } catch (IOException | IllegalArgumentException invalid) {
                // Corrupt metadata is never displayed or executed.
            }
        }
        result.sort(Comparator.comparing((Entry item) -> item.role.name())
                .thenComparing(item -> item.name).thenComparing(item -> item.sha256));
        return Collections.unmodifiableList(result);
    }

    synchronized boolean delete(final Entry entry) throws IOException {
        if (entry == null) return false;
        final File metadata = metadataFile(entry.sha256, entry.role);
        if (!metadata.isFile() || !metadata.delete()) return false;
        boolean shared = false;
        for (final Entry other : installed()) {
            if (entry.sha256.equals(other.sha256)) shared = true;
        }
        if (!shared) {
            final File blob = modelFile(entry.sha256, entry.format);
            if (blob.isFile() && !blob.delete()) throw new IOException("Could not delete model file");
        }
        return true;
    }
}
