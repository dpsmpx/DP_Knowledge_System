/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.util.Arrays;

/**
 * MODEL-M3 preparation/aggregation boundary, independent of a tokenizer or
 * native ONNX Runtime. Nothing here proves a package is runnable.
 *
 * A verified tokenizer must supply complete BOS/content/EOS token IDs and a
 * right-padded attention mask. In particular, a WordPiece approximation is
 * NOT compatible with the curated XLM-R/SentencePiece E5 tokenizer.
 */
final class KnowledgeEmbeddingTensorContract {
    static final int MAX_SEQUENCE_LENGTH = 512;

    static final class Inputs {
        private final long[] tokenIds;
        private final long[] mask;
        final boolean tokenizerTruncated;
        private final int activeTokens;

        private Inputs(final long[] tokenIds, final long[] mask,
                       final int activeTokens, final boolean tokenizerTruncated) {
            this.tokenIds = tokenIds;
            this.mask = mask;
            this.activeTokens = activeTokens;
            this.tokenizerTruncated = tokenizerTruncated;
        }

        int sequenceLength() { return tokenIds.length; }

        int activeTokens() { return activeTokens; }

        /** JNI/ORT-friendly INT64 rank-two arrays, batch size exactly one. */
        long[][] inputIds() { return new long[][]{Arrays.copyOf(tokenIds, tokenIds.length)}; }

        long[][] attentionMask() { return new long[][]{Arrays.copyOf(mask, mask.length)}; }

        /** Single-sequence BERT-family ONNX exports may also require this. */
        long[][] tokenTypeIds() { return new long[][]{new long[tokenIds.length]}; }
    }

    private KnowledgeEmbeddingTensorContract() { }

    static Inputs fromTokenizer(final int[] tokenIds, final int[] attentionMask,
                                final int padId, final int bosId, final int eosId,
                                final boolean tokenizerTruncated) {
        if (tokenIds == null || attentionMask == null
                || tokenIds.length != attentionMask.length
                || tokenIds.length < 3 || tokenIds.length > MAX_SEQUENCE_LENGTH
                || padId < 0 || bosId < 0 || eosId < 0
                || padId == bosId || padId == eosId || bosId == eosId) {
            throw new IllegalArgumentException("Invalid tokenizer tensor shape or special IDs");
        }
        if (tokenIds[0] != bosId || attentionMask[0] != 1) {
            throw new IllegalArgumentException("Missing beginning-of-sequence token");
        }
        final long[] ids = new long[tokenIds.length];
        final long[] mask = new long[tokenIds.length];
        int active = 0;
        boolean paddingStarted = false;
        for (int i = 0; i < tokenIds.length; i++) {
            final int id = tokenIds[i];
            final int bit = attentionMask[i];
            if (id < 0 || (bit != 0 && bit != 1)) {
                throw new IllegalArgumentException("Negative token ID or non-binary attention mask");
            }
            if (bit == 0) {
                paddingStarted = true;
                if (id != padId) {
                    throw new IllegalArgumentException("Masked position is not a pad token");
                }
            } else {
                if (paddingStarted || id == padId) {
                    throw new IllegalArgumentException("Mask must represent right padding only");
                }
                active++;
            }
            ids[i] = id;
            mask[i] = bit;
        }
        if (active < 3 || tokenIds[active - 1] != eosId) {
            throw new IllegalArgumentException("Missing end-of-sequence token");
        }
        for (int i = 1; i < active - 1; i++) {
            if (tokenIds[i] == bosId || tokenIds[i] == eosId) {
                throw new IllegalArgumentException("Unexpected special token within content");
            }
        }
        return new Inputs(ids, mask, active, tokenizerTruncated);
    }

    /**
     * E5's masked mean pooling over a single last_hidden_state [1, seq, dim],
     * followed by the existing index's L2 normalization. Padded rows never
     * influence the result. A pooled [1, dim] or other output is rejected.
     */
    static float[] poolAndNormalize(final float[][][] lastHiddenState,
                                    final Inputs inputs, final int expectedDimensions) {
        if (inputs == null || lastHiddenState == null || lastHiddenState.length != 1
                || lastHiddenState[0] == null
                || lastHiddenState[0].length != inputs.sequenceLength()
                || expectedDimensions < KnowledgeEmbeddingVectors.MIN_DIMENSIONS
                || expectedDimensions > KnowledgeEmbeddingVectors.MAX_DIMENSIONS) {
            throw new IllegalArgumentException("Unexpected ONNX output shape");
        }
        final double[] sums = new double[expectedDimensions];
        for (int t = 0; t < lastHiddenState[0].length; t++) {
            final float[] row = lastHiddenState[0][t];
            if (row == null || row.length != expectedDimensions) {
                throw new IllegalArgumentException("Unexpected hidden-state dimensions");
            }
            for (int d = 0; d < row.length; d++) {
                if (!Float.isFinite(row[d])) {
                    throw new IllegalArgumentException("Non-finite ONNX output");
                }
                if (inputs.mask[t] == 1) sums[d] += row[d];
            }
        }
        final float[] average = new float[expectedDimensions];
        for (int d = 0; d < average.length; d++) {
            average[d] = (float) (sums[d] / inputs.activeTokens);
        }
        return KnowledgeEmbeddingVectors.normalize(average);
    }
}
