/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit, offline manifest builder for the two byte-pinned official E5 ONNX
 * weights. The caller chooses exactly three SAF streams. Each is hashed under
 * a strict length bound before a manifest is composed. PackageStore.install()
 * reopens and hashes them during its atomic, private commit; this does not
 * authorize inference, network access, or a claim of upstream Git provenance.
 */
final class KnowledgeE5OfficialPackage {
    private static final String SOURCE = "intfloat/multilingual-e5-small";
    private static final String LOCAL_REVISION_DOMAIN = "dpks-e5-local-package-v1\n";
    private static final long CONFIG_BYTES = 653;
    private static final long TOKENIZER_CONFIG_BYTES = 443;

    private KnowledgeE5OfficialPackage() { }

    static byte[] fromSelectedFiles(final KnowledgeModelFiles.Entry weight,
                                    final List<KnowledgeModelPackageStore.Source> chosen)
            throws IOException {
        final List<KnowledgeModelCatalog.Companion> expected = companionsFor(weight);
        if (chosen == null || chosen.size() != expected.size()) {
            throw new IOException("Select exactly three official E5 companion files");
        }
        final Map<String, KnowledgeModelPackageStore.Source> sources = new HashMap<>();
        for (final KnowledgeModelPackageStore.Source source : chosen) {
            if (source == null || source.name == null || source.provider == null
                    || sources.put(source.name, source) != null) {
                throw new IOException("Invalid or duplicated E5 companion selection");
            }
        }
        final Map<String, Long> checked = new LinkedHashMap<>();
        for (final KnowledgeModelCatalog.Companion companion : expected) {
            final KnowledgeModelPackageStore.Source source = sources.get(companion.filename);
            if (source == null) throw new IOException("Official E5 companion is missing");
            checked.put(companion.filename, verify(source,
                    companion.expectedSha256, maxBytes(companion.filename)));
        }
        return manifestForVerifiedSizes(weight, checked);
    }

    /** A pure formatter. Its caller MUST first hash the supplied source bytes. */
    static byte[] manifestForVerifiedSizes(final KnowledgeModelFiles.Entry weight,
                                           final Map<String, Long> verifiedSizes)
            throws IOException {
        final List<KnowledgeModelCatalog.Companion> expected = companionsFor(weight);
        if (verifiedSizes == null || verifiedSizes.size() != expected.size()) {
            throw new IOException("Incomplete E5 companion sizes");
        }
        final KnowledgeModelCatalog.Artifact artifact = KnowledgeModelCatalog.findInstalled(
                weight.role, weight.sha256);
        if (artifact == null) throw new IOException("Unknown official E5 weight");
        final StringBuilder identity = new StringBuilder(LOCAL_REVISION_DOMAIN)
                .append(artifact.filename).append(':').append(weight.sha256)
                .append(':').append(weight.bytes).append('\n');
        final JSONArray files = new JSONArray();
        try {
            files.put(file("WEIGHTS", artifact.filename, weight.sha256, weight.bytes));
            for (final KnowledgeModelCatalog.Companion companion : expected) {
                final Long bytes = verifiedSizes.get(companion.filename);
                if (bytes == null || bytes < 1 || bytes > maxBytes(companion.filename)
                        || ("config.json".equals(companion.filename) && bytes != CONFIG_BYTES)
                        || ("tokenizer_config.json".equals(companion.filename)
                                && bytes != TOKENIZER_CONFIG_BYTES)) {
                    throw new IOException("Invalid official E5 companion size");
                }
                identity.append(companion.filename).append(':')
                        .append(companion.expectedSha256).append(':').append(bytes).append('\n');
                files.put(file("config.json".equals(companion.filename)
                                ? "MODEL_CONFIG" : "TOKENIZER",
                        companion.filename, companion.expectedSha256, bytes));
            }
            final JSONObject manifest = new JSONObject();
            manifest.put("schema_version", 1);
            manifest.put("role", "EMBEDDING");
            manifest.put("format", "ONNX_UNVERIFIED");
            manifest.put("weight_sha256", weight.sha256);
            manifest.put("source_id", SOURCE);
            // This is a local content-set identifier, NOT a publisher commit.
            manifest.put("revision", "sha256:" + sha256(
                    identity.toString().getBytes(StandardCharsets.US_ASCII)));
            manifest.put("license", "MIT");
            manifest.put("files", files);
            final byte[] result = manifest.toString().getBytes(StandardCharsets.UTF_8);
            KnowledgeModelPackageManifest.parse(result).requireWeight(weight);
            return result;
        } catch (JSONException invalid) {
            throw new IOException("Unable to compose E5 manifest", invalid);
        }
    }

    private static List<KnowledgeModelCatalog.Companion> companionsFor(
            final KnowledgeModelFiles.Entry weight) throws IOException {
        if (weight == null || weight.role != KnowledgeModelPolicy.Role.EMBEDDING
                || weight.format != KnowledgeModelFiles.Format.ONNX_UNVERIFIED
                || weight.bytes < 16) {
            throw new IOException("A known installed ONNX embedding weight is required");
        }
        final List<KnowledgeModelCatalog.Companion> companions =
                KnowledgeModelCatalog.e5Companions(weight.role, weight.sha256);
        if (companions.size() != 3) throw new IOException("Unknown official E5 ONNX SHA-256");
        return companions;
    }

    private static long maxBytes(final String name) {
        if ("config.json".equals(name)) return CONFIG_BYTES;
        if ("tokenizer_config.json".equals(name)) return TOKENIZER_CONFIG_BYTES;
        return KnowledgeModelPackageManifest.MAX_SIDECAR_BYTES;
    }

    static long verify(final KnowledgeModelPackageStore.Source source,
                       final String expectedSha, final long limit) throws IOException {
        if (source == null || source.provider == null || expectedSha == null
                || !expectedSha.matches("[0-9a-f]{64}") || limit < 1) {
            throw new IOException("Invalid companion verification request");
        }
        final MessageDigest digest = digest();
        long count = 0;
        int emptyReads = 0;
        final byte[] buffer = new byte[64 * 1024];
        try (InputStream input = source.provider.open()) {
            if (input == null) throw new IOException("Unavailable selected document");
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("E5 companion verification cancelled");
                }
                final int n = input.read(buffer);
                if (n < 0) break;
                if (n == 0) {
                    if (++emptyReads > 1024) throw new IOException("Stalled companion provider");
                    continue;
                }
                emptyReads = 0;
                if (n > limit - count) throw new IOException("Oversized E5 companion");
                digest.update(buffer, 0, n);
                count += n;
            }
        }
        if (count == 0 || !expectedSha.equals(hex(digest.digest()))) {
            throw new IOException("E5 companion does not match independent SHA-256 pin");
        }
        return count;
    }

    private static JSONObject file(final String kind, final String name,
                                   final String sha, final long length) throws JSONException {
        final JSONObject value = new JSONObject();
        value.put("kind", kind);
        value.put("name", name);
        value.put("sha256", sha);
        value.put("bytes", length);
        return value;
    }

    private static MessageDigest digest() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 unavailable", unavailable);
        }
    }

    private static String sha256(final byte[] bytes) throws IOException {
        return hex(digest().digest(bytes));
    }

    private static String hex(final byte[] bytes) {
        final char[] alphabet = "0123456789abcdef".toCharArray();
        final StringBuilder result = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            result.append(alphabet[(b >>> 4) & 15]).append(alphabet[b & 15]);
        }
        return result.toString();
    }
}
