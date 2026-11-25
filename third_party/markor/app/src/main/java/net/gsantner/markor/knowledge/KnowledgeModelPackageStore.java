/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

/**
 * User-triggered, offline package assembly for a weight already on our private
 * shelf. Companion bytes are staged, SHA-checked, then made visible atomically
 * with the manifest. No archive extraction, external paths or code execution.
 */
final class KnowledgeModelPackageStore {
    private static final int MAX_JSON_PREFLIGHT_BYTES = 512 * 1024;
    private static final int MAX_JSON_DEPTH = 64;
    private static final long MIN_STAGING_FREE_BYTES = 64L * 1024L * 1024L;
    interface StreamProvider { InputStream open() throws IOException; }
    interface AuditProgress { void onVerifiedBytes(long verified, long total); }

    /** An explicit audit of FILE integrity, not an inference/quality benchmark. */
    static final class Audit {
        final long verifiedBytes;
        final long elapsedMillis;
        final int companionFiles;
        final int jsonObjectsChecked;
        final int jsonNotInspected;
        final int jsonInvalid;
        final KnowledgeModelContainerProbe.Result container;
        final int externalReferencesMatched;
        final int externalReferencesUnresolved;
        final int externalRangesOutOfBounds;
        final int externalComponentsUnused;

        Audit(final long bytes, final long millis, final int companionCount,
              final int checked, final int skipped, final int invalid,
              final KnowledgeModelContainerProbe.Result preflight,
              final int matched, final int unresolved, final int outOfBounds,
              final int unused) {
            verifiedBytes = bytes;
            elapsedMillis = millis;
            companionFiles = companionCount;
            jsonObjectsChecked = checked;
            jsonNotInspected = skipped;
            jsonInvalid = invalid;
            container = preflight;
            externalReferencesMatched = matched;
            externalReferencesUnresolved = unresolved;
            externalRangesOutOfBounds = outOfBounds;
            externalComponentsUnused = unused;
        }

        boolean integrityPassed() { return true; } // Otherwise audit throws.
        boolean jsonPreflightPassed() { return jsonInvalid == 0; }
        boolean externalDataPreflightPassed() {
            return container.detailState == KnowledgeModelContainerProbe.DetailState.DECLARATIONS_READ
                    && externalReferencesUnresolved == 0 && externalRangesOutOfBounds == 0
                    && externalComponentsUnused == 0;
        }
    }

    static final class Source {
        final String name;
        final StreamProvider provider;

        Source(final String filename, final StreamProvider stream) {
            name = filename;
            provider = stream;
        }
    }

    /**
     * One explicitly prepared, transient ONNX directory. No inference engine
     * is started. Call close() even if a future runtime rejects the model.
     */
    static final class PreparedOnnx implements AutoCloseable {
        final File modelFile;
        final Map<String, File> companions;
        private final File folder;
        private boolean closed;

        private PreparedOnnx(final File temporaryFolder, final File model,
                             final Map<String, File> copied) {
            folder = temporaryFolder;
            modelFile = model;
            companions = Collections.unmodifiableMap(new LinkedHashMap<>(copied));
        }

        @Override public synchronized void close() throws IOException {
            if (closed) return;
            removePreparedFiles(folder, modelFile, companions.values());
            closed = true;
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
        if (!bundle.exists()) return null;
        if (!safeChild(directory, bundle) || !bundle.isDirectory()) {
            throw new IOException("Unsafe model package directory");
        }
        final File manifestFile = new File(bundle, KnowledgeModelPackageManifest.FILENAME);
        if (!manifestFile.exists()) return null;
        if (!safeChild(bundle, manifestFile) || !manifestFile.isFile()) {
            throw new IOException("Unsafe model package manifest");
        }
        final KnowledgeModelPackageManifest manifest;
        try (InputStream input = new FileInputStream(manifestFile)) {
            manifest = KnowledgeModelPackageManifest.parse(
                    KnowledgeModelPackageManifest.readBounded(input));
        }
        manifest.requireWeight(entry);
        for (final KnowledgeModelPackageManifest.Component component : manifest.components) {
            if (component.kind == KnowledgeModelPackageManifest.Kind.WEIGHTS) continue;
            final File sidecar = new File(bundle, component.sha256 + ".sidecar");
            if (!safeChild(bundle, sidecar) || !sidecar.isFile()
                    || sidecar.length() != component.bytes) {
                throw new IOException("Installed companion file is unavailable");
            }
        }
        return manifest;
    }

    /**
     * Rehash every saved byte on a user-requested worker thread. Previously
     * imported files may have been modified or damaged; their name and length
     * cannot prove present-day integrity. JSON checks are strictly preliminary:
     * they do not establish an ONNX/GGUF runtime contract or tokenizer support.
     */
    synchronized Audit audit(final KnowledgeModelFiles.Entry entry,
                             final AuditProgress progress) throws IOException {
        final KnowledgeModelPackageManifest manifest = installed(entry);
        if (manifest == null) throw new IOException("No complete package to audit");
        if (!registered(entry)) throw new IOException("Weight registration changed");
        final File weight = weightFile(entry);
        if (!safeChild(directory, weight)) throw new IOException("Unsafe model weight file");
        long total = entry.bytes;
        for (final KnowledgeModelPackageManifest.Component component : manifest.components) {
            if (component.kind == KnowledgeModelPackageManifest.Kind.WEIGHTS) continue;
            if (Long.MAX_VALUE - total < component.bytes) {
                throw new IOException("Package audit byte count overflow");
            }
            total += component.bytes;
        }
        final long totalBytes = total;
        final File packageDirectory = bundle(entry);
        final long started = System.nanoTime();
        long verified = 0;
        int checked = 0;
        int skipped = 0;
        int invalid = 0;
        final String weightHash;
        try (InputStream source = new FileInputStream(weight)) {
            weightHash = digest(source, entry.bytes, progress, 0, null, totalBytes);
        }
        if (!entry.sha256.equals(weightHash)) throw new IOException("Weight digest changed");
        verified += entry.bytes;
        for (final KnowledgeModelPackageManifest.Component component : manifest.components) {
            if (component.kind == KnowledgeModelPackageManifest.Kind.WEIGHTS) continue;
            final File part = new File(packageDirectory, component.sha256 + ".sidecar");
            if (!safeChild(packageDirectory, part) || !part.isFile()
                    || part.length() != component.bytes) {
                throw new IOException("Companion missing or changed size");
            }
            final boolean isJson = component.kind != KnowledgeModelPackageManifest.Kind.ONNX_EXTERNAL_DATA
                    && component.name.endsWith(".json");
            final boolean inspect = isJson && component.bytes <= MAX_JSON_PREFLIGHT_BYTES;
            final ByteArrayOutputStream json = inspect
                    ? new ByteArrayOutputStream((int) component.bytes) : null;
            final long previous = verified;
            final String actual;
            try (InputStream source = new FileInputStream(part)) {
                actual = digest(source, component.bytes, progress, previous, json, totalBytes);
            }
            if (!component.sha256.equals(actual)) {
                throw new IOException("Companion digest changed");
            }
            verified += component.bytes;
            if (isJson) {
                if (inspect) {
                    if (basicJsonObject(json.toByteArray())) checked++;
                    else invalid++;
                } else {
                    skipped++;
                }
            }
        }
        final KnowledgeModelContainerProbe.Result container =
                KnowledgeModelContainerProbe.inspect(weight, entry.format);
        final Map<String, Long> externalFiles = new HashMap<>();
        for (final KnowledgeModelPackageManifest.Component component : manifest.components) {
            if (component.kind == KnowledgeModelPackageManifest.Kind.ONNX_EXTERNAL_DATA) {
                externalFiles.put(component.name, component.bytes);
            }
        }
        final Set<String> usedExternal = new HashSet<>();
        int matched = 0, outOfBounds = 0;
        for (final KnowledgeModelContainerProbe.ExternalReference reference :
                container.externalReferences) {
            final Long size = externalFiles.get(reference.location);
            if (size == null) continue; // Never resolve the ONNX location as a path.
            usedExternal.add(reference.location);
            if (reference.offset >= size || (reference.length >= 0
                    && reference.length > size - reference.offset)) {
                outOfBounds++;
            } else {
                matched++; // Bytes were rehashed above via the *manifest* identity.
            }
        }
        final int unresolved = (int) Math.max(0L,
                container.externalDataTensorsDeclared - matched - outOfBounds);
        return new Audit(verified, Math.max(0L, (System.nanoTime() - started) / 1_000_000L),
                manifest.components.size() - 1, checked, skipped, invalid, container,
                matched, unresolved, outOfBounds, externalFiles.size() - usedExternal.size());
    }

    /**
     * Create the safe, short-lived on-disk layout required by file-based ONNX
     * loaders. It is never invoked automatically, and deliberately does not
     * create an inference session. The caller supplies app-private cacheDir.
     * All source paths come from our own shelf and SHA-named package storage;
     * untrusted ONNX `location` values are only compared to manifest names.
     */
    synchronized PreparedOnnx prepareOnnx(final KnowledgeModelFiles.Entry entry,
                                         final File privateCache) throws IOException {
        if (entry == null || entry.format != KnowledgeModelFiles.Format.ONNX_UNVERIFIED) {
            throw new IOException("Only installed ONNX models can be staged");
        }
        final Audit verified = audit(entry, null);
        if (verified.container.state != KnowledgeModelContainerProbe.State.METADATA_HINTS
                || !verified.jsonPreflightPassed() || !verified.externalDataPreflightPassed()) {
            throw new IOException("ONNX package preflight is incomplete");
        }
        final KnowledgeModelPackageManifest manifest = installed(entry);
        if (manifest == null) throw new IOException("Model package no longer installed");
        if (privateCache == null || (!privateCache.isDirectory() && !privateCache.mkdirs())) {
            throw new IOException("Private cache unavailable");
        }
        long total = 0;
        for (final KnowledgeModelPackageManifest.Component component : manifest.components) {
            if (Long.MAX_VALUE - total < component.bytes) {
                throw new IOException("ONNX staging size overflow");
            }
            total += component.bytes;
        }
        final long free = privateCache.getUsableSpace();
        if (free < total || free - total < MIN_STAGING_FREE_BYTES) {
            throw new IOException("Insufficient private space to prepare ONNX package");
        }
        final File stage = File.createTempFile("dpks-onnx-", ".part", privateCache);
        if (!stage.delete() || !stage.mkdir()) {
            throw new IOException("Could not allocate ONNX session directory");
        }
        final Map<String, File> copied = new LinkedHashMap<>();
        File stagedModel = null;
        boolean finished = false;
        try {
            final File installedPackage = bundle(entry);
            for (final KnowledgeModelPackageManifest.Component component : manifest.components) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("ONNX preparation cancelled");
                }
                final boolean model = component.kind == KnowledgeModelPackageManifest.Kind.WEIGHTS;
                final File parent = model ? directory : installedPackage;
                final File source = model ? weightFile(entry)
                        : new File(installedPackage, component.sha256 + ".sidecar");
                if (!safeChild(parent, source) || !source.isFile()
                        || source.length() != component.bytes) {
                    throw new IOException("Source changed before ONNX preparation");
                }
                // Manifest already validates names as single ASCII path segments.
                final File destination = new File(stage, component.name);
                if (!safeChild(stage, destination)) {
                    throw new IOException("Unsafe ONNX staging destination");
                }
                // Track before copying so cancellation removes a partial file.
                if (model) stagedModel = destination;
                else copied.put(component.name, destination);
                try (InputStream input = new FileInputStream(source)) {
                    copyExact(input, destination, component.bytes, component.sha256);
                }
            }
            if (stagedModel == null || Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("ONNX preparation cancelled before commit");
            }
            final File ready = new File(privateCache,
                    stage.getName().substring(0, stage.getName().length() - 5) + ".ready");
            if (ready.exists() || !stage.renameTo(ready)) {
                throw new IOException("Could not commit private ONNX staging directory");
            }
            final File modelPath = new File(ready, stagedModel.getName());
            final Map<String, File> paths = new LinkedHashMap<>();
            for (final String name : copied.keySet()) paths.put(name, new File(ready, name));
            finished = true;
            return new PreparedOnnx(ready, modelPath, paths);
        } finally {
            if (!finished) removePreparedFiles(stage, stagedModel, copied.values());
        }
    }

    private static void removePreparedFiles(final File folder, final File model,
                                            final Iterable<File> companions) throws IOException {
        if (!safeChild(folder.getParentFile(), folder)) {
            throw new IOException("Unsafe ONNX staging directory");
        }
        if (!folder.exists()) return;
        if (model != null) deleteStagedChild(folder, new File(folder, model.getName()));
        for (final File companion : companions) {
            deleteStagedChild(folder, new File(folder, companion.getName()));
        }
        if (!folder.delete()) throw new IOException("Could not remove ONNX staging directory");
    }

    private static void deleteStagedChild(final File folder, final File file) throws IOException {
        if (!safeChild(folder, file)) throw new IOException("Unsafe ONNX staged file");
        if (file.exists() && !file.delete()) throw new IOException("Could not remove ONNX staged file");
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
        if (!safeChild(directory, stored)) throw new IOException("Unsafe package directory");
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
        if (!registered(entry)) return false;
        final File weight = weightFile(entry);
        if (!safeChild(directory, weight)) return false;
        try (InputStream source = new FileInputStream(weight)) {
            return entry.sha256.equals(digest(source, entry.bytes));
        }
    }

    private boolean registered(final KnowledgeModelFiles.Entry entry) throws IOException {
        for (final KnowledgeModelFiles.Entry candidate : weights.installed()) {
            if (candidate.role == entry.role && candidate.format == entry.format
                    && candidate.sha256.equals(entry.sha256) && candidate.bytes == entry.bytes) {
                final File weight = weightFile(entry);
                return safeChild(directory, weight) && weight.isFile()
                        && weight.length() == entry.bytes;
            }
        }
        return false;
    }

    private File weightFile(final KnowledgeModelFiles.Entry entry) {
        return new File(directory, entry.sha256
                + (entry.format == KnowledgeModelFiles.Format.GGUF ? ".gguf" : ".onnx"));
    }

    /** Reject a symlink at the child boundary, even on Android API 24 (without java.nio.file). */
    private static boolean safeChild(final File trustedParent, final File child) throws IOException {
        return child.getCanonicalFile().equals(
                new File(trustedParent.getCanonicalFile(), child.getName()).getAbsoluteFile());
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
        return digest(source, expectedBytes, null, 0, null, expectedBytes);
    }

    private static String digest(final InputStream source, final long expectedBytes,
                                 final AuditProgress progress, final long alreadyVerified,
                                 final ByteArrayOutputStream capturedJson, final long totalBytes)
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
            if (n > expectedBytes - count) return "";
            sha.update(buffer, 0, n);
            count += n;
            if (capturedJson != null) capturedJson.write(buffer, 0, n);
            if (progress != null) progress.onVerifiedBytes(alreadyVerified + count, totalBytes);
        }
        return count == expectedBytes ? hex(sha.digest()) : "";
    }

    /** Bounded, non-executing JSON-object shape probe; no backend semantics. */
    private static boolean basicJsonObject(final byte[] utf8) {
        final String document;
        try {
            document = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException invalid) {
            return false;
        }
        int depth = 0;
        boolean quoted = false;
        boolean escape = false;
        for (int i = 0; i < document.length(); i++) {
            final char character = document.charAt(i);
            if (quoted) {
                if (escape) escape = false;
                else if (character == '\\') escape = true;
                else if (character == '"') quoted = false;
                continue;
            }
            if (character == '"') quoted = true;
            else if (character == '{' || character == '[') {
                if (++depth > MAX_JSON_DEPTH) return false;
            } else if (character == '}' || character == ']') {
                if (--depth < 0) return false;
            }
        }
        if (quoted || depth != 0) return false;
        try {
            new JSONObject(document);
            return true;
        } catch (JSONException invalid) {
            return false;
        }
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
        if (!safeChild(folder.getParentFile(), folder)) {
            throw new IOException("Refusing to remove symlinked package directory");
        }
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
