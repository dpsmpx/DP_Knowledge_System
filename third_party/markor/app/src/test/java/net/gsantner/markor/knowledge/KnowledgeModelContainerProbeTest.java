/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public final class KnowledgeModelContainerProbeTest {
    @Test public void readsGgufVersionTensorAndSafeArchitectureHints() throws Exception {
        final ByteArrayOutputStream data = newGguf(3, 2, 2);
        ggufString(data, "general.architecture");
        u32(data, 8);
        ggufString(data, "llama");
        ggufString(data, "tokenizer.ggml.model");
        u32(data, 8);
        ggufString(data, "gpt2");
        ggufTensorInfo(data, "blk.0.attn", new long[]{16, 16}, 2, 0);
        ggufTensorInfo(data, "output", new long[]{128}, 2, 32);
        ggufTensorBytes(data, 32, 64);
        final KnowledgeModelContainerProbe.Result result = probe(data.toByteArray(),
                KnowledgeModelFiles.Format.GGUF);
        assertEquals(KnowledgeModelContainerProbe.State.METADATA_HINTS, result.state);
        assertEquals(3, result.version);
        assertEquals(2, result.tensorCount);
        assertEquals(2, result.metadataCount);
        assertEquals("llama", result.architecture);
        assertEquals("gpt2", result.tokenizer);
        assertEquals(KnowledgeModelContainerProbe.DetailState.DECLARATIONS_READ,
                result.detailState);
        assertEquals(2, result.tensorDescriptors);
    }

    @Test public void ggufMalformedAndUnboundedArrayNeverReachRuntimeValidation() throws Exception {
        final byte[] bad = new byte[64];
        assertEquals(KnowledgeModelContainerProbe.State.INVALID, probe(bad,
                KnowledgeModelFiles.Format.GGUF).state);
        final ByteArrayOutputStream huge = newGguf(3, 1, 2);
        ggufString(huge, "general.architecture");
        u32(huge, 8);
        ggufString(huge, "qwen3");
        ggufString(huge, "tokenizer.ggml.tokens");
        u32(huge, 9);
        u32(huge, 8);
        u64(huge, 1_000_000); // synthetic hostile array length, no allocation
        final KnowledgeModelContainerProbe.Result limited = probe(huge.toByteArray(),
                KnowledgeModelFiles.Format.GGUF);
        assertEquals(KnowledgeModelContainerProbe.State.PARTIAL, limited.state);
        assertEquals("qwen3", limited.architecture);
        assertEquals(1, limited.tensorCount);
        final ByteArrayOutputStream cut = newGguf(3, 1, 1);
        ggufString(cut, "general.architecture");
        u32(cut, 8);
        u64(cut, 5); // missing actual architecture bytes
        assertEquals(KnowledgeModelContainerProbe.State.INVALID, probe(cut.toByteArray(),
                KnowledgeModelFiles.Format.GGUF).state);
    }

    @Test public void ggufHintCannotInjectNewlineOrFakeState() throws Exception {
        final ByteArrayOutputStream data = newGguf(2, 1, 1);
        ggufString(data, "general.architecture");
        u32(data, 8);
        ggufString(data, "llama\nRUNTIME_PASS");
        ggufTensorInfo(data, "weight", new long[]{8}, 0, 0);
        ggufTensorBytes(data, 32, 64);
        final KnowledgeModelContainerProbe.Result result = probe(data.toByteArray(),
                KnowledgeModelFiles.Format.GGUF);
        assertEquals(KnowledgeModelContainerProbe.State.METADATA_HINTS, result.state);
        assertEquals("", result.architecture);
        assertEquals(2, result.version);
    }

    @Test public void recognizesOnnxTopLevelIrGraphAndOpsetDeclarationsOnly() throws Exception {
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        varint(data, 8); // ModelProto.ir_version, field 1
        varint(data, 9);
        varint(data, 58); // ModelProto.graph, field 7
        final ByteArrayOutputStream graph = new ByteArrayOutputStream();
        final ByteArrayOutputStream node = new ByteArrayOutputStream();
        varint(node, 34); // NodeProto.op_type, field 4
        protobufString(node, "MatMul");
        varint(graph, 10); // GraphProto.node, field 1
        protobufBytes(graph, node.toByteArray());
        final ByteArrayOutputStream input = new ByteArrayOutputStream();
        varint(input, 10); // ValueInfoProto.name, field 1
        protobufString(input, "input_ids");
        varint(graph, 90); // GraphProto.input, field 11
        protobufBytes(graph, input.toByteArray());
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        varint(output, 10);
        protobufString(output, "output");
        varint(graph, 98); // GraphProto.output, field 12
        protobufBytes(graph, output.toByteArray());
        protobufBytes(data, graph.toByteArray());
        varint(data, 66); // ModelProto.opset_import, field 8
        varint(data, 2);
        varint(data, 16); // OperatorSetIdProto.version, field 2
        varint(data, 17);
        final KnowledgeModelContainerProbe.Result result = probe(data.toByteArray(),
                KnowledgeModelFiles.Format.ONNX_UNVERIFIED);
        assertEquals(KnowledgeModelContainerProbe.State.METADATA_HINTS, result.state);
        assertEquals(9, result.version);
        assertTrue(result.graphDeclared);
        assertEquals(1, result.opsetDeclarations);
        assertEquals("", result.architecture);
        assertEquals(KnowledgeModelContainerProbe.DetailState.DECLARATIONS_READ,
                result.detailState);
        assertEquals(1, result.graphNodes);
        assertEquals(1, result.graphInputs);
        assertEquals(1, result.graphOutputs);
        assertEquals("MatMul", result.operatorHints);
    }

    @Test public void ggufRejectsMisalignedOrDuplicateTensorDescriptor() throws Exception {
        final ByteArrayOutputStream misaligned = newGguf(3, 1, 0);
        ggufTensorInfo(misaligned, "weight", new long[]{12}, 2, 3);
        ggufTensorBytes(misaligned, 32, 64);
        final KnowledgeModelContainerProbe.Result badOffset = probe(misaligned.toByteArray(),
                KnowledgeModelFiles.Format.GGUF);
        assertEquals(KnowledgeModelContainerProbe.State.INVALID, badOffset.state);

        final ByteArrayOutputStream duplicate = newGguf(3, 2, 0);
        ggufTensorInfo(duplicate, "weight", new long[]{12}, 2, 0);
        ggufTensorInfo(duplicate, "weight", new long[]{12}, 2, 32);
        ggufTensorBytes(duplicate, 32, 64);
        final KnowledgeModelContainerProbe.Result badNames = probe(duplicate.toByteArray(),
                KnowledgeModelFiles.Format.GGUF);
        assertEquals(KnowledgeModelContainerProbe.State.INVALID, badNames.state);
    }

    @Test public void truncatedOnnxGraphFieldCannotReportDeclarationsRead() throws Exception {
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        varint(data, 8);
        varint(data, 9);
        varint(data, 58);
        varint(data, 4); // A malformed GraphProto node length within declared graph.
        data.write(10);
        data.write(5);
        data.write(34);
        data.write(0);
        final KnowledgeModelContainerProbe.Result result = probe(data.toByteArray(),
                KnowledgeModelFiles.Format.ONNX_UNVERIFIED);
        assertEquals(KnowledgeModelContainerProbe.State.INVALID, result.state);
        assertEquals(KnowledgeModelContainerProbe.DetailState.INVALID, result.detailState);
    }

    @Test public void invalidOnnxProtobufCannotClaimMetadataHints() throws Exception {
        final byte[] fake = new byte[48]; // Existing .onnx shelf only checks size.
        assertEquals(KnowledgeModelContainerProbe.State.INVALID, probe(fake,
                KnowledgeModelFiles.Format.ONNX_UNVERIFIED).state);
        final ByteArrayOutputStream missingOpset = new ByteArrayOutputStream();
        varint(missingOpset, 8);
        varint(missingOpset, 8);
        varint(missingOpset, 58);
        varint(missingOpset, 1);
        missingOpset.write(0);
        assertEquals(KnowledgeModelContainerProbe.State.INVALID,
                probe(missingOpset.toByteArray(), KnowledgeModelFiles.Format.ONNX_UNVERIFIED).state);

        final ByteArrayOutputStream badLength = new ByteArrayOutputStream();
        varint(badLength, 8);
        varint(badLength, 8);
        varint(badLength, 58);
        varint(badLength, 9_000_000); // exceeds actual content length
        assertEquals(KnowledgeModelContainerProbe.State.INVALID,
                probe(badLength.toByteArray(), KnowledgeModelFiles.Format.ONNX_UNVERIFIED).state);
        final ByteArrayOutputStream illegalWire = new ByteArrayOutputStream();
        varint(illegalWire, 11); // field 1, wire=3, unsupported group
        assertEquals(KnowledgeModelContainerProbe.State.INVALID,
                probe(illegalWire.toByteArray(), KnowledgeModelFiles.Format.ONNX_UNVERIFIED).state);
    }

    @Test public void interruptionPropagatesWithoutRunningAContainer() throws Exception {
        final File folder = Files.createTempDirectory("dpks-container-cancel").toFile();
        try {
            final File item = new File(folder, "probe.onnx");
            Files.write(item.toPath(), new byte[64]);
            Thread.currentThread().interrupt();
            try {
                KnowledgeModelContainerProbe.inspect(item,
                        KnowledgeModelFiles.Format.ONNX_UNVERIFIED);
                fail("A cancelled preflight returned a status");
            } catch (InterruptedIOException expected) { }
            finally { Thread.interrupted(); }
        } finally { removeFolder(folder); }
    }

    private static KnowledgeModelContainerProbe.Result probe(final byte[] bytes,
            final KnowledgeModelFiles.Format format) throws IOException {
        final File folder = Files.createTempDirectory("dpks-container-probe").toFile();
        try {
            final File item = new File(folder, "synthetic.model");
            Files.write(item.toPath(), bytes);
            return KnowledgeModelContainerProbe.inspect(item, format);
        } finally { removeFolder(folder); }
    }

    private static ByteArrayOutputStream newGguf(final int version,
            final long tensorCount, final long metadataCount) {
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write('G'); data.write('G'); data.write('U'); data.write('F');
        u32(data, version);
        u64(data, tensorCount);
        u64(data, metadataCount);
        return data;
    }

    private static void ggufString(final ByteArrayOutputStream data, final String text) {
        final byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        u64(data, utf8.length);
        data.write(utf8, 0, utf8.length);
    }

    private static void ggufTensorInfo(final ByteArrayOutputStream data, final String name,
                                       final long[] dimensions, final long type, final long offset) {
        ggufString(data, name);
        u32(data, dimensions.length);
        for (final long dimension : dimensions) u64(data, dimension);
        u32(data, type);
        u64(data, offset);
    }

    private static void ggufTensorBytes(final ByteArrayOutputStream data,
                                        final int alignment, final int bytes) {
        while (data.size() % alignment != 0) data.write(0);
        for (int i = 0; i < bytes; i++) data.write(0);
    }

    private static void protobufBytes(final ByteArrayOutputStream data, final byte[] contents) {
        varint(data, contents.length);
        data.write(contents, 0, contents.length);
    }

    private static void protobufString(final ByteArrayOutputStream data, final String value) {
        protobufBytes(data, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void u32(final ByteArrayOutputStream data, final long value) {
        for (int b = 0; b < 4; b++) data.write((int) (value >>> (b * 8)) & 255);
    }

    private static void u64(final ByteArrayOutputStream data, final long value) {
        for (int b = 0; b < 8; b++) data.write((int) (value >>> (b * 8)) & 255);
    }

    private static void varint(final ByteArrayOutputStream data, long value) {
        while (value >= 128) {
            data.write(((int) value & 127) | 128);
            value >>>= 7;
        }
        data.write((int) value);
    }

    private static void removeFolder(final File folder) {
        final File[] files = folder.listFiles();
        if (files != null) for (final File file : files) file.delete();
        folder.delete();
    }
}
