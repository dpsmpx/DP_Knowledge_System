package com.dpsmpx.dpks.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/** Persists the user-chosen vault tree URI only — never note contents. */
class VaultPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var treeUri: Uri?
        get() = prefs.getString(KEY_TREE, null)?.let(Uri::parse)
        set(value) {
            prefs.edit().putString(KEY_TREE, value?.toString()).apply()
        }

    var displayName: String?
        get() = prefs.getString(KEY_NAME, null)
        set(value) {
            prefs.edit().putString(KEY_NAME, value).apply()
        }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS = "dpks_vault"
        private const val KEY_TREE = "tree_uri"
        private const val KEY_NAME = "display_name"
    }
}
