/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import android.content.Context;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Thin Android transport to the *actual* Python ksys package, without a
 * separate Java reimplementation of its source or knowledge semantics.
 * Invoke from a worker thread. Only the caller owns the original Markdown.
 */
public final class KnowledgePythonBridge {
    private KnowledgePythonBridge() {
    }

    public static JSONObject inspect(final Context context, final String relativePath,
                                     final String markdown) throws JSONException {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(context.getApplicationContext()));
        }
        final String output = Python.getInstance().getModule("ksys_android_bridge")
                .callAttr("inspect_note", relativePath, markdown).toString();
        final JSONObject result = new JSONObject(output);
        if (!"dpksys.inspect.v1".equals(result.optString("protocol"))
                || !result.optBoolean("read_only", false)) {
            throw new JSONException("Unexpected knowledge core protocol");
        }
        return result;
    }
}
