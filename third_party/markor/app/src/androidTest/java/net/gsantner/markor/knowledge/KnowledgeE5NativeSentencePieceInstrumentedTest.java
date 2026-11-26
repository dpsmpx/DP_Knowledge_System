/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;

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
}
