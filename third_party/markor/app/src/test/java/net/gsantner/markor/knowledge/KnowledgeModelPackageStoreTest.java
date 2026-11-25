/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public final class KnowledgeModelPackageStoreTest {
    private static final byte[] WEIGHT = new byte[48];
    private static final byte[] TOKENIZER = "{\"vocab\":{}}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONFIG = "{\"hidden_size\":8}".getBytes(StandardCharsets.UTF_8);

    @Test public void atomicPrivatePackageGivesEvidenceButNeverRuntimeReadiness() throws Exception {
        final File directory = Files.createTempDirectory("dpks-package-ok").toFile();
        try {
            final KnowledgeModelFiles weights = new KnowledgeModelFiles(directory);
            final KnowledgeModelFiles.Entry embedding = installWeight(weights);
            final KnowledgeModelPackageStore packages = new KnowledgeModelPackageStore(directory, weights);
            assertNull(packages.installed(embedding));
            final KnowledgeModelPackageManifest manifest = packages.install(
                    manifest(embedding, "tokenizer.json", TOKENIZER, "config.json", CONFIG),
                    companions("tokenizer.json", TOKENIZER, "config.json", CONFIG), embedding);
            assertEquals(3, manifest.components.size());
            assertTrue(new File(directory, "package-" + embedding.sha256 + "-EMBEDDING").isDirectory());
            assertNotNull(packages.installed(embedding));
            final KnowledgeModelPackageContract.Report report =
                    KnowledgeModelPackageContract.inspect(embedding, packages.installed(embedding));
            assertEquals(2, report.companionFileCount);
            assertTrue(report.companionFilesInstalled);
            assertFalse(report.missing.contains(KnowledgeModelPackageContract.Requirement.TOKENIZER));
            assertFalse(report.missing.contains(KnowledgeModelPackageContract.Requirement.MODEL_CONFIG));
            assertTrue(report.missing.contains(KnowledgeModelPackageContract.Requirement.PINNED_REVISION));
            assertTrue(report.missing.contains(KnowledgeModelPackageContract.Requirement.VALIDATED_ANDROID_BACKEND));
            assertTrue(report.missing.contains(KnowledgeModelPackageContract.Requirement.EMBEDDING_POOLING));
            assertFalse(report.runnable());
            assertTrue(weights.delete(embedding));
            assertNull(packages.installed(embedding));
            assertTrue(weights.installed().isEmpty());
            assertEquals(0, directory.listFiles().length);
        } finally { removeTree(directory); }
    }

    @Test public void wrongHashAndMissingCompanionAreRolledBackWithoutTouchingWeight()
            throws Exception {
        final File directory = Files.createTempDirectory("dpks-package-rollback").toFile();
        try {
            final KnowledgeModelFiles weights = new KnowledgeModelFiles(directory);
            final KnowledgeModelFiles.Entry embedding = installWeight(weights);
            final KnowledgeModelPackageStore packages = new KnowledgeModelPackageStore(directory, weights);
            final byte[] expected = manifest(embedding, "tokenizer.json", TOKENIZER,
                    "config.json", CONFIG);
            try {
                packages.install(expected, Collections.singletonList(
                        new KnowledgeModelPackageStore.Source("tokenizer.json",
                                () -> new ByteArrayInputStream(TOKENIZER))), embedding);
                fail("A missing declared sidecar was accepted");
            } catch (IOException expectedError) { }
            final byte[] wrong = "{\"fake\":1}".getBytes(StandardCharsets.UTF_8);
            try {
                packages.install(expected, companions("tokenizer.json", wrong,
                        "config.json", CONFIG), embedding);
                fail("A wrong companion digest was accepted");
            } catch (IOException expectedError) { }
            assertNull(packages.installed(embedding));
            assertEquals(1, weights.installed().size());
            assertEquals(2, directory.listFiles().length); // weight blob + metadata only
            assertNotNull(packages.install(expected, companions("tokenizer.json", TOKENIZER,
                    "config.json", CONFIG), embedding));
            try {
                packages.install(expected, companions("tokenizer.json", TOKENIZER,
                        "config.json", CONFIG), embedding);
                fail("An existing package was silently replaced");
            } catch (IOException expectedError) { }
            assertTrue(packages.forget(embedding));
            assertEquals(1, weights.installed().size());
        } finally { removeTree(directory); }
    }

    @Test public void mismatchedRoleAndTamperedWeightCannotBeBundled() throws Exception {
        final File directory = Files.createTempDirectory("dpks-package-weight").toFile();
        try {
            final KnowledgeModelFiles weights = new KnowledgeModelFiles(directory);
            final KnowledgeModelFiles.Entry embedding = installWeight(weights);
            final KnowledgeModelPackageStore packages = new KnowledgeModelPackageStore(directory, weights);
            final byte[] json = manifest(embedding, "tokenizer.json", TOKENIZER,
                    "config.json", CONFIG);
            final KnowledgeModelFiles.Entry wrongRole = new KnowledgeModelFiles.Entry(
                    KnowledgeModelPolicy.Role.GENERATION, embedding.format, embedding.sha256,
                    embedding.name, embedding.bytes);
            try {
                packages.install(json, companions("tokenizer.json", TOKENIZER,
                        "config.json", CONFIG), wrongRole);
                fail("A package crossed a role boundary");
            } catch (IOException expectedError) { }
            final File rawWeight = new File(directory, embedding.sha256 + ".onnx");
            final byte[] changed = Arrays.copyOf(WEIGHT, WEIGHT.length);
            changed[0] = 1;
            Files.write(rawWeight.toPath(), changed);
            try {
                packages.install(json, companions("tokenizer.json", TOKENIZER,
                        "config.json", CONFIG), embedding);
                fail("Tampered weights were accepted by size only");
            } catch (IOException expectedError) { }
            assertNull(packages.installed(embedding));
        } finally { removeTree(directory); }
    }

    @Test public void rejectsTraversalDuplicateUnknownAndUnboundedManifest() throws Exception {
        final File directory = Files.createTempDirectory("dpks-package-invalid").toFile();
        try {
            final KnowledgeModelFiles.Entry entry = installWeight(new KnowledgeModelFiles(directory));
            final byte[] good = manifest(entry, "tokenizer.json", TOKENIZER,
                    "config.json", CONFIG);
            final JSONObject root = new JSONObject(new String(good, StandardCharsets.UTF_8));
            root.getJSONArray("files").getJSONObject(1).put("name", "../evil.json");
            reject(root);
            root.getJSONArray("files").getJSONObject(1).put("name", "tokenizer.json");
            root.getJSONArray("files").getJSONObject(2).put("name", "tokenizer.json");
            reject(root);
            root.getJSONArray("files").getJSONObject(2).put("name", "config.json");
            root.put("unexpected", "remote code");
            reject(root);
            root.remove("unexpected");
            root.put("revision", "main");
            reject(root);
            root.put("revision", "sha256:" + entry.sha256);
            root.put("license", "MIT\n<script>");
            reject(root);
            root.put("license", "MIT");
            root.getJSONArray("files").getJSONObject(2).put("bytes", 0);
            reject(root);
            final byte[] huge = new byte[KnowledgeModelPackageManifest.MAX_MANIFEST_BYTES + 1];
            try {
                KnowledgeModelPackageManifest.parse(huge);
                fail("Oversized manifest accepted");
            } catch (IOException expected) { }
            try {
                KnowledgeModelPackageManifest.readBounded(new ByteArrayInputStream(huge));
                fail("Oversized SAF manifest was not bounded before parsing");
            } catch (IOException expected) { }
        } finally { removeTree(directory); }
    }

    @Test public void cancelledPackageStaysInvisibleAndCleansStaging() throws Exception {
        final File directory = Files.createTempDirectory("dpks-package-cancel").toFile();
        try {
            final KnowledgeModelFiles weights = new KnowledgeModelFiles(directory);
            final KnowledgeModelFiles.Entry entry = installWeight(weights);
            final KnowledgeModelPackageStore packages = new KnowledgeModelPackageStore(directory, weights);
            Thread.currentThread().interrupt();
            try {
                packages.install(manifest(entry, "tokenizer.json", TOKENIZER,
                        "config.json", CONFIG), companions("tokenizer.json", TOKENIZER,
                        "config.json", CONFIG), entry);
                fail("Interrupted package import committed");
            } catch (InterruptedIOException expected) { }
            finally { Thread.interrupted(); }
            assertNull(packages.installed(entry));
            assertEquals(2, directory.listFiles().length);
        } finally { removeTree(directory); }
    }

    private static void reject(final JSONObject json) throws Exception {
        try {
            KnowledgeModelPackageManifest.parse(json.toString().getBytes(StandardCharsets.UTF_8));
            fail("Invalid package manifest was accepted");
        } catch (IOException expected) { }
    }

    private static KnowledgeModelFiles.Entry installWeight(final KnowledgeModelFiles store)
            throws Exception {
        return store.importFile(new ByteArrayInputStream(WEIGHT), "model.onnx",
                KnowledgeModelPolicy.Role.EMBEDDING);
    }

    private static List<KnowledgeModelPackageStore.Source> companions(
            final String firstName, final byte[] first, final String secondName, final byte[] second) {
        return Arrays.asList(
                new KnowledgeModelPackageStore.Source(firstName, () -> new ByteArrayInputStream(first)),
                new KnowledgeModelPackageStore.Source(secondName, () -> new ByteArrayInputStream(second)));
    }

    private static byte[] manifest(final KnowledgeModelFiles.Entry weight,
                                   final String tokenizerName, final byte[] tokenizer,
                                   final String configName, final byte[] config) throws Exception {
        final JSONObject root = new JSONObject();
        root.put("schema_version", 1);
        root.put("role", weight.role.name());
        root.put("format", weight.format.name());
        root.put("weight_sha256", weight.sha256);
        root.put("source_id", "user/example");
        root.put("revision", "sha256:" + weight.sha256);
        root.put("license", "LicenseRef-UserReview");
        final JSONArray files = new JSONArray();
        files.put(component("WEIGHTS", "model.onnx", weight.sha256, weight.bytes));
        files.put(component("TOKENIZER", tokenizerName, sha256(tokenizer), tokenizer.length));
        files.put(component("MODEL_CONFIG", configName, sha256(config), config.length));
        root.put("files", files);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static JSONObject component(final String kind, final String name,
                                        final String sha, final long bytes) throws Exception {
        return new JSONObject().put("kind", kind).put("name", name)
                .put("sha256", sha).put("bytes", bytes);
    }

    private static String sha256(final byte[] bytes) throws Exception {
        final byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        final StringBuilder output = new StringBuilder();
        for (final byte b : hash) output.append(String.format("%02x", b & 255));
        return output.toString();
    }

    private static void removeTree(final File directory) {
        final File[] children = directory.listFiles();
        if (children != null) for (final File child : children) {
            if (child.isDirectory()) removeTree(child);
            else child.delete();
        }
        directory.delete();
    }
}
