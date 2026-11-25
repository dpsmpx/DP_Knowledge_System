/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

/** Upstream Markor flavor deliberately has no bundled ONNX Runtime AAR. */
final class KnowledgeOnnxCpuBackend {
    private KnowledgeOnnxCpuBackend() { }
    static boolean available() { return false; }
    static KnowledgeOnnxSmoke.Report smoke() {
        throw new UnsupportedOperationException("ONNX CPU backend not included in this flavor");
    }
}
