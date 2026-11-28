package com.mahithpaulin.primalspace

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var connect: Button
    private lateinit var sync: Button
    private lateinit var enrollmentKey: EditText
    private lateinit var credits: TextView
    private lateinit var homeMinutes: TextView
    private lateinit var homeMode: TextView
    private lateinit var homeStatusDot: android.view.View
    private lateinit var homeBalance: TextView
    private lateinit var homeEarned: TextView
    private lateinit var homeAvatar: TextView
    private lateinit var homeGreeting: TextView
    private lateinit var connectCard: android.view.View
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)
        Ui.paintSystemBars(this, Color.rgb(11, 15, 13))

        status = findViewById(R.id.status)
        detail = findViewById(R.id.detail)
        connect = findViewById(R.id.connect)
        sync = findViewById(R.id.sync)
        enrollmentKey = findViewById(R.id.enrollment_key)
        credits = findViewById(R.id.credits)
        homeMinutes = findViewById(R.id.home_minutes)
        homeMode = findViewById(R.id.home_mode)
        homeStatusDot = findViewById(R.id.home_status_dot)
        homeBalance = findViewById(R.id.home_balance)
        homeEarned = findViewById(R.id.home_earned)
        homeAvatar = findViewById(R.id.home_avatar)
        homeGreeting = findViewById(R.id.home_greeting)
        connectCard = findViewById(R.id.connect_card)

        connect.setOnClickListener { connectDevice() }
        sync.setOnClickListener { syncNow() }
        findViewById<android.view.View>(R.id.home_compute).setOnClickListener { open(ComputeActivity::class.java) }
        findViewById<android.view.View>(R.id.home_messages).setOnClickListener { open(MessagesActivity::class.java) }
        findViewById<android.view.View>(R.id.home_work).setOnClickListener { open(JobsActivity::class.java) }
        findViewById<android.view.View>(R.id.home_agent).setOnClickListener { open(AgentActivity::class.java) }
        AppNavigation.bind(this, "MainActivity")

        enrollmentKey.setText(prefs.enrollmentKey)
        requestPermissions()
        updateStatus()

        if (intent?.action == ACTION_CONNECT) {
            connectDevice()
        } else if (intent?.action == ACTION_COMPUTE) {
            setCompute(true)
        } else if (intent?.action == ACTION_COMPUTE_OFF) {
            setCompute(false)
        } else if (prefs.isRegistered()) {
            applyAutoLend()
            startWorker()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun open(target: Class<out AppCompatActivity>) {
        startActivity(Intent(this, target))
    }

    private fun applyAutoLend() {
        if (prefs.computeMode == "lend" && prefs.autoLendOnStart) {
            if (!prefs.lendingEnabled) {
                prefs.lendingEnabled = true
                prefs.computeEnabled = true
            }
            startWorker()
        }
    }

    private fun setCompute(enabled: Boolean) {
        prefs.computeEnabled = enabled
        if (enabled) startWorker() else stopService(Intent(this, WorkerService::class.java))
        updateStatus()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun connectDevice() {
        val key = enrollmentKey.text.toString().trim()
        if (key.isBlank()) {
            status.text = "Enrollment key required"
            detail.text = "Ask the operator for the network key, then try again."
            return
        }
        connect.isEnabled = false
        status.text = "Connecting…"
        detail.text = "Handshaking with PrimalSpace."
        Thread {
            try {
                val client = PrimalClient(this, BuildConfig.PRIMAL_SERVER_URL, prefs)
                val (deviceId, token) = client.enroll(key)
                prefs.deviceId = deviceId
                prefs.token = token
                prefs.enrollmentKey = key
                prefs.lastError = ""
                val heartbeat = client.heartbeat(prefs.computeEnabled, "idle")
                client.applyLendingRequest(heartbeat)
                client.applyServerWallet(heartbeat)
                prefs.lastSyncAt = System.currentTimeMillis()
                runOnUiThread {
                    status.text = "Connected"
                    updateStatus()
                    startWorker()
                }
            } catch (error: Exception) {
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
            status.text = "Connect the device first"
            return
        }
        sync.isEnabled = false
        Thread {
            try {
                val client = PrimalClient(this, BuildConfig.PRIMAL_SERVER_URL, prefs)
                val heartbeat = client.heartbeat(prefs.computeEnabled, "idle")
                client.applyLendingRequest(heartbeat)
                client.applyServerWallet(heartbeat)
                prefs.lastError = ""
                prefs.lastSyncAt = System.currentTimeMillis()
                runOnUiThread { updateStatus() }
            } catch (error: Exception) {
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

    private fun startWorker() {
        if (!prefs.isRegistered()) return
        ContextCompat.startForegroundService(this, Intent(this, WorkerService::class.java))
    }

    private fun updateStatus() {
        homeAvatar.text = prefs.profileAvatar.ifBlank {
            Ui.initials(prefs.profileName.ifBlank { prefs.deviceName }, "PS")
        }
        homeGreeting.text = if (prefs.profileName.isBlank()) {
            "Your node, in the wild."
        } else {
            "Welcome back, ${prefs.profileName}."
        }
        if (!prefs.isRegistered()) {
            connectCard.visibility = android.view.View.VISIBLE
            status.text = "Not connected"
            detail.text = "Enter the enrollment key to join the network."
            connect.isEnabled = true
            connect.text = "Connect to PrimalSpace"
            sync.isEnabled = false
            homeMode.text = "IDLE"
            homeStatusDot.backgroundTintList = ColorStateList.valueOf(Color.rgb(101, 117, 106))
            homeBalance.text = "0.000 CU"
            homeEarned.text = "0.000 CU"
            credits.text = "Wallet: 0.00 CU · earned 0.00"
            homeMinutes.text = "No compute credited yet."
            return
        }
        connectCard.visibility = android.view.View.GONE
        connect.isEnabled = true
        connect.text = "Reconnect device"
        sync.isEnabled = true
        val mode = when {
            prefs.computeEnabled && prefs.lendingEnabled -> "LENDING"
            prefs.computeEnabled -> "COMPUTE"
            else -> "IDLE"
        }
        homeMode.text = mode
        homeStatusDot.backgroundTintList = ColorStateList.valueOf(
            if (prefs.computeEnabled) Color.rgb(201, 243, 107) else Color.rgb(95, 227, 192),
        )
        status.text = if (prefs.computeEnabled) "Node is awake" else "Connected"
        val stamp = if (prefs.lastSyncAt > 0) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(prefs.lastSyncAt))
        } else {
            "never"
        }
        detail.text = "Last sync $stamp · mode ${prefs.computeMode}" +
            if (prefs.lastError.isNotBlank()) " · ${prefs.lastError}" else ""
        homeBalance.text = String.format(Locale.US, "%.3f CU", prefs.walletBalance)
        homeEarned.text = String.format(Locale.US, "%.3f CU", prefs.walletEarned)
        credits.text = String.format(
            Locale.US,
            "Balance %.3f CU · earned %.3f · spent %.3f",
            prefs.walletBalance,
            prefs.walletEarned,
            prefs.walletSpent,
        )
        homeMinutes.text = String.format(
            Locale.US,
            "This hour: %.1f min credited · Cap use today: %.1f min",
            prefs.creditMinutesUsed,
            prefs.computeDayMinutes,
        )
    }

    companion object {
        const val ACTION_CONNECT = "com.mahithpaulin.primalspace.CONNECT"
        const val ACTION_COMPUTE = "com.mahithpaulin.primalspace.COMPUTE"
        const val ACTION_COMPUTE_OFF = "com.mahithpaulin.primalspace.COMPUTE_OFF"
    }
}
