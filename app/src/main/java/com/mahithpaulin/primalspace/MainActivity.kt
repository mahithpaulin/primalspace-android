package com.mahithpaulin.primalspace

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var connect: Button
    private lateinit var compute: Button
    private lateinit var sync: Button
    private lateinit var messages: TextView
    private lateinit var messageTarget: Spinner
    private lateinit var messageBody: EditText
    private lateinit var messageSend: Button
    private lateinit var lendingToggle: Button
    private lateinit var lendCpu: EditText
    private lateinit var lendMinutes: EditText
    private lateinit var lendUnits: EditText
    private lateinit var credits: TextView
    private val peerIds = mutableListOf<String?>()
    private val peerNames = mutableListOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            renderInbox()
            renderLending()
            handler.postDelayed(this, 4000)
        }
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)

        status = findViewById<TextView>(R.id.status)
        detail = findViewById<TextView>(R.id.detail)
        connect = findViewById<Button>(R.id.connect)
        compute = findViewById<Button>(R.id.compute)
        sync = findViewById<Button>(R.id.sync)
        messages = findViewById<TextView>(R.id.messages)
        messageTarget = findViewById<Spinner>(R.id.message_target)
        messageBody = findViewById<EditText>(R.id.message_body)
        messageSend = findViewById<Button>(R.id.message_send)
        lendingToggle = findViewById<Button>(R.id.lending_toggle)
        lendCpu = findViewById<EditText>(R.id.lend_cpu)
        lendMinutes = findViewById<EditText>(R.id.lend_minutes)
        lendUnits = findViewById<EditText>(R.id.lend_units)
        credits = findViewById<TextView>(R.id.credits)

        connect.setOnClickListener { connectDevice() }
        compute.setOnClickListener {
            if (!prefs.isRegistered()) {
                status.text = "Connect the device first."
                return@setOnClickListener
            }
            prefs.computeEnabled = !prefs.computeEnabled
            updateStatus()
            startWorker()
        }
        sync.setOnClickListener { syncNow() }
        findViewById<Button>(R.id.background).setOnClickListener { openBackgroundSettings() }
        messageSend.setOnClickListener { sendMessage() }
        lendingToggle.setOnClickListener { toggleLending() }
        findViewById<Button>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.open_messages).setOnClickListener {
            startActivity(Intent(this, MessagesActivity::class.java))
        }

        setTargets(listOf("All devices" to null))

        requestPermissions()
        updateStatus()
        renderInbox()
        renderLending()

        if (intent?.action == ACTION_CONNECT) {
            connectDevice()
        } else if (intent?.action == ACTION_COMPUTE) {
            setCompute(true)
        } else if (intent?.action == ACTION_COMPUTE_OFF) {
            setCompute(false)
        } else if (prefs.isRegistered()) {
            applyAutoLend()
            startWorker()
            refreshPeers()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(ticker, 1000)
        renderInbox()
        renderLending()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun applyAutoLend() {
        if (prefs.computeMode == "lend" && prefs.autoLendOnStart) {
            if (!prefs.lendingEnabled) {
                prefs.lendingEnabled = true
                prefs.computeEnabled = true
                android.util.Log.i("PrimalSpace", "auto-lend enabled on app open")
            }
            startWorker()
        }
    }

    private fun setCompute(enabled: Boolean) {
        prefs.computeEnabled = enabled
        android.util.Log.i("PrimalSpace", "compute toggled: $enabled")
        if (enabled) startWorker()
        updateStatus()
    }

    private fun openBackgroundSettings() {
        try {
            val manager = getSystemService(PowerManager::class.java)
            if (manager != null && !manager.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"),
                    ),
                )
                return
            }
        } catch (_: Exception) {
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            status.text = "Open Settings > Apps > PrimalSpace and allow background running."
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun connectDevice() {
        connect.isEnabled = false
        status.text = "Connecting to PrimalSpace…"
        detail.text = ""
        Thread {
            try {
                android.util.Log.i("PrimalSpace", "connect: starting enroll")
                val client = PrimalClient(this, BuildConfig.PRIMAL_SERVER_URL, prefs)
                val (deviceId, token, enrollmentKey) = client.enroll()
                android.util.Log.i("PrimalSpace", "connect: enrolled device=$deviceId")
                prefs.deviceId = deviceId
                prefs.token = token
                prefs.enrollmentKey = enrollmentKey
                prefs.lastError = ""
                client.heartbeat(prefs.computeEnabled, "idle")
                android.util.Log.i("PrimalSpace", "connect: heartbeat ok")
                prefs.lastSyncAt = System.currentTimeMillis()
                runOnUiThread {
                    status.text = "Connected. Enable compute whenever you are ready."
                    updateStatus()
                    startWorker()
                    refreshPeers()
                }
            } catch (error: Exception) {
                android.util.Log.e("PrimalSpace", "connect failed", error)
                prefs.lastError = error.message ?: "connect failed"
                runOnUiThread {
                    status.text = "Connection failed"
                    detail.text = prefs.lastError
                    connect.isEnabled = true
                }
            }
        }.start()
    }

    private fun syncNow() {
        if (!prefs.isRegistered()) {
            status.text = "Connect the device first."
            return
        }
        sync.isEnabled = false
        Thread {
            try {
                val client = PrimalClient(this, BuildConfig.PRIMAL_SERVER_URL, prefs)
                android.util.Log.i("PrimalSpace", "sync: heartbeat start")
                client.heartbeat(prefs.computeEnabled, "idle")
                android.util.Log.i("PrimalSpace", "sync: heartbeat ok")
                prefs.lastError = ""
                prefs.lastSyncAt = System.currentTimeMillis()
                runOnUiThread { updateStatus() }
            } catch (error: Exception) {
                android.util.Log.e("PrimalSpace", "sync failed", error)
                prefs.lastError = error.message ?: "sync failed"
                runOnUiThread {
                    updateStatus()
                    detail.text = prefs.lastError
                }
            } finally {
                runOnUiThread { sync.isEnabled = true }
            }
        }.start()
    }

    private fun refreshPeers() {
        if (!prefs.isRegistered()) return
        Thread {
            try {
                val client = PrimalClient(this, BuildConfig.PRIMAL_SERVER_URL, prefs)
                val peers = client.getPeers()
                val entries = mutableListOf<Pair<String, String?>>()
                entries.add("All devices" to null)
                for (index in 0 until peers.length()) {
                    val peer = peers.getJSONObject(index)
                    val name = peer.optString("name", "device")
                    val id = peer.optString("id")
                    if (id.isNotBlank()) entries.add("$name · ${peer.optString("status", "offline")}" to id)
                }
                runOnUiThread { setTargets(entries) }
            } catch (error: Exception) {
                android.util.Log.w("PrimalSpace", "peer refresh failed: ${error.message}")
            }
        }.start()
    }

    private fun setTargets(entries: List<Pair<String, String?>>) {
        peerIds.clear()
        peerNames.clear()
        for ((name, id) in entries) {
            peerNames.add(name)
            peerIds.add(id)
        }
        val adapter = ArrayAdapter(this, R.layout.spinner_item, peerNames)
        adapter.setDropDownViewResource(R.layout.spinner_item)
        messageTarget.adapter = adapter
    }

    private fun sendMessage() {
        if (!prefs.isRegistered()) {
            status.text = "Connect the device first."
            return
        }
        val body = messageBody.text.toString().trim()
        if (body.isEmpty()) {
            detail.text = "Type a message first."
            return
        }
        val position = messageTarget.selectedItemPosition.coerceIn(0, peerIds.size - 1)
        val target = peerIds.getOrNull(position)
        messageSend.isEnabled = false
        Thread {
            try {
                val client = PrimalClient(this, BuildConfig.PRIMAL_SERVER_URL, prefs)
                client.sendMessage(target, body)
                val echo = JSONObject()
                    .put("from", prefs.deviceId)
                    .put("to", target)
                    .put("body", body)
                    .put("created_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date()))
                prefs.addMessage(echo)
                runOnUiThread {
                    messageBody.setText("")
                    detail.text = if (target == null) "Message sent to all devices." else "Message sent."
                    renderInbox()
                }
            } catch (error: Exception) {
                android.util.Log.e("PrimalSpace", "message send failed", error)
                runOnUiThread { detail.text = error.message ?: "Could not send message." }
            } finally {
                runOnUiThread { messageSend.isEnabled = true }
            }
        }.start()
    }

    private fun toggleLending() {
        if (!prefs.isRegistered()) {
            status.text = "Connect the device first."
            return
        }
        val enabling = !prefs.lendingEnabled
        if (enabling) {
            prefs.lendCpuPct = lendCpu.text.toString().trim().toIntOrNull()?.coerceIn(1, 100) ?: prefs.lendCpuPct
            prefs.lendMinutesPerHour = lendMinutes.text.toString().trim().toIntOrNull()?.coerceIn(1, 60) ?: prefs.lendMinutesPerHour
            prefs.lendUnitsPerHour = lendUnits.text.toString().trim().toDoubleOrNull()?.coerceIn(0.01, 100.0) ?: prefs.lendUnitsPerHour
        }
        prefs.lendingEnabled = enabling
        renderLending()
        detail.text = if (enabling) {
            "Lending ${prefs.lendCpuPct}% CPU for ${prefs.lendMinutesPerHour} min/hour · ${prefs.lendUnitsPerHour} unit(s)/hour"
        } else {
            "Active compute off. Banked units kept."
        }
        startWorker()
    }

    private fun renderLending() {
        lendingToggle.text = if (prefs.lendingEnabled) "Stop lending compute" else "Start lending compute"
        if (lendCpu.text.isNullOrBlank()) lendCpu.setText(prefs.lendCpuPct.toString())
        if (lendMinutes.text.isNullOrBlank()) lendMinutes.setText(prefs.lendMinutesPerHour.toString())
        if (lendUnits.text.isNullOrBlank()) lendUnits.setText(prefs.lendUnitsPerHour.toString())
        credits.text = "Wallet: %.3f CU · earned %.3f · %.1f min used this hour\nCompute used today: %.1f min (caps %d/%d/%d)".format(
            prefs.walletBalance,
            prefs.walletEarned,
            prefs.creditMinutesUsed,
            prefs.computeDayMinutes,
            prefs.capDayMinutes,
            prefs.capWeekMinutes,
            prefs.capMonthMinutes,
        )
    }

    private fun renderInbox() {
        val inbox = prefs.inbox()
        if (inbox.length() == 0) {
            messages.text = "No messages yet."
            return
        }
        val lines = mutableListOf<String>()
        val start = maxOf(0, inbox.length() - 8)
        for (index in start until inbox.length()) {
            val message = inbox.getJSONObject(index)
            val from = message.optString("from")
            val to = message.optString("to")
            val stamp = message.optString("created_at")
            val time = try {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(stamp))
            } catch (_: Exception) {
                ""
            }
            val fromLabel = if (from == prefs.deviceId) "You" else if (from == "operator") "Operator" else "Device"
            val toLabel = if (to.isNullOrBlank()) "everyone" else if (to == prefs.deviceId) "you" else "device"
            lines.add("$time · $fromLabel → $toLabel: ${message.optString("body")}")
        }
        messages.text = lines.joinToString("\n")
    }

    private fun startWorker() {
        if (!prefs.isRegistered()) return
        ContextCompat.startForegroundService(this, Intent(this, WorkerService::class.java))
    }

    private fun updateStatus() {
        if (!prefs.isRegistered()) {
            status.text = "Not connected"
            detail.text = "Tap connect to join."
            connect.isEnabled = true
            connect.text = "Connect to PrimalSpace"
            compute.text = "Enable compute"
            sync.isEnabled = false
            return
        }
        connect.isEnabled = true
        connect.text = "Reconnect device"
        compute.text = if (prefs.computeEnabled) "Disable compute" else "Enable compute"
        sync.isEnabled = true
        val computeState = if (prefs.computeEnabled) "enabled" else "disabled"
        status.text = "Connected · mode ${prefs.computeMode} · compute $computeState"
        val stamp = if (prefs.lastSyncAt > 0) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(prefs.lastSyncAt))
        } else {
            "never"
        }
        detail.text = "Last sync $stamp" + if (prefs.lastError.isNotBlank()) " · ${prefs.lastError}" else ""
    }

    companion object {
        const val ACTION_CONNECT = "com.mahithpaulin.primalspace.CONNECT"
        const val ACTION_COMPUTE = "com.mahithpaulin.primalspace.COMPUTE"
        const val ACTION_COMPUTE_OFF = "com.mahithpaulin.primalspace.COMPUTE_OFF"
    }
}
