package io.github.chuck852.linksonda.updater

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.chuck852.linksonda.MainActivity
import io.github.chuck852.linksonda.R

internal class UpdateNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun show(update: OfferedUpdate): Boolean {
        if (!notificationsAllowed()) return false
        ensureChannel()
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            UPDATE_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, UPDATE_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_linksonda)
            .setContentTitle(context.getString(R.string.update_notification_title, update.versionName))
            .setContentText(context.getString(R.string.update_notification_body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(UPDATE_NOTIFICATION_ID, notification)
        return true
    }

    fun cancel() {
        manager.cancel(UPDATE_NOTIFICATION_ID)
    }

    private fun notificationsAllowed(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            UPDATE_NOTIFICATION_CHANNEL,
            context.getString(R.string.update_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.update_notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }
}
