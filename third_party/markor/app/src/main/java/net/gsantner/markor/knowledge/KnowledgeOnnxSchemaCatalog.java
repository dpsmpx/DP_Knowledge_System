/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

/**
 * A deliberately tiny, offline ONNX schema revision lookup, pinned to the
 * published ONNX 1.24.0 operator pages (opsets through 25). This is NOT an
 * operator validator: attributes, arity, tensor types, function definitions,
 * execution providers and Android runtime availability are not inspected.
 *
 * https://onnx.ai/onnx/operators/onnx__MatMul.html
 * https://onnx.ai/onnx/operators/onnx__Add.html
 * https://onnx.ai/onnx/operators/onnx__LayerNormalization.html
 * https://onnx.ai/onnx/operators/onnx__Gelu.html
 */
final class KnowledgeOnnxSchemaCatalog {
    static final long MAX_SNAPSHOT_OPSET = 25;

    enum Kind {
        SNAPSHOT_REVISION, BEFORE_INTRODUCTION, UNLISTED,
        UNRESOLVED_IMPORT, BEYOND_SNAPSHOT
    }

    static final class Match {
        final Kind kind;
        final int sinceVersion;

        Match(final Kind outcome, final int revision) {
            kind = outcome;
            sinceVersion = revision;
        }
    }

    private KnowledgeOnnxSchemaCatalog() { }

    static Match lookup(final String domain, final String operator,
                        final long importedOpset, final boolean ambiguousImport) {
        // Only the standard (empty) domain has entries in this snapshot.
        if (!"".equals(domain)) return new Match(Kind.UNLISTED, 0);
        if (ambiguousImport || importedOpset <= 0) {
            return new Match(Kind.UNRESOLVED_IMPORT, 0);
        }
        if (importedOpset > MAX_SNAPSHOT_OPSET) {
            return new Match(Kind.BEYOND_SNAPSHOT, 0);
        }
        final int[] revisions;
        if ("MatMul".equals(operator)) revisions = new int[]{1, 9, 13};
        else if ("Add".equals(operator)) revisions = new int[]{1, 6, 7, 13, 14};
        else if ("LayerNormalization".equals(operator)) revisions = new int[]{17};
        else if ("Gelu".equals(operator)) revisions = new int[]{20};
        else return new Match(Kind.UNLISTED, 0);

        int selected = 0;
        for (final int revision : revisions) {
            if (revision <= importedOpset) selected = revision;
            else break;
        }
        return selected == 0
                ? new Match(Kind.BEFORE_INTRODUCTION, 0)
                : new Match(Kind.SNAPSHOT_REVISION, selected);
    }
}
