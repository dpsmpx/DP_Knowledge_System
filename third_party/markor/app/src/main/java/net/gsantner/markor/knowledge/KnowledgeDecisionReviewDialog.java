/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.StringRes;
import androidx.core.view.ViewCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.gsantner.markor.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Reviewable, selectable, scrollable before/after presentation. This view
 * never writes Markdown or a journal: callers retain all explicit commit and
 * stale-head validation paths. A label, not color alone, identifies changes.
 */
final class KnowledgeDecisionReviewDialog {
    private KnowledgeDecisionReviewDialog() { }

    static final class Section {
        @StringRes final int label;
        final String text;
        final boolean previous;

        Section(@StringRes final int label, final String text, final boolean previous) {
            this.label = label;
            this.text = text == null ? "" : text;
            this.previous = previous;
        }
    }

    static void show(final Context context, @StringRes final int title,
                     @StringRes final int confirm,
                     final List<Section> sections, final String diagnostics,
                     final String warning, final Runnable onConfirm) {
        final ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        final int padding = dp(context, 20);
        column.setPadding(padding, dp(context, 8), padding, dp(context, 16));
        scroll.addView(column);

        final ArrayList<Section> old = new ArrayList<>();
        final ArrayList<Section> next = new ArrayList<>();
        for (final Section section : sections) {
            (section.previous ? old : next).add(section);
        }
        final boolean simpleDiff = old.size() == 1 && next.size() == 1;
        final int[] ranges = simpleDiff
                ? changedRanges(old.get(0).text, next.get(0).text)
                : null;

        for (final Section section : sections) {
            final MaterialCardView card = new MaterialCardView(context);
            card.setRadius(dp(context, 16));
            card.setStrokeWidth(dp(context, 1));
            card.setStrokeColor(MaterialColors.getColor(column,
                    com.google.android.material.R.attr.colorOutlineVariant));
            card.setCardBackgroundColor(MaterialColors.getColor(column,
                    com.google.android.material.R.attr.colorSurfaceContainerLow));
            final LinearLayout body = new LinearLayout(context);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(context, 16), dp(context, 14),
                    dp(context, 16), dp(context, 14));

            final TextView heading = new TextView(context);
            heading.setText(section.label);
            heading.setTextSize(13);
            heading.setTypeface(null, Typeface.BOLD);
            heading.setTextColor(MaterialColors.getColor(column,
                    R.attr.colorPrimary));
            ViewCompat.setAccessibilityHeading(heading, true);
            body.addView(heading);

            final TextView content = new TextView(context);
            content.setTextSize(16);
            content.setTextColor(MaterialColors.getColor(column,
                    com.google.android.material.R.attr.colorOnSurface));
            content.setTextIsSelectable(true);
            content.setPadding(0, dp(context, 8), 0, 0);
            if (ranges == null) {
                content.setText(section.text);
            } else {
                final int start = section.previous ? ranges[0] : ranges[2];
                final int end = section.previous ? ranges[1] : ranges[3];
                final SpannableString marked = new SpannableString(section.text);
                if (end > start) {
                    final int bgAttr = section.previous
                            ? com.google.android.material.R.attr.colorErrorContainer
                            : com.google.android.material.R.attr.colorTertiaryContainer;
                    final int fgAttr = section.previous
                            ? com.google.android.material.R.attr.colorOnErrorContainer
                            : com.google.android.material.R.attr.colorOnTertiaryContainer;
                    marked.setSpan(new BackgroundColorSpan(MaterialColors.getColor(column,
                                    bgAttr)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    marked.setSpan(new ForegroundColorSpan(MaterialColors.getColor(column,
                                    fgAttr)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                content.setText(marked);
            }
            body.addView(content);
            card.addView(body);
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(context, 10);
            column.addView(card, params);
        }

        addNotice(context, column, diagnostics,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        addNotice(context, column, warning,
                R.attr.colorError);
        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(confirm, (dialog, which) -> onConfirm.run())
                .show();
    }

    /** Code-point-aware common-prefix/suffix diff avoids cutting surrogate pairs. */
    private static int[] changedRanges(final String before, final String after) {
        int prefixOld = 0;
        int prefixNew = 0;
        while (prefixOld < before.length() && prefixNew < after.length()) {
            final int oldCp = before.codePointAt(prefixOld);
            if (oldCp != after.codePointAt(prefixNew)) break;
            prefixOld += Character.charCount(oldCp);
            prefixNew += Character.charCount(oldCp);
        }
        int endOld = before.length();
        int endNew = after.length();
        while (endOld > prefixOld && endNew > prefixNew) {
            final int oldCp = before.codePointBefore(endOld);
            if (oldCp != after.codePointBefore(endNew)) break;
            endOld -= Character.charCount(oldCp);
            endNew -= Character.charCount(oldCp);
        }
        return new int[]{prefixOld, endOld, prefixNew, endNew};
    }

    private static void addNotice(final Context context, final LinearLayout column,
                                  final String text, final int colorAttr) {
        if (text == null || text.isEmpty()) return;
        final TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(14);
        label.setTextColor(MaterialColors.getColor(column, colorAttr));
        label.setPadding(0, dp(context, 14), 0, 0);
        column.addView(label);
    }

    private static int dp(final Context context, final int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }
}
