package com.mahithpaulin.primalspace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object Notifications {
    const val CHANNEL_ID = "primalspace_worker"
    const val CHANNEL_MESSAGES = "primalspace_messages"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "PrimalSpace worker", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Keeps the PrimalSpace device connected and eligible for compute"
        manager.createNotificationChannel(channel)
        val messages = NotificationChannel(CHANNEL_MESSAGES, "PrimalSpace messages", NotificationManager.IMPORTANCE_DEFAULT)
        messages.description = "Messages sent to this device by the operator or other devices"
        manager.createNotificationChannel(messages)
    }

    fun build(context: Context, title: String, text: String): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun notifyMessage(context: Context, title: String, text: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle(title)
            .setContentText(text.take(240))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(500)))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt() and 0x7fffffff, notification)
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
