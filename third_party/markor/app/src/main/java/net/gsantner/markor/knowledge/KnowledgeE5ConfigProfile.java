/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Strict, read-only MODEL-M3 E5 Small configuration gate. User-declared
 * source/revision remain assertions; this does not authorize model inference.
 *
 * Note the actual upstream BERT config has pad_token_id=0 even though the
 * slow XLM-R tokenizer assigns <pad>=1. These are distinct declarations.
 */
final class KnowledgeE5ConfigProfile {
    static final int MAX_CONFIG_BYTES = 64 * 1024;
    static final int HIDDEN_SIZE = 384;
    static final int MAX_TOKENS = 512;
    static final int VOCAB_SIZE = 250037;

    static final class Result {
        final int hiddenSize;
        final int maxSequence;
        final int vocabSize;
        final int modelPadId;
        final boolean tokenizerConfigChecked;

        private Result(final boolean tokenizerChecked) {
            hiddenSize = HIDDEN_SIZE;
            maxSequence = MAX_TOKENS;
            vocabSize = VOCAB_SIZE;
            modelPadId = 0;
            tokenizerConfigChecked = tokenizerChecked;
        }
    }

    private KnowledgeE5ConfigProfile() { }

    static Result parse(final byte[] modelConfig, final byte[] tokenizerConfig)
            throws IOException {
        final JSONObject model = object(modelConfig);
        text(model, "model_type", "bert");
        text(model, "tokenizer_class", "XLMRobertaTokenizer");
        text(model, "position_embedding_type", "absolute");
        text(model, "hidden_act", "gelu");
        number(model, "hidden_size", HIDDEN_SIZE);
        number(model, "max_position_embeddings", MAX_TOKENS);
        number(model, "vocab_size", VOCAB_SIZE);
        number(model, "num_hidden_layers", 12);
        number(model, "num_attention_heads", 12);
        number(model, "intermediate_size", 1536);
        number(model, "type_vocab_size", 2);
        // This is deliberately NOT compared to XLM-R tokenizer's <pad>=1.
        number(model, "pad_token_id", 0);

        if (tokenizerConfig != null) {
            final JSONObject tokenizer = object(tokenizerConfig);
            text(tokenizer, "tokenizer_class", "XLMRobertaTokenizer");
            text(tokenizer, "bos_token", "<s>");
            text(tokenizer, "cls_token", "<s>");
            text(tokenizer, "eos_token", "</s>");
            text(tokenizer, "sep_token", "</s>");
            text(tokenizer, "pad_token", "<pad>");
            text(tokenizer, "unk_token", "<unk>");
            number(tokenizer, "model_max_length", MAX_TOKENS);
            try {
                if (!(tokenizer.get("sp_model_kwargs") instanceof JSONObject)
                        || ((JSONObject) tokenizer.get("sp_model_kwargs")).length() != 0) {
                    throw new IOException("Non-default E5 SentencePiece options");
                }
            } catch (JSONException invalid) {
                throw new IOException("Missing E5 tokenizer options", invalid);
            }
        }
        return new Result(tokenizerConfig != null);
    }

    private static JSONObject object(final byte[] utf8) throws IOException {
        if (utf8 == null || utf8.length < 2 || utf8.length > MAX_CONFIG_BYTES) {
            throw new IOException("E5 configuration exceeds bounded input contract");
        }
        try {
            final String content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8)).toString();
            return new JSONObject(content);
        } catch (CharacterCodingException | JSONException invalid) {
            throw new IOException("Malformed E5 JSON configuration", invalid);
        }
    }

    private static void number(final JSONObject json, final String key,
                               final int expected) throws IOException {
        try {
            final Object value = json.get(key);
            if (!(value instanceof Number) || !Integer.toString(expected).equals(value.toString())) {
                throw new IOException("Unexpected E5 numeric field: " + key);
            }
        } catch (JSONException invalid) {
            throw new IOException("Missing E5 numeric field: " + key, invalid);
        }
    }

    private static void text(final JSONObject json, final String key,
                             final String expected) throws IOException {
        try {
            final Object value = json.get(key);
            if (!(value instanceof String) || !expected.equals(value)) {
                throw new IOException("Unexpected E5 config string: " + key);
            }
        } catch (JSONException invalid) {
            throw new IOException("Missing E5 config string: " + key, invalid);
        }
    }
}
