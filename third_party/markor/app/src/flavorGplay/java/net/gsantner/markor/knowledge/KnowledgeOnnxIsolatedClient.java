/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.content.Context;

/** Upstream Markor variants have no isolated ONNX Runtime process. */
final class KnowledgeOnnxIsolatedClient {
    interface Callback {
        void success(KnowledgeOnnxSmoke.Report report);
        void failure();
    }

    KnowledgeOnnxIsolatedClient(final Context ignored) { }
    boolean start(final Callback ignored) { return false; }
    void cancel() { }
}
