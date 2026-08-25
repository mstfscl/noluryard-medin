package com.noluryard.autoclicker.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.noluryard.autoclicker.R
import com.noluryard.autoclicker.engine.RunStats
import com.noluryard.autoclicker.engine.StopReason
import com.noluryard.autoclicker.overlay.OverlayService
import com.noluryard.autoclicker.ui.MainActivity

object Notifications {

    const val CHANNEL_RUNNING = "clicker_running"
    const val CHANNEL_EVENTS = "clicker_events"

    const val ID_FOREGROUND = 1001
    const val ID_STOPPED = 1002

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        // LOW: kalici bildirim ses/titresim uretmesin, sadece dursun.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RUNNING,
                context.getString(R.string.notif_channel_running),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_running_desc)
                setShowBadge(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENTS,
                context.getString(R.string.notif_channel_events),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notif_channel_events_desc)
            }
        )
    }

    /** Foreground service bildirimi. Calisirken canli sayac, bostayken kisa ozet. */
    fun buildForeground(context: Context, stats: RunStats): Notification {
        val running = stats.running || stats.countingDown

        val builder = NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_target)
            .setContentTitle(
                context.getString(
                    if (running) R.string.notif_running_title else R.string.notif_idle_title
                )
            )
            .setContentText(Formatting.notificationLine(context, stats))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent(context))

        if (running) {
            builder.addAction(
                R.drawable.ic_stop,
                context.getString(R.string.notif_action_stop),
                serviceAction(context, OverlayService.ACTION_STOP_CLICKING),
            )
        } else {
            builder.addAction(
                R.drawable.ic_play,
                context.getString(R.string.notif_action_start),
                serviceAction(context, OverlayService.ACTION_START_CLICKING),
            )
            builder.addAction(
                R.drawable.ic_close,
                context.getString(R.string.notif_action_close),
                serviceAction(context, OverlayService.ACTION_SHUTDOWN),
            )
        }

        // Limitler varsa ilerleme cubugu bildirimde de gorunsun.
        val progress = stats.clickProgress ?: stats.timeProgress
        if (running && progress != null) {
            builder.setProgress(1000, (progress * 1000).toInt(), false)
        }

        return builder.build()
    }

    fun showStopped(context: Context, reason: StopReason, stats: RunStats) {
        val manager = NotificationManagerCompat.from(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stop)
            .setContentTitle(Formatting.stopTitle(reason))
            .setContentText(Formatting.stopBody(stats))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        // POST_NOTIFICATIONS reddedilmisse sistem sessizce yok sayar; yine de
        // SecurityException'a karsi koruma.
        runCatching { manager.notify(ID_STOPPED, notification) }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun serviceAction(context: Context, action: String): PendingIntent {
        val intent = Intent(context, OverlayService::class.java).setAction(action)
        return PendingIntent.getService(
            context, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
