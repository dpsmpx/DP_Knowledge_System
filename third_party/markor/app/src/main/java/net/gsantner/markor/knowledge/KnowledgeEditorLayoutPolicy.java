/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

/** Pure, bounded layout decision. The actual editor widget remains Markor's proven component. */
final class KnowledgeEditorLayoutPolicy {
    final boolean sideDock;
    final int dockWidthDp;
    final int phoneScrollDp;

    private KnowledgeEditorLayoutPolicy(final boolean sideDock, final int dockWidthDp,
                                        final int phoneScrollDp) {
        this.sideDock = sideDock;
        this.dockWidthDp = dockWidthDp;
        this.phoneScrollDp = phoneScrollDp;
    }

    static KnowledgeEditorLayoutPolicy forWindow(final int widthDp, final int heightDp,
                                                  final int smallestDp) {
        final int width = Math.max(0, widthDp);
        final int height = Math.max(0, heightDp);
        // A landscape phone is not a tablet. Keep the editor from becoming
        // unusably narrow even on large foldable or multi-window displays.
        final boolean side = smallestDp >= 600 && width >= 720 && height >= 400;
        final int dockWidth = side ? Math.min(360, Math.max(280, width / 3)) : 0;
        // Phone dock must leave space for controls, text and the soft keyboard.
        final int scroll = Math.max(96, Math.min(200, (height - 240) / 3));
        return new KnowledgeEditorLayoutPolicy(side, dockWidth, scroll);
    }
}
