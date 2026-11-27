/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** Synthetic protobuf only: no downloaded E5 file, ONNX engine, or user notes. */
public final class KnowledgeE5OnnxMetadataProbeTest {
    private static void varint(final ByteArrayOutputStream out, long value) {
        while ((value & ~127L) != 0) {
            out.write((int) (value & 127) | 128);
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static void number(final ByteArrayOutputStream out, final int field,
                               final long value) {
        varint(out, field * 8L);
        varint(out, value);
    }

    private static void bytes(final ByteArrayOutputStream out, final int field,
                              final byte[] value) {
        varint(out, field * 8L + 2);
        varint(out, value.length);
        out.write(value, 0, value.length);
    }

    private static byte[] text(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] dimension(final long size) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (size < 0) bytes(out, 2, text("symbolic_axis"));
        else number(out, 1, size);
        return out.toByteArray();
    }

    private static byte[] valueInfo(final String name, final long element,
                                    final long... shape) {
        final ByteArrayOutputStream dimensions = new ByteArrayOutputStream();
        for (final long axis : shape) bytes(dimensions, 1, dimension(axis));
        final ByteArrayOutputStream tensor = new ByteArrayOutputStream();
        number(tensor, 1, element);
        bytes(tensor, 2, dimensions.toByteArray());
        final ByteArrayOutputStream type = new ByteArrayOutputStream();
        bytes(type, 1, tensor.toByteArray());
        final ByteArrayOutputStream value = new ByteArrayOutputStream();
        bytes(value, 1, text(name));
        bytes(value, 2, type.toByteArray());
        return value.toByteArray();
    }

    static byte[] model(final long channels, final boolean extraInput,
                        final boolean largeInitializer) {
        final ByteArrayOutputStream graph = new ByteArrayOutputStream();
        final ByteArrayOutputStream node = new ByteArrayOutputStream();
        bytes(node, 1, text("input_ids"));
        bytes(node, 2, text("last_hidden_state"));
        bytes(node, 4, text("Identity"));
        bytes(graph, 1, node.toByteArray());
        if (largeInitializer) {
            final ByteArrayOutputStream initializer = new ByteArrayOutputStream();
            bytes(initializer, 9, new byte[1024 * 1024]);
            bytes(graph, 5, initializer.toByteArray());
        }
        bytes(graph, 11, valueInfo("input_ids", 7, -1, -1));
        bytes(graph, 11, valueInfo("attention_mask", 7, -1, -1));
        if (extraInput) bytes(graph, 11, valueInfo("unexpected", 7, -1, -1));
        bytes(graph, 12, valueInfo("last_hidden_state", 1, -1, -1, channels));
        final ByteArrayOutputStream top = new ByteArrayOutputStream();
        number(top, 1, 9); // ModelProto.ir_version
        bytes(top, 7, graph.toByteArray());
        final ByteArrayOutputStream opset = new ByteArrayOutputStream();
        number(opset, 2, 17);
        bytes(top, 8, opset.toByteArray());
        return top.toByteArray();
    }

    private static KnowledgeE5OnnxMetadataProbe.Report inspect(final byte[] source)
            throws Exception {
        final File file = Files.createTempFile("dpks-e5-onnx-meta", ".onnx").toFile();
        try {
            Files.write(file.toPath(), source);
            return KnowledgeE5OnnxMetadataProbe.inspect(file, source.length,
                    KnowledgeE5ConfigProfile.parse(KnowledgeE5ConfigProfileTest.model(), null));
        } finally { assertTrue(file.delete()); }
    }

    private static void reject(final byte[] source) throws Exception {
        try {
            inspect(source);
            fail("Incompatible or malformed E5 graph declarations accepted");
        } catch (IOException expected) { }
    }

    @Test public void namedInt64InputsAndFloat384OutputAreAcceptedWithoutRuntime()
            throws Exception {
        final KnowledgeE5OnnxMetadataProbe.Report report = inspect(model(384, false, false));
        assertEquals(2, report.inputs);
        assertEquals(1, report.outputs);
        assertEquals(6, report.dynamicAxes);
        final Map<String, KnowledgeE5OnnxSignature.Tensor> expectedInputs =
                new LinkedHashMap<>();
        final Map<String, KnowledgeE5OnnxSignature.Tensor> expectedOutputs =
                new LinkedHashMap<>();
        expectedInputs.put("input_ids", new KnowledgeE5OnnxSignature.Tensor(
                KnowledgeE5OnnxSignature.DataType.INT64, -1, -1));
        expectedInputs.put("attention_mask", new KnowledgeE5OnnxSignature.Tensor(
                KnowledgeE5OnnxSignature.DataType.INT64, -1, -1));
        expectedOutputs.put("last_hidden_state", new KnowledgeE5OnnxSignature.Tensor(
                KnowledgeE5OnnxSignature.DataType.FLOAT32, -1, -1, 384));
        assertEquals("Independent protobuf scan must retain exact E5 metadata",
                KnowledgeE5OnnxSignature.descriptorSha256(expectedInputs, expectedOutputs),
                KnowledgeE5OnnxSignature.descriptorSha256(
                        report.inputSignature, report.outputSignature));
        final byte[] large = model(384, false, true);
        assertTrue(large.length > 1024 * 1024);
        assertEquals(large.length, inspect(large).modelBytes);
    }

    @Test public void incompatibleOutputsAndUnknownInputsFailClosed() throws Exception {
        reject(model(768, false, false));
        reject(model(384, true, false));
        final byte[] valid = model(384, false, false);
        reject(java.util.Arrays.copyOf(valid, valid.length - 1)); // Truncated opset.
        final byte[] malformed = valid.clone();
        malformed[0] = 0; // Invalid protobuf tag.
        reject(malformed);
    }

    @Test public void exactFileLengthAndCanonicalConfigAreRequired() throws Exception {
        final byte[] valid = model(384, false, false);
        final File file = Files.createTempFile("dpks-e5-wrong-length", ".onnx").toFile();
        try {
            Files.write(file.toPath(), valid);
            final KnowledgeE5ConfigProfile.Result profile = KnowledgeE5ConfigProfile.parse(
                    KnowledgeE5ConfigProfileTest.model(), null);
            try {
                KnowledgeE5OnnxMetadataProbe.inspect(file, valid.length + 1, profile);
                fail("Changed model length accepted");
            } catch (IOException expected) { }
            try {
                KnowledgeE5OnnxMetadataProbe.inspect(file, valid.length, null);
                fail("Missing E5 profile accepted");
            } catch (IOException expected) { }
        } finally { assertTrue(file.delete()); }
    }
}
