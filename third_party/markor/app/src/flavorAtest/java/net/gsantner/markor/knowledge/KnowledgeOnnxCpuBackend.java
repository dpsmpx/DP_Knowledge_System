/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.os.Debug;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/** DPKS-only, explicit CPU smoke run against a generated, weight-free graph. */
final class KnowledgeOnnxCpuBackend {
    private KnowledgeOnnxCpuBackend() { }

    static boolean available() { return true; }

    static KnowledgeOnnxSmoke.Report smoke() throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("CPU smoke test cancelled");
        }
        final long nativeBefore = Debug.getNativeHeapAllocatedSize();
        final long started = System.nanoTime();
        final OrtEnvironment environment = OrtEnvironment.getEnvironment();
        final long initMillis;
        final long runMicros;
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            // CPU only, bounded native thread pools. No GPU/NNAPI/QNN provider and
            // no profiling file, remote endpoint or model-related background work.
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            try (OrtSession session = environment.createSession(
                    KnowledgeOnnxSmoke.identityModel(), options)) {
                initMillis = Math.max(0L,
                        (System.nanoTime() - started) / 1_000_000L);
                final FloatBuffer input = ByteBuffer.allocateDirect(4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
                input.put(KnowledgeOnnxSmoke.INPUT);
                input.rewind();
                try (OnnxTensor tensor = OnnxTensor.createTensor(environment, input,
                        new long[]{1})) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("CPU smoke test cancelled");
                    }
                    final long runStarted = System.nanoTime();
                    final float output;
                    try (OrtSession.Result result = session.run(
                            Collections.singletonMap("x", tensor))) {
                        if (result.size() != 1) throw new IOException("Wrong smoke output count");
                        final Object value = result.get(0).getValue();
                        if (!(value instanceof float[]) || ((float[]) value).length != 1) {
                            throw new IOException("Wrong smoke output shape");
                        }
                        output = ((float[]) value)[0];
                    }
                    runMicros = Math.max(0L,
                            (System.nanoTime() - runStarted) / 1_000L);
                    if (Float.compare(output, KnowledgeOnnxSmoke.EXPECTED_OUTPUT) != 0) {
                        throw new IOException("Incorrect synthetic ONNX CPU output");
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("CPU smoke test cancelled");
                    }
                }
            }
        }
        // Count AFTER the session, options, result and input tensor have closed.
        // This is a diagnostic native-heap delta, NOT peak RSS or model RAM.
        final long nativeDelta = Debug.getNativeHeapAllocatedSize() - nativeBefore;
        return new KnowledgeOnnxSmoke.Report(environment.getVersion(),
                initMillis, runMicros, nativeDelta);
    }
}
