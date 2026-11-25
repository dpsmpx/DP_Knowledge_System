/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * User-triggered, offline package assembly for a weight already on our private
 * shelf. Companion bytes are staged, SHA-checked, then made visible atomically
 * with the manifest. No archive extraction, external paths or code execution.
 */
final class KnowledgeModelPackageStore {
    interface StreamProvider { InputStream open() throws IOException; }

    static final class Source {
        final String name;
        final StreamProvider provider;

        Source(final String filename, final StreamProvider stream) {
            name = filename;
            provider = stream;
        }
    }

    private final File directory;
    private final KnowledgeModelFiles weights;

    KnowledgeModelPackageStore(final File privateModelDirectory, final KnowledgeModelFiles models) {
        directory = privateModelDirectory;
        weights = models;
    }

    /** Only a successfully committed package is reported as present. */
    synchronized KnowledgeModelPackageManifest installed(final KnowledgeModelFiles.Entry entry)
            throws IOException {
        final File bundle = bundle(entry);
        if (!bundle.isDirectory()) return null;
        final File manifestFile = new File(bundle, KnowledgeModelPackageManifest.FILENAME);
        if (!manifestFile.isFile()) return null;
        final KnowledgeModelPackageManifest manifest;
        try (InputStream input = new FileInputStream(manifestFile)) {
            manifest = KnowledgeModelPackageManifest.parse(
                    KnowledgeModelPackageManifest.readBounded(input));
        }
        manifest.requireWeight(entry);
        for (final KnowledgeModelPackageManifest.Component component : manifest.components) {
            if (component.kind == KnowledgeModelPackageManifest.Kind.WEIGHTS) continue;
            final File sidecar = new File(bundle, component.sha256 + ".sidecar");
            if (!sidecar.isFile() || sidecar.length() != component.bytes) {
                throw new IOException("Installed companion file is unavailable");
            }
        }
        return manifest;
    }

    synchronized KnowledgeModelPackageManifest install(final byte[] manifestBytes,
            final List<Source> selected, final KnowledgeModelFiles.Entry entry) throws IOException {
        final KnowledgeModelPackageManifest manifest = KnowledgeModelPackageManifest.parse(manifestBytes);
        manifest.requireWeight(entry);
        if (selected == null || selected.size() != manifest.components.size() - 1) {
            throw new IOException("Select exactly the declared companion files");
        }
        final Map<String, Source> sources = new HashMap<>();
        for (final Source source : selected) {
            if (source == null || source.name == null || source.provider == null
                    || sources.put(source.name, source) != null) {
                throw new IOException("Duplicate or missing companion selection");
            }
        }
        for (final KnowledgeModelPackageManifest.Component component : manifest.components) {
            if (component.kind != KnowledgeModelPackageManifest.Kind.WEIGHTS
                    && !sources.containsKey(component.name)) {
                throw new IOException("Missing declared companion: " + component.kind);
            }
        }
        final File destination = bundle(entry);
        if (destination.exists()) {
            throw new IOException("Package is already installed; remove it before replacing");
        }
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create private model store");
        }
        // Recover space after a process kill during an earlier transfer. The
        // Android UI serializes installs on one model worker.
        final File[] leftovers = directory.listFiles();
        if (leftovers != null) {
            for (final File leftover : leftovers) {
                if (leftover.isDirectory() && leftover.getName().matches("package-[0-9]+.*\\.part")) {
                    removeKnownFiles(leftover);
                }
            }
        }
        if (!registeredWeightMatches(entry)) {
            throw new IOException("Installed weight failed SHA-256 verification");
        }
        // CreateTempFile supplies an unpredictable, reserved stage name. It is
        // converted into a directory before any untrusted source is read.
        final File staging = File.createTempFile("package-", ".part", directory);
        if (!staging.delete() || !staging.mkdir()) {
            throw new IOException("Could not prepare private package staging");
        }
        boolean committed = false;
        try {
            for (final KnowledgeModelPackageManifest.Component component : manifest.components) {
                if (component.kind == KnowledgeModelPackageManifest.Kind.WEIGHTS) continue;
                final File target = new File(staging, component.sha256 + ".sidecar");
                final Source source = sources.get(component.name);
                try (InputStream data = source.provider.open()) {
                    if (data == null) throw new IOException("Missing companion stream");
                    copyExact(data, target, component.bytes, component.sha256);
                }
            }
            final File manifestOutput = new File(staging, KnowledgeModelPackageManifest.FILENAME);
            try (FileOutputStream output = new FileOutputStream(manifestOutput)) {
                output.write(manifestBytes);
                output.getFD().sync();
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Package import cancelled before commit");
            }
            if (!staging.renameTo(destination)) {
                throw new IOException("Could not atomically commit model package");
            }
            committed = true;
            return manifest;
        } finally {
            if (!committed) removeKnownFiles(staging);
        }
    }

    synchronized boolean forget(final KnowledgeModelFiles.Entry entry) throws IOException {
        final File stored = bundle(entry);
        if (!stored.exists()) return false;
        removeKnownFiles(stored);
        if (stored.exists()) throw new IOException("Could not remove model package");
        return true;
    }

    private File bundle(final KnowledgeModelFiles.Entry entry) throws IOException {
        if (entry == null || entry.role == null || entry.format == null
                || entry.sha256 == null || !entry.sha256.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid model package identity");
        }
        return new File(directory, "package-" + entry.sha256 + "-" + entry.role.name());
    }

    private boolean registeredWeightMatches(final KnowledgeModelFiles.Entry entry)
            throws IOException {
        boolean registered = false;
        for (final KnowledgeModelFiles.Entry candidate : weights.installed()) {
            if (candidate.role == entry.role && candidate.format == entry.format
                    && candidate.sha256.equals(entry.sha256) && candidate.bytes == entry.bytes) {
                registered = true;
                break;
            }
        }
        if (!registered) return false;
        final File weight = new File(directory, entry.sha256
                + (entry.format == KnowledgeModelFiles.Format.GGUF ? ".gguf" : ".onnx"));
        if (!weight.isFile() || weight.length() != entry.bytes) return false;
        try (InputStream source = new FileInputStream(weight)) {
            return entry.sha256.equals(digest(source, entry.bytes));
        }
    }

    private static void copyExact(final InputStream source, final File destination,
                                  final long expectedBytes, final String expectedSha)
            throws IOException {
        final MessageDigest sha = newDigest();
        long written = 0;
        final byte[] buffer = new byte[64 * 1024];
        try (FileOutputStream output = new FileOutputStream(destination)) {
            int n;
            while ((n = source.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Package import cancelled");
                }
                if (n == 0) continue;
                if (written + n > expectedBytes) throw new IOException("Companion exceeds declared length");
                output.write(buffer, 0, n);
                sha.update(buffer, 0, n);
                written += n;
                if (destination.getParentFile().getUsableSpace() < 16L * 1024L * 1024L) {
                    throw new IOException("Insufficient space for safe package import");
                }
            }
            output.getFD().sync();
        }
        if (written != expectedBytes || !expectedSha.equals(hex(sha.digest()))) {
            throw new IOException("Companion SHA-256 or size mismatch");
        }
    }

    private static String digest(final InputStream source, final long expectedBytes)
            throws IOException {
        final MessageDigest sha = newDigest();
        final byte[] buffer = new byte[64 * 1024];
        long count = 0;
        int n;
        while ((n = source.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Package verification cancelled");
            }
            if (n == 0) continue;
            sha.update(buffer, 0, n);
            count += n;
            if (count > expectedBytes) return "";
        }
        return count == expectedBytes ? hex(sha.digest()) : "";
    }

    private static MessageDigest newDigest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 unavailable", unavailable);
        }
    }

    private static String hex(final byte[] bytes) {
        final char[] alphabet = "0123456789abcdef".toCharArray();
        final StringBuilder out = new StringBuilder(64);
        for (final byte b : bytes) {
            out.append(alphabet[(b >>> 4) & 15]).append(alphabet[b & 15]);
        }
        return out.toString();
    }

    private static void removeKnownFiles(final File folder) throws IOException {
        if (!folder.isDirectory()) return;
        final File[] files = folder.listFiles();
        if (files == null) throw new IOException("Could not inspect package contents");
        for (final File file : files) {
            final String name = file.getName();
            if (file.isFile() && (KnowledgeModelPackageManifest.FILENAME.equals(name)
                    || name.matches("[0-9a-f]{64}\\.sidecar"))) {
                if (!file.delete()) throw new IOException("Could not remove model companion");
            }
        }
        if (!folder.delete()) throw new IOException("Could not remove package directory");
    }
}
