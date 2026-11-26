/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.content.Context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * CI-only 384-channel E5 synthetic inference inside the actual isolated UID.
 * No installed model, external data, tokenized notes or IPC vector payload.
 */
final class KnowledgeE5IsolatedSmoke {
    private static final String CANONICAL_SYNTHETIC_CONFIG = "{"
            + "\"model_type\":\"bert\","
            + "\"tokenizer_class\":\"XLMRobertaTokenizer\","
            + "\"position_embedding_type\":\"absolute\","
            + "\"hidden_act\":\"gelu\","
            + "\"hidden_size\":384,"
            + "\"max_position_embeddings\":512,"
            + "\"vocab_size\":250037,"
            + "\"num_hidden_layers\":12,"
            + "\"num_attention_heads\":12,"
            + "\"intermediate_size\":1536,"
            + "\"type_vocab_size\":2,"
            + "\"pad_token_id\":0}";

    private KnowledgeE5IsolatedSmoke() { }

    static void verify(final Context workerContext, final OrtEnvironment environment,
                       final OrtSession.SessionOptions options,
                       final KnowledgeOnnxMemoryTrace.Recorder memory) throws Exception {
        if (workerContext == null || environment == null || options == null || memory == null
                || !KnowledgeOnnxProcessIdentity.isWorker()) {
            throw new IOException("E5 synthetic CPU smoke requires the isolated worker UID");
        }
        final KnowledgeE5ConfigProfile.Result profile = KnowledgeE5ConfigProfile.parse(
                CANONICAL_SYNTHETIC_CONFIG.getBytes(StandardCharsets.UTF_8), null);
        final KnowledgeEmbeddingTensorContract.Inputs batch =
                KnowledgeEmbeddingTensorContract.fromTokenizer(
                        new int[]{0, 3, 5, 2, 1, 1},
                        new int[]{1, 1, 1, 1, 0, 0}, 1, 0, 2, false);
        // Verify both E5 export signatures through the same runtime route.
        verifyVariant(workerContext, environment, options, memory, profile, batch,
                false, true);
        verifyVariant(workerContext, environment, options, memory, profile, batch,
                true, false);
    }

    private static void verifyVariant(final Context context, final OrtEnvironment environment,
                                      final OrtSession.SessionOptions options,
                                      final KnowledgeOnnxMemoryTrace.Recorder memory,
                                      final KnowledgeE5ConfigProfile.Result profile,
                                      final KnowledgeEmbeddingTensorContract.Inputs batch,
                                      final boolean hasTokenTypes, final boolean poolerOutput)
            throws Exception {
        KnowledgeOnnxWorkerMemory.requireAvailable(context, false);
        final byte[] syntheticModel = KnowledgeE5TokenEmbeddingSmoke.graph(
                batch.sequenceLength(), hasTokenTypes, poolerOutput);
        try {
            // A fresh actual worker RAM snapshot; estimate is diagnostic, not
            // a native allocation guarantee or a model-specific memory cap.
            final KnowledgeOnnxWorkerMemory.Snapshot available =
                    KnowledgeOnnxWorkerMemory.snapshot(context);
            final KnowledgeE5ResourceEstimate.Plan estimate =
                    KnowledgeE5ResourceEstimate.assess(syntheticModel.length, 0,
                            batch.sequenceLength(), hasTokenTypes,
                            available.availableBytes, available.lowMemory);
            if (estimate.memoryDenied || estimate.hiddenTensorBytes
                    != (long) batch.sequenceLength() * 384 * Float.BYTES) {
                throw new KnowledgeOnnxWorkerMemory.Denied();
            }
            try (OrtSession session = environment.createSession(syntheticModel, options)) {
                memory.observe(KnowledgeOnnxWorkerMemory.samplePssKiB());
                final KnowledgeE5OrtTensorRunner.StopSignal stop =
                        new KnowledgeE5OrtTensorRunner.StopSignal();
                final float[] actual = KnowledgeE5OrtTensorRunner.run(
                        environment, session, batch, profile, stop);
                final float[] expected = KnowledgeE5TokenEmbeddingSmoke.expectedVector(batch);
                if (actual.length != KnowledgeE5ConfigProfile.HIDDEN_SIZE
                        || expected.length != actual.length || stop.isCancelled()) {
                    throw new IOException("Isolated E5 synthetic vector outside 384-channel contract");
                }
                for (int i = 0; i < actual.length; i++) {
                    if (!Float.isFinite(actual[i]) || Math.abs(actual[i] - expected[i]) > .00001f) {
                        throw new IOException("Isolated E5 synthetic masked-mean/L2 mismatch");
                    }
                }
                memory.observe(KnowledgeOnnxWorkerMemory.samplePssKiB());
            }
        } finally {
            Arrays.fill(syntheticModel, (byte) 0);
        }
    }
}
