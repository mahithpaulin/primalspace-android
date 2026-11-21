package com.mahithpaulin.primalspace

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WorkerService : android.app.Service() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        ServiceCompat.startForeground(this, 7, Notifications.build(this, "PrimalSpace", "Starting worker…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun runLoop() {
        val prefs = Prefs(this)
        val client = PrimalClient(
            this,
            prefs.serverUrl,
            prefs.enrollmentKey,
            prefs.deviceId,
            prefs.token,
            prefs.deviceName,
            prefs.installId,
        )
        while (scope.isActive) {
            try {
                if (prefs.deviceId.isEmpty() || prefs.token.isEmpty()) {
                    delay(5000)
                    continue
                }
                val task = client.claim(prefs.computeEnabled)
                if (task == null) {
                    client.heartbeat(prefs.computeEnabled, "idle")
                    delay(5000)
                    continue
                }
                val taskId = task.getString("id")
                client.heartbeat(prefs.computeEnabled, "running", taskId)
                val workload = task.optString("workload")
                if (workload != "hash_sum_v1") {
                    client.submitResult(taskId, "failed", null, "unsupported_workload", 0)
                    continue
                }
                val params = task.getJSONObject("params")
                val seed = params.getString("seed")
                val iterations = params.getInt("iterations")
                val chunks = params.getInt("chunks")
                val started = System.currentTimeMillis()
                try {
                    val value = HashWorkload.compute(seed, iterations, chunks)
                    client.submitResult(taskId, "completed", value, null, System.currentTimeMillis() - started)
                } catch (error: Exception) {
                    client.submitResult(taskId, "failed", null, error.message ?: "execution_failed", System.currentTimeMillis() - started)
                }
            } catch (error: Exception) {
                delay(5000)
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.mahithpaulin.primalspace.STOP"
    }
}
