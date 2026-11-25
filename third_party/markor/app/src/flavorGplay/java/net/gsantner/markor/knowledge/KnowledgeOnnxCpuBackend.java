/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

/** Upstream Markor Google Play flavor deliberately has no ONNX Runtime AAR. */
final class KnowledgeOnnxCpuBackend {
    private KnowledgeOnnxCpuBackend() { }
    static boolean available() { return false; }

    static KnowledgeOnnxSessionGate.Report inspect(final byte[] bytes) {
        throw new UnsupportedOperationException("ONNX runtime is only in DPKS flavor");
    }
    static KnowledgeOnnxSmoke.Report smoke() {
        throw new UnsupportedOperationException("ONNX CPU backend not included in this flavor");
    }
}
