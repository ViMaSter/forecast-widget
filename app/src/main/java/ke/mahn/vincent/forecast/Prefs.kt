package ke.mahn.vincent.forecast

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("forecast", Context.MODE_PRIVATE)

    var latitude: Double?
        get() = prefs.getString(KEY_LAT, null)?.toDoubleOrNull()
        set(value) = prefs.edit { putString(KEY_LAT, value?.toString()) }

    var longitude: Double?
        get() = prefs.getString(KEY_LON, null)?.toDoubleOrNull()
        set(value) = prefs.edit { putString(KEY_LON, value?.toString()) }

    var lastSuccessAt: Long
        get() = prefs.getLong(KEY_LAST_SUCCESS, 0)
        set(value) = prefs.edit { putLong(KEY_LAST_SUCCESS, value) }

    var lastAttemptAt: Long
        get() = prefs.getLong(KEY_LAST_ATTEMPT, 0)
        set(value) = prefs.edit { putLong(KEY_LAST_ATTEMPT, value) }

    var lastError: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)
        set(value) = prefs.edit { putString(KEY_LAST_ERROR, value) }

    var nextAlarmAt: Long
        get() = prefs.getLong(KEY_NEXT_ALARM, 0)
        set(value) = prefs.edit { putLong(KEY_NEXT_ALARM, value) }

    var backgroundLocationDisclosureAccepted: Boolean
        get() = prefs.getBoolean(KEY_DISCLOSURE, false)
        set(value) = prefs.edit { putBoolean(KEY_DISCLOSURE, value) }

    /** ETags are stored per zoom together with the URL they belong to, so a moved location invalidates them. */
    fun etag(zoom: Int, url: String): String? {
        val stored = prefs.getString(KEY_ETAG_PREFIX + zoom, null) ?: return null
        val (storedUrl, etag) = stored.split('\n', limit = 2).takeIf { it.size == 2 } ?: return null
        return etag.takeIf { storedUrl == url }
    }

    fun setEtag(zoom: Int, url: String, etag: String?) = prefs.edit {
        if (etag == null) remove(KEY_ETAG_PREFIX + zoom) else putString(KEY_ETAG_PREFIX + zoom, "$url\n$etag")
    }

    fun retainEtags(zooms: Set<Int>) = prefs.edit {
        prefs.all.keys
            .filter { it.startsWith(KEY_ETAG_PREFIX) && it.removePrefix(KEY_ETAG_PREFIX).toIntOrNull() !in zooms }
            .forEach { remove(it) }
    }

    private companion object {
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
        const val KEY_LAST_SUCCESS = "last_success"
        const val KEY_LAST_ATTEMPT = "last_attempt"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_NEXT_ALARM = "next_alarm"
        const val KEY_DISCLOSURE = "bg_location_disclosure"
        const val KEY_ETAG_PREFIX = "etag:"
    }
}
