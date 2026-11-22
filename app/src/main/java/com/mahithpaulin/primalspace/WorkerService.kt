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
                val task = client.claim(prefs.computeEnabled)
                if (task == null) {
                    client.heartbeat(prefs.computeEnabled, "idle")
                    android.util.Log.i("PrimalSpace", "worker: heartbeat ok")
                    prefs.lastError = ""
                    prefs.lastSyncAt = System.currentTimeMillis()
                    delay(5000)
                    continue
                }
                val taskId = task.getString("id")
                client.heartbeat(prefs.computeEnabled, "running", taskId)
                val workload = task.optString("workload")
                if (workload != "http") {
                    client.submitResult(taskId, "failed", null, "unsupported_workload", 0)
                    continue
                }
                val params = task.getJSONObject("params")
                val started = System.currentTimeMillis()
                try {
                    val value = HttpWorkload.execute(params)
                    client.submitResult(taskId, "completed", value, null, System.currentTimeMillis() - started)
                } catch (error: Exception) {
                    client.submitResult(taskId, "failed", null, error.message ?: "execution_failed", System.currentTimeMillis() - started)
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
                        client.heartbeat(prefs.computeEnabled, "error", null, message)
                        prefs.lastSyncAt = System.currentTimeMillis()
                    } catch (ignored: Exception) {
                    }
                }
                delay(5000)
            }
        }
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
