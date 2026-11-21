package com.mahithpaulin.primalspace

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var connect: Button
    private lateinit var compute: Button
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)

        status = findViewById<TextView>(R.id.status)
        connect = findViewById<Button>(R.id.connect)
        compute = findViewById<Button>(R.id.compute)

        connect.setOnClickListener { connectDevice() }
        compute.setOnClickListener {
            if (!prefs.isRegistered()) {
                status.text = "Connect the device first."
                return@setOnClickListener
            }
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

    private fun connectDevice() {
        connect.isEnabled = false
        status.text = "Connecting to PrimalSpace…"
        Thread {
            try {
                val client = PrimalClient(this, BuildConfig.PRIMAL_SERVER_URL, prefs)
                val registration = client.enroll()
                prefs.deviceId = registration.first
                prefs.token = registration.second
                prefs.enrollmentKey = registration.third
                runOnUiThread {
                    status.text = "Connected. You can enable compute whenever you are ready."
                    updateStatus()
                    startWorker()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    status.text = "Connection failed: ${error.message}"
                    connect.isEnabled = true
                }
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
            connect.isEnabled = true
            compute.text = "Enable compute"
            return
        }
        connect.isEnabled = true
        connect.text = "Reconnect device"
        compute.text = if (prefs.computeEnabled) "Disable compute" else "Enable compute"
        status.text = "Connected · compute ${if (prefs.computeEnabled) "enabled" else "disabled"}"
    }
}
