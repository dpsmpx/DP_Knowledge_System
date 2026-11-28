/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

public final class KnowledgeOnnxEmbeddingSmokeTest {
    @Test public void generatedGatherGraphDeclaresOnlySyntheticInt64InputsAndFloatOutput()
            throws Exception {
        final byte[] graph = KnowledgeOnnxEmbeddingSmoke.gatherModel();
        assertArrayEquals(graph, KnowledgeOnnxEmbeddingSmoke.gatherModel());
        assertTrue(graph.length > 16 && graph.length < 1500);
        final File file = File.createTempFile("dpks-synthetic-gather-", ".onnx");
        try {
            Files.write(file.toPath(), graph);
            final KnowledgeModelContainerProbe.Result result = KnowledgeModelContainerProbe.inspect(
                    file, KnowledgeModelFiles.Format.ONNX_UNVERIFIED);
            assertEquals(KnowledgeModelContainerProbe.State.METADATA_HINTS, result.state);
            assertEquals(KnowledgeModelContainerProbe.DetailState.DECLARATIONS_READ,
                    result.detailState);
            assertEquals(9, result.version);
            assertEquals(4, result.graphNodes);
            assertEquals(3, result.graphInputs);
            assertEquals(1, result.graphOutputs);
            assertEquals(3, result.int64Inputs);
            assertEquals(1, result.floatOutputs);
            assertEquals(0, result.externalDataTensorsDeclared);
            assertTrue(result.operatorInventoryHints.contains("Gather"));
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test public void paddingIsAnAdversarialRowInTheFixedFixture() {
        final KnowledgeEmbeddingTensorContract.Inputs batch =
                KnowledgeEmbeddingTensorContract.fromTokenizer(
                        KnowledgeOnnxEmbeddingSmoke.IDS, KnowledgeOnnxEmbeddingSmoke.MASK,
                        1, 0, 2, false);
        assertArrayEquals(new long[]{0, 3, 2, 1}, batch.inputIds()[0]);
        assertArrayEquals(new long[]{1, 1, 1, 0}, batch.attentionMask()[0]);
        assertArrayEquals(new long[]{0, 0, 0, 0}, batch.tokenTypeIds()[0]);
        final float[][][] rows = new float[1][4][8];
        rows[0][0][0] = 2;
        rows[0][1][1] = 4;
        rows[0][2][1] = 4;
        rows[0][3][0] = 9999;
        final float[] normalized = KnowledgeEmbeddingTensorContract.poolAndNormalize(
                rows, batch, KnowledgeOnnxEmbeddingSmoke.DIMENSIONS);
        assertEquals(KnowledgeOnnxEmbeddingSmoke.EXPECTED_0, normalized[0], .000001f);
        assertEquals(KnowledgeOnnxEmbeddingSmoke.EXPECTED_1, normalized[1], .000001f);
    }

    @Test public void generatedE5WidthGraphHasCanonicalReadOnlyIoSignature() throws Exception {
        final byte[] e5Width = KnowledgeOnnxEmbeddingSmoke.gatherModel(384);
        assertArrayEquals(e5Width, KnowledgeOnnxEmbeddingSmoke.gatherModel(384));
        assertTrue(e5Width.length > 1024 && e5Width.length < 20 * 1024);
        final File file = File.createTempFile("dpks-e5-width-gather-", ".onnx");
        try {
            Files.write(file.toPath(), e5Width);
            final KnowledgeE5OnnxMetadataProbe.Report report =
                    KnowledgeE5OnnxMetadataProbe.inspect(file, e5Width.length,
                            KnowledgeE5ConfigProfile.parse(KnowledgeE5ConfigProfileTest.model(), null),
                            KnowledgeOnnxSessionPipe.sha256Hex(e5Width));
            assertEquals(3, report.inputs);
            assertEquals(1, report.outputs);
            assertEquals(0, report.dynamicAxes);
            assertEquals(e5Width.length, report.modelBytes);
        } finally { assertTrue(file.delete()); }
    }
}
