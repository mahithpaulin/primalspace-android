package com.mahithpaulin.primalspace

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ComputeActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var toggle: Button
    private lateinit var modeOff: TextView
    private lateinit var modeOnDemand: TextView
    private lateinit var modeLend: TextView
    private lateinit var autoLend: Switch
    private lateinit var hourUsed: TextView
    private lateinit var hourBar: ProgressBar
    private lateinit var dayUsed: TextView
    private lateinit var dayBar: ProgressBar
    private lateinit var dayCap: TextView
    private lateinit var weekUsed: TextView
    private lateinit var weekBar: ProgressBar
    private lateinit var weekCap: TextView
    private lateinit var monthUsed: TextView
    private lateinit var monthBar: ProgressBar
    private lateinit var monthCap: TextView
    private lateinit var balance: TextView
    private lateinit var earned: TextView
    private lateinit var landed: TextView
    private lateinit var lastSync: TextView
    private lateinit var cpuSeek: SeekBar
    private lateinit var cpuLabel: TextView
    private lateinit var minutesSeek: SeekBar
    private lateinit var minutesLabel: TextView
    private lateinit var unitsInput: EditText
    private lateinit var capDay: EditText
    private lateinit var capWeek: EditText
    private lateinit var capMonth: EditText
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_compute)
        Ui.paintSystemBars(this, Color.rgb(7, 19, 26))

        status = findViewById(R.id.compute_status)
        statusTitle = findViewById(R.id.compute_status_title)
        statusDetail = findViewById(R.id.compute_status_detail)
        toggle = findViewById(R.id.compute_toggle)
        modeOff = findViewById(R.id.mode_off)
        modeOnDemand = findViewById(R.id.mode_ondemand)
        modeLend = findViewById(R.id.mode_lend)
        autoLend = findViewById(R.id.compute_auto_lend)
        hourUsed = findViewById(R.id.compute_hour_used)
        hourBar = findViewById(R.id.compute_hour_bar)
        dayUsed = findViewById(R.id.compute_day_used)
        dayBar = findViewById(R.id.compute_day_bar)
        dayCap = findViewById(R.id.compute_day_cap)
        weekUsed = findViewById(R.id.compute_week_used)
        weekBar = findViewById(R.id.compute_week_bar)
        weekCap = findViewById(R.id.compute_week_cap)
        monthUsed = findViewById(R.id.compute_month_used)
        monthBar = findViewById(R.id.compute_month_bar)
        monthCap = findViewById(R.id.compute_month_cap)
        balance = findViewById(R.id.compute_balance)
        earned = findViewById(R.id.compute_earned)
        landed = findViewById(R.id.compute_landed)
        lastSync = findViewById(R.id.compute_last_sync)
        cpuSeek = findViewById(R.id.lend_cpu)
        cpuLabel = findViewById(R.id.lend_cpu_label)
        minutesSeek = findViewById(R.id.lend_minutes)
        minutesLabel = findViewById(R.id.lend_minutes_label)
        unitsInput = findViewById(R.id.lend_units)
        capDay = findViewById(R.id.cap_day)
        capWeek = findViewById(R.id.cap_week)
        capMonth = findViewById(R.id.cap_month)

        AppNavigation.bind(this, "ComputeActivity")
        toggle.setOnClickListener { toggleCompute() }
        modeOff.setOnClickListener { setMode("off") }
        modeOnDemand.setOnClickListener { setMode("ondemand") }
        modeLend.setOnClickListener { setMode("lend") }
        findViewById<Button>(R.id.compute_save).setOnClickListener { savePlan() }
        findViewById<Button>(R.id.compute_refresh).setOnClickListener { refreshFromServer(true) }
        findViewById<Button>(R.id.compute_background).setOnClickListener { openBackgroundSettings() }
        autoLend.setOnCheckedChangeListener { _, checked -> prefs.autoLendOnStart = checked }

        cpuSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                cpuLabel.text = "${progress + 1}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        minutesSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                minutesLabel.text = "${progress.coerceAtLeast(1)} min"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        loadPlan()
        update()
    }

    override fun onResume() {
        super.onResume()
        update()
        refreshFromServer(false)
    }

    private fun loadPlan() {
        cpuSeek.progress = (prefs.lendCpuPct.coerceIn(1, 100) - 1)
        minutesSeek.progress = prefs.lendMinutesPerHour.coerceIn(1, 60)
        cpuLabel.text = "${cpuSeek.progress + 1}%"
        minutesLabel.text = "${minutesSeek.progress.coerceAtLeast(1)} min"
        unitsInput.setText(prefs.lendUnitsPerHour.toString())
        capDay.setText(prefs.capDayMinutes.toString())
        capWeek.setText(prefs.capWeekMinutes.toString())
        capMonth.setText(prefs.capMonthMinutes.toString())
        autoLend.isChecked = prefs.autoLendOnStart
    }

    private fun savePlan() {
        prefs.lendCpuPct = cpuSeek.progress + 1
        prefs.lendMinutesPerHour = minutesSeek.progress.coerceAtLeast(1)
        prefs.lendUnitsPerHour = unitsInput.text.toString().trim().toDoubleOrNull()
            ?.coerceIn(0.01, 100.0) ?: prefs.lendUnitsPerHour
        prefs.capDayMinutes = capDay.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
        prefs.capWeekMinutes = capWeek.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
        prefs.capMonthMinutes = capMonth.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
        update()
        Toast.makeText(this, "Plan saved", Toast.LENGTH_SHORT).show()
    }

    private fun setMode(mode: String) {
        prefs.computeMode = mode
        when (mode) {
            "off" -> {
                prefs.lendingEnabled = false
                prefs.computeEnabled = false
                stopWorker()
            }
            "ondemand" -> {
                prefs.lendingEnabled = false
                prefs.computeEnabled = true
                startWorker()
            }
            "lend" -> {
                prefs.lendingEnabled = true
                prefs.computeEnabled = true
                startWorker()
            }
        }
        update()
    }

    private fun toggleCompute() {
        if (!prefs.isRegistered()) {
            Toast.makeText(this, "Connect this device from Home first", Toast.LENGTH_SHORT).show()
            return
        }
        if (prefs.computeEnabled) {
            prefs.computeEnabled = false
            stopWorker()
        } else {
            if (prefs.computeMode == "off") prefs.computeMode = "ondemand"
            prefs.computeEnabled = true
            if (prefs.computeMode == "lend") prefs.lendingEnabled = true
            startWorker()
        }
        update()
    }

    private fun update() {
        prefs.resetComputeCountersIfNeeded()
        val mode = prefs.computeMode
        styleMode(modeOff, mode == "off")
        styleMode(modeOnDemand, mode == "ondemand")
        styleMode(modeLend, mode == "lend")

        if (!prefs.isRegistered()) {
            status.text = "OFFLINE"
            statusTitle.text = "Connect this node"
            statusDetail.text = "Return Home and enter the operator enrollment key."
            toggle.text = "Start compute"
            toggle.isEnabled = false
        } else if (!prefs.computeEnabled) {
            status.text = "PAUSED"
            statusTitle.text = "Not lending"
            statusDetail.text = "Start compute to accept work. Nothing runs while paused."
            toggle.text = "Start compute"
            toggle.isEnabled = true
        } else if (prefs.lendingEnabled) {
            status.text = "LIVE"
            statusTitle.text = "Lending is live"
            statusDetail.text = "Up to ${prefs.lendMinutesPerHour} min/hour at ${formatRate(prefs.lendUnitsPerHour)} CU/hour."
            toggle.text = "Pause compute"
            toggle.isEnabled = true
        } else {
            status.text = "ACTIVE"
            statusTitle.text = "On-demand compute"
            statusDetail.text = "Accepting work without earning CU. Switch to Lend to get paid."
            toggle.text = "Pause compute"
            toggle.isEnabled = true
        }

        val hourAllowance = prefs.lendMinutesPerHour.coerceAtLeast(1)
        val hourValue = prefs.creditMinutesUsed.coerceIn(0.0, hourAllowance.toDouble())
        hourUsed.text = String.format(Locale.US, "%.1f / %d min", hourValue, hourAllowance)
        hourBar.progress = ((hourValue / hourAllowance) * 100).toInt().coerceIn(0, 100)

        updateCap(dayUsed, dayBar, dayCap, prefs.computeDayMinutes, prefs.capDayMinutes, "daily")
        updateCap(weekUsed, weekBar, weekCap, prefs.computeWeekMinutes, prefs.capWeekMinutes, "weekly")
        updateCap(monthUsed, monthBar, monthCap, prefs.computeMonthMinutes, prefs.capMonthMinutes, "monthly")

        balance.text = String.format(Locale.US, "%.3f CU", prefs.walletBalance)
        earned.text = String.format(Locale.US, "Earned %.3f CU", prefs.walletEarned)
        landed.text = String.format(Locale.US, "%.3f CU", prefs.creditTotalUnits)
        val stamp = if (prefs.lastSyncAt > 0) {
            SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(prefs.lastSyncAt))
        } else {
            "never"
        }
        lastSync.text = "Last settled: $stamp" +
            if (prefs.lastError.isNotBlank()) " · ${prefs.lastError}" else ""
    }

    private fun styleMode(view: TextView, selected: Boolean) {
        view.backgroundTintList = ColorStateList.valueOf(
            if (selected) Color.rgb(95, 227, 192) else Color.rgb(20, 48, 57),
        )
        view.setTextColor(if (selected) Color.rgb(7, 19, 26) else Color.rgb(168, 200, 199))
    }

    private fun updateCap(
        valueView: TextView,
        bar: ProgressBar,
        label: TextView,
        used: Double,
        cap: Int,
        period: String,
    ) {
        valueView.text = String.format(Locale.US, "%.1f min", used)
        if (cap <= 0) {
            bar.progress = 0
            label.text = "No $period cap"
        } else {
            bar.progress = ((used / cap) * 100).toInt().coerceIn(0, 100)
            label.text = "Cap $cap min"
        }
    }

    private fun refreshFromServer(showToast: Boolean) {
        if (!prefs.isRegistered() || syncing) {
            if (showToast) Toast.makeText(this, "Connect this device from Home first", Toast.LENGTH_SHORT).show()
            return
        }
        syncing = true
        Thread {
            try {
                val client = PrimalClient(this, BuildConfig.PRIMAL_SERVER_URL, prefs)
                val heartbeat = client.heartbeat(prefs.computeEnabled, "idle")
                client.applyLendingRequest(heartbeat)
                client.applyServerWallet(heartbeat)
                prefs.lastError = ""
                prefs.lastSyncAt = System.currentTimeMillis()
                runOnUiThread {
                    syncing = false
                    update()
                    if (showToast) Toast.makeText(this, "Settlement refreshed", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                prefs.lastError = error.message ?: "sync failed"
                runOnUiThread {
                    syncing = false
                    update()
                    if (showToast) Toast.makeText(this, prefs.lastError, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun startWorker() {
        if (!prefs.isRegistered()) return
        ContextCompat.startForegroundService(this, Intent(this, WorkerService::class.java))
    }

    private fun stopWorker() {
        stopService(Intent(this, WorkerService::class.java))
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

    private fun formatRate(value: Double): String = String.format(Locale.US, "%.2f", value)
}
