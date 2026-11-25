/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.content.Context;
import java.util.Arrays;

/** No user ONNX session execution is compiled into the standard Markor flavor. */
final class KnowledgeOnnxIsolatedSessionClient {
    interface Callback {
        void success(KnowledgeOnnxSessionGate.Report report);
        void failure();
    }
    KnowledgeOnnxIsolatedSessionClient(final Context ignored) { }
    boolean start(final byte[] model, final String hash, final Callback callback) {
        if (model != null) Arrays.fill(model, (byte) 0);
        return false;
    }
    void cancel() { }
}
