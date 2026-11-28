package com.mahithpaulin.primalspace

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

class JobsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var summary: TextView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_jobs)
        Ui.paintSystemBars(this, Color.rgb(15, 12, 8))
        summary = findViewById(R.id.history_summary)
        list = findViewById(R.id.history_list)
        findViewById<Button>(R.id.history_refresh).setOnClickListener { refresh() }
        AppNavigation.bind(this, "JobsActivity")
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (!prefs.isRegistered()) {
            summary.text = "Connect the device to load monitor history."
            clearList()
            addEmpty("No device history yet.")
            return
        }
        summary.text = "Loading history…"
        Thread {
            try {
                val client = PrimalClient(this, BuildConfig.PRIMAL_SERVER_URL, prefs)
                val payload = client.getHistory(50)
                runOnUiThread { render(payload) }
            } catch (error: Exception) {
                runOnUiThread {
                    summary.text = error.message ?: "Could not load history."
                    clearList()
                    addEmpty("History is unavailable.")
                }
            }
        }.start()
    }

    private fun render(payload: JSONObject) {
        val stats = payload.optJSONObject("summary") ?: JSONObject()
        summary.text = "${stats.optInt("jobs")} jobs · ${stats.optInt("completed")} completed · ${stats.optInt("failed")} failed · ${stats.optInt("active")} active"
        clearList()
        val jobs = payload.optJSONArray("jobs") ?: org.json.JSONArray()
        if (jobs.length() == 0) {
            addEmpty("No distributed jobs have run on this device yet.")
        } else {
            addHeading("Job history")
            for (index in 0 until jobs.length()) {
                addJobCard(jobs.getJSONObject(index))
            }
        }
        val tasks = payload.optJSONArray("tasks") ?: org.json.JSONArray()
        val standalone = mutableListOf<JSONObject>()
        for (index in 0 until tasks.length()) {
            val task = tasks.getJSONObject(index)
            if (task.optString("job_id").isBlank()) standalone.add(task)
        }
        if (standalone.isNotEmpty()) {
            addHeading("Standalone tasks")
            for (task in standalone) addTaskCard(task)
        }
    }

    private fun addJobCard(job: JSONObject) {
        val progress = job.optJSONObject("progress") ?: JSONObject()
        val completed = progress.optInt("completed")
        val total = progress.optInt("total")
        val shards = job.optJSONArray("shards") ?: org.json.JSONArray()
        val card = card()
        card.addView(text("${job.optString("workload")} job · ${shortId(job.optString("id"))}", 16, Color.rgb(245, 238, 229), true))
        card.addView(text("${job.optString("status")} · $completed/$total shards · ${formatTime(job.optString("created_at"))}", 12, Color.rgb(179, 155, 126), false))
        for (index in 0 until shards.length()) {
            val shard = shards.getJSONObject(index)
            val detail = shard.optString("error").ifBlank { shard.opt("value")?.toString().orEmpty() }.let(::shortDetail)
            val line = "Shard ${shard.optInt("index") + 1} · ${shard.optString("status")}" + if (detail.isNotBlank()) " · $detail" else ""
            card.addView(text(line, 12, Color.rgb(133, 116, 94), false))
        }
        list.addView(card)
    }

    private fun addTaskCard(task: JSONObject) {
        val card = card()
        card.addView(text("${task.optString("workload")} task · ${shortId(task.optString("id"))}", 15, Color.rgb(245, 238, 229), true))
        card.addView(text("${task.optString("status")} · ${formatTime(task.optString("created_at"))}", 12, Color.rgb(179, 155, 126), false))
        val detail = task.optString("error").ifBlank { task.opt("result_value")?.toString().orEmpty() }.let(::shortDetail)
        if (detail.isNotBlank()) card.addView(text(detail, 12, Color.rgb(133, 116, 94), false))
        list.addView(card)
    }

    private fun card(): LinearLayout {
        val view = LinearLayout(this)
        view.orientation = LinearLayout.VERTICAL
        view.setPadding(16, 14, 16, 14)
        val margin = (10 * resources.displayMetrics.density).toInt()
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.topMargin = margin
        view.layoutParams = params
        view.background = GradientDrawable().apply {
            setColor(Color.rgb(26, 23, 18))
            cornerRadius = 14f
            setStroke(1, Color.rgb(58, 48, 37))
        }
        return view
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean): TextView {
        return TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 5)
        }
    }

    private fun addHeading(value: String) {
        list.addView(text(value, 12, Color.rgb(255, 184, 107), true).apply {
            setPadding(0, 16, 0, 2)
        })
    }

    private fun addEmpty(value: String) {
        list.addView(text(value, 13, Color.rgb(133, 116, 94), false).apply {
            setPadding(0, 18, 0, 18)
        })
    }

    private fun clearList() {
        list.removeAllViews()
    }

    private fun shortId(value: String): String = if (value.length > 8) value.take(8) else value

    private fun shortDetail(value: String): String = if (value.length > 240) value.take(240) + "..." else value

    private fun formatTime(value: String): String {
        if (value.isBlank()) return "unknown time"
        return try {
            SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(value))
        } catch (_: Exception) {
            value
        }
    }
}
