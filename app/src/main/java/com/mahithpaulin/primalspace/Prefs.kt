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

    var computeMode: String
        get() = prefs.getString("compute_mode", if (lendingEnabled) "lend" else "ondemand")!!
        set(value) = prefs.edit().putString("compute_mode", value).apply()

    var autoLendOnStart: Boolean
        get() = prefs.getBoolean("auto_lend_on_start", true)
        set(value) = prefs.edit().putBoolean("auto_lend_on_start", value).apply()

    var profileName: String
        get() = prefs.getString("profile_name", "")!!
        set(value) = prefs.edit().putString("profile_name", value.take(48)).apply()

    var profileStatus: String
        get() = prefs.getString("profile_status", "")!!
        set(value) = prefs.edit().putString("profile_status", value.take(120)).apply()

    var profileAvatar: String
        get() = prefs.getString("profile_avatar", "")!!
        set(value) = prefs.edit().putString("profile_avatar", value.take(8)).apply()

    var walletBalance: Double
        get() = prefs.getString("wallet_balance", "0.0")!!.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString("wallet_balance", value.toString()).apply()

    var walletEarned: Double
        get() = prefs.getString("wallet_earned", "0.0")!!.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString("wallet_earned", value.toString()).apply()

    var walletSpent: Double
        get() = prefs.getString("wallet_spent", "0.0")!!.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString("wallet_spent", value.toString()).apply()

    var capDayMinutes: Int
        get() = prefs.getInt("cap_day_minutes", 0)
        set(value) = prefs.edit().putInt("cap_day_minutes", value.coerceAtLeast(0)).apply()

    var capWeekMinutes: Int
        get() = prefs.getInt("cap_week_minutes", 0)
        set(value) = prefs.edit().putInt("cap_week_minutes", value.coerceAtLeast(0)).apply()

    var capMonthMinutes: Int
        get() = prefs.getInt("cap_month_minutes", 0)
        set(value) = prefs.edit().putInt("cap_month_minutes", value.coerceAtLeast(0)).apply()

    var computeDayMinutes: Double
        get() = prefs.getString("compute_day_minutes", "0.0")!!.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString("compute_day_minutes", value.toString()).apply()

    var computeWeekMinutes: Double
        get() = prefs.getString("compute_week_minutes", "0.0")!!.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString("compute_week_minutes", value.toString()).apply()

    var computeMonthMinutes: Double
        get() = prefs.getString("compute_month_minutes", "0.0")!!.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString("compute_month_minutes", value.toString()).apply()

    var computeWindowKey: String
        get() = prefs.getString("compute_window_key", "")!!
        set(value) = prefs.edit().putString("compute_window_key", value).apply()

    fun resetComputeCountersIfNeeded() {
        val now = java.time.ZonedDateTime.now()
        val day = now.toLocalDate().toString()
        val localDate = now.toLocalDate()
        val monday = localDate.minusDays((localDate.dayOfWeek.value - 1).toLong())
        val week = monday.toString()
        val month = "%04d-%02d".format(now.year, now.monthValue)
        val key = "$day|$week|$month"
        if (computeWindowKey != key) {
            computeWindowKey = key
            computeDayMinutes = 0.0
            computeWeekMinutes = 0.0
            computeMonthMinutes = 0.0
        }
    }

    fun addComputeMinutes(minutes: Double) {
        resetComputeCountersIfNeeded()
        computeDayMinutes += minutes
        computeWeekMinutes += minutes
        computeMonthMinutes += minutes
    }

    fun capReached(): String? {
        resetComputeCountersIfNeeded()
        if (capDayMinutes > 0 && computeDayMinutes >= capDayMinutes) return "daily_compute_cap"
        if (capWeekMinutes > 0 && computeWeekMinutes >= capWeekMinutes) return "weekly_compute_cap"
        if (capMonthMinutes > 0 && computeMonthMinutes >= capMonthMinutes) return "monthly_compute_cap"
        return null
    }

    fun walletPayload(): JSONObject {
        return JSONObject()
            .put("balance", walletBalance)
            .put("earned_total", walletEarned)
            .put("spent_total", walletSpent)
            .put("units", walletBalance)
            .put("total_units", walletEarned)
            .put("updated_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(java.util.Date()))
    }

    fun profilePayload(): JSONObject {
        return JSONObject()
            .put("name", profileName)
            .put("status", profileStatus)
            .put("avatar", profileAvatar)
    }

    fun capsPayload(): JSONObject {
        resetComputeCountersIfNeeded()
        return JSONObject()
            .put("day_minutes", capDayMinutes.takeIf { it > 0 })
            .put("week_minutes", capWeekMinutes.takeIf { it > 0 })
            .put("month_minutes", capMonthMinutes.takeIf { it > 0 })
            .put("day_used", computeDayMinutes)
            .put("week_used", computeWeekMinutes)
            .put("month_used", computeMonthMinutes)
    }

    var appliedTransferIds: String
        get() = prefs.getString("applied_transfer_ids", "")!!
        set(value) = prefs.edit().putString("applied_transfer_ids", value).apply()

    fun applyCredit(kind: String, amount: Double, transferId: String? = null): Boolean {
        if (!transferId.isNullOrBlank()) {
            val ids = appliedTransferIds.split(",").filter { it.isNotBlank() }.toMutableSet()
            if (!ids.add(transferId)) return false
            appliedTransferIds = ids.toList().takeLast(200).joinToString(",")
        }
        if (kind == "incoming") {
            walletBalance += amount
        } else if (kind == "outgoing") {
            walletBalance = maxOf(0.0, walletBalance - amount)
            walletSpent += amount
        } else {
            return false
        }
        return true
    }

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
