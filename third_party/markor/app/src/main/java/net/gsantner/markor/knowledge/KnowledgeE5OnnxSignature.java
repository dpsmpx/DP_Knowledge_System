/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.util.Arrays;
import java.util.Map;

/**
 * Fail-closed, name-aware E5 graph I/O gate before any real-model inference.
 * No model or note data flows through this class; only names/types/shapes.
 */
final class KnowledgeE5OnnxSignature {
    enum DataType { INT64, FLOAT32, OTHER }

    static final class Tensor {
        final DataType type;
        private final long[] shape;

        Tensor(final DataType type, final long... shape) {
            if (type == null || shape == null) throw new IllegalArgumentException("Missing tensor type");
            this.type = type;
            this.shape = Arrays.copyOf(shape, shape.length);
        }

        /** Immutable snapshot for native execution's fixed-axis preflight. */
        long[] shape() { return Arrays.copyOf(shape, shape.length); }
    }

    private KnowledgeE5OnnxSignature() { }

    /** Real E5 metadata must derive the hidden size from validated config. */
    static void requireE5Small(final Map<String, Tensor> inputs,
                               final Map<String, Tensor> outputs,
                               final KnowledgeE5ConfigProfile.Result config) {
        if (config == null || config.hiddenSize != KnowledgeE5ConfigProfile.HIDDEN_SIZE
                || config.maxSequence != KnowledgeEmbeddingTensorContract.MAX_SEQUENCE_LENGTH
                || config.vocabSize != KnowledgeE5ConfigProfile.VOCAB_SIZE) {
            throw new IllegalArgumentException("Missing canonical E5 model configuration");
        }
        require(inputs, outputs, config.hiddenSize);
    }

    /** Compare exact names, element types and axes from two independent readers. */
    static boolean sameDescriptors(final Map<String, Tensor> first,
                                   final Map<String, Tensor> second) {
        if (first == null || second == null || !first.keySet().equals(second.keySet())) {
            return false;
        }
        for (final Map.Entry<String, Tensor> item : first.entrySet()) {
            final Tensor a = item.getValue();
            final Tensor b = second.get(item.getKey());
            if (a == null || b == null || a.type != b.type
                    || !Arrays.equals(a.shape, b.shape)) return false;
        }
        return true;
    }

    static void require(final Map<String, Tensor> inputs, final Map<String, Tensor> outputs,
                        final int expectedDimensions) {
        if (inputs == null || outputs == null || expectedDimensions < 8
                || expectedDimensions > KnowledgeEmbeddingVectors.MAX_DIMENSIONS
                || inputs.size() < 2 || inputs.size() > 3
                || outputs.size() < 1 || outputs.size() > 2
                || !inputs.containsKey("input_ids") || !inputs.containsKey("attention_mask")
                || !outputs.containsKey("last_hidden_state")) {
            throw new IllegalArgumentException("Unrecognized E5 ONNX graph I/O");
        }
        for (final Map.Entry<String, Tensor> entry : inputs.entrySet()) {
            if (!entry.getKey().equals("input_ids")
                    && !entry.getKey().equals("attention_mask")
                    && !entry.getKey().equals("token_type_ids")) {
                throw new IllegalArgumentException("Unknown E5 input name");
            }
            requireShape(entry.getValue(), DataType.INT64, 2);
            final long[] shape = entry.getValue().shape;
            if (!batchAxis(shape[0]) || !sequenceAxis(shape[1])) {
                throw new IllegalArgumentException("Incompatible E5 input shape");
            }
        }
        final Tensor ids = inputs.get("input_ids");
        for (final Tensor other : inputs.values()) {
            if (!axesAgree(ids.shape[0], other.shape[0])
                    || !axesAgree(ids.shape[1], other.shape[1])) {
                throw new IllegalArgumentException("E5 input dimensions disagree");
            }
        }
        for (final Map.Entry<String, Tensor> entry : outputs.entrySet()) {
            final Tensor out = entry.getValue();
            if (entry.getKey().equals("last_hidden_state")) {
                requireShape(out, DataType.FLOAT32, 3);
                if (!batchAxis(out.shape[0]) || !sequenceAxis(out.shape[1])
                        || out.shape[2] != expectedDimensions
                        || !axesAgree(ids.shape[0], out.shape[0])
                        || !axesAgree(ids.shape[1], out.shape[1])) {
                    throw new IllegalArgumentException("Incompatible E5 hidden-state shape");
                }
            } else if (entry.getKey().equals("pooler_output")) {
                // Optional BERT diagnostic output is not used for embeddings.
                requireShape(out, DataType.FLOAT32, 2);
                if (!batchAxis(out.shape[0]) || out.shape[1] != expectedDimensions
                        || !axesAgree(ids.shape[0], out.shape[0])) {
                    throw new IllegalArgumentException("Incompatible optional pooler output");
                }
            } else {
                throw new IllegalArgumentException("Unknown E5 output name");
            }
        }
    }

    private static void requireShape(final Tensor tensor, final DataType type, final int rank) {
        if (tensor == null || tensor.type != type || tensor.shape.length != rank) {
            throw new IllegalArgumentException("Incompatible ONNX element type or rank");
        }
    }

    private static boolean batchAxis(final long dimension) {
        return dimension == -1 || dimension == 1;
    }

    private static boolean sequenceAxis(final long dimension) {
        return dimension == -1 || (dimension >= 3
                && dimension <= KnowledgeEmbeddingTensorContract.MAX_SEQUENCE_LENGTH);
    }

    private static boolean axesAgree(final long a, final long b) {
        return a == -1 || b == -1 || a == b;
    }
}
