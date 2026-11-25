/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.Assert.*;

/** All cases are JVM-only and never load or execute native ONNX Runtime. */
public final class KnowledgeOnnxSessionGateTest {
    private static final byte[] CONFIG = "{\"hidden_size\":1}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TOKENIZER = "{\"vocab\":{}}".getBytes(StandardCharsets.UTF_8);

    @Test public void availableMemoryGateRefusesLowMemoryAndUnknownSnapshots() throws Exception {
        final long threshold = KnowledgeOnnxSessionGate.MIN_AVAILABLE_RAM_BYTES;
        KnowledgeOnnxSessionGate.requireAvailableMemory(threshold, false);
        KnowledgeOnnxSessionGate.requireAvailableMemory(Long.MAX_VALUE, false);
        for (final long insufficient : new long[]{-1, 0, threshold - 1}) {
            try {
                KnowledgeOnnxSessionGate.requireAvailableMemory(insufficient, false);
                fail("Insufficient system RAM was accepted");
            } catch (IOException expected) { }
        }
        try {
            KnowledgeOnnxSessionGate.requireAvailableMemory(threshold * 2, true);
            fail("OS low-memory flag was ignored");
        } catch (IOException expected) { }
    }

    @Test public void boundedSyntheticPackageIsRehashedIntoPrivateInMemoryBytes()
            throws Exception {
        final File directory = Files.createTempDirectory("dpks-session-gate-").toFile();
        try {
            final byte[] model = KnowledgeOnnxSmoke.identityModel();
            final KnowledgeModelFiles weights = new KnowledgeModelFiles(directory);
            final KnowledgeModelFiles.Entry entry = weights.importFile(
                    new ByteArrayInputStream(model), "identity.onnx",
                    KnowledgeModelPolicy.Role.EMBEDDING);
            final KnowledgeModelPackageStore store = new KnowledgeModelPackageStore(directory, weights);
            try {
                store.readForCpuSession(entry);
                fail("Unpackaged weights were allowed to initialize a CPU session");
            } catch (IOException expected) { }

            store.install(manifest(entry), Arrays.asList(
                    new KnowledgeModelPackageStore.Source("tokenizer.json",
                            () -> new ByteArrayInputStream(TOKENIZER)),
                    new KnowledgeModelPackageStore.Source("config.json",
                            () -> new ByteArrayInputStream(CONFIG))), entry);
            assertArrayEquals(model, store.readForCpuSession(entry));
            final KnowledgeModelPackageStore.Audit report = store.audit(entry, null);
            KnowledgeOnnxSessionGate.requireEligible(entry, report.container);

            // Same-length tampering is still detected before the model reaches native code.
            final File weight = new File(directory, entry.sha256 + ".onnx");
            final byte[] changed = model.clone();
            changed[changed.length - 1] ^= 1;
            Files.write(weight.toPath(), changed);
            try {
                store.readForCpuSession(entry);
                fail("Unverified bytes were delivered to ONNX Runtime");
            } catch (IOException expected) { }
        } finally {
            removeTree(directory);
        }
    }

    @Test public void refusesOtherFormatsOversizedModelsAndInvalidHeaders() throws Exception {
        final File file = File.createTempFile("dpks-gate-invalid", ".onnx");
        try {
            final byte[] model = KnowledgeOnnxSmoke.identityModel();
            Files.write(file.toPath(), model);
            final KnowledgeModelContainerProbe.Result valid = KnowledgeModelContainerProbe.inspect(
                    file, KnowledgeModelFiles.Format.ONNX_UNVERIFIED);
            final KnowledgeModelFiles.Entry small = new KnowledgeModelFiles.Entry(
                    KnowledgeModelPolicy.Role.EMBEDDING,
                    KnowledgeModelFiles.Format.ONNX_UNVERIFIED, sha256(model), "tiny.onnx",
                    model.length);
            KnowledgeOnnxSessionGate.requireEligible(small, valid);
            assertRefused(new KnowledgeModelFiles.Entry(small.role,
                    KnowledgeModelFiles.Format.GGUF, small.sha256, "tiny.gguf", small.bytes), valid);
            assertRefused(new KnowledgeModelFiles.Entry(small.role, small.format,
                    small.sha256, "giant.onnx", KnowledgeOnnxSessionGate.MAX_MODEL_BYTES + 1), valid);
            assertRefused(small, null);
            Files.write(file.toPath(), new byte[]{1, 2, 3, 4});
            assertRefused(small, KnowledgeModelContainerProbe.inspect(file,
                    KnowledgeModelFiles.Format.ONNX_UNVERIFIED));
        } finally {
            assertTrue(file.delete());
        }
    }

    private static void assertRefused(final KnowledgeModelFiles.Entry entry,
                                      final KnowledgeModelContainerProbe.Result parsed) {
        try {
            KnowledgeOnnxSessionGate.requireEligible(entry, parsed);
            fail("Unbounded or unverified ONNX was treated as eligible");
        } catch (IOException expected) { }
    }

    private static byte[] manifest(final KnowledgeModelFiles.Entry weight) throws Exception {
        final JSONObject root = new JSONObject();
        root.put("schema_version", 1);
        root.put("role", weight.role.name());
        root.put("format", weight.format.name());
        root.put("weight_sha256", weight.sha256);
        root.put("source_id", "test/synthetic");
        root.put("revision", "sha256:" + weight.sha256);
        root.put("license", "MIT");
        root.put("files", new JSONArray()
                .put(component("WEIGHTS", "identity.onnx", weight.sha256, weight.bytes))
                .put(component("TOKENIZER", "tokenizer.json", sha256(TOKENIZER), TOKENIZER.length))
                .put(component("MODEL_CONFIG", "config.json", sha256(CONFIG), CONFIG.length)));
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static JSONObject component(final String kind, final String name,
                                        final String sha, final long bytes) throws Exception {
        return new JSONObject().put("kind", kind).put("name", name)
                .put("sha256", sha).put("bytes", bytes);
    }

    private static String sha256(final byte[] bytes) throws Exception {
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        final StringBuilder output = new StringBuilder(64);
        for (final byte part : digest) output.append(String.format("%02x", part & 255));
        return output.toString();
    }

    private static void removeTree(final File path) {
        final File[] children = path.listFiles();
        if (children != null) for (final File child : children) {
            if (child.isDirectory()) removeTree(child);
            else child.delete();
        }
        path.delete();
    }
}
