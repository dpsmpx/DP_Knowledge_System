/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import static org.junit.Assert.*;

/**
 * CI-only native SentencePiece -> input_ids/mask -> synthetic Gather ->
 * masked mean/L2 integration. No real E5 weights, notes or downloads.
 */
@RunWith(AndroidJUnit4.class)
public final class KnowledgeE5TokenEmbeddingPipelineInstrumentedTest {
    @Test public void pinnedSyntheticTokenizerTo384DimensionalOrtPoolerIsDeterministic()
            throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final byte[] bpe = KnowledgeE5NativeSentencePieceInstrumentedTest.syntheticBpe();
        final File file = File.createTempFile("dpks-e5-chain-sp-", ".model", context.getCacheDir());
        try {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(bpe);
            }
            final KnowledgeEmbeddingTensorContract.Inputs raw = KnowledgeE5SentencePiece.encode(
                    context, file, bpe.length,
                    KnowledgeE5NativeSentencePieceInstrumentedTest.sha256(bpe),
                    KnowledgeE5Text.Purpose.QUERY, "hi");
            assertTrue(raw.sequenceLength() >= 3);
            assertTrue(raw.sequenceLength() <= 510);
            assertEquals(0, raw.inputIds()[0][0]);
            assertEquals(2, raw.inputIds()[0][raw.sequenceLength() - 1]);

            // Add two synthetic <pad>=1 tokens after EOS. Their lookup row
            // is deliberately 10000x larger than any active token row.
            final int active = raw.sequenceLength();
            final int[] ids = new int[active + 2];
            final int[] mask = new int[ids.length];
            final long[] encoded = raw.inputIds()[0];
            for (int i = 0; i < active; i++) {
                assertTrue("Toy tokenizer no longer fits the fixture table",
                        encoded[i] >= 0 && encoded[i] < KnowledgeE5TokenEmbeddingSmoke.TABLE_ROWS);
                ids[i] = (int) encoded[i];
                mask[i] = 1;
            }
            ids[active] = KnowledgeE5TokenEmbeddingSmoke.PAD_ID;
            ids[active + 1] = KnowledgeE5TokenEmbeddingSmoke.PAD_ID;
            final KnowledgeEmbeddingTensorContract.Inputs batch =
                    KnowledgeEmbeddingTensorContract.fromTokenizer(ids, mask,
                            KnowledgeE5TokenEmbeddingSmoke.PAD_ID, 0, 2, false);
            assertEquals(active, batch.activeTokens());
            assertEquals(0, batch.attentionMask()[0][active]);
            assertEquals(0, batch.attentionMask()[0][active + 1]);
            for (final long tokenType : batch.tokenTypeIds()[0]) assertEquals(0, tokenType);

            final int[] invalidMask = mask.clone();
            invalidMask[active] = 1;
            try {
                KnowledgeEmbeddingTensorContract.fromTokenizer(ids, invalidMask,
                        KnowledgeE5TokenEmbeddingSmoke.PAD_ID, 0, 2, false);
                fail("An active PAD row was accepted by the tensor contract");
            } catch (IllegalArgumentException expected) { }

            final byte[] graph = KnowledgeE5TokenEmbeddingSmoke.graph(batch.sequenceLength());
            final KnowledgeE5ConfigProfile.Result profile =
                    KnowledgeE5OnnxMetadataInstrumentedTest.profile();
            final KnowledgeE5OnnxMetadataProbe.Report declarations =
                    KnowledgeE5OnnxMetadataInstrumentedTest.staticMetadata(context, graph, profile);
            assertEquals(3, declarations.inputs);
            assertEquals(1, declarations.outputs);
            assertEquals(0, declarations.dynamicAxes);

            final OrtEnvironment environment = OrtEnvironment.getEnvironment();
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setIntraOpNumThreads(1);
                options.setInterOpNumThreads(1);
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
                try (OrtSession session = environment.createSession(graph, options)) {
                    final Map<String, KnowledgeE5OnnxSignature.Tensor> nativeInputs =
                            KnowledgeOnnxCpuBackend.signature(session.getInputInfo());
                    final Map<String, KnowledgeE5OnnxSignature.Tensor> nativeOutputs =
                            KnowledgeOnnxCpuBackend.signature(session.getOutputInfo());
                    KnowledgeE5OnnxSignature.requireE5Small(nativeInputs, nativeOutputs, profile);
                    assertTrue(KnowledgeE5OnnxSignature.sameDescriptors(
                            declarations.inputSignature, nativeInputs));
                    assertTrue(KnowledgeE5OnnxSignature.sameDescriptors(
                            declarations.outputSignature, nativeOutputs));

                    try (OnnxTensor inputIds = OnnxTensor.createTensor(environment, batch.inputIds());
                         OnnxTensor attention = OnnxTensor.createTensor(environment, batch.attentionMask());
                         OnnxTensor types = OnnxTensor.createTensor(environment, batch.tokenTypeIds())) {
                        final Map<String, OnnxTensor> named = new LinkedHashMap<>();
                        named.put("input_ids", inputIds);
                        named.put("attention_mask", attention);
                        named.put("token_type_ids", types);
                        try (OrtSession.Result result = session.run(named)) {
                            assertEquals(1, result.size());
                            assertTrue(result.get(0).getValue() instanceof float[][][]);
                            final float[][][] hidden = (float[][][]) result.get(0).getValue();
                            assertEquals(10000f, hidden[0][active][0], 0f);
                            assertEquals(10000f, hidden[0][active + 1][0], 0f);
                            final float[] actual = KnowledgeEmbeddingTensorContract.poolAndNormalize(
                                    hidden, batch, KnowledgeE5TokenEmbeddingSmoke.DIMENSIONS);
                            final float[] reference = KnowledgeE5TokenEmbeddingSmoke.expectedVector(batch);
                            assertEquals(384, actual.length);
                            for (int i = 0; i < actual.length; i++) {
                                assertEquals("Synthetic E5 component " + i, reference[i],
                                        actual[i], 0.00001f);
                            }
                        }
                    }

                    // The reusable, name-aware runner must independently
                    // produce the same result and close its native tensors.
                    final float[] throughRunner = KnowledgeE5OrtTensorRunner.run(
                            environment, session, batch, profile);
                    final float[] reference = KnowledgeE5TokenEmbeddingSmoke.expectedVector(batch);
                    for (int i = 0; i < reference.length; i++) {
                        assertEquals("Named E5 runner component " + i,
                                reference[i], throughRunner[i], 0.00001f);
                    }
                }
            }
        } finally {
            assertTrue("Toy SentencePiece fixture was not removed", file.delete());
        }
    }

    @Test public void optionalTokenTypesAndPoolerOutputDoNotAffectEmbedding() throws Exception {
        final KnowledgeEmbeddingTensorContract.Inputs batch =
                KnowledgeEmbeddingTensorContract.fromTokenizer(
                        new int[]{0, 3, 5, 2, 1, 1}, new int[]{1, 1, 1, 1, 0, 0},
                        1, 0, 2, false);
        final byte[] graph = KnowledgeE5TokenEmbeddingSmoke.graph(
                batch.sequenceLength(), false, true);
        final KnowledgeE5ConfigProfile.Result profile =
                KnowledgeE5OnnxMetadataInstrumentedTest.profile();
        final OrtEnvironment environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
            try (OrtSession session = environment.createSession(graph, options)) {
                assertEquals(2, session.getNumInputs());
                assertEquals(2, session.getNumOutputs());
                KnowledgeE5OnnxSignature.requireE5Small(
                        KnowledgeOnnxCpuBackend.signature(session.getInputInfo()),
                        KnowledgeOnnxCpuBackend.signature(session.getOutputInfo()), profile);
                final float[] actual = KnowledgeE5OrtTensorRunner.run(
                        environment, session, batch, profile);
                final float[] expected = KnowledgeE5TokenEmbeddingSmoke.expectedVector(batch);
                for (int i = 0; i < expected.length; i++) {
                    assertEquals("Two-input E5 component " + i,
                            expected[i], actual[i], 0.00001f);
                }
            }
        }
    }

    @Test public void runnerRejectsShapeAndTokenizerErrorsBeforeNativeRun() throws Exception {
        final OrtEnvironment environment = OrtEnvironment.getEnvironment();
        final KnowledgeE5ConfigProfile.Result profile =
                KnowledgeE5OnnxMetadataInstrumentedTest.profile();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
            try (OrtSession session = environment.createSession(
                    KnowledgeE5TokenEmbeddingSmoke.graph(5), options)) {
                final KnowledgeEmbeddingTensorContract.Inputs wrongLength =
                        KnowledgeEmbeddingTensorContract.fromTokenizer(
                                new int[]{0, 3, 5, 2, 1, 1}, new int[]{1, 1, 1, 1, 0, 0},
                                1, 0, 2, false);
                expectRunnerRefusal(environment, session, wrongLength, profile);

                final KnowledgeEmbeddingTensorContract.Inputs wrongBos =
                        KnowledgeEmbeddingTensorContract.fromTokenizer(
                                new int[]{4, 3, 5, 6, 1}, new int[]{1, 1, 1, 1, 0},
                                1, 4, 6, false);
                expectRunnerRefusal(environment, session, wrongBos, profile);

                final KnowledgeEmbeddingTensorContract.Inputs excessiveId =
                        KnowledgeEmbeddingTensorContract.fromTokenizer(
                                new int[]{0, KnowledgeE5ConfigProfile.VOCAB_SIZE, 2, 1, 1},
                                new int[]{1, 1, 1, 0, 0}, 1, 0, 2, false);
                expectRunnerRefusal(environment, session, excessiveId, profile);

                final KnowledgeEmbeddingTensorContract.Inputs truncated =
                        KnowledgeEmbeddingTensorContract.fromTokenizer(
                                new int[]{0, 5, 2, 1, 1}, new int[]{1, 1, 1, 0, 0},
                                1, 0, 2, true);
                expectRunnerRefusal(environment, session, truncated, profile);
            }
        }
    }

    private static void expectRunnerRefusal(final OrtEnvironment environment,
                                            final OrtSession session,
                                            final KnowledgeEmbeddingTensorContract.Inputs batch,
                                            final KnowledgeE5ConfigProfile.Result profile)
            throws Exception {
        try {
            KnowledgeE5OrtTensorRunner.run(environment, session, batch, profile);
            fail("Invalid E5 batch was passed to native ORT");
        } catch (IllegalArgumentException expected) { }
    }
}
