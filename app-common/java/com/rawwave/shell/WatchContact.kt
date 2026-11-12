package com.rawwave.shell

import android.content.Context

/**
 * Last time the phone actually heard from a watch.
 *
 * There is no persistent Bluetooth "connected" flag in this app, and inventing one would lie
 * while the watch is out of range. Storing the last real message lets the home say exactly what
 * it knows: "waiting for the watch, last contact at ...". Persisted so a process restart does
 * not erase the evidence.
 */
object WatchContact {

    private const val PREFS = "watch-contact"
    private const val KEY_UTC_MS = "lastUtcMs"
    private const val KEY_NODE = "node"
    private const val KEY_PATH = "path"

    fun record(context: Context, node: String, path: String) {
        try {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_UTC_MS, System.currentTimeMillis())
                .putString(KEY_NODE, node)
                .putString(KEY_PATH, path)
                .apply()
        } catch (_: Exception) {
            // A connection hint must never break the transfer itself.
        }
    }

    fun lastUtcMs(context: Context): Long = try {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_UTC_MS, 0L)
    } catch (_: Exception) {
        0L
    }
}
