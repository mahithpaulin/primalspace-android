package com.mahithpaulin.primalspace

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

object AppNavigation {
    private data class Destination(
        val item: Int,
        val icon: Int,
        val label: Int,
        val target: Class<out AppCompatActivity>,
        val accent: Int,
    )

    private val destinations = listOf(
        Destination(R.id.nav_home, R.id.nav_home_icon, R.id.nav_home_label, MainActivity::class.java, Color.rgb(201, 243, 107)),
        Destination(R.id.nav_compute, R.id.nav_compute_icon, R.id.nav_compute_label, ComputeActivity::class.java, Color.rgb(95, 227, 192)),
        Destination(R.id.nav_work, R.id.nav_work_icon, R.id.nav_work_label, JobsActivity::class.java, Color.rgb(255, 184, 107)),
        Destination(R.id.nav_messages, R.id.nav_messages_icon, R.id.nav_messages_label, MessagesActivity::class.java, Color.rgb(37, 211, 102)),
        Destination(R.id.nav_agent, R.id.nav_agent_icon, R.id.nav_agent_label, AgentActivity::class.java, Color.rgb(182, 156, 255)),
        Destination(R.id.nav_settings, R.id.nav_settings_icon, R.id.nav_settings_label, SettingsActivity::class.java, Color.rgb(170, 182, 194)),
    )

    fun bind(activity: AppCompatActivity, active: String) {
        val inactive = Color.rgb(104, 117, 109)
        for (destination in destinations) {
            val item = activity.findViewById<View>(destination.item)
            val icon = activity.findViewById<ImageView>(destination.icon)
            val label = activity.findViewById<TextView>(destination.label)
            val selected = destination.target.simpleName == active
            item.setOnClickListener {
                if (destination.target == activity.javaClass) return@setOnClickListener
                activity.startActivity(Intent(activity, destination.target))
                activity.finish()
            }
            val color = if (selected) destination.accent else inactive
            icon.imageTintList = ColorStateList.valueOf(color)
            label.setTextColor(color)
            label.alpha = if (selected) 1f else 0.86f
            icon.alpha = if (selected) 1f else 0.82f
        }
    }
}
