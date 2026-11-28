package com.mahithpaulin.primalspace

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var enrollmentKey: EditText
    private lateinit var nameInput: EditText
    private lateinit var statusInput: EditText
    private lateinit var avatarInput: EditText
    private lateinit var summary: TextView
    private lateinit var deviceId: TextView
    private lateinit var server: TextView
    private lateinit var version: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_settings)
        Ui.paintSystemBars(this, Color.rgb(13, 16, 18))

        enrollmentKey = findViewById(R.id.enrollment_key)
        nameInput = findViewById(R.id.profile_name)
        statusInput = findViewById(R.id.profile_status)
        avatarInput = findViewById(R.id.profile_avatar)
        summary = findViewById(R.id.settings_summary)
        deviceId = findViewById(R.id.settings_device_id)
        server = findViewById(R.id.settings_server)
        version = findViewById(R.id.settings_version)

        findViewById<Button>(R.id.save_settings).setOnClickListener { save() }
        findViewById<Button>(R.id.settings_connect).setOnClickListener { reconnect() }
        findViewById<Button>(R.id.settings_background).setOnClickListener { openBackgroundSettings() }
        AppNavigation.bind(this, "SettingsActivity")
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        enrollmentKey.setText(prefs.enrollmentKey)
        nameInput.setText(prefs.profileName)
        statusInput.setText(prefs.profileStatus)
        avatarInput.setText(prefs.profileAvatar)
        deviceId.text = "Device id: ${prefs.deviceId.ifBlank { "not registered" }}"
        server.text = "Server: ${BuildConfig.PRIMAL_SERVER_URL}"
        version.text = "Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        updateSummary()
    }

    private fun save() {
        prefs.enrollmentKey = enrollmentKey.text.toString().trim()
        prefs.profileName = nameInput.text.toString().trim()
        prefs.profileStatus = statusInput.text.toString().trim()
        prefs.profileAvatar = avatarInput.text.toString().trim()
        updateSummary()
        Toast.makeText(this, "Identity saved", Toast.LENGTH_SHORT).show()
    }

    private fun reconnect() {
        save()
        startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_CONNECT))
    }

    private fun updateSummary() {
        summary.text = if (prefs.isRegistered()) {
            String.format(
                java.util.Locale.US,
                "Connected · mode %s · balance %.3f CU · earned %.3f CU",
                prefs.computeMode,
                prefs.walletBalance,
                prefs.walletEarned,
            )
        } else {
            "Not connected. Enter the operator key and reconnect."
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
            Toast.makeText(this, "Open Settings > Apps > PrimalSpace", Toast.LENGTH_LONG).show()
        }
    }
}
