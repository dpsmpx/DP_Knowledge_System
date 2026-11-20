/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

/** Explicit, revocable SAF destination; may be a cloud-backed provider. */
final class KnowledgeExternalBackup {
    private static final String PREF = "dpks_stage21_external_backup_v1";
    private static final String TREE_URI = "tree";
    static final String LAST_OK = "last_external_ok";
    static final String LAST_AT = "last_external_at";

    private KnowledgeExternalBackup() { }

    private static SharedPreferences prefs(final Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    static Uri destination(final Context context) {
        final String encoded = prefs(context).getString(TREE_URI, null);
        if (encoded == null) return null;
        return Uri.parse(encoded);
    }

    static void configure(final Context context, final Uri uri, final int flags)
            throws SecurityException {
        if (uri == null || !"content".equals(uri.getScheme()))
            throw new SecurityException("SAF tree unavailable");
        final int required = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if ((flags & required) != required)
            throw new SecurityException("SAF read/write permission missing");
        context.getContentResolver().takePersistableUriPermission(uri, required);
        final Uri previous = destination(context);
        if (!prefs(context).edit().putString(TREE_URI, uri.toString()).commit()) {
            context.getContentResolver().releasePersistableUriPermission(uri, required);
            throw new SecurityException("Cannot preserve user-selected destination");
        }
        if (previous != null && !previous.equals(uri)) {
            try { context.getContentResolver().releasePersistableUriPermission(
                    previous, required); } catch (SecurityException ignored) { }
        }
    }

    static void disable(final Context context) {
        final Uri previous = destination(context);
        prefs(context).edit().remove(TREE_URI).commit();
        if (previous != null) try {
            context.getContentResolver().releasePersistableUriPermission(previous,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) { }
    }

    static void record(final Context context, final boolean success) {
        final SharedPreferences.Editor edit = prefs(context).edit()
                .putBoolean(LAST_OK, success);
        if (success) edit.putLong(LAST_AT, System.currentTimeMillis());
        edit.commit();
    }

    static boolean hasSuccessfulCopy(final Context context) {
        return prefs(context).getLong(LAST_AT, 0) > 0;
    }
}
