/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * DPKS editor chrome around the retained, independently licensed Markor engine.
 */
package net.gsantner.markor.knowledge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;

import com.google.android.material.color.MaterialColors;

import net.gsantner.markor.R;
import net.gsantner.markor.activity.DocumentActivity;
import net.gsantner.markor.model.Document;
import net.gsantner.opoc.frontend.base.GsFragmentBase;

import java.io.File;

/** A note opened from DPKS must return to the library, not destroy its task. */
public final class KnowledgeDocumentActivity extends DocumentActivity {

    public static void open(final Activity activity, final File file) {
        final Intent intent = new Intent(activity, KnowledgeDocumentActivity.class);
        intent.putExtra(Document.EXTRA_FILE, file);
        intent.putExtra(Document.EXTRA_DO_PREVIEW, false);
        activity.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable final Bundle state) {
        super.onCreate(state);
        final Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back_white_24dp);
            if (toolbar.getNavigationIcon() != null) {
                toolbar.getNavigationIcon().mutate().setTint(
                        MaterialColors.getColor(toolbar,
                                com.google.android.material.R.attr.colorOnSurface));
            }
            toolbar.setNavigationContentDescription(R.string.knowledge_editor_back);
            toolbar.setNavigationOnClickListener(view -> onBackPressed());
        }
    }

    @Override
    public void applyActivityBarBackgroundColors() {
        final Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar == null) return;
        final int surface = MaterialColors.getColor(toolbar,
                com.google.android.material.R.attr.colorSurface);
        toolbar.setBackgroundColor(surface);
        getWindow().setStatusBarColor(surface);
        getWindow().setNavigationBarColor(surface);
    }

    @Override
    public void onBackPressed() {
        // The inherited DocumentActivity calls finishAndRemoveTask(), which
        // would also remove the user's DPKS library from Android recents.
        final Fragment fragment = getSupportFragmentManager()
                .findFragmentById(R.id.document__placeholder_fragment);
        if (fragment instanceof GsFragmentBase
                && !((GsFragmentBase<?, ?>) fragment).onBackPressed()
                && getSupportFragmentManager().getBackStackEntryCount() > 1) {
            getSupportFragmentManager().popBackStack();
        } else {
            finish();
        }
    }
}
