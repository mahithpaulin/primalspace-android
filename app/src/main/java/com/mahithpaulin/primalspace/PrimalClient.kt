package com.mahithpaulin.primalspace

import android.app.ActivityManager
import android.content.Context
import org.json.JSONObject
import java.net.URLEncoder

class ApiException(val status: Int, message: String) : Exception(message)

class PrimalClient(
    private val context: Context,
    private val serverUrl: String,
    private val prefs: Prefs,
) {
    private val base = serverUrl.trimEnd('/')
    private val workloads = org.json.JSONArray().put("http").put("shell")

    fun enroll(enrollmentKey: String): Pair<String, String> {
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
            .put("enrollment_key", enrollmentKey)
        val response = post("/api/v1/enroll", body, null)
        return response.getString("device_id") to response.getString("token")
    }

    fun enrollIfNeeded(): Boolean {
        if (!prefs.isRegistered()) {
            require(prefs.enrollmentKey.isNotBlank()) { "Enrollment key is required" }
            val (deviceId, token) = enroll(prefs.enrollmentKey)
            prefs.deviceId = deviceId
            prefs.token = token
            return true
        }
        return false
    }

    fun heartbeat(
        computeEnabled: Boolean,
        workerStatus: String,
        taskId: String? = null,
        lastError: String? = null,
    ): JSONObject {
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
            .put("cli_version", "android-1.11.0")
            .put("workloads", workloads)
            .put("credits", creditsPayload())
            .put("lending", JSONObject()
                .put("enabled", prefs.lendingEnabled)
                .put("cpu_pct", prefs.lendCpuPct)
                .put("minutes_per_hour", prefs.lendMinutesPerHour)
                .put("units_per_hour", prefs.lendUnitsPerHour))
            .put("profile", prefs.profilePayload())
            .put("wallet", prefs.walletPayload())
            .put("caps", prefs.capsPayload())
            .put("agent", prefs.agentPayload())
        return post("/api/v1/heartbeat", body, prefs.token)
    }

    private fun creditsPayload(): JSONObject {
        return JSONObject()
            .put("units", prefs.creditUnits)
            .put("total_units", prefs.creditTotalUnits)
            .put("minutes_used", prefs.creditMinutesUsed)
            .put("window_start", prefs.creditWindowStart.takeIf { it > 0 }?.toString())
            .put("updated_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(java.util.Date()))
    }

    fun applyLendingRequest(response: JSONObject) {
        val request = response.optJSONObject("lending_request") ?: return
        val enabled = request.optBoolean("enabled", prefs.lendingEnabled)
        val cpu = request.optInt("cpu_pct", prefs.lendCpuPct).coerceIn(1, 100)
        val minutes = request.optInt("minutes_per_hour", prefs.lendMinutesPerHour).coerceIn(1, 60)
        val units = request.optDouble("units_per_hour", prefs.lendUnitsPerHour).coerceIn(0.01, 100.0)
        if (enabled != prefs.lendingEnabled || cpu != prefs.lendCpuPct || minutes != prefs.lendMinutesPerHour || units != prefs.lendUnitsPerHour) {
            prefs.lendingEnabled = enabled
            prefs.lendCpuPct = cpu
            prefs.lendMinutesPerHour = minutes
            prefs.lendUnitsPerHour = units
        }
    }

    fun applyServerWallet(response: JSONObject) {
        response.optJSONObject("wallet")?.let { wallet ->
            prefs.walletBalance = wallet.optDouble("balance", prefs.walletBalance)
            prefs.walletEarned = wallet.optDouble("earned_total", prefs.walletEarned)
            prefs.walletSpent = wallet.optDouble("spent_total", prefs.walletSpent)
        }
        response.optJSONObject("credits")?.let { credits ->
            prefs.creditUnits = credits.optDouble("units", prefs.creditUnits)
            prefs.creditTotalUnits = credits.optDouble("total_units", prefs.creditTotalUnits)
            val window = credits.optString("window_start")
            if (window.isBlank()) {
                prefs.creditWindowStart = 0L
                prefs.creditMinutesUsed = 0.0
            } else {
                runCatching { java.time.Instant.parse(window).toEpochMilli() }.getOrNull()?.let { epoch ->
                    prefs.creditWindowStart = epoch - (epoch % 3600000L)
                    prefs.creditMinutesUsed = credits.optDouble("minutes_used", prefs.creditMinutesUsed)
                }
            }
        }
    }

    fun claim(computeEnabled: Boolean): JSONObject? {
        val body = JSONObject()
            .put("device_id", prefs.deviceId)
            .put("compute_enabled", computeEnabled)
            .put("workloads", workloads)
        val response = post("/api/v1/tasks/claim", body, prefs.token)
        return response.optJSONObject("task")
    }

    fun markExecuting(taskId: String): JSONObject {
        val body = JSONObject()
            .put("device_id", prefs.deviceId)
            .put("task_id", taskId)
        return post("/api/v1/tasks/executing", body, prefs.token)
    }

    fun submitResult(
        taskId: String,
        status: String,
        value: Any?,
        error: String? = null,
        durationMs: Long,
        metrics: JSONObject? = null,
    ): JSONObject {
        val result = JSONObject()
        if (value != null) result.put("value", value)
        val body = JSONObject()
            .put("device_id", prefs.deviceId)
            .put("task_id", taskId)
            .put("status", status)
            .put("duration_ms", durationMs)
            .put("result", result)
        if (error != null) body.put("error", error)
        if (metrics != null) body.put("metrics", metrics)
        return post("/api/v1/tasks/result", body, prefs.token)
    }

    fun getHistory(limit: Int = 50): JSONObject {
        val path = "/api/v1/history?device_id=${encode(prefs.deviceId)}&limit=$limit"
        return get(path, prefs.token)
    }

    fun getPeers(): org.json.JSONArray {
        val response = get("/api/v1/peers?device_id=${encode(prefs.deviceId)}", prefs.token)
        return response.optJSONArray("peers") ?: org.json.JSONArray()
    }

    fun getMessages(since: String?, limit: Int = 50): org.json.JSONArray {
        var path = "/api/v1/messages?device_id=${encode(prefs.deviceId)}&limit=${limit.coerceIn(1, 100)}"
        if (!since.isNullOrBlank()) path += "&since=${encode(since)}"
        val response = get(path, prefs.token)
        return response.optJSONArray("messages") ?: org.json.JSONArray()
    }

    fun getGroups(): org.json.JSONArray {
        val response = get("/api/v1/groups?device_id=${encode(prefs.deviceId)}", prefs.token)
        return response.optJSONArray("groups") ?: org.json.JSONArray()
    }

    fun createGroup(name: String, memberIds: List<String>): JSONObject {
        val members = org.json.JSONArray()
        memberIds.forEach { members.put(it) }
        val body = JSONObject()
            .put("device_id", prefs.deviceId)
            .put("name", name)
            .put("members", members)
            .put("topic", "chat")
        return post("/api/v1/groups", body, prefs.token).optJSONObject("group") ?: JSONObject()
    }

    fun sendMessage(to: String?, body: String): JSONObject {
        val payload = JSONObject()
            .put("device_id", prefs.deviceId)
            .put("to", to)
            .put("topic", "chat")
            .put("body", body)
        return post("/api/v1/messages", payload, prefs.token)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun get(path: String, authToken: String?): JSONObject {
        val connection = java.net.URL(base + path).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("Accept", "application/json")
        if (authToken != null) connection.setRequestProperty("Authorization", "Bearer $authToken")
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) throw ApiException(code, "HTTP $code: ${text.take(200)}")
        return JSONObject(text)
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
