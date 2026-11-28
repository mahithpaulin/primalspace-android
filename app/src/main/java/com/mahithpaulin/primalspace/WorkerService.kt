package com.mahithpaulin.primalspace

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import org.json.JSONObject

class WorkerService : android.app.Service() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        try {
            val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PrimalSpace:worker").apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L)
            }
        } catch (_: Exception) {
        }
        ServiceCompat.startForeground(
            this,
            7,
            Notifications.build(this, "PrimalSpace", "Starting worker…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        job = scope.launch { runLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.coroutineContext[Job]?.cancel()
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun runLoop() {
        val prefs = Prefs(this)
        val client = PrimalClient(this, BuildConfig.PRIMAL_SERVER_URL, prefs)
        while (scope.isActive) {
            try {
                if (!prefs.isRegistered()) {
                    android.util.Log.i("PrimalSpace", "worker: enrolling")
                    client.enrollIfNeeded()
                    android.util.Log.i("PrimalSpace", "worker: enrolled")
                    delay(1000)
                    continue
                }
                pollMessages(client, prefs)
                val cap = prefs.capReached()
                if (cap != null) {
                    android.util.Log.i("PrimalSpace", "worker: compute cap reached ($cap)")
                    client.heartbeat(false, "capped", null, cap)
                    prefs.lastError = cap
                    delay(10000)
                    continue
                }
                val computeOn = prefs.computeEnabled && prefs.computeMode != "off"
                if (!computeOn) {
                    client.heartbeat(false, "idle")
                    delay(10000)
                    continue
                }
                if (allowanceReached(prefs)) {
                    android.util.Log.i("PrimalSpace", "worker: lending allowance reached for this hour")
                    val heartbeat = client.heartbeat(true, "capped", null, "lending_hourly_limit")
                    client.applyLendingRequest(heartbeat)
                    client.applyServerWallet(heartbeat)
                    delay(5000)
                    continue
                }
                val task = client.claim(true)
                if (task == null) {
                    val heartbeat = client.heartbeat(true, "idle")
                    client.applyLendingRequest(heartbeat)
                    client.applyServerWallet(heartbeat)
                    android.util.Log.i("PrimalSpace", "worker: heartbeat ok")
                    prefs.lastError = ""
                    prefs.lastSyncAt = System.currentTimeMillis()
                    delay(5000)
                    continue
                }
                val taskId = task.getString("id")
                val heartbeat = client.heartbeat(true, "running", taskId)
                client.applyLendingRequest(heartbeat)
                client.applyServerWallet(heartbeat)
                val executing = client.markExecuting(taskId).optJSONObject("task")
                if (executing?.optString("status") != "executing") continue
                val workload = task.optString("workload")
                val params = task.getJSONObject("params")
                val started = System.currentTimeMillis()
                try {
                    val cpuStarted = android.os.Process.getElapsedCpuTime()
                    val value = when (workload) {
                        "http" -> HttpWorkload.execute(params)
                        "shell" -> ShellWorkload.execute(
                            params,
                            task.optJSONObject("resource_limits")?.optInt("max_seconds", 10) ?: 10,
                            File(cacheDir, "workloads"),
                        )
                        else -> throw IllegalArgumentException("unsupported_workload")
                    }
                    val elapsed = System.currentTimeMillis() - started
                    val cpuUsed = (android.os.Process.getElapsedCpuTime() - cpuStarted).toDouble()
                    val cpuPct = if (elapsed > 0) cpuUsed / elapsed.toDouble() * 100.0 else 0.0
                    val metrics = JSONObject()
                        .put("worker_cpu_pct_avg", cpuPct.coerceIn(0.0, 100.0))
                        .put("cpu_load_pct", 0.0)
                    client.submitResult(taskId, "completed", value, null, elapsed, metrics)
                    accrueUnits(prefs, elapsed, cpuUsed)
                } catch (error: Exception) {
                    val elapsed = System.currentTimeMillis() - started
                    val metrics = JSONObject()
                        .put("worker_cpu_pct_avg", 0.0)
                        .put("cpu_load_pct", 0.0)
                    client.submitResult(taskId, "failed", null, error.message ?: "execution_failed", elapsed, metrics)
                    consumeAllowance(prefs, elapsed)
                }
                prefs.lastError = ""
                prefs.lastSyncAt = System.currentTimeMillis()
            } catch (error: Exception) {
                android.util.Log.e("PrimalSpace", "worker: error", error)
                val message = error.message ?: error.javaClass.simpleName
                prefs.lastError = message
                if (error is ApiException && (error.status == 401 || error.status == 404)) {
                    prefs.clearRegistration()
                    try {
                        client.enrollIfNeeded()
                    } catch (enrollError: Exception) {
                        prefs.lastError = enrollError.message ?: "re-enroll failed"
                    }
                } else {
                    try {
                        client.heartbeat(prefs.computeEnabled && prefs.computeMode != "off", "error", null, message)
                        prefs.lastSyncAt = System.currentTimeMillis()
                    } catch (ignored: Exception) {
                    }
                }
                delay(5000)
            }
        }
    }

    private fun allowanceReached(prefs: Prefs): Boolean {
        if (!prefs.lendingEnabled) return false
        val window = System.currentTimeMillis() - (System.currentTimeMillis() % 3600000L)
        if (prefs.creditWindowStart != window) return false
        return prefs.creditMinutesUsed >= prefs.lendMinutesPerHour
    }

    private fun accrueUnits(prefs: Prefs, durationMs: Long, cpuMs: Double) {
        if (!prefs.lendingEnabled) return
        val minutes = durationMs / 60000.0
        consumeAllowance(prefs, durationMs)
        val wallSeconds = durationMs / 1000.0
        val cpuRatio = if (wallSeconds > 0) (cpuMs / 1000.0 / wallSeconds).coerceIn(0.0, 1.0) else 0.0
        val promisedCpu = prefs.lendCpuPct.coerceIn(1, 100) / 100.0
        val cpuFactor = (cpuRatio / promisedCpu).coerceIn(0.0, 1.0)
        val earned = (durationMs / 3600000.0) * prefs.lendUnitsPerHour * cpuFactor
        if (earned > 0.0) {
            prefs.creditUnits = prefs.creditUnits + earned
            prefs.creditTotalUnits = prefs.creditTotalUnits + earned
            prefs.walletBalance = prefs.walletBalance + earned
            prefs.walletEarned = prefs.walletEarned + earned
            android.util.Log.i("PrimalSpace", "earned " + earned + " CU (cpu " + cpuRatio + ", balance " + prefs.walletBalance + ")")
        }
    }

    private fun consumeAllowance(prefs: Prefs, durationMs: Long) {
        val now = System.currentTimeMillis()
        val window = now - (now % 3600000L)
        if (prefs.creditWindowStart != window) {
            prefs.creditWindowStart = window
            prefs.creditMinutesUsed = 0.0
        }
        prefs.creditMinutesUsed = minOf(60.0, prefs.creditMinutesUsed + durationMs / 60000.0)
        prefs.addComputeMinutes(durationMs / 60000.0)
    }

    private fun pollMessages(client: PrimalClient, prefs: Prefs) {
        try {
            val cursor = prefs.messageCursor.takeIf { it.isNotBlank() }
            val messages = client.getMessages(cursor)
            if (messages.length() == 0) return
            var newest = cursor
            for (index in 0 until messages.length()) {
                val message = messages.getJSONObject(index)
                prefs.addMessage(message)
                val credit = message.optJSONObject("credit")
                if (credit != null) {
                    val kind = credit.optString("kind")
                    val amount = credit.optDouble("amount", 0.0)
                    val transferId = credit.optString("transfer_id").takeIf { it.isNotBlank() }
                    if (amount > 0.0 && prefs.applyCredit(kind, amount, transferId)) {
                        android.util.Log.i("PrimalSpace", "credit " + kind + " " + amount + " CU (balance " + prefs.walletBalance + ")")
                    }
                }
                val from = message.optString("from")
                val label = if (from == "operator") "Operator" else "Device"
                Notifications.notifyMessage(this, "Message from $label", message.optString("body"))
                val created = message.optString("created_at")
                if (created.isNotBlank() && (newest == null || created > newest)) newest = created
            }
            if (newest != null) prefs.messageCursor = newest
        } catch (error: Exception) {
            android.util.Log.w("PrimalSpace", "message poll failed: ${error.message}")
        }
    }

    companion object {
        const val ACTION_STOP = "com.mahithpaulin.primalspace.STOP"
    }
}
