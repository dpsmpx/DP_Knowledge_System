/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.*;

public final class KnowledgeSentencePieceProbeTest {
    private static void varint(final ByteArrayOutputStream out, long number) {
        while ((number & ~127L) != 0) {
            out.write(((int) number & 127) | 128);
            number >>>= 7;
        }
        out.write((int) number);
    }

    private static void number(final ByteArrayOutputStream out, final int field, final long value) {
        varint(out, field << 3);
        varint(out, value);
    }

    private static void bytes(final ByteArrayOutputStream out, final int field,
                              final byte[] value) {
        varint(out, (field << 3) | 2);
        varint(out, value.length);
        out.write(value, 0, value.length);
    }

    private static void string(final ByteArrayOutputStream out, final int field,
                               final String value) {
        bytes(out, field, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] piece(final String name, final int type) {
        final ByteArrayOutputStream part = new ByteArrayOutputStream();
        string(part, 1, name);
        number(part, 3, type);
        return part.toByteArray();
    }

    static byte[] model(final int kind, final int normalizationFlag,
                        final boolean normalizerPresent, final boolean duplicateSpecial) {
        final ByteArrayOutputStream model = new ByteArrayOutputStream();
        bytes(model, 1, piece("<unk>", 2));
        bytes(model, 1, piece("<s>", 3));
        bytes(model, 1, piece("</s>", 3));
        bytes(model, 1, piece("▁hello", 1));
        if (duplicateSpecial) bytes(model, 1, piece("<s>", 3));
        final ByteArrayOutputStream trainer = new ByteArrayOutputStream();
        number(trainer, 3, kind);
        number(trainer, 4, duplicateSpecial ? 5 : 4);
        bytes(model, 2, trainer.toByteArray());
        if (normalizerPresent) {
            final ByteArrayOutputStream norm = new ByteArrayOutputStream();
            string(norm, 1, "nmt_nfkc");
            bytes(norm, 2, new byte[]{1, 2, 3});
            number(norm, 3, normalizationFlag);
            number(norm, 4, 1);
            number(norm, 5, 1);
            bytes(model, 3, norm.toByteArray());
        }
        // Unknown, safe protobuf extensions must be skippable.
        bytes(model, 200, new byte[]{1, 2, 3});
        return model.toByteArray();
    }

    private static KnowledgeSentencePieceProbe.Result inspect(final byte[] bytes)
            throws IOException {
        final File file = File.createTempFile("dpks-synthetic-sentencepiece-", ".model");
        try {
            Files.write(file.toPath(), bytes);
            return KnowledgeSentencePieceProbe.inspect(file, bytes.length);
        } finally {
            assertTrue(file.delete());
        }
    }

    private static void invalid(final byte[] bytes) throws Exception {
        try {
            inspect(bytes);
            fail("Malformed SentencePiece model was accepted");
        } catch (IOException expected) {
            // Only a bounded data preflight; not the native SentencePiece parser.
        }
    }

    @Test public void syntheticBpeReportsRawPieceIdsAndNormalization() throws Exception {
        final byte[] source = model(2, 1, true, false);
        final KnowledgeSentencePieceProbe.Result report = inspect(source);
        assertTrue(report.looksLikeBpe());
        assertEquals(2, report.modelType);
        assertEquals(4, report.pieces);
        assertEquals(4, report.declaredVocab);
        assertEquals(0, report.unknownPieceId);
        assertEquals(1, report.bosPieceId);
        assertEquals(2, report.eosPieceId);
        assertEquals(-1, report.padPieceId);
        assertEquals("nmt_nfkc", report.normalizerName);
        assertEquals(3, report.charsMapBytes);
        assertTrue(report.addDummyPrefix);
        assertTrue(report.removeExtraWhitespaces);
        assertTrue(report.escapeWhitespaces);
    }

    @Test public void modelTypesAndNormalizerFlagsAreNotGuessed() throws Exception {
        final KnowledgeSentencePieceProbe.Result unigram = inspect(model(1, 0, true, false));
        assertFalse(unigram.looksLikeBpe());
        assertFalse(unigram.addDummyPrefix);
        assertEquals(1, unigram.modelType);
        invalid(model(3, 1, true, false));
        invalid(model(2, 2, true, false));
        invalid(model(2, 1, false, false));
        invalid(model(2, 1, true, true));
    }

    @Test public void truncationUnknownWireAndLengthMismatchFailClosed() throws Exception {
        final byte[] original = model(2, 1, true, false);
        invalid(Arrays.copyOf(original, original.length - 1));
        final byte[] unsupported = Arrays.copyOf(original, original.length + 1);
        unsupported[unsupported.length - 1] = 0x0b; // Field 1, disallowed START_GROUP.
        invalid(unsupported);
        final File file = File.createTempFile("dpks-sp-size-", ".model");
        try {
            Files.write(file.toPath(), original);
            try {
                KnowledgeSentencePieceProbe.inspect(file, original.length + 1);
                fail("Mismatched declared sidecar size was accepted");
            } catch (IOException expected) { }
            try {
                KnowledgeSentencePieceProbe.inspect(file, KnowledgeSentencePieceProbe.MAX_BYTES + 1);
                fail("Oversized sidecar was accepted");
            } catch (IOException expected) { }
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test public void longOrMalformedNormalizerNamesCannotReachUi() throws Exception {
        final ByteArrayOutputStream model = new ByteArrayOutputStream();
        bytes(model, 1, piece("<unk>", 2));
        final ByteArrayOutputStream trainer = new ByteArrayOutputStream();
        number(trainer, 3, 2);
        bytes(model, 2, trainer.toByteArray());
        final ByteArrayOutputStream norm = new ByteArrayOutputStream();
        string(norm, 1, "nmt_nfkc\nspoofed status");
        bytes(model, 3, norm.toByteArray());
        invalid(model.toByteArray());
    }
}
