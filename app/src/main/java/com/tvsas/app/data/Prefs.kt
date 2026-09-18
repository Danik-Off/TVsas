package com.tvsas.app.data

import android.content.Context
import android.content.SharedPreferences

/** Small key/value store: auth token, quality cap, cached profile and local watch history. */
object Prefs {
    private const val KEY_TOKEN = "token"
    private const val KEY_USERNAME = "username"
    private const val KEY_SUBSCRIPTION = "subscription"
    private const val KEY_MAX_QUALITY = "max_quality"
    private const val KEY_HISTORY = "history"

    const val QUALITY_AUTO = 0
    private const val HISTORY_LIMIT = 20

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("tvsas", Context.MODE_PRIVATE)
    }

    var token: String?
        get() = sp.getString(KEY_TOKEN, null)
        set(v) = sp.edit().putString(KEY_TOKEN, v).apply()

    val isLoggedIn: Boolean get() = token != null

    var username: String?
        get() = sp.getString(KEY_USERNAME, null)
        set(v) = sp.edit().putString(KEY_USERNAME, v).apply()

    var subscription: String?
        get() = sp.getString(KEY_SUBSCRIPTION, null)
        set(v) = sp.edit().putString(KEY_SUBSCRIPTION, v).apply()

    /** Max video height in pixels; [QUALITY_AUTO] lets ExoPlayer pick by display size. Default 1080p. */
    var maxQuality: Int
        get() = sp.getInt(KEY_MAX_QUALITY, 1080)
        set(v) = sp.edit().putInt(KEY_MAX_QUALITY, v).apply()

    fun clearSession() {
        sp.edit().remove(KEY_TOKEN).remove(KEY_USERNAME).remove(KEY_SUBSCRIPTION).apply()
    }

    // ---- Local watch history ("continue watching") ----

    fun history(): List<HistoryEntry> = Json.historyFromJson(sp.getString(KEY_HISTORY, null))

    fun historyFor(videoUuid: String): HistoryEntry? = history().firstOrNull { it.videoUuid == videoUuid }

    fun saveHistory(entry: HistoryEntry) {
        val list = history().filter { it.videoUuid != entry.videoUuid }.toMutableList()
        list.add(0, entry)
        while (list.size > HISTORY_LIMIT) list.removeAt(list.size - 1)
        sp.edit().putString(KEY_HISTORY, Json.historyToJson(list)).apply()
    }

    fun removeHistory(videoUuid: String) {
        val list = history().filter { it.videoUuid != videoUuid }
        sp.edit().putString(KEY_HISTORY, Json.historyToJson(list)).apply()
    }

    fun clearHistory() {
        sp.edit().remove(KEY_HISTORY).apply()
    }
}
