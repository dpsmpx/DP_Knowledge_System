/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Deterministic, weight-free ONNX Identity fixture generated only for a
 * user-requested CPU smoke check. This is neither a shipped neural model nor a
 * benchmark of an installed model. No vault bytes or external network data.
 */
final class KnowledgeOnnxSmoke {
    static final float INPUT = 1.25f;
    static final float EXPECTED_OUTPUT = 1.25f;

    static final class Report {
        final String runtimeVersion;
        final long sessionInitMillis;
        final long inferenceMicros;
        final long nativeHeapDeltaBytes;

        Report(final String version, final long initMillis,
               final long runMicros, final long nativeDeltaBytes) {
            runtimeVersion = version;
            sessionInitMillis = initMillis;
            inferenceMicros = runMicros;
            nativeHeapDeltaBytes = nativeDeltaBytes;
        }
    }

    private KnowledgeOnnxSmoke() { }

    static byte[] identityModel() {
        final ByteArrayOutputStream model = new ByteArrayOutputStream();
        uint(model, 1, 9); // ModelProto.ir_version
        string(model, 2, "DPKS synthetic CPU test");
        final ByteArrayOutputStream importOpset = new ByteArrayOutputStream();
        uint(importOpset, 2, 17); // Standard ONNX domain, opset 17.
        bytes(model, 8, importOpset.toByteArray());

        final ByteArrayOutputStream graph = new ByteArrayOutputStream();
        final ByteArrayOutputStream node = new ByteArrayOutputStream();
        string(node, 1, "x");
        string(node, 2, "y");
        string(node, 4, "Identity");
        bytes(graph, 1, node.toByteArray());
        string(graph, 2, "dpks_identity_smoke");
        bytes(graph, 11, valueInfo("x"));
        bytes(graph, 12, valueInfo("y"));
        bytes(model, 7, graph.toByteArray());
        return model.toByteArray();
    }

    private static byte[] valueInfo(final String name) {
        final ByteArrayOutputStream dimension = new ByteArrayOutputStream();
        uint(dimension, 1, 1);
        final ByteArrayOutputStream shape = new ByteArrayOutputStream();
        bytes(shape, 1, dimension.toByteArray());
        final ByteArrayOutputStream tensor = new ByteArrayOutputStream();
        uint(tensor, 1, 1); // FLOAT
        bytes(tensor, 2, shape.toByteArray());
        final ByteArrayOutputStream type = new ByteArrayOutputStream();
        bytes(type, 1, tensor.toByteArray());
        final ByteArrayOutputStream value = new ByteArrayOutputStream();
        string(value, 1, name);
        bytes(value, 2, type.toByteArray());
        return value.toByteArray();
    }

    private static void uint(final ByteArrayOutputStream out, final int field, final long value) {
        varint(out, (long) field << 3);
        varint(out, value);
    }

    private static void string(final ByteArrayOutputStream out,
                               final int field, final String value) {
        bytes(out, field, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(final ByteArrayOutputStream out,
                              final int field, final byte[] value) {
        varint(out, ((long) field << 3) | 2);
        varint(out, value.length);
        out.write(value, 0, value.length);
    }

    private static void varint(final ByteArrayOutputStream out, long value) {
        while ((value & ~127L) != 0) {
            out.write(((int) value & 127) | 128);
            value >>>= 7;
        }
        out.write((int) value);
    }
}
