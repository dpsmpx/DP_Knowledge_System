/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Generated, tiny ONNX Gather graph for a manual synthetic embedding smoke.
 * A four-row float lookup table is diagnostic test data, NOT neural weights.
 */
final class KnowledgeOnnxEmbeddingSmoke {
    static final int DIMENSIONS = 8;
    static final int[] IDS = {0, 3, 2, 1};
    static final int[] MASK = {1, 1, 1, 0};
    static final float EXPECTED_0 = (float) (1.0 / Math.sqrt(17.0));
    static final float EXPECTED_1 = (float) (4.0 / Math.sqrt(17.0));

    private KnowledgeOnnxEmbeddingSmoke() { }

    static byte[] gatherModel() {
        final ByteArrayOutputStream model = new ByteArrayOutputStream();
        KnowledgeOnnxSmoke.uint(model, 1, 9);
        KnowledgeOnnxSmoke.string(model, 2, "DPKS synthetic embedding tensor test");
        final ByteArrayOutputStream opset = new ByteArrayOutputStream();
        KnowledgeOnnxSmoke.uint(opset, 2, 17);
        KnowledgeOnnxSmoke.bytes(model, 8, opset.toByteArray());

        final ByteArrayOutputStream graph = new ByteArrayOutputStream();
        // All declared graph inputs participate in computation. Sub(mask,
        // mask) is always zero; zero token types preserve the supplied IDs.
        // The three names therefore cannot be treated as unused decoration.
        operation(graph, "Sub", "zero_mask", "attention_mask", "attention_mask");
        operation(graph, "Add", "masked_ids", "input_ids", "zero_mask");
        operation(graph, "Add", "ids_with_types", "masked_ids", "token_type_ids");
        operation(graph, "Gather", "last_hidden_state", "synthetic_table", "ids_with_types");
        KnowledgeOnnxSmoke.string(graph, 2, "dpks_embedding_smoke");

        final ByteArrayOutputStream table = new ByteArrayOutputStream();
        KnowledgeOnnxSmoke.uint(table, 1, 4);
        KnowledgeOnnxSmoke.uint(table, 1, DIMENSIONS);
        KnowledgeOnnxSmoke.uint(table, 2, 1); // ONNX FLOAT tensor
        KnowledgeOnnxSmoke.string(table, 8, "synthetic_table");
        final ByteBuffer values = ByteBuffer.allocate(4 * DIMENSIONS * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < DIMENSIONS; column++) {
                final float value = row == 0 && column == 0 ? 2f
                        : row == 1 && column == 0 ? 9999f
                        : (row == 2 || row == 3) && column == 1 ? 4f : 0f;
                values.putFloat(value);
            }
        }
        KnowledgeOnnxSmoke.bytes(table, 9, values.array()); // TensorProto.raw_data
        KnowledgeOnnxSmoke.bytes(graph, 5, table.toByteArray());

        KnowledgeOnnxSmoke.bytes(graph, 11,
                KnowledgeOnnxSmoke.valueInfo("input_ids", 7, 1, 4)); // INT64
        KnowledgeOnnxSmoke.bytes(graph, 11,
                KnowledgeOnnxSmoke.valueInfo("attention_mask", 7, 1, 4));
        KnowledgeOnnxSmoke.bytes(graph, 11,
                KnowledgeOnnxSmoke.valueInfo("token_type_ids", 7, 1, 4));
        KnowledgeOnnxSmoke.bytes(graph, 12,
                KnowledgeOnnxSmoke.valueInfo("last_hidden_state", 1, 1, 4, DIMENSIONS));
        KnowledgeOnnxSmoke.bytes(model, 7, graph.toByteArray());
        return model.toByteArray();
    }

    private static void operation(final ByteArrayOutputStream graph, final String op,
                                  final String output, final String... inputs) {
        final ByteArrayOutputStream node = new ByteArrayOutputStream();
        for (final String name : inputs) KnowledgeOnnxSmoke.string(node, 1, name);
        KnowledgeOnnxSmoke.string(node, 2, output);
        KnowledgeOnnxSmoke.string(node, 4, op);
        KnowledgeOnnxSmoke.bytes(graph, 1, node.toByteArray());
    }
}
