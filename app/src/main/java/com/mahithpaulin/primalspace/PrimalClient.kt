package com.mahithpaulin.primalspace

import android.app.ActivityManager
import android.content.Context
import org.json.JSONObject

class ApiException(val status: Int, message: String) : Exception(message)

class PrimalClient(
    private val context: Context,
    private val serverUrl: String,
    private val prefs: Prefs,
) {
    private val base = serverUrl.trimEnd('/')

    fun enroll(): Triple<String, String, String> {
        val device = JSONObject()
            .put("name", prefs.deviceName)
            .put("manufacturer", android.os.Build.MANUFACTURER)
            .put("model", android.os.Build.MODEL)
            .put("android_version", android.os.Build.VERSION.RELEASE)
            .put("android_sdk", android.os.Build.VERSION.SDK_INT)
            .put("cpu_arch", "arm64-v8a")
            .put("cpu_cores", Runtime.getRuntime().availableProcessors())
        val body = JSONObject()
            .put("install_id", prefs.installId)
            .put("device", device)
        val response = post("/api/v1/enroll", body, null)
        return Triple(
            response.getString("device_id"),
            response.getString("token"),
            response.getString("enrollment_key"),
        )
    }

    fun enrollIfNeeded(): Boolean {
        if (!prefs.isRegistered()) {
            val (deviceId, token, enrollmentKey) = enroll()
            prefs.deviceId = deviceId
            prefs.token = token
            prefs.enrollmentKey = enrollmentKey
            return true
        }
        return false
    }

    fun heartbeat(computeEnabled: Boolean, workerStatus: String, taskId: String? = null, lastError: String? = null): JSONObject {
        val metrics = JSONObject()
            .put("ram_total_mb", totalRamMb())
            .put("ram_available_mb", availableRamMb())
            .put("ram_used_pct", usedRamPct())
            .put("storage_free_mb", storageFreeMb())
            .put("battery_pct", batteryPct())
            .put("charging", isCharging())
            .put("network_connected", isNetworkConnected())
            .put("network_type", "wifi")
        val worker = JSONObject()
            .put("status", workerStatus)
            .put("compute_enabled", computeEnabled)
            .put("paused", false)
        if (taskId != null) worker.put("current_task_id", taskId)
        if (!lastError.isNullOrBlank()) worker.put("last_error", lastError.take(180))
        val body = JSONObject()
            .put("device_id", prefs.deviceId)
            .put("metrics", metrics)
            .put("worker", worker)
            .put("cli_version", "android-1.1.0")
        return post("/api/v1/heartbeat", body, prefs.token)
    }

    fun claim(computeEnabled: Boolean): JSONObject? {
        val body = JSONObject().put("device_id", prefs.deviceId).put("compute_enabled", computeEnabled)
        val response = post("/api/v1/tasks/claim", body, prefs.token)
        return response.optJSONObject("task")
    }

    fun submitResult(taskId: String, status: String, value: Long?, error: String? = null, durationMs: Long): JSONObject {
        val result = JSONObject()
        if (value != null) result.put("value", value)
        val body = JSONObject()
            .put("device_id", prefs.deviceId)
            .put("task_id", taskId)
            .put("status", status)
            .put("duration_ms", durationMs)
            .put("result", result)
        if (error != null) body.put("error", error)
        return post("/api/v1/tasks/result", body, prefs.token)
    }

    private fun post(path: String, body: JSONObject, authToken: String?): JSONObject {
        val connection = java.net.URL(base + path).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        if (authToken != null) connection.setRequestProperty("Authorization", "Bearer $authToken")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) throw ApiException(code, "HTTP $code: ${text.take(200)}")
        return JSONObject(text)
    }

    private fun totalRamMb(): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.totalMem / (1024L * 1024L)
    }

    private fun availableRamMb(): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.availMem / (1024L * 1024L)
    }

    private fun usedRamPct(): Double {
        val total = totalRamMb()
        return if (total <= 0) 0.0 else ((total - availableRamMb()).toDouble() / total * 100.0).coerceIn(0.0, 100.0)
    }

    private fun storageFreeMb(): Long {
        val stat = android.os.StatFs(context.filesDir.absolutePath)
        return stat.availableBytes / (1024L * 1024L)
    }

    private fun batteryPct(): Int {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        return manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun isCharging(): Boolean {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        return manager.isCharging
    }

    private fun isNetworkConnected(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
