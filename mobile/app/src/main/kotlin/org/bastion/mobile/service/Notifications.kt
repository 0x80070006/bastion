package org.bastion.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import org.bastion.mobile.R
import org.bastion.mobile.ui.LostModeActivity
import org.bastion.mobile.ui.MainActivity

/** Notification channels and builders. Notifications never contain location or contact data. */
object Notifications {
    const val CHANNEL_PROTECTION = "protection"
    const val CHANNEL_ALARM = "alarm"
    const val CHANNEL_LOST = "lost"
    const val ID_PROTECTION = 1
    const val ID_ALARM = 2
    const val ID_LOST = 3

    fun createChannels(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_PROTECTION,
                    context.getString(R.string.channel_protection),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
                NotificationChannel(
                    CHANNEL_ALARM,
                    context.getString(R.string.channel_alarm),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { setSound(null, null) },
                NotificationChannel(
                    CHANNEL_LOST,
                    context.getString(R.string.channel_lost),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC },
            ),
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun protection(context: Context, active: Boolean): Notification =
        NotificationCompat.Builder(context, CHANNEL_PROTECTION)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.app_protection_title))
            .setContentText(
                context.getString(if (active) R.string.notif_protection_active else R.string.notif_protection_pending),
            )
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(context))
            .build()

    /** The alarm can only be stopped from the app, which requires the owner to authenticate. */
    fun alarm(context: Context): Notification = NotificationCompat.Builder(context, CHANNEL_ALARM)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle(context.getString(R.string.notif_alarm_title))
        .setContentText(context.getString(R.string.notif_alarm_text))
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setContentIntent(openApp(context))
        .build()

    fun lost(context: Context): Notification {
        val intent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, LostModeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_LOST)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.notif_lost_title))
            .setContentText(context.getString(R.string.notif_lost_text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(intent, true)
            .setContentIntent(intent)
            .build()
    }
}
