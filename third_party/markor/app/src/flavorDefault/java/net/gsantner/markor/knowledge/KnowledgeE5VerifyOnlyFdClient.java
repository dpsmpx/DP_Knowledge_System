/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.content.Context;

/** Real model-FD verification is not shipped in the standard Markor flavor. */
final class KnowledgeE5VerifyOnlyFdClient {
    interface Callback {
        void match();
        default void matchWithDiagnostics(final KnowledgeE5FdVerificationMetrics.Report report) {
            match();
        }
        void failure();
    }

    KnowledgeE5VerifyOnlyFdClient(final Context ignored) { }

    boolean start(final KnowledgeModelPackageStore packages,
                  final KnowledgeModelFiles.Entry entry, final Callback callback) {
        return false;
    }

    void cancel() { }
}
