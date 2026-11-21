package com.mahithpaulin.primalspace

import android.content.Context
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

    fun isRegistered(): Boolean = deviceId.isNotEmpty() && token.isNotEmpty()
}
