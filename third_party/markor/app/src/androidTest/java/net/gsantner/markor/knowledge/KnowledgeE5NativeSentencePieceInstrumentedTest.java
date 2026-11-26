/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** No user downloads, weights, private notes or network; native Android SP integration. */
@RunWith(AndroidJUnit4.class)
public final class KnowledgeE5NativeSentencePieceInstrumentedTest {
    private static void varint(final ByteArrayOutputStream out, long value) {
        while ((value & ~127L) != 0) {
            out.write(((int) value & 127) | 128);
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static void integer(final ByteArrayOutputStream out, final int field, final int value) {
        varint(out, field << 3);
        varint(out, value);
    }

    private static void bytes(final ByteArrayOutputStream out, final int field,
                              final byte[] value) {
        varint(out, (field << 3) | 2);
        varint(out, value.length);
        out.write(value, 0, value.length);
    }

    private static byte[] piece(final String text, final int type) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        bytes(out, 1, text.getBytes("UTF-8"));
        integer(out, 3, type);
        return out.toByteArray();
    }

    private static byte[] syntheticBpe() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        bytes(out, 1, piece("<unk>", 2));
        bytes(out, 1, piece("<s>", 3));
        bytes(out, 1, piece("</s>", 3));
        // A small alphabet is enough for the native BPE encoder to process
        // the synthetic E5 prefix and text; no learned model is shipped.
        final String[] alphabet = {"▁", ":", "a", "e", "g", "h", "i", "l",
                "o", "p", "q", "r", "s", "t", "u", "w", "y", "▁hi"};
        for (final String item : alphabet) bytes(out, 1, piece(item, 1));
        final ByteArrayOutputStream trainer = new ByteArrayOutputStream();
        integer(trainer, 3, 2); // BPE
        integer(trainer, 4, alphabet.length + 3);
        bytes(out, 2, trainer.toByteArray());
        final ByteArrayOutputStream normalizer = new ByteArrayOutputStream();
        bytes(normalizer, 1, "identity".getBytes("UTF-8"));
        integer(normalizer, 3, 1);
        integer(normalizer, 4, 1);
        integer(normalizer, 5, 1);
        bytes(out, 3, normalizer.toByteArray());
        return out.toByteArray();
    }

    @Test public void nativeSentencePieceEncodesPinnedSyntheticBpe() throws Exception {
        final byte[] model = syntheticBpe();
        final File file = File.createTempFile("dpks-qa-synthetic-", ".model",
                getInstrumentation().getTargetContext().getCacheDir());
        try {
            try (FileOutputStream stream = new FileOutputStream(file)) {
                stream.write(model);
            }
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(model);
            final StringBuilder sha = new StringBuilder(64);
            for (final byte b : digest) {
                sha.append(Character.forDigit((b >> 4) & 15, 16));
                sha.append(Character.forDigit(b & 15, 16));
            }
            final KnowledgeEmbeddingTensorContract.Inputs input =
                    KnowledgeE5SentencePiece.encode(getInstrumentation().getTargetContext(),
                            file, model.length, sha.toString(), KnowledgeE5Text.Purpose.QUERY, "hi");
            assertTrue(input.sequenceLength() > 3);
            assertTrue(input.sequenceLength() <= 512);
            assertEquals(0, input.inputIds()[0][0]);
            assertEquals(2, input.inputIds()[0][input.sequenceLength() - 1]);
            for (final long bit : input.attentionMask()[0]) assertEquals(1, bit);
            for (final long id : input.inputIds()[0]) assertTrue(id >= 0);
            try {
                KnowledgeE5SentencePiece.encode(getInstrumentation().getTargetContext(),
                        file, model.length,
                        "0000000000000000000000000000000000000000000000000000000000000000",
                        KnowledgeE5Text.Purpose.QUERY, "hi");
                fail("Tampered SHA-256 accepted");
            } catch (java.io.IOException expected) {
                // File is intentionally unchanged; caller's pinned digest fails.
            }
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test public void manualGoldenQaUsesOnlyManifestPinnedSyntheticTokenizer() throws Exception {
        final File directory = File.createTempFile("dpks-golden-qa-", ".temp",
                getInstrumentation().getTargetContext().getCacheDir());
        assertTrue(directory.delete());
        assertTrue(directory.mkdir());
        final KnowledgeModelFiles files = new KnowledgeModelFiles(directory);
        final KnowledgeModelPackageStore packages = new KnowledgeModelPackageStore(directory, files);
        KnowledgeModelFiles.Entry entry = null;
        try {
            final byte[] weight = new byte[48]; // Never a real ONNX model.
            final byte[] tokenizer = syntheticBpe();
            // Canonical metadata paired with toy weights/tokenizer: tests the gate,
            // never claims that this tiny synthetic tokenizer is real E5.
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
                    .put("pad_token_id", 0).toString().getBytes(StandardCharsets.UTF_8);
            final byte[] tokenizerConfig = new JSONObject()
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
            entry = files.importFile(new ByteArrayInputStream(weight), "model.onnx",
                    KnowledgeModelPolicy.Role.EMBEDDING);
            final JSONObject manifest = new JSONObject()
                    .put("schema_version", 1)
                    .put("role", "EMBEDDING")
                    .put("format", "ONNX_UNVERIFIED")
                    .put("weight_sha256", entry.sha256)
                    .put("source_id", "intfloat/multilingual-e5-small")
                    .put("revision", "sha256:" + entry.sha256)
                    .put("license", "MIT");
            final JSONArray companions = new JSONArray();
            companions.put(new JSONObject().put("kind", "WEIGHTS")
                    .put("name", "model.onnx").put("sha256", entry.sha256)
                    .put("bytes", entry.bytes));
            companions.put(new JSONObject().put("kind", "TOKENIZER")
                    .put("name", "sentencepiece.bpe.model")
                    .put("sha256", sha256(tokenizer)).put("bytes", tokenizer.length));
            companions.put(new JSONObject().put("kind", "MODEL_CONFIG")
                    .put("name", "config.json").put("sha256", sha256(config))
                    .put("bytes", config.length));
            companions.put(new JSONObject().put("kind", "MODEL_CONFIG")
                    .put("name", "tokenizer_config.json").put("sha256", sha256(tokenizerConfig))
                    .put("bytes", tokenizerConfig.length));
            manifest.put("files", companions);
            packages.install(manifest.toString().getBytes(StandardCharsets.UTF_8),
                    Arrays.asList(
                            new KnowledgeModelPackageStore.Source("sentencepiece.bpe.model",
                                    () -> new ByteArrayInputStream(tokenizer)),
                            new KnowledgeModelPackageStore.Source("config.json",
                                    () -> new ByteArrayInputStream(config)),
                            new KnowledgeModelPackageStore.Source("tokenizer_config.json",
                                    () -> new ByteArrayInputStream(tokenizerConfig))), entry);
            final KnowledgeE5GoldenQa.Report result = KnowledgeE5GoldenQa.run(
                    getInstrumentation().getTargetContext(), packages, entry);
            assertEquals(sha256(tokenizer), result.modelSha256);
            assertEquals(64, result.fingerprintSha256.length());
            assertEquals(4, result.counts.length);
            for (final int tokens : result.counts) assertTrue(tokens >= 3);
            assertFalse(KnowledgeModelPackageContract.inspect(entry,
                    packages.installed(entry)).runnable());
        } finally {
            if (entry != null) {
                packages.forget(entry);
                files.delete(entry);
            }
            assertTrue(directory.delete());
        }
    }

    private static String sha256(final byte[] bytes) throws Exception {
        final byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        final StringBuilder hex = new StringBuilder();
        for (final byte item : hash) {
            hex.append(Character.forDigit((item >>> 4) & 15, 16));
            hex.append(Character.forDigit(item & 15, 16));
        }
        return hex.toString();
    }
}
