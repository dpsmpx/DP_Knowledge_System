/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

    @Test public void manualAuditRehashesAllBytesWithoutClaimingModelSpeed() throws Exception {
        final File directory = Files.createTempDirectory("dpks-package-audit-ok").toFile();
        try {
            final KnowledgeModelFiles weights = new KnowledgeModelFiles(directory);
            final KnowledgeModelFiles.Entry entry = installWeight(weights);
            final KnowledgeModelPackageStore packages = new KnowledgeModelPackageStore(directory, weights);
            packages.install(manifest(entry, "tokenizer.json", TOKENIZER, "config.json", CONFIG),
                    companions("tokenizer.json", TOKENIZER, "config.json", CONFIG), entry);
            final long[] last = {-1, -1};
            final KnowledgeModelPackageStore.Audit result = packages.audit(entry, (done, total) -> {
                assertTrue("Progress must be monotonic", done >= last[0]);
                assertTrue("Progress exceeded exact total", done <= total);
                last[0] = done;
                last[1] = total;
            });
            final long expectedBytes = WEIGHT.length + TOKENIZER.length + CONFIG.length;
            assertEquals(expectedBytes, result.verifiedBytes);
            assertEquals(expectedBytes, last[0]);
            assertEquals(expectedBytes, last[1]);
            assertEquals(2, result.companionFiles);
            assertEquals(2, result.jsonObjectsChecked);
            assertEquals(0, result.jsonNotInspected);
            assertEquals(0, result.jsonInvalid);
            assertTrue(result.integrityPassed());
            assertTrue(result.jsonPreflightPassed());
            assertTrue(result.elapsedMillis >= 0);
            assertFalse(KnowledgeModelPackageContract.inspect(entry,
                    packages.installed(entry)).runnable());
        } finally { removeTree(directory); }
    }

    @Test public void sameSizePostInstallDamageIsDetectedByExplicitAudit() throws Exception {
        final File directory = Files.createTempDirectory("dpks-package-audit-damage").toFile();
        try {
            final KnowledgeModelFiles weights = new KnowledgeModelFiles(directory);
            final KnowledgeModelFiles.Entry entry = installWeight(weights);
            final KnowledgeModelPackageStore packages = new KnowledgeModelPackageStore(directory, weights);
            packages.install(manifest(entry, "tokenizer.json", TOKENIZER, "config.json", CONFIG),
                    companions("tokenizer.json", TOKENIZER, "config.json", CONFIG), entry);
            final File bundle = new File(directory, "package-" + entry.sha256 + "-EMBEDDING");
            final File tokenizer = new File(bundle, sha256(TOKENIZER) + ".sidecar");
            final byte[] corrupted = Arrays.copyOf(TOKENIZER, TOKENIZER.length);
            corrupted[0] ^= 1;
            Files.write(tokenizer.toPath(), corrupted);
            assertNotNull("Size-only presence cannot detect same-size corruption",
                    packages.installed(entry));
            try {
                packages.audit(entry, null);
                fail("Audit accepted modified tokenizer with the same file length");
            } catch (IOException expected) { }
            Files.write(tokenizer.toPath(), TOKENIZER);
            final File weight = new File(directory, entry.sha256 + ".onnx");
            final byte[] wrongWeight = Arrays.copyOf(WEIGHT, WEIGHT.length);
            wrongWeight[3] = 72;
            Files.write(weight.toPath(), wrongWeight);
            try {
                packages.audit(entry, null);
                fail("Audit accepted modified weight with the same file length");
            } catch (IOException expected) { }
        } finally { removeTree(directory); }
    }

    @Test public void jsonShapeWarningAndOversizedJsonSkipAreNotFalseCompatibilityPasses()
            throws Exception {
        final File directory = Files.createTempDirectory("dpks-package-json-preflight").toFile();
        try {
            final KnowledgeModelFiles weights = new KnowledgeModelFiles(directory);
            final KnowledgeModelFiles.Entry entry = installWeight(weights);
            final KnowledgeModelPackageStore packages = new KnowledgeModelPackageStore(directory, weights);
            final byte[] invalid = "[]".getBytes(StandardCharsets.UTF_8);
            final byte[] large = new byte[512 * 1024 + 1];
            Arrays.fill(large, (byte) 32);
            packages.install(manifest(entry, "tokenizer.json", invalid, "config.json", large),
                    companions("tokenizer.json", invalid, "config.json", large), entry);
            final KnowledgeModelPackageStore.Audit result = packages.audit(entry, null);
            assertTrue("Hashes still match exactly", result.integrityPassed());
            assertFalse("Array is not a JSON object", result.jsonPreflightPassed());
            assertEquals(1, result.jsonInvalid);
            assertEquals(1, result.jsonNotInspected);
            assertEquals(0, result.jsonObjectsChecked);
            assertFalse(KnowledgeModelPackageContract.inspect(entry,
                    packages.installed(entry)).runnable());
        } finally { removeTree(directory); }
    }

    @Test public void auditCancellationMustNeverReturnSuccess() throws Exception {
        final File directory = Files.createTempDirectory("dpks-package-audit-cancel").toFile();
        try {
            final KnowledgeModelFiles weights = new KnowledgeModelFiles(directory);
            final KnowledgeModelFiles.Entry entry = installWeight(weights);
            final KnowledgeModelPackageStore packages = new KnowledgeModelPackageStore(directory, weights);
            packages.install(manifest(entry, "tokenizer.json", TOKENIZER, "config.json", CONFIG),
                    companions("tokenizer.json", TOKENIZER, "config.json", CONFIG), entry);
            Thread.currentThread().interrupt();
            try {
                packages.audit(entry, null);
                fail("Cancelled verification returned PASS");
            } catch (InterruptedIOException expected) { }
            finally { Thread.interrupted(); }
            assertNotNull("Cancel is read-only; installed package remains", packages.installed(entry));
        } finally { removeTree(directory); }
    }

    @Test public void onnxExternalBytesAreMatchedToPrivateHashedCompanionsNotPaths()
            throws Exception {
        final File directory = Files.createTempDirectory("dpks-external-ok").toFile();
        try {
            final byte[] shard = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
            final KnowledgeModelFiles weights = new KnowledgeModelFiles(directory);
            final KnowledgeModelFiles.Entry entry = weights.importFile(
                    new ByteArrayInputStream(onnxExternal("tensors.data", 2, 5)),
                    "model.onnx", KnowledgeModelPolicy.Role.EMBEDDING);
            final KnowledgeModelPackageStore packages = new KnowledgeModelPackageStore(directory, weights);
            packages.install(externalManifest(entry, "tensors.data", shard),
                    externalCompanions("tensors.data", shard), entry);
            final KnowledgeModelPackageStore.Audit audit = packages.audit(entry, null);
            assertEquals(1, audit.externalReferencesMatched);
            assertEquals(0, audit.externalReferencesUnresolved);
            assertEquals(0, audit.externalRangesOutOfBounds);
            assertEquals(0, audit.externalComponentsUnused);
            assertTrue(audit.externalDataPreflightPassed());
            assertFalse(KnowledgeModelPackageContract.inspect(entry,
                    packages.installed(entry)).runnable());

            // Identical length, different bytes: next explicit audit must fail.
            final File bundle = new File(directory, "package-" + entry.sha256 + "-EMBEDDING");
            final File stored = new File(bundle, sha256(shard) + ".sidecar");
            Files.write(stored.toPath(), new byte[]{9, 2, 3, 4, 5, 6, 7, 8});
            try {
                packages.audit(entry, null);
                fail("An external tensor shard changed without detection");
            } catch (IOException expected) { }
        } finally { removeTree(directory); }
    }

    @Test public void missingOrOutOfBoundsOnnxExternalDataNeverBecomesReady() throws Exception {
        final File directory = Files.createTempDirectory("dpks-external-unresolved").toFile();
        try {
            final byte[] shard = new byte[]{1, 2, 3, 4};
            final KnowledgeModelFiles weights = new KnowledgeModelFiles(directory);
            final KnowledgeModelFiles.Entry entry = weights.importFile(
                    new ByteArrayInputStream(onnxExternal("other.data", 0, 4)),
                    "model.onnx", KnowledgeModelPolicy.Role.EMBEDDING);
            final KnowledgeModelPackageStore packages = new KnowledgeModelPackageStore(directory, weights);
            packages.install(externalManifest(entry, "tensors.data", shard),
                    externalCompanions("tensors.data", shard), entry);
            final KnowledgeModelPackageStore.Audit unresolved = packages.audit(entry, null);
            assertEquals(0, unresolved.externalReferencesMatched);
            assertEquals(1, unresolved.externalReferencesUnresolved);
            assertEquals(1, unresolved.externalComponentsUnused);
            assertFalse(unresolved.externalDataPreflightPassed());
            assertTrue(packages.forget(entry));

            final KnowledgeModelFiles.Entry outside = weights.importFile(
                    new ByteArrayInputStream(onnxExternal("tensors.data", 3, 2)),
                    "model.onnx", KnowledgeModelPolicy.Role.EMBEDDING);
            packages.install(externalManifest(outside, "tensors.data", shard),
                    externalCompanions("tensors.data", shard), outside);
            final KnowledgeModelPackageStore.Audit invalidRange = packages.audit(outside, null);
            assertEquals(1, invalidRange.externalRangesOutOfBounds);
            assertEquals(0, invalidRange.externalReferencesUnresolved);
            assertFalse(invalidRange.externalDataPreflightPassed());
        } finally { removeTree(directory); }
    }

    @Test public void packageRejectsSymlinkedShardAndExternalDataInV1() throws Exception {
        final File directory = Files.createTempDirectory("dpks-external-symlink").toFile();
        try {
            final byte[] shard = new byte[]{1, 2, 3, 4};
            final KnowledgeModelFiles weights = new KnowledgeModelFiles(directory);
            final KnowledgeModelFiles.Entry entry = weights.importFile(
                    new ByteArrayInputStream(onnxExternal("tensors.data", 0, 4)),
                    "model.onnx", KnowledgeModelPolicy.Role.EMBEDDING);
            final JSONObject versionOne = new JSONObject(new String(
                    externalManifest(entry, "tensors.data", shard), StandardCharsets.UTF_8));
            versionOne.put("schema_version", 1);
            reject(versionOne);
            versionOne.put("schema_version", 2);
            versionOne.getJSONArray("files").getJSONObject(3)
                    .put("bytes", KnowledgeModelPackageManifest.MAX_TOTAL_EXTERNAL_BYTES + 1);
            reject(versionOne);
            final KnowledgeModelPackageStore packages = new KnowledgeModelPackageStore(directory, weights);
            packages.install(externalManifest(entry, "tensors.data", shard),
                    externalCompanions("tensors.data", shard), entry);
            final File bundle = new File(directory, "package-" + entry.sha256 + "-EMBEDDING");
            final File sidecar = new File(bundle, sha256(shard) + ".sidecar");
            final File outside = new File(directory, "unrelated.data");
            Files.write(outside.toPath(), shard);
            assertTrue(sidecar.delete());
            Files.createSymbolicLink(sidecar.toPath(), outside.toPath());
            try {
                packages.audit(entry, null);
                fail("Symlinked external shard was followed");
            } catch (IOException expected) { }
            assertArrayEquals(shard, Files.readAllBytes(outside.toPath()));
        } finally { removeTree(directory); }
    }

    @Test public void explicitOnnxPreparationCreatesVerifiedLayoutAndCleansOnlyItsOwnFiles()
            throws Exception {
        final File directory = Files.createTempDirectory("dpks-runtime-stage").toFile();
        try {
            final byte[] shard = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
            final byte[] onnx = onnxExternal("tensors.data", 2, 5);
            final KnowledgeModelFiles weights = new KnowledgeModelFiles(directory);
            final KnowledgeModelFiles.Entry entry = weights.importFile(
                    new ByteArrayInputStream(onnx), "model.onnx", KnowledgeModelPolicy.Role.EMBEDDING);
            final KnowledgeModelPackageStore packages = new KnowledgeModelPackageStore(directory, weights);
            packages.install(externalManifest(entry, "tensors.data", shard),
                    externalCompanions("tensors.data", shard), entry);
            final File cache = new File(directory, "runtime-cache");
            assertTrue(cache.mkdir());
            final File unrelated = new File(cache, "preserve-unrelated.txt");
            Files.write(unrelated.toPath(), new byte[]{21});

            final KnowledgeModelPackageStore.PreparedOnnx prepared =
                    packages.prepareOnnx(entry, cache);
            assertTrue(prepared.modelFile.getParentFile().getName().endsWith(".ready"));
            assertEquals("model.onnx", prepared.modelFile.getName());
            assertArrayEquals(onnx, Files.readAllBytes(prepared.modelFile.toPath()));
            assertArrayEquals(shard,
                    Files.readAllBytes(prepared.companions.get("tensors.data").toPath()));
            assertArrayEquals(TOKENIZER,
                    Files.readAllBytes(prepared.companions.get("tokenizer.json").toPath()));
            assertArrayEquals(CONFIG,
                    Files.readAllBytes(prepared.companions.get("config.json").toPath()));
            final File preparedDirectory = prepared.modelFile.getParentFile();
            prepared.close();
            prepared.close(); // Idempotent release: no new mutation or failure.
            assertFalse(preparedDirectory.exists());
            assertArrayEquals(new byte[]{21}, Files.readAllBytes(unrelated.toPath()));
            assertNotNull(packages.installed(entry));
            assertFalse(KnowledgeModelPackageContract.inspect(entry,
                    packages.installed(entry)).runnable());
        } finally { removeTree(directory); }
    }

    @Test public void stagingRefusesUnmatchedExternalAndCancellationWithoutCacheLeak()
            throws Exception {
        final File directory = Files.createTempDirectory("dpks-stage-refuse").toFile();
        try {
            final byte[] shard = new byte[]{1, 2, 3, 4};
            final KnowledgeModelFiles weights = new KnowledgeModelFiles(directory);
            final KnowledgeModelFiles.Entry entry = weights.importFile(
                    new ByteArrayInputStream(onnxExternal("not-selected.data", 0, 4)),
                    "model.onnx", KnowledgeModelPolicy.Role.EMBEDDING);
            final KnowledgeModelPackageStore packages = new KnowledgeModelPackageStore(directory, weights);
            packages.install(externalManifest(entry, "tensors.data", shard),
                    externalCompanions("tensors.data", shard), entry);
            final File cache = new File(directory, "runtime-cache");
            try {
                packages.prepareOnnx(entry, cache);
                fail("Unresolved ONNX external reference was staged");
            } catch (IOException expected) { }
            assertFalse("Preflight failure must not allocate temporary files", cache.exists());
            Thread.currentThread().interrupt();
            try {
                packages.prepareOnnx(entry, cache);
                fail("Cancelled staging proceeded");
            } catch (InterruptedIOException expected) { }
            finally { Thread.interrupted(); }
            assertFalse(cache.exists());
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

    private static List<KnowledgeModelPackageStore.Source> externalCompanions(
            final String name, final byte[] external) {
        return Arrays.asList(
                new KnowledgeModelPackageStore.Source("tokenizer.json",
                        () -> new ByteArrayInputStream(TOKENIZER)),
                new KnowledgeModelPackageStore.Source("config.json",
                        () -> new ByteArrayInputStream(CONFIG)),
                new KnowledgeModelPackageStore.Source(name,
                        () -> new ByteArrayInputStream(external)));
    }

    private static byte[] externalManifest(final KnowledgeModelFiles.Entry weight,
                                           final String name, final byte[] shard) throws Exception {
        final JSONObject root = new JSONObject(new String(manifest(weight,
                "tokenizer.json", TOKENIZER, "config.json", CONFIG), StandardCharsets.UTF_8));
        root.put("schema_version", 2);
        root.getJSONArray("files").put(component("ONNX_EXTERNAL_DATA", name,
                sha256(shard), shard.length));
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] onnxExternal(final String name, final int offset, final int length) {
        final ByteArrayOutputStream tensor = new ByteArrayOutputStream();
        pbVarint(tensor, 112); // data_location EXTERNAL
        pbVarint(tensor, 1);
        pbExternal(tensor, "location", name);
        pbExternal(tensor, "offset", Integer.toString(offset));
        pbExternal(tensor, "length", Integer.toString(length));
        final ByteArrayOutputStream graph = new ByteArrayOutputStream();
        pbVarint(graph, 42); // GraphProto.initializer
        pbBytes(graph, tensor.toByteArray());
        final ByteArrayOutputStream model = new ByteArrayOutputStream();
        pbVarint(model, 8); // ModelProto.ir_version = 9
        pbVarint(model, 9);
        pbVarint(model, 58); // ModelProto.graph
        pbBytes(model, graph.toByteArray());
        pbVarint(model, 66); // ModelProto.opset_import
        pbBytes(model, new byte[]{16, 17});
        return model.toByteArray();
    }

    private static void pbExternal(final ByteArrayOutputStream tensor,
                                   final String key, final String value) {
        final ByteArrayOutputStream entry = new ByteArrayOutputStream();
        pbVarint(entry, 10);
        pbBytes(entry, key.getBytes(StandardCharsets.UTF_8));
        pbVarint(entry, 18);
        pbBytes(entry, value.getBytes(StandardCharsets.UTF_8));
        pbVarint(tensor, 106);
        pbBytes(tensor, entry.toByteArray());
    }

    private static void pbBytes(final ByteArrayOutputStream destination, final byte[] bytes) {
        pbVarint(destination, bytes.length);
        destination.write(bytes, 0, bytes.length);
    }

    private static void pbVarint(final ByteArrayOutputStream destination, long number) {
        while ((number & ~127L) != 0) {
            destination.write((int) (number & 127) | 128);
            number >>>= 7;
        }
        destination.write((int) number);
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
