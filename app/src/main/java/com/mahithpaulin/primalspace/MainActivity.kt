package com.mahithpaulin.primalspace

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)

        val server = findViewById<EditText>(R.id.server)
        val enrollment = findViewById<EditText>(R.id.enrollment)
        val compute = findViewById<Button>(R.id.compute)
        status = findViewById<TextView>(R.id.status)
        server.setText(prefs.serverUrl)
        enrollment.setText(prefs.enrollmentKey)

        findViewById<Button>(R.id.connect).setOnClickListener { connect(server.text.toString(), enrollment.text.toString()) }
        findViewById<Button>(R.id.start).setOnClickListener { startWorker() }
        compute.setOnClickListener {
            prefs.computeEnabled = !prefs.computeEnabled
            updateStatus()
        }
        requestPermissions()
        updateStatus()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun connect(server: String, enrollment: String) {
        if (server.isBlank() || enrollment.isBlank()) {
            status.text = "Server and enrollment key are required."
            return
        }
        prefs.serverUrl = server.trim()
        prefs.enrollmentKey = enrollment.trim()
        status.text = "Connecting…"
        Thread {
            try {
                val client = PrimalClient(this, prefs.serverUrl, prefs.enrollmentKey, "", "", prefs.deviceName, prefs.installId)
                val (deviceId, token) = client.register()
                prefs.deviceId = deviceId
                prefs.token = token
                runOnUiThread { status.text = "Connected. Worker is ready." }
                startWorker()
            } catch (error: Exception) {
                runOnUiThread { status.text = "Connection failed: ${error.message}" }
            }
        }.start()
    }

    private fun startWorker() {
        if (prefs.deviceId.isEmpty() || prefs.token.isEmpty()) {
            status.text = "Connect the device first."
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, WorkerService::class.java))
        updateStatus()
    }

    private fun updateStatus() {
        val connected = prefs.deviceId.isNotEmpty() && prefs.token.isNotEmpty()
        status.text = if (connected) {
            "Connected · compute ${if (prefs.computeEnabled) "enabled" else "disabled"}"
        } else {
            "Not connected"
        }
    }
}
