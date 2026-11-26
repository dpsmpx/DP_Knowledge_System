/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Generated test-only E5-width Gather lookup with variable token length.
 * The 32-row table is arithmetic fixture data, never a trained neural model.
 */
final class KnowledgeE5TokenEmbeddingSmoke {
    static final int DIMENSIONS = 384;
    static final int TABLE_ROWS = 32;
    static final int PAD_ID = 1;

    private KnowledgeE5TokenEmbeddingSmoke() { }

    static byte[] graph(final int sequenceLength) {
        if (sequenceLength < 3 || sequenceLength > KnowledgeEmbeddingTensorContract.MAX_SEQUENCE_LENGTH) {
            throw new IllegalArgumentException("Synthetic E5 sequence outside tensor contract");
        }
        final ByteArrayOutputStream model = new ByteArrayOutputStream();
        KnowledgeOnnxSmoke.uint(model, 1, 9);
        KnowledgeOnnxSmoke.string(model, 2, "DPKS synthetic E5 token-to-vector pipeline");
        final ByteArrayOutputStream opset = new ByteArrayOutputStream();
        KnowledgeOnnxSmoke.uint(opset, 2, 17);
        KnowledgeOnnxSmoke.bytes(model, 8, opset.toByteArray());

        final ByteArrayOutputStream graph = new ByteArrayOutputStream();
        // All three external inputs matter; zero token types and a binary
        // mask must preserve the token IDs before the synthetic Gather.
        operation(graph, "Sub", "zero_mask", "attention_mask", "attention_mask");
        operation(graph, "Add", "masked_ids", "input_ids", "zero_mask");
        operation(graph, "Add", "ids_with_types", "masked_ids", "token_type_ids");
        operation(graph, "Gather", "last_hidden_state", "arithmetic_table", "ids_with_types");
        KnowledgeOnnxSmoke.string(graph, 2, "dpks_e5_token_pipeline");

        final ByteArrayOutputStream table = new ByteArrayOutputStream();
        KnowledgeOnnxSmoke.uint(table, 1, TABLE_ROWS);
        KnowledgeOnnxSmoke.uint(table, 1, DIMENSIONS);
        KnowledgeOnnxSmoke.uint(table, 2, 1); // TensorProto.FLOAT
        KnowledgeOnnxSmoke.string(table, 8, "arithmetic_table");
        final ByteBuffer floats = ByteBuffer.allocate(TABLE_ROWS * DIMENSIONS * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int token = 0; token < TABLE_ROWS; token++) {
            for (int channel = 0; channel < DIMENSIONS; channel++) {
                floats.putFloat(value(token, channel));
            }
        }
        KnowledgeOnnxSmoke.bytes(table, 9, floats.array());
        KnowledgeOnnxSmoke.bytes(graph, 5, table.toByteArray());

        KnowledgeOnnxSmoke.bytes(graph, 11,
                KnowledgeOnnxSmoke.valueInfo("input_ids", 7, 1, sequenceLength));
        KnowledgeOnnxSmoke.bytes(graph, 11,
                KnowledgeOnnxSmoke.valueInfo("attention_mask", 7, 1, sequenceLength));
        KnowledgeOnnxSmoke.bytes(graph, 11,
                KnowledgeOnnxSmoke.valueInfo("token_type_ids", 7, 1, sequenceLength));
        KnowledgeOnnxSmoke.bytes(graph, 12,
                KnowledgeOnnxSmoke.valueInfo("last_hidden_state", 1, 1,
                        sequenceLength, DIMENSIONS));
        KnowledgeOnnxSmoke.bytes(model, 7, graph.toByteArray());
        return model.toByteArray();
    }

    private static void operation(final ByteArrayOutputStream graph, final String operation,
                                  final String output, final String... inputs) {
        final ByteArrayOutputStream node = new ByteArrayOutputStream();
        for (final String input : inputs) KnowledgeOnnxSmoke.string(node, 1, input);
        KnowledgeOnnxSmoke.string(node, 2, output);
        KnowledgeOnnxSmoke.string(node, 4, operation);
        KnowledgeOnnxSmoke.bytes(graph, 1, node.toByteArray());
    }

    static float value(final int token, final int channel) {
        if (token < 0 || token >= TABLE_ROWS) {
            throw new IllegalArgumentException("Synthetic token outside lookup table");
        }
        if (channel == 0) return token == PAD_ID ? 10000f : (token % 7 + 1) * .125f;
        if (channel == 1) return (token % 5 - 2) * .25f;
        if (channel == 2) return (token % 3) * .5f;
        return 0f;
    }

    /** Independent scalar reference: never calls the production pooler. */
    static float[] expectedVector(final KnowledgeEmbeddingTensorContract.Inputs inputs) {
        if (inputs == null) throw new IllegalArgumentException("Missing synthetic token batch");
        final long[] ids = inputs.inputIds()[0];
        final long[] mask = inputs.attentionMask()[0];
        final double[] sums = new double[3];
        int active = 0;
        for (int index = 0; index < ids.length; index++) {
            if (ids[index] < 0 || ids[index] >= TABLE_ROWS) {
                throw new IllegalArgumentException("Synthetic token ID outside fixture table");
            }
            if (mask[index] != 1) continue;
            active++;
            for (int channel = 0; channel < sums.length; channel++) {
                sums[channel] += value((int) ids[index], channel);
            }
        }
        if (active < 3) throw new IllegalArgumentException("No synthetic active tokens");
        double square = 0;
        for (int channel = 0; channel < sums.length; channel++) {
            sums[channel] /= active;
            square += sums[channel] * sums[channel];
        }
        if (square <= 0) throw new IllegalArgumentException("Zero synthetic vector");
        final float[] expected = new float[DIMENSIONS];
        for (int channel = 0; channel < sums.length; channel++) {
            expected[channel] = (float) (sums[channel] / Math.sqrt(square));
        }
        return expected;
    }
}
