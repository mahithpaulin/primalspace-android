package com.mahithpaulin.primalspace

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

class MessagesActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var target: Spinner
    private lateinit var input: EditText
    private lateinit var thread: TextView
    private lateinit var peersLabel: TextView
    private val peerIds = mutableListOf<String?>()
    private val peerNames = mutableListOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            renderThread()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_messages)

        target = findViewById(R.id.message_target)
        input = findViewById(R.id.message_body)
        thread = findViewById(R.id.message_thread)
        peersLabel = findViewById(R.id.peers_label)

        findViewById<Button>(R.id.message_send).setOnClickListener { send() }
        findViewById<Button>(R.id.close_messages).setOnClickListener { finish() }

        setTargets(listOf("All devices" to null))
        refreshPeers()
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(ticker, 500)
        renderThread()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun setTargets(entries: List<Pair<String, String?>>) {
        peerIds.clear()
        peerNames.clear()
        for ((name, id) in entries) {
            peerNames.add(name)
            peerIds.add(id)
        }
        val adapter = android.widget.ArrayAdapter(this, R.layout.spinner_item, peerNames)
        adapter.setDropDownViewResource(R.layout.spinner_item)
        target.adapter = adapter
    }

    private fun refreshPeers() {
        if (!prefs.isRegistered()) {
            peersLabel.text = "Connect the device to message peers."
            return
        }
        Thread {
            try {
                val client = PrimalClient(this, BuildConfig.PRIMAL_SERVER_URL, prefs)
                val peers = client.getPeers()
                val entries = mutableListOf<Pair<String, String?>>()
                entries.add("All devices" to null)
                val lines = mutableListOf<String>()
                for (index in 0 until peers.length()) {
                    val peer = peers.getJSONObject(index)
                    val name = peer.optString("name", "device")
                    val avatar = peer.optJSONObject("profile")?.optString("avatar").orEmpty()
                    val status = peer.optJSONObject("profile")?.optString("status").orEmpty()
                    val balance = peer.optDouble("balance", 0.0)
                    val id = peer.optString("id")
                    if (id.isNotBlank()) {
                        entries.add("$avatar $name · ${peer.optString("status", "offline")}" to id)
                        lines.add("$avatar $name — $status · ${String.format(Locale.US, "%.3f", balance)} CU")
                    }
                }
                runOnUiThread {
                    setTargets(entries)
                    peersLabel.text = if (lines.isEmpty()) "No other devices yet." else lines.joinToString("\n")
                }
            } catch (error: Exception) {
                runOnUiThread { peersLabel.text = error.message ?: "Could not load peers." }
            }
        }.start()
    }

    private fun send() {
        if (!prefs.isRegistered()) {
            peersLabel.text = "Connect the device first."
            return
        }
        val body = input.text.toString().trim()
        if (body.isEmpty()) return
        val position = target.selectedItemPosition.coerceIn(0, peerIds.size - 1)
        val to = peerIds.getOrNull(position)
        Thread {
            try {
                val client = PrimalClient(this, BuildConfig.PRIMAL_SERVER_URL, prefs)
                client.sendMessage(to, body)
                val echo = JSONObject()
                    .put("from", prefs.deviceId)
                    .put("to", to)
                    .put("body", body)
                    .put("created_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date()))
                prefs.addMessage(echo)
                runOnUiThread {
                    input.setText("")
                    renderThread()
                }
            } catch (error: Exception) {
                runOnUiThread { peersLabel.text = error.message ?: "Could not send message." }
            }
        }.start()
    }

    private fun renderThread() {
        val inbox = prefs.inbox()
        if (inbox.length() == 0) {
            thread.text = "No messages yet."
            return
        }
        val lines = mutableListOf<String>()
        val start = maxOf(0, inbox.length() - 40)
        for (index in start until inbox.length()) {
            val message = inbox.getJSONObject(index)
            val from = message.optString("from")
            val body = message.optString("body")
            val created = message.optString("created_at")
            val time = try {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(created))
            } catch (_: Exception) {
                ""
            }
            val mine = from == prefs.deviceId
            lines.add(if (mine) "$time  you: $body" else "$time  $from: $body")
        }
        thread.text = lines.joinToString("\n")
    }
}
