/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class KnowledgeEmbeddingTensorContractTest {
    private static final int PAD = 1;
    private static final int BOS = 0;
    private static final int EOS = 2;

    private static void invalid(final Runnable test) {
        try {
            test.run();
            fail("Invalid embedding input/output was accepted");
        } catch (IllegalArgumentException expected) {
            // Malformed tokenizer/ONNX data must never enter the vector index.
        }
    }

    private static KnowledgeEmbeddingTensorContract.Inputs syntheticInputs() {
        return KnowledgeEmbeddingTensorContract.fromTokenizer(
                new int[]{BOS, 101, 102, EOS, PAD, PAD},
                new int[]{1, 1, 1, 1, 0, 0}, PAD, BOS, EOS, false);
    }

    @Test
    public void e5PrefixesAreExplicitAndKeepUnicodeContents() {
        assertEquals("query: Привет 🌍", KnowledgeE5Text.prepare(
                KnowledgeE5Text.Purpose.QUERY, "Привет 🌍"));
        assertEquals("passage: 你好", KnowledgeE5Text.prepare(
                KnowledgeE5Text.Purpose.PASSAGE, "你好"));
        assertEquals("passage:  first line\nsecond line", KnowledgeE5Text.prepare(
                KnowledgeE5Text.Purpose.PASSAGE, " first line\nsecond line"));
        invalid(() -> KnowledgeE5Text.prepare(null, "something"));
        invalid(() -> KnowledgeE5Text.prepare(KnowledgeE5Text.Purpose.QUERY, null));
        invalid(() -> KnowledgeE5Text.prepare(KnowledgeE5Text.Purpose.QUERY, "\n  \t"));
        invalid(() -> KnowledgeE5Text.prepare(KnowledgeE5Text.Purpose.QUERY, "query: hello"));
        invalid(() -> KnowledgeE5Text.prepare(KnowledgeE5Text.Purpose.PASSAGE, "passage: hi"));
        invalid(() -> KnowledgeE5Text.prepare(KnowledgeE5Text.Purpose.QUERY, "bad\ud800"));
        invalid(() -> KnowledgeE5Text.prepare(KnowledgeE5Text.Purpose.QUERY, "\udc00bad"));
        invalid(() -> KnowledgeE5Text.prepare(KnowledgeE5Text.Purpose.QUERY,
                new String(new char[KnowledgeE5Text.MAX_TEXT_CHARS + 1]).replace('\0', 'x')));
    }

    @Test
    public void integerTokensBecomeRankTwoInt64WithRightPadding() {
        final KnowledgeEmbeddingTensorContract.Inputs batch = syntheticInputs();
        assertEquals(6, batch.sequenceLength());
        assertEquals(4, batch.activeTokens());
        assertFalse(batch.tokenizerTruncated);
        assertArrayEquals(new long[]{BOS, 101, 102, EOS, PAD, PAD}, batch.inputIds()[0]);
        assertArrayEquals(new long[]{1, 1, 1, 1, 0, 0}, batch.attentionMask()[0]);
        assertEquals(1, batch.inputIds().length);
        // Neither the original tokenizer buffers nor returned arrays can
        // modify the verified immutable snapshot used by the pooling step.
        final long[][] copy = batch.attentionMask();
        copy[0][0] = 0;
        assertEquals(1, batch.attentionMask()[0][0]);
        final int[] tokens = {BOS, 99, EOS};
        final int[] mask = {1, 1, 1};
        final KnowledgeEmbeddingTensorContract.Inputs truncated =
                KnowledgeEmbeddingTensorContract.fromTokenizer(
                        tokens, mask, PAD, BOS, EOS, true);
        tokens[1] = 200;
        mask[1] = 0;
        assertEquals(99, truncated.inputIds()[0][1]);
        assertTrue(truncated.tokenizerTruncated);
    }

    @Test
    public void malformedSpecialIdsMasksAndLengthsFailClosed() {
        invalid(() -> KnowledgeEmbeddingTensorContract.fromTokenizer(
                null, new int[]{1}, PAD, BOS, EOS, false));
        invalid(() -> KnowledgeEmbeddingTensorContract.fromTokenizer(
                new int[]{BOS, 42, EOS}, null, PAD, BOS, EOS, false));
        invalid(() -> KnowledgeEmbeddingTensorContract.fromTokenizer(
                new int[]{BOS, EOS}, new int[]{1, 1}, PAD, BOS, EOS, false));
        invalid(() -> KnowledgeEmbeddingTensorContract.fromTokenizer(
                new int[]{BOS, 42, EOS}, new int[]{1, 1}, PAD, BOS, EOS, false));
        invalid(() -> KnowledgeEmbeddingTensorContract.fromTokenizer(
                new int[]{BOS, 42, EOS}, new int[]{1, 1, 1}, PAD, BOS, BOS, false));
        invalid(() -> KnowledgeEmbeddingTensorContract.fromTokenizer(
                new int[]{BOS, 42, EOS}, new int[]{1, 1, 1}, -1, BOS, EOS, false));
        invalid(() -> KnowledgeEmbeddingTensorContract.fromTokenizer(
                new int[]{13, 42, EOS}, new int[]{1, 1, 1}, PAD, BOS, EOS, false));
        invalid(() -> KnowledgeEmbeddingTensorContract.fromTokenizer(
                new int[]{BOS, 42, 13}, new int[]{1, 1, 1}, PAD, BOS, EOS, false));
        invalid(() -> KnowledgeEmbeddingTensorContract.fromTokenizer(
                new int[]{BOS, -3, EOS}, new int[]{1, 1, 1}, PAD, BOS, EOS, false));
        invalid(() -> KnowledgeEmbeddingTensorContract.fromTokenizer(
                new int[]{BOS, EOS, EOS}, new int[]{1, 1, 1}, PAD, BOS, EOS, false));
        invalid(() -> KnowledgeEmbeddingTensorContract.fromTokenizer(
                new int[]{BOS, BOS, EOS}, new int[]{1, 1, 1}, PAD, BOS, EOS, false));
        invalid(() -> KnowledgeEmbeddingTensorContract.fromTokenizer(
                new int[]{BOS, PAD, EOS}, new int[]{1, 1, 1}, PAD, BOS, EOS, false));
        invalid(() -> KnowledgeEmbeddingTensorContract.fromTokenizer(
                new int[]{BOS, 42, EOS, 5}, new int[]{1, 1, 1, 0}, PAD, BOS, EOS, false));
        invalid(() -> KnowledgeEmbeddingTensorContract.fromTokenizer(
                new int[]{BOS, 42, EOS, PAD}, new int[]{1, 2, 1, 0}, PAD, BOS, EOS, false));
        invalid(() -> KnowledgeEmbeddingTensorContract.fromTokenizer(
                new int[]{BOS, PAD, 42, EOS}, new int[]{1, 0, 1, 1}, PAD, BOS, EOS, false));
        invalid(() -> KnowledgeEmbeddingTensorContract.fromTokenizer(
                new int[]{BOS, 42, PAD}, new int[]{1, 1, 0}, PAD, BOS, EOS, false));
        final int[] tooLong = new int[KnowledgeEmbeddingTensorContract.MAX_SEQUENCE_LENGTH + 1];
        Arrays.fill(tooLong, 99);
        tooLong[0] = BOS;
        tooLong[tooLong.length - 1] = EOS;
        final int[] mask = new int[tooLong.length];
        Arrays.fill(mask, 1);
        invalid(() -> KnowledgeEmbeddingTensorContract.fromTokenizer(
                tooLong, mask, PAD, BOS, EOS, false));
    }

    @Test
    public void maskedMeanPoolingExcludesPaddingAndNormalizes() {
        final KnowledgeEmbeddingTensorContract.Inputs input = syntheticInputs();
        final float[][][] hidden = new float[1][input.sequenceLength()][8];
        for (int t = 0; t < input.activeTokens(); t++) {
            hidden[0][t][0] = 3;
            hidden[0][t][1] = 4;
        }
        hidden[0][4][0] = 90000;
        hidden[0][5][1] = -90000;
        assertArrayEquals(new float[]{.6f, .8f, 0, 0, 0, 0, 0, 0},
                KnowledgeEmbeddingTensorContract.poolAndNormalize(hidden, input, 8), .00001f);

        // Unequal content token vectors must be averaged, not CLS-selected.
        hidden[0][0][0] = 0;
        hidden[0][0][1] = 0;
        assertArrayEquals(new float[]{.6f, .8f, 0, 0, 0, 0, 0, 0},
                KnowledgeEmbeddingTensorContract.poolAndNormalize(hidden, input, 8), .00001f);
        hidden[0][2][0] = -6;
        hidden[0][2][1] = 0;
        final float[] vector = KnowledgeEmbeddingTensorContract.poolAndNormalize(hidden, input, 8);
        assertEquals(0.0, vector[0], .00001);
        assertEquals(1.0, vector[1], .00001);
    }

    @Test
    public void malformedOnnxOutputCannotBeNormalizedOrIndexed() {
        final KnowledgeEmbeddingTensorContract.Inputs input = syntheticInputs();
        final float[][][] valid = new float[1][input.sequenceLength()][8];
        valid[0][0][0] = 1;
        invalid(() -> KnowledgeEmbeddingTensorContract.poolAndNormalize(null, input, 8));
        invalid(() -> KnowledgeEmbeddingTensorContract.poolAndNormalize(valid, null, 8));
        invalid(() -> KnowledgeEmbeddingTensorContract.poolAndNormalize(valid, input, 7));
        invalid(() -> KnowledgeEmbeddingTensorContract.poolAndNormalize(valid, input, 4097));
        invalid(() -> KnowledgeEmbeddingTensorContract.poolAndNormalize(valid, input, 384));
        invalid(() -> KnowledgeEmbeddingTensorContract.poolAndNormalize(new float[2][6][8], input, 8));
        invalid(() -> KnowledgeEmbeddingTensorContract.poolAndNormalize(new float[1][5][8], input, 8));
        invalid(() -> KnowledgeEmbeddingTensorContract.poolAndNormalize(new float[1][6][], input, 8));
        valid[0][4][3] = Float.NaN; // Reject even in masked rows.
        invalid(() -> KnowledgeEmbeddingTensorContract.poolAndNormalize(valid, input, 8));
        valid[0][4][3] = 0;
        valid[0][2][3] = Float.POSITIVE_INFINITY;
        invalid(() -> KnowledgeEmbeddingTensorContract.poolAndNormalize(valid, input, 8));
        valid[0][2][3] = 0;
        valid[0][0][0] = 0;
        invalid(() -> KnowledgeEmbeddingTensorContract.poolAndNormalize(valid, input, 8));
    }
}
