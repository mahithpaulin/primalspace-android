package com.mahithpaulin.primalspace

import android.content.Context
import org.json.JSONObject

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("primalspace", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", "https://primal-space-monitor.vercel.app")!!
        set(value) = prefs.edit().putString("server_url", value).apply()

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

    fun clearRegistration() {
        prefs.edit().remove("device_id").remove("token").apply()
    }
}
