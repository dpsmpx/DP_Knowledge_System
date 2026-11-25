/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Strict, data-only MODEL-M1 package manifest. User-authored provenance is an
 * assertion, not a verified upstream revision, a license grant or executable code.
 * No paths in this document are ever resolved against the filesystem.
 */
final class KnowledgeModelPackageManifest {
    static final String FILENAME = "dpks-model-package.json";
    static final int MAX_MANIFEST_BYTES = 64 * 1024;
    static final long MAX_SIDECAR_BYTES = 64L * 1024L * 1024L;
    static final long MAX_TOTAL_SIDECAR_BYTES = 256L * 1024L * 1024L;

    enum Kind {
        WEIGHTS, TOKENIZER, MODEL_CONFIG, CHAT_TEMPLATE, EMBEDDING_POOLING,
        RERANKER_SCORE, FEATURE_LABEL_SCHEMA
    }

    static final class Component {
        final Kind kind;
        final String name;
        final String sha256;
        final long bytes;

        private Component(final Kind type, final String fileName,
                          final String sha, final long length) {
            kind = type;
            name = fileName;
            sha256 = sha;
            bytes = length;
        }
    }

    final KnowledgeModelPolicy.Role role;
    final KnowledgeModelFiles.Format format;
    final String weightSha256;
    final String sourceId;
    final String assertedRevision;
    final String assertedLicense;
    final List<Component> components;

    private KnowledgeModelPackageManifest(final KnowledgeModelPolicy.Role modelRole,
                                          final KnowledgeModelFiles.Format modelFormat,
                                          final String weightHash, final String source,
                                          final String revision, final String license,
                                          final List<Component> files) {
        role = modelRole;
        format = modelFormat;
        weightSha256 = weightHash;
        sourceId = source;
        assertedRevision = revision;
        assertedLicense = license;
        components = Collections.unmodifiableList(new ArrayList<>(files));
    }

    static byte[] readBounded(final InputStream input) throws IOException {
        if (input == null) throw new IOException("Missing package manifest");
        final ByteArrayOutputStream result = new ByteArrayOutputStream();
        final byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.io.InterruptedIOException("Package import cancelled");
            }
            if (count == 0) continue;
            if (result.size() + count > MAX_MANIFEST_BYTES) {
                throw new IOException("Package manifest exceeds size limit");
            }
            result.write(buffer, 0, count);
        }
        return result.toByteArray();
    }

    static KnowledgeModelPackageManifest parse(final byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_MANIFEST_BYTES) {
            throw new IOException("Invalid package manifest size");
        }
        try {
            final JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            exactKeys(root, "schema_version", "role", "format", "weight_sha256",
                    "source_id", "revision", "license", "files");
            final Object version = root.get("schema_version");
            if (!(version instanceof Number) || !"1".equals(version.toString())) {
                throw new IOException("Unsupported package schema version");
            }
            final KnowledgeModelPolicy.Role role =
                    KnowledgeModelPolicy.Role.valueOf(string(root, "role"));
            final KnowledgeModelFiles.Format format =
                    KnowledgeModelFiles.Format.valueOf(string(root, "format"));
            final String weightSha = hash(string(root, "weight_sha256"));
            final String source = string(root, "source_id");
            if (!source.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}(/[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63})?")) {
                throw new IOException("Unsafe model source id");
            }
            final String revision = string(root, "revision");
            if (!revision.matches("[0-9a-f]{40}|sha256:[0-9a-f]{64}")) {
                throw new IOException("Unpinned model revision");
            }
            final String license = string(root, "license");
            if (!license.matches("[A-Za-z0-9][A-Za-z0-9.+-]{0,63}")) {
                throw new IOException("Invalid declared model license");
            }
            final JSONArray listed = root.getJSONArray("files");
            if (listed.length() < 2 || listed.length() > 16) {
                throw new IOException("Package requires weights and 1–15 companion files");
            }
            final List<Component> components = new ArrayList<>();
            final Set<String> names = new HashSet<>();
            int weightCount = 0;
            long sidecarBytes = 0;
            for (int i = 0; i < listed.length(); i++) {
                final JSONObject item = listed.getJSONObject(i);
                exactKeys(item, "kind", "name", "sha256", "bytes");
                final Kind kind = Kind.valueOf(string(item, "kind"));
                final String name = string(item, "name");
                if (!name.matches("[A-Za-z0-9_-][A-Za-z0-9._+-]{0,95}")
                        || name.contains("..") || FILENAME.equals(name)
                        || !names.add(name)) {
                    throw new IOException("Unsafe or duplicate companion filename");
                }
                final String digest = hash(string(item, "sha256"));
                final Object rawBytes = item.get("bytes");
                if (!(rawBytes instanceof Number)
                        || !rawBytes.toString().matches("[0-9]{1,19}")) {
                    throw new IOException("Invalid package component length");
                }
                final long length = Long.parseLong(rawBytes.toString());
                if (kind == Kind.WEIGHTS) {
                    weightCount++;
                    if (length < 16 || !digest.equals(weightSha)
                            || (format == KnowledgeModelFiles.Format.GGUF && !name.endsWith(".gguf"))
                            || (format == KnowledgeModelFiles.Format.ONNX_UNVERIFIED && !name.endsWith(".onnx"))) {
                        throw new IOException("Weight identity does not match manifest");
                    }
                } else {
                    if (length == 0 || length > MAX_SIDECAR_BYTES
                            || !allowedSidecar(kind, name)) {
                        throw new IOException("Unsupported companion file type/size");
                    }
                    sidecarBytes += length;
                    if (sidecarBytes > MAX_TOTAL_SIDECAR_BYTES) {
                        throw new IOException("Package companions exceed size limit");
                    }
                }
                components.add(new Component(kind, name, digest, length));
            }
            if (weightCount != 1) throw new IOException("Package requires exactly one weight");
            return new KnowledgeModelPackageManifest(role, format, weightSha, source,
                    revision, license, components);
        } catch (JSONException | IllegalArgumentException invalid) {
            throw new IOException("Invalid package manifest structure", invalid);
        }
    }

    void requireWeight(final KnowledgeModelFiles.Entry installed) throws IOException {
        if (installed == null || installed.role != role || installed.format != format
                || !weightSha256.equals(installed.sha256)) {
            throw new IOException("Package does not match selected installed model");
        }
        for (final Component item : components) {
            if (item.kind == Kind.WEIGHTS && item.bytes != installed.bytes) {
                throw new IOException("Installed weight size differs from package manifest");
            }
        }
    }

    boolean includes(final Kind kind) {
        for (final Component item : components) if (item.kind == kind) return true;
        return false;
    }

    private static boolean allowedSidecar(final Kind kind, final String name) {
        if (kind != Kind.TOKENIZER) return name.endsWith(".json");
        return name.endsWith(".json") || name.endsWith(".model")
                || name.endsWith(".txt") || name.endsWith(".vocab")
                || name.endsWith(".merges") || name.endsWith(".tiktoken");
    }

    private static String string(final JSONObject json, final String key) throws JSONException {
        final Object value = json.get(key);
        if (!(value instanceof String)) throw new JSONException("Expected string: " + key);
        return (String) value;
    }

    private static String hash(final String supplied) throws IOException {
        if (!supplied.matches("[0-9a-f]{64}")) throw new IOException("Invalid SHA-256");
        return supplied;
    }

    private static void exactKeys(final JSONObject item, final String... allowed) throws IOException {
        final Set<String> expected = new HashSet<>();
        Collections.addAll(expected, allowed);
        final Iterator<String> keys = item.keys();
        while (keys.hasNext()) {
            if (!expected.remove(keys.next())) throw new IOException("Unexpected package field");
        }
        if (!expected.isEmpty()) throw new IOException("Missing package fields");
    }
}
