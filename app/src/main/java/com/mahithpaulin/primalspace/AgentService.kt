package com.mahithpaulin.primalspace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.json.JSONObject

/**
 * Foreground service that lets the ChatGPT-backed Primal agent answer direct and
 * group chat messages using the signed-in account's ChatGPT plan.
 */
class AgentService : Service() {
    private lateinit var prefs: Prefs
    private var worker: Thread? = null
    @Volatile private var running = false

    companion object {
        const val CHANNEL_ID = "primalspace_agent"
        const val NOTIFICATION_ID = 42
        const val POLL_SECONDS = 20L
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Primal agent is waiting for messages"))
        if (!running) {
            running = true
            worker = Thread({ loop() }, "primal-agent").apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Primal agent", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("PrimalSpace agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun loop() {
        val client = PrimalClient(this, prefs.serverUrl, prefs)
        while (running) {
            try {
                if (prefs.chatgptAccessToken.isNotBlank() && prefs.isRegistered()) {
                    val replies = PrimalAgent(this, prefs, client).runOnce()
                    if (replies.isNotEmpty()) {
                        updateNotification("Replied to ${replies.size} message(s)")
                    }
                }
            } catch (error: Exception) {
                prefs.lastError = "agent: ${error.message}".take(180)
            }
            try {
                Thread.sleep(POLL_SECONDS * 1000)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(text))
    }
}

class PrimalAgent(
    private val service: Service,
    private val prefs: Prefs,
    private val client: PrimalClient,
) {
    private val agentName: String
        get() = prefs.agentName.ifBlank { prefs.profileName.ifBlank { "Primal Agent" } }

    private fun processed(): MutableSet<String> =
        prefs.agentProcessedIds.split(",").filter { it.isNotBlank() }.toMutableSet()

    fun runOnce(): List<String> {
        val groups = client.getGroups()
        val groupIds = groups.map { it.optString("id") }.toSet()
        val messages = client.getMessages(null)
        val seen = processed()
        val replies = mutableListOf<String>()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val id = message.optString("id")
            if (id.isBlank() || id in seen) continue
            if (message.optString("from") == prefs.deviceId) continue
            if (message.has("credit")) continue
            val groupId = message.optString("group_id")
            val target: String
            if (groupId.isNotBlank()) {
                if (!groupIds.contains(groupId)) continue
                val body = message.optString("body")
                if (!prefs.agentReplyAll && !body.contains(agentName, ignoreCase = true)) {
                    seen.add(id)
                    continue
                }
                target = "group:$groupId"
            } else if (message.optString("to") == prefs.deviceId) {
                target = message.optString("from")
            } else {
                continue
            }
            val prompt = buildString {
                append("Group chat: ").append(if (groupId.isBlank()) "direct" else groupId).append('\n')
                append("New message from ").append(message.optString("from").take(8)).append(": ")
                append(message.optString("body")).append("\n\nReply as the agent.")
            }
            val text = ask(prompt, groups)
            if (text.isBlank()) continue
            client.sendMessage(target, text)
            seen.add(id)
            replies.add(text)
            prefs.agentReplies += 1
        }
        prefs.agentProcessedIds = seen.toList().takeLast(800).joinToString(",")
        prefs.agentLastRun = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date())
        return replies
    }

    private fun tools(): List<JSONObject> {
        val nodeStatus = JSONObject()
            .put("type", "function")
            .put("name", "node_status")
            .put("description", "Report this node's compute status and wallet balance.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()).put("required", org.json.JSONArray()))
        val sendMessage = JSONObject()
            .put("type", "function")
            .put("name", "send_message")
            .put("description", "Send a message to another PrimalSpace device or group chat.")
            .put("parameters", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("target", JSONObject().put("type", "string"))
                    .put("body", JSONObject().put("type", "string")))
                .put("required", org.json.JSONArray().put("target").put("body")))
        return listOf(nodeStatus, sendMessage)
    }

    private fun ask(prompt: String, groups: org.json.JSONArray): String {
        val token = ChatGptOAuth.accessToken(prefs)
        val model = prefs.agentModel.ifBlank { ChatGptOAuth.listModels(token).firstOrNull() }
            ?: throw ChatGptOAuth.OAuthException("no models available to this account")
        val roster = buildString {
            for (index in 0 until groups.length()) {
                val group = groups.optJSONObject(index) ?: continue
                if (index > 0) append(", ")
                append(group.optString("name"))
            }
        }
        val instructions = buildString {
            append("You are the PrimalSpace agent for a personal compute node.\n")
            append("Your name is ").append(agentName).append(".\n")
            append("Group chats: ").append(if (roster.isBlank()) "none" else roster).append('\n')
            append("Reply in plain text. Use send_message only to notify another device.")
        }
        val input = mutableListOf(
            JSONObject().put("role", "user").put(
                "content",
                org.json.JSONArray().put(JSONObject().put("type", "input_text").put("text", prompt)),
            ),
        )
        var text = ""
        repeat(4) {
            val completion = ChatGptOAuth.responses(token, model, instructions, input, tools())
            if (completion.text.isNotBlank()) text = completion.text
            if (completion.toolCalls.isEmpty()) return@repeat
            for (call in completion.toolCalls) {
                val name = call.optString("name")
                val arguments = try {
                    JSONObject(call.optString("arguments", "{}"))
                } catch (_: Exception) {
                    JSONObject()
                }
                val output = when (name) {
                    "node_status" -> JSONObject()
                        .put("device", prefs.deviceName)
                        .put("balance", prefs.walletBalance)
                        .put("earned_total", prefs.walletEarned)
                        .put("compute_mode", prefs.computeMode)
                        .toString()
                    "send_message" -> {
                        val target = arguments.optString("target")
                        val body = arguments.optString("body")
                        try {
                            client.sendMessage(target, body)
                            JSONObject().put("ok", true).toString()
                        } catch (error: Exception) {
                            JSONObject().put("ok", false).put("error", error.message).toString()
                        }
                    }
                    else -> JSONObject().put("ok", false).put("error", "unknown tool").toString()
                }
                input.add(JSONObject()
                    .put("type", "function_call")
                    .put("call_id", call.optString("call_id"))
                    .put("name", name)
                    .put("arguments", call.optString("arguments", "{}")))
                input.add(JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", call.optString("call_id"))
                    .put("output", output))
            }
        }
        return text.trim()
    }
}
