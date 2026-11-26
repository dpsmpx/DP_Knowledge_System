/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import static org.junit.Assert.*;

/**
 * CI-only agreement test on generated Gather graphs, not actual E5 weights.
 * Runtime metadata inspection occurs without session.run() or note access.
 */
@RunWith(AndroidJUnit4.class)
public final class KnowledgeE5OnnxMetadataInstrumentedTest {
    static KnowledgeE5ConfigProfile.Result profile() throws Exception {
        final byte[] config = new JSONObject()
                .put("model_type", "bert")
                .put("tokenizer_class", "XLMRobertaTokenizer")
                .put("position_embedding_type", "absolute")
                .put("hidden_act", "gelu")
                .put("hidden_size", 384)
                .put("max_position_embeddings", 512)
                .put("vocab_size", 250037)
                .put("num_hidden_layers", 12)
                .put("num_attention_heads", 12)
                .put("intermediate_size", 1536)
                .put("type_vocab_size", 2)
                .put("pad_token_id", 0)
                .toString().getBytes(StandardCharsets.UTF_8);
        return KnowledgeE5ConfigProfile.parse(config, null);
    }

    static KnowledgeE5OnnxMetadataProbe.Report staticMetadata(
            final Context context, final byte[] bytes,
            final KnowledgeE5ConfigProfile.Result profile) throws Exception {
        final File file = File.createTempFile("dpks-e5-onnx-meta-", ".onnx",
                context.getCacheDir());
        try {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(bytes);
            }
            final KnowledgeModelContainerProbe.Result generic =
                    KnowledgeModelContainerProbe.inspect(file,
                            KnowledgeModelFiles.Format.ONNX_UNVERIFIED);
            assertEquals(KnowledgeModelContainerProbe.State.METADATA_HINTS, generic.state);
            assertEquals(KnowledgeModelContainerProbe.DetailState.DECLARATIONS_READ,
                    generic.detailState);
            assertEquals(4, generic.graphNodes);
            return KnowledgeE5OnnxMetadataProbe.inspect(file, bytes.length, profile);
        } finally {
            assertTrue("Synthetic ONNX cache file was not removed", file.delete());
        }
    }

    @Test public void independentProtobufAndOrtMetadataAgreeFor384Channels()
            throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final byte[] generated = KnowledgeOnnxEmbeddingSmoke.gatherModel(384);
        assertTrue(generated.length < 20 * 1024);
        final KnowledgeE5ConfigProfile.Result config = profile();
        final KnowledgeE5OnnxMetadataProbe.Report parsed =
                staticMetadata(context, generated, config);
        assertEquals(3, parsed.inputs);
        assertEquals(1, parsed.outputs);
        assertEquals(0, parsed.dynamicAxes);

        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
            try (OrtSession session = OrtEnvironment.getEnvironment().createSession(
                    generated, options)) {
                // This deliberately never calls session.run(). It checks the
                // independent native metadata interpretation only.
                final java.util.Map<String, KnowledgeE5OnnxSignature.Tensor> nativeInputs =
                        KnowledgeOnnxCpuBackend.signature(session.getInputInfo());
                final java.util.Map<String, KnowledgeE5OnnxSignature.Tensor> nativeOutputs =
                        KnowledgeOnnxCpuBackend.signature(session.getOutputInfo());
                KnowledgeE5OnnxSignature.requireE5Small(
                        nativeInputs, nativeOutputs, config);
                assertEquals(parsed.inputs, session.getNumInputs());
                assertEquals(parsed.outputs, session.getNumOutputs());
                assertTrue("Protobuf and ORT input names/types/axes disagree",
                        KnowledgeE5OnnxSignature.sameDescriptors(
                                parsed.inputSignature, nativeInputs));
                assertTrue("Protobuf and ORT output names/types/axes disagree",
                        KnowledgeE5OnnxSignature.sameDescriptors(
                                parsed.outputSignature, nativeOutputs));
            }
        }
    }

    @Test public void bothReadersRejectSynthetic768ChannelGraphAsE5Small()
            throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final byte[] generated = KnowledgeOnnxEmbeddingSmoke.gatherModel(768);
        final KnowledgeE5ConfigProfile.Result config = profile();
        try {
            staticMetadata(context, generated, config);
            fail("Static ONNX parser accepted a 768-channel E5 Small output");
        } catch (IOException expected) { }

        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
            try (OrtSession session = OrtEnvironment.getEnvironment().createSession(
                    generated, options)) {
                try {
                    KnowledgeE5OnnxSignature.requireE5Small(
                            KnowledgeOnnxCpuBackend.signature(session.getInputInfo()),
                            KnowledgeOnnxCpuBackend.signature(session.getOutputInfo()), config);
                    fail("ORT metadata gate accepted a 768-channel E5 Small output");
                } catch (IllegalArgumentException expected) { }
            }
        }
    }
}
