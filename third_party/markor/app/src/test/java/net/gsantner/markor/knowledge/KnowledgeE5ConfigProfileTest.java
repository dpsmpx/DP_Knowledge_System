/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public final class KnowledgeE5ConfigProfileTest {
    static byte[] model() throws Exception {
        return new JSONObject()
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
    }

    static byte[] tokenizer() throws Exception {
        return new JSONObject()
                .put("tokenizer_class", "XLMRobertaTokenizer")
                .put("bos_token", "<s>")
                .put("cls_token", "<s>")
                .put("eos_token", "</s>")
                .put("sep_token", "</s>")
                .put("pad_token", "<pad>")
                .put("unk_token", "<unk>")
                .put("model_max_length", 512)
                .put("sp_model_kwargs", new JSONObject())
                .toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] change(final byte[] initial, final String field,
                                 final Object value) throws Exception {
        return new JSONObject(new String(initial, StandardCharsets.UTF_8))
                .put(field, value).toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void reject(final byte[] config, final byte[] tokenizer) throws Exception {
        try {
            KnowledgeE5ConfigProfile.parse(config, tokenizer);
            fail("Incompatible E5 configuration was accepted");
        } catch (IOException expected) { }
    }

    @Test public void curatedProfileAndOptionalTokenizerConfigAreStrict() throws Exception {
        final KnowledgeE5ConfigProfile.Result modelOnly =
                KnowledgeE5ConfigProfile.parse(model(), null);
        assertEquals(384, modelOnly.hiddenSize);
        assertEquals(512, modelOnly.maxSequence);
        assertEquals(250037, modelOnly.vocabSize);
        // The BertModel config contains 0, even though XLM-R tokenizer <pad>=1.
        assertEquals(0, modelOnly.modelPadId);
        assertFalse(modelOnly.tokenizerConfigChecked);
        assertTrue(KnowledgeE5ConfigProfile.parse(model(), tokenizer()).tokenizerConfigChecked);
    }

    @Test public void wrongArchitectureDimensionsAndPaddingNeverBecomeE5() throws Exception {
        reject(change(model(), "hidden_size", 768), null);
        reject(change(model(), "hidden_size", 384.5), null);
        reject(change(model(), "model_type", "xlm-roberta"), null);
        reject(change(model(), "tokenizer_class", "BertTokenizer"), null);
        reject(change(model(), "max_position_embeddings", 1024), null);
        reject(change(model(), "pad_token_id", 1), null);
        reject(change(model(), "vocab_size", 250002), null);
        reject(change(model(), "num_attention_heads", 24), null);
        reject(model(), change(tokenizer(), "model_max_length", 1024));
        reject(model(), change(tokenizer(), "pad_token", "[PAD]"));
        reject(model(), change(tokenizer(), "sp_model_kwargs",
                new JSONObject().put("enable_sampling", true)));
    }

    @Test public void malformedAndUnboundedJsonAreRejected() throws Exception {
        reject(null, null);
        reject(new byte[]{'{', '"', (byte) 0xc3, (byte) 0x28, '}'}, null);
        reject(new byte[KnowledgeE5ConfigProfile.MAX_CONFIG_BYTES + 1], null);
        reject(model(), new byte[]{1, 2});
    }
}
