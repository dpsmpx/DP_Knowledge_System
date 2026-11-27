/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

public final class KnowledgeE5TokenEmbeddingSmokeTest {
    @Test public void variableLength384GraphHasStrictE5MetadataWithoutRealWeights()
            throws Exception {
        final byte[] fixture = KnowledgeE5TokenEmbeddingSmoke.graph(5);
        assertArrayEquals(fixture, KnowledgeE5TokenEmbeddingSmoke.graph(5));
        assertTrue(fixture.length > 40 * 1024 && fixture.length < 60 * 1024);
        final File file = File.createTempFile("dpks-e5-token-gather-", ".onnx");
        try {
            Files.write(file.toPath(), fixture);
            final KnowledgeE5ConfigProfile.Result config = KnowledgeE5ConfigProfile.parse(
                    KnowledgeE5ConfigProfileTest.model(), null);
            final KnowledgeE5OnnxMetadataProbe.Report result =
                    KnowledgeE5OnnxMetadataProbe.inspect(file, fixture.length, config,
                            KnowledgeOnnxSessionPipe.sha256Hex(fixture));
            assertEquals(3, result.inputs);
            assertEquals(1, result.outputs);
            assertEquals(0, result.dynamicAxes);
            final KnowledgeModelContainerProbe.Result container =
                    KnowledgeModelContainerProbe.inspect(file,
                            KnowledgeModelFiles.Format.ONNX_UNVERIFIED);
            assertEquals(KnowledgeModelContainerProbe.DetailState.DECLARATIONS_READ,
                    container.detailState);
            assertEquals(4, container.graphNodes);
        } finally { assertTrue(file.delete()); }
    }

    @Test public void independentlyCalculatedVectorExcludesHugePaddedRows() {
        final int[] ids = {0, 3, 2, 1, 1};
        final int[] mask = {1, 1, 1, 0, 0};
        final KnowledgeEmbeddingTensorContract.Inputs batch =
                KnowledgeEmbeddingTensorContract.fromTokenizer(ids, mask, 1, 0, 2, false);
        final float[][][] hidden = new float[1][ids.length][384];
        for (int i = 0; i < ids.length; i++) {
            for (int channel = 0; channel < 384; channel++) {
                hidden[0][i][channel] = KnowledgeE5TokenEmbeddingSmoke.value(ids[i], channel);
            }
        }
        assertEquals(10000f, hidden[0][3][0], 0);
        final float[] pooled = KnowledgeEmbeddingTensorContract.poolAndNormalize(hidden, batch, 384);
        final float[] independent = KnowledgeE5TokenEmbeddingSmoke.expectedVector(batch);
        for (int i = 0; i < 384; i++) assertEquals(independent[i], pooled[i], 0.000001f);
    }

    @Test public void twoInputTwoOutputFixtureHasOptionalPoolerSignature()
            throws Exception {
        final byte[] fixture = KnowledgeE5TokenEmbeddingSmoke.graph(6, false, true);
        final File file = File.createTempFile("dpks-e5-optional-pooler-", ".onnx");
        try {
            Files.write(file.toPath(), fixture);
            final KnowledgeE5ConfigProfile.Result config = KnowledgeE5ConfigProfile.parse(
                    KnowledgeE5ConfigProfileTest.model(), null);
            final KnowledgeE5OnnxMetadataProbe.Report result =
                    KnowledgeE5OnnxMetadataProbe.inspect(file, fixture.length, config,
                            KnowledgeOnnxSessionPipe.sha256Hex(fixture));
            assertEquals(2, result.inputs);
            assertEquals(2, result.outputs);
            assertEquals(0, result.dynamicAxes);
            assertFalse(result.inputSignature.containsKey("token_type_ids"));
            assertTrue(result.outputSignature.containsKey("last_hidden_state"));
            assertTrue(result.outputSignature.containsKey("pooler_output"));
            final KnowledgeModelContainerProbe.Result container =
                    KnowledgeModelContainerProbe.inspect(file,
                            KnowledgeModelFiles.Format.ONNX_UNVERIFIED);
            assertEquals(4, container.graphNodes);
        } finally { assertTrue(file.delete()); }
    }

    @Test public void fixtureRejectsUnknownLengthsAndIds() {
        for (final int length : new int[]{0, 2, 513}) {
            try {
                KnowledgeE5TokenEmbeddingSmoke.graph(length);
                fail("Unsupported synthetic sequence length accepted");
            } catch (IllegalArgumentException expected) { }
        }
        try {
            KnowledgeE5TokenEmbeddingSmoke.value(32, 0);
            fail("Unknown toy token accepted");
        } catch (IllegalArgumentException expected) { }
    }
}
