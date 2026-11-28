package com.fulltech.devilscutehornsai

import android.content.Context
import org.json.JSONArray

internal object DeviceConnectReceiveEvents {
    private const val PREFS = "dch_device_connect_events"
    private const val QUEUE = "pending_received_session_ids"

    @Synchronized
    fun enqueue(context: Context, sessionId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val queue = readQueue(prefs)

        for (i in 0 until queue.length()) {
            if (queue.optString(i) == sessionId) return
        }

        queue.put(sessionId)
        check(prefs.edit().putString(QUEUE, queue.toString()).commit())
    }

    @Synchronized
    fun peekNext(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return readQueue(prefs).optString(0).takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun takeNext(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val queue = readQueue(prefs)
        if (queue.length() == 0) return null

        val next = queue.optString(0).takeIf { it.isNotBlank() }
        val remaining = JSONArray()

        for (i in 1 until queue.length()) {
            remaining.put(queue.optString(i))
        }

        check(prefs.edit().putString(QUEUE, remaining.toString()).commit())
        return next
    }

    @Synchronized
    fun markNotified(context: Context, sessionId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "notified_$sessionId"
        if (prefs.getBoolean(key, false)) return false
        check(prefs.edit().putBoolean(key, true).commit())
        return true
    }

    private fun readQueue(
        prefs: android.content.SharedPreferences
    ): JSONArray {
        return runCatching {
            JSONArray(prefs.getString(QUEUE, "[]"))
        }.getOrElse {
            JSONArray()
        }
    }
}
