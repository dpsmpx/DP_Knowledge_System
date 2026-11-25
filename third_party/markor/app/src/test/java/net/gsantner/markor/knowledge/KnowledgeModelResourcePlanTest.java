/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** Calculations are file-size declarations, never a model-RAM prediction. */
public final class KnowledgeModelResourcePlanTest {
    private static final String HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SIDE_SHA =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final long MIB = 1024L * 1024L;
    private static final long RAM = 512L * MIB;

    @Test public void noPackageAndGgufNeverBecomeReady() throws Exception {
        final KnowledgeModelFiles.Entry onnx = entry(KnowledgeModelFiles.Format.ONNX_UNVERIFIED,
                128 * MIB);
        final KnowledgeModelResourcePlan.Report noPackage =
                KnowledgeModelResourcePlan.plan(onnx, null, RAM * 3, MIB * 1024);
        assertEquals(KnowledgeModelResourcePlan.Gate.MISSING_PACKAGE, noPackage.gate);
        assertEquals(128 * MIB, noPackage.weightBytes);
        assertEquals(0, noPackage.externalBytes);
        assertEquals(128 * MIB, noPackage.totalDeclaredBytes);
        assertEquals(RAM * 3, noPackage.availableRamBytes);
        assertEquals(1024 * MIB, noPackage.freeStorageBytes);

        final KnowledgeModelResourcePlan.Report gguf = KnowledgeModelResourcePlan.plan(
                entry(KnowledgeModelFiles.Format.GGUF, 42 * MIB), null, RAM * 3, 0);
        assertEquals(KnowledgeModelResourcePlan.Gate.GGUF_BACKEND_PENDING, gguf.gate);
    }

    @Test public void smallSelfContainedPackagePassesOnlyTheResourcePrefilter()
            throws Exception {
        final KnowledgeModelFiles.Entry entry = entry(
                KnowledgeModelFiles.Format.ONNX_UNVERIFIED, 2 * MIB);
        final KnowledgeModelPackageManifest packageManifest = manifest(entry, 1,
                "MODEL_CONFIG", "config.json", MIB);
        final KnowledgeModelResourcePlan.Report allowed = KnowledgeModelResourcePlan.plan(
                entry, packageManifest, RAM, 500 * MIB);
        assertEquals(2 * MIB, allowed.weightBytes);
        assertEquals(MIB, allowed.otherCompanionBytes);
        assertEquals(3 * MIB, allowed.totalDeclaredBytes);
        assertEquals(KnowledgeModelResourcePlan.Gate.RESOURCES_ONLY_PASSED, allowed.gate);
        assertEquals(KnowledgeModelResourcePlan.Gate.RAM_TOO_LOW,
                KnowledgeModelResourcePlan.plan(entry, packageManifest, RAM - 1, 0).gate);
        assertEquals(KnowledgeModelResourcePlan.Gate.RAM_UNKNOWN,
                KnowledgeModelResourcePlan.plan(entry, packageManifest, -1, -1).gate);
    }

    @Test public void externalAndLargeFilesStayOutsideCurrentNativeGate() throws Exception {
        final KnowledgeModelFiles.Entry small = entry(
                KnowledgeModelFiles.Format.ONNX_UNVERIFIED, 2 * MIB);
        final KnowledgeModelPackageManifest external = manifest(small, 2,
                "ONNX_EXTERNAL_DATA", "weights.data", 70 * MIB);
        final KnowledgeModelResourcePlan.Report report = KnowledgeModelResourcePlan.plan(
                small, external, 8 * 1024 * MIB, 1000 * MIB);
        assertEquals(70 * MIB, report.externalBytes);
        assertEquals(1, report.externalFiles);
        assertEquals(72 * MIB, report.totalDeclaredBytes);
        assertEquals(KnowledgeModelResourcePlan.Gate.EXTERNAL_DATA_PENDING, report.gate);

        final KnowledgeModelFiles.Entry large = entry(
                KnowledgeModelFiles.Format.ONNX_UNVERIFIED,
                KnowledgeOnnxSessionGate.MAX_MODEL_BYTES + 1);
        assertEquals(KnowledgeModelResourcePlan.Gate.MODEL_TOO_LARGE,
                KnowledgeModelResourcePlan.plan(large, manifest(large, 1,
                        "MODEL_CONFIG", "config.json", 4), RAM * 8, MIB * 1024).gate);

        assertEquals(KnowledgeModelResourcePlan.Gate.PACKAGE_TOO_LARGE,
                KnowledgeModelResourcePlan.plan(small, manifest(small, 1,
                        "MODEL_CONFIG", "config.json", 32 * MIB), RAM * 8, 0).gate);
    }

    @Test public void totalByteOverflowIsRejectedWithoutWraparound() throws Exception {
        final KnowledgeModelFiles.Entry nearMax = entry(
                KnowledgeModelFiles.Format.ONNX_UNVERIFIED, Long.MAX_VALUE);
        try {
            KnowledgeModelResourcePlan.plan(nearMax, manifest(nearMax, 2,
                    "ONNX_EXTERNAL_DATA", "weights.data", 1), RAM, 0);
            fail("Declared package byte count overflowed silently");
        } catch (IOException expected) { }
    }

    private static KnowledgeModelFiles.Entry entry(final KnowledgeModelFiles.Format format,
                                                   final long bytes) {
        return new KnowledgeModelFiles.Entry(KnowledgeModelPolicy.Role.EMBEDDING,
                format, HASH, format == KnowledgeModelFiles.Format.GGUF
                        ? "test.gguf" : "test.onnx", bytes);
    }

    private static KnowledgeModelPackageManifest manifest(final KnowledgeModelFiles.Entry entry,
                                                          final int version, final String kind,
                                                          final String name, final long bytes)
            throws Exception {
        final JSONObject root = new JSONObject();
        root.put("schema_version", version);
        root.put("role", entry.role.name());
        root.put("format", entry.format.name());
        root.put("weight_sha256", entry.sha256);
        root.put("source_id", "test/budget");
        root.put("revision", "sha256:" + HASH);
        root.put("license", "MIT");
        root.put("files", new JSONArray()
                .put(new JSONObject().put("kind", "WEIGHTS")
                        .put("name", entry.name).put("sha256", entry.sha256)
                        .put("bytes", entry.bytes))
                .put(new JSONObject().put("kind", kind).put("name", name)
                        .put("sha256", SIDE_SHA).put("bytes", bytes)));
        return KnowledgeModelPackageManifest.parse(
                root.toString().getBytes(StandardCharsets.UTF_8));
    }
}
