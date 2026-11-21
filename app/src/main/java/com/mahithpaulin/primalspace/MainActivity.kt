package com.mahithpaulin.primalspace

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
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
    private lateinit var compute: Button
    private lateinit var sync: Button
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

        requestPermissions()
        updateStatus()

        if (intent?.action == ACTION_CONNECT) {
            connectDevice()
        } else if (prefs.isRegistered()) {
            startWorker()
        }
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
        status.text = "Connected · compute ${if (prefs.computeEnabled) "enabled" else "disabled"}"
        val stamp = if (prefs.lastSyncAt > 0) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(prefs.lastSyncAt))
        } else {
            "never"
        }
        detail.text = "Last sync $stamp" + if (prefs.lastError.isNotBlank()) " · ${prefs.lastError}" else ""
    }

    companion object {
        const val ACTION_CONNECT = "com.mahithpaulin.primalspace.CONNECT"
    }
}
