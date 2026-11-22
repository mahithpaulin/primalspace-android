package com.mahithpaulin.primalspace

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("primalspace", Context.MODE_PRIVATE)

    var enrollmentKey: String
        get() = prefs.getString("enrollment_key", "")!!
        set(value) = prefs.edit().putString("enrollment_key", value).apply()

    var deviceId: String
        get() = prefs.getString("device_id", "")!!
        set(value) = prefs.edit().putString("device_id", value).apply()

    var token: String
        get() = prefs.getString("token", "")!!
        set(value) = prefs.edit().putString("token", value).apply()

    var deviceName: String
        get() = prefs.getString("device_name", "Android device")!!
        set(value) = prefs.edit().putString("device_name", value).apply()

    var installId: String
        get() {
            val existing = prefs.getString("install_id", null)
            if (existing != null) return existing
            val created = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("install_id", created).apply()
            return created
        }
        set(value) = prefs.edit().putString("install_id", value).apply()

    var computeEnabled: Boolean
        get() = prefs.getBoolean("compute_enabled", false)
        set(value) = prefs.edit().putBoolean("compute_enabled", value).apply()

    var lastError: String
        get() = prefs.getString("last_error", "")!!
        set(value) = prefs.edit().putString("last_error", value.take(180)).apply()

    var lastSyncAt: Long
        get() = prefs.getLong("last_sync_at", 0L)
        set(value) = prefs.edit().putLong("last_sync_at", value).apply()

    var lendingEnabled: Boolean
        get() = prefs.getBoolean("lending_enabled", false)
        set(value) = prefs.edit().putBoolean("lending_enabled", value).apply()

    var lendCpuPct: Int
        get() = prefs.getInt("lend_cpu_pct", 20)
        set(value) = prefs.edit().putInt("lend_cpu_pct", value.coerceIn(1, 100)).apply()

    var lendMinutesPerHour: Int
        get() = prefs.getInt("lend_minutes_per_hour", 30)
        set(value) = prefs.edit().putInt("lend_minutes_per_hour", value.coerceIn(1, 60)).apply()

    var lendUnitsPerHour: Double
        get() = prefs.getString("lend_units_per_hour", "1.0")!!.toDoubleOrNull() ?: 1.0
        set(value) = prefs.edit().putString("lend_units_per_hour", value.toString()).apply()

    var creditUnits: Double
        get() = prefs.getString("credit_units", "0.0")!!.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString("credit_units", value.toString()).apply()

    var creditTotalUnits: Double
        get() = prefs.getString("credit_total_units", "0.0")!!.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString("credit_total_units", value.toString()).apply()

    var creditMinutesUsed: Double
        get() = prefs.getString("credit_minutes_used", "0.0")!!.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString("credit_minutes_used", value.toString()).apply()

    var creditWindowStart: Long
        get() = prefs.getLong("credit_window_start", 0L)
        set(value) = prefs.edit().putLong("credit_window_start", value).apply()

    var messageCursor: String
        get() = prefs.getString("message_cursor", "")!!
        set(value) = prefs.edit().putString("message_cursor", value).apply()

    val inboxVersion: Long
        get() = prefs.getLong("inbox_version", 0L)

    fun inbox(): JSONArray {
        return try {
            JSONArray(prefs.getString("inbox", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
    }

    fun addMessage(message: JSONObject) {
        val existing = inbox()
        val trimmed = JSONArray()
        val start = maxOf(0, existing.length() - 29)
        for (index in start until existing.length()) {
            trimmed.put(existing.get(index))
        }
        trimmed.put(message)
        prefs.edit()
            .putString("inbox", trimmed.toString())
            .putLong("inbox_version", System.currentTimeMillis())
            .apply()
    }

    fun isRegistered(): Boolean = deviceId.isNotEmpty() && token.isNotEmpty()

    fun clearRegistration() {
        prefs.edit().remove("device_id").remove("token").apply()
    }
}
