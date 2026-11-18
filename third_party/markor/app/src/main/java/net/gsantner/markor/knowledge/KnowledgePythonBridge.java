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
        return invoke(context, "inspect_note", relativePath, markdown);
    }

    public static JSONObject preview(final Context context, final String workspace,
                                     final String relativePath, final String markdown,
                                     final String fragmentId) throws JSONException {
        return invoke(context, "preview_fragment", workspace, relativePath, markdown, fragmentId);
    }

    public static JSONObject decide(final Context context, final String workspace,
                                    final String relativePath, final String markdown,
                                    final String fragmentId, final String readingId,
                                    final String role, final String sourceHash,
                                    final String journalHead) throws JSONException {
        return invoke(context, "record_decision", workspace, relativePath, markdown,
                fragmentId, readingId, role, sourceHash, journalHead);
    }

    public static JSONObject query(final Context context, final String workspace,
                                   final String question) throws JSONException {
        return invoke(context, "ask_knowledge", workspace, question);
    }

    public static JSONObject diagnostics(final Context context, final String workspace)
            throws JSONException {
        return invoke(context, "knowledge_diagnostics", workspace);
    }

    private static JSONObject invoke(final Context context, final String method,
                                     final Object... arguments) throws JSONException {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(context.getApplicationContext()));
        }
        final String output = Python.getInstance().getModule("ksys_android_bridge")
                .callAttr(method, arguments).toString();
        final JSONObject result = new JSONObject(output);
        final String protocol = result.optString("protocol");
        if (!"dpksys.inspect.v1".equals(protocol)
                && !"dpksys.decision.v1".equals(protocol)
                && !"dpksys.query.v1".equals(protocol)) {
            throw new JSONException("Unexpected knowledge core protocol");
        }
        return result;
    }
}
