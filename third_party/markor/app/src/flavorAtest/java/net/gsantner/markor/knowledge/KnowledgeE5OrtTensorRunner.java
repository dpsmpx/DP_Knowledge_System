/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Experimental MODEL-M3 named tensor runner, compiled only in flavorAtest.
 * Callers must provide an already-open session and validated in-memory input;
 * this class NEVER finds model files, grants model readiness or reads notes.
 * Current callers use only generated, weight-free ONNX graphs in CI.
 */
final class KnowledgeE5OrtTensorRunner {
    private static final String HIDDEN = "last_hidden_state";

    private KnowledgeE5OrtTensorRunner() { }

    static float[] run(final OrtEnvironment environment, final OrtSession session,
                       final KnowledgeEmbeddingTensorContract.Inputs batch,
                       final KnowledgeE5ConfigProfile.Result profile) throws Exception {
        if (environment == null || session == null || batch == null
                || batch.tokenizerTruncated) {
            throw new IllegalArgumentException("Missing E5 runtime input or truncated tokens");
        }
        checkCancelled();
        final Map<String, KnowledgeE5OnnxSignature.Tensor> inputs =
                KnowledgeOnnxCpuBackend.signature(session.getInputInfo());
        final Map<String, KnowledgeE5OnnxSignature.Tensor> outputs =
                KnowledgeOnnxCpuBackend.signature(session.getOutputInfo());
        KnowledgeE5OnnxSignature.requireE5Small(inputs, outputs, profile);
        // A declared fixed sequence must match the actual batch, even when
        // the other graph axes are dynamic. Never send a mismatched input to ORT.
        requireSequence(inputs, batch.sequenceLength());
        requireSequence(outputs, batch.sequenceLength());
        requireCanonicalTokens(batch);

        try (OnnxTensor ids = OnnxTensor.createTensor(environment, batch.inputIds());
             OnnxTensor mask = OnnxTensor.createTensor(environment, batch.attentionMask());
             OnnxTensor types = inputs.containsKey("token_type_ids")
                     ? OnnxTensor.createTensor(environment, batch.tokenTypeIds()) : null) {
            final Map<String, OnnxTensor> named = new LinkedHashMap<>();
            named.put("input_ids", ids);
            named.put("attention_mask", mask);
            if (types != null) named.put("token_type_ids", types);
            checkCancelled();
            // Optional pooler_output must not be used for embeddings or
            // transferred back to Java; ask ORT for the named hidden state only.
            try (OrtSession.Result result = session.run(named, Collections.singleton(HIDDEN))) {
                final Optional<OnnxValue> value = result.get(HIDDEN);
                if (result.size() != 1 || !value.isPresent()
                        || !(value.get().getValue() instanceof float[][][])) {
                    throw new IOException("E5 hidden-state output has unexpected rank/type");
                }
                final float[] vector = KnowledgeEmbeddingTensorContract.poolAndNormalize(
                        (float[][][]) value.get().getValue(), batch, profile.hiddenSize);
                checkCancelled();
                return vector;
            }
        }
    }

    private static void requireSequence(
            final Map<String, KnowledgeE5OnnxSignature.Tensor> tensors,
            final int sequence) {
        for (final Map.Entry<String, KnowledgeE5OnnxSignature.Tensor> item : tensors.entrySet()) {
            // A pooled [1,384] output has no sequence axis.
            if (item.getKey().equals("pooler_output")) continue;
            final long[] shape = item.getValue().shape();
            if (shape.length < 2 || (shape[1] != -1 && shape[1] != sequence)) {
                throw new IllegalArgumentException("E5 ONNX fixed sequence disagrees with batch");
            }
        }
    }

    private static void requireCanonicalTokens(
            final KnowledgeEmbeddingTensorContract.Inputs batch) {
        final long[] ids = batch.inputIds()[0];
        final long[] mask = batch.attentionMask()[0];
        if (ids[0] != 0 || ids[batch.activeTokens() - 1] != 2) {
            throw new IllegalArgumentException("E5 requires XLM-R BOS/EOS IDs");
        }
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] < 0 || ids[i] >= KnowledgeE5ConfigProfile.VOCAB_SIZE
                    || (mask[i] == 0 && ids[i] != 1)
                    || (mask[i] == 1 && ids[i] == 1)) {
                throw new IllegalArgumentException("Non-canonical E5 token or padding ID");
            }
        }
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Synthetic E5 ONNX run cancelled");
        }
    }
}
