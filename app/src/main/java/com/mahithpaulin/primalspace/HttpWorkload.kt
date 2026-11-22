package com.mahithpaulin.primalspace

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object HttpWorkload {
    private const val MAX_BODY = 32768

    fun execute(params: JSONObject): JSONObject {
        val method = params.optString("method", "GET").uppercase()
        val url = params.optString("url")
        require(url.startsWith("http://") || url.startsWith("https://")) { "invalid_url" }
        val timeout = params.optInt("timeout_s", 20).coerceIn(1, 30) * 1000
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = timeout
        connection.readTimeout = timeout
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "PrimalSpace/1.0")
        val headers = params.optJSONObject("headers")
        headers?.keys()?.forEach { key -> connection.setRequestProperty(key, headers.optString(key)) }
        val body = if (params.has("body") && !params.isNull("body")) params.optString("body") else null
        if (body != null && method != "GET" && method != "HEAD") {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val output = ByteArrayOutputStream()
        stream?.use { input ->
            val buffer = ByteArray(8192)
            while (output.size() <= MAX_BODY) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, minOf(read, MAX_BODY + 1 - output.size()))
            }
        }
        connection.disconnect()
        val bytes = output.toByteArray()
        val truncated = bytes.size > MAX_BODY
        val text = String(bytes, 0, minOf(bytes.size, MAX_BODY), Charsets.UTF_8)
        return JSONObject()
            .put("status", status)
            .put("body", text)
            .put("truncated", truncated)
    }
}
