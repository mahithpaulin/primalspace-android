package com.mahithpaulin.primalspace

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var modeGroup: RadioGroup
    private lateinit var autoStart: RadioButton
    private lateinit var autoOff: RadioButton
    private lateinit var cpuInput: EditText
    private lateinit var minutesInput: EditText
    private lateinit var unitsInput: EditText
    private lateinit var dayCap: EditText
    private lateinit var weekCap: EditText
    private lateinit var monthCap: EditText
    private lateinit var nameInput: EditText
    private lateinit var statusInput: EditText
    private lateinit var avatarInput: EditText
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_settings)

        modeGroup = findViewById(R.id.mode_group)
        autoStart = findViewById(R.id.auto_lend_on)
        autoOff = findViewById(R.id.auto_lend_off)
        cpuInput = findViewById(R.id.lend_cpu)
        minutesInput = findViewById(R.id.lend_minutes)
        unitsInput = findViewById(R.id.lend_units)
        dayCap = findViewById(R.id.cap_day)
        weekCap = findViewById(R.id.cap_week)
        monthCap = findViewById(R.id.cap_month)
        nameInput = findViewById(R.id.profile_name)
        statusInput = findViewById(R.id.profile_status)
        avatarInput = findViewById(R.id.profile_avatar)
        summary = findViewById(R.id.settings_summary)

        findViewById<Button>(R.id.save_settings).setOnClickListener { save() }
        findViewById<Button>(R.id.close_settings).setOnClickListener { finish() }

        load()
    }

    private fun load() {
        val mode = prefs.computeMode
        when (mode) {
            "lend" -> modeGroup.check(R.id.mode_lend)
            "ondemand" -> modeGroup.check(R.id.mode_ondemand)
            else -> modeGroup.check(R.id.mode_off)
        }
        if (prefs.autoLendOnStart) autoStart.isChecked = true else autoOff.isChecked = true
        cpuInput.setText(prefs.lendCpuPct.toString())
        minutesInput.setText(prefs.lendMinutesPerHour.toString())
        unitsInput.setText(prefs.lendUnitsPerHour.toString())
        dayCap.setText(prefs.capDayMinutes.toString())
        weekCap.setText(prefs.capWeekMinutes.toString())
        monthCap.setText(prefs.capMonthMinutes.toString())
        nameInput.setText(prefs.profileName)
        statusInput.setText(prefs.profileStatus)
        avatarInput.setText(prefs.profileAvatar)
        updateSummary()
    }

    private fun save() {
        prefs.computeMode = when (modeGroup.checkedRadioButtonId) {
            R.id.mode_lend -> "lend"
            R.id.mode_ondemand -> "ondemand"
            else -> "off"
        }
        prefs.autoLendOnStart = autoStart.isChecked
        prefs.lendCpuPct = cpuInput.text.toString().trim().toIntOrNull()?.coerceIn(1, 100) ?: prefs.lendCpuPct
        prefs.lendMinutesPerHour = minutesInput.text.toString().trim().toIntOrNull()?.coerceIn(1, 60) ?: prefs.lendMinutesPerHour
        prefs.lendUnitsPerHour = unitsInput.text.toString().trim().toDoubleOrNull()?.coerceIn(0.01, 100.0) ?: prefs.lendUnitsPerHour
        prefs.capDayMinutes = dayCap.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
        prefs.capWeekMinutes = weekCap.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
        prefs.capMonthMinutes = monthCap.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
        prefs.profileName = nameInput.text.toString().trim()
        prefs.profileStatus = statusInput.text.toString().trim()
        prefs.profileAvatar = avatarInput.text.toString().trim()

        if (prefs.computeMode == "lend") {
            prefs.lendingEnabled = true
            prefs.computeEnabled = true
        } else if (prefs.computeMode == "ondemand") {
            prefs.lendingEnabled = false
        } else {
            prefs.lendingEnabled = false
            prefs.computeEnabled = false
        }

        updateSummary()
        val intent = Intent(this, WorkerService::class.java)
        try {
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
        } catch (_: Exception) {
        }
    }

    private fun updateSummary() {
        val cap = prefs.capReached()
        summary.text = buildString {
            append("Mode: ").append(prefs.computeMode).append('\n')
            append("Wallet: ").append(String.format(java.util.Locale.US, "%.3f CU", prefs.walletBalance)).append('\n')
            append("Earned: ").append(String.format(java.util.Locale.US, "%.3f CU", prefs.walletEarned)).append('\n')
            append("Compute used: ")
                .append(String.format(java.util.Locale.US, "%.0f", prefs.computeDayMinutes)).append("m day / ")
                .append(String.format(java.util.Locale.US, "%.0f", prefs.computeWeekMinutes)).append("m week / ")
                .append(String.format(java.util.Locale.US, "%.0f", prefs.computeMonthMinutes)).append("m month")
                .append('\n')
            append(if (cap == null) "Within compute limits." else "Stopped: $cap")
        }
    }
}
