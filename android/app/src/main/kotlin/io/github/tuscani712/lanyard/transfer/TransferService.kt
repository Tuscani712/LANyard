package io.github.tuscani712.lanyard.transfer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.tuscani712.lanyard.SettingsHolder
import io.github.tuscani712.lanyard.core.formatSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A `dataSync` foreground service that keeps transfers alive with the screen off
 * and mirrors their progress into an ongoing notification with a Cancel action.
 * It stops itself as soon as nothing is running or queued.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TransferChannels.ensure(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(TransferManager.running())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        scope.launch {
            TransferManager.state.collect { list ->
                val active = list.filter { it.state == TransferState.Running || it.state == TransferState.Queued }
                if (active.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val notification = buildNotification(active)
                    ContextCompat.getSystemService(this@TransferService, NotificationManager::class.java)
                        ?.notify(NOTIFICATION_ID, notification)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(active: List<TransferRecord>): Notification {
        val done = active.sumOf { it.done }
        val total = active.sumOf { it.total }
        val sending = active.any { it.direction == "send" }
        val text = when {
            active.isEmpty() -> "Preparing…"
            active.size == 1 -> {
                val a = active[0]
                val speed = if (a.speed > 0) " · " + formatSpeed(a.speed, SettingsHolder.settings.value.speedUnit) else ""
                (if (sending) "Sending " else "Receiving ") + a.label + speed
            }
            else -> "${active.size} transfers"
        }
        val cancel = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, TransferCancelReceiver::class.java).setAction(TransferCancelReceiver.ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, TransferChannels.PROGRESS)
            .setSmallIcon(if (sending) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
            .setContentTitle("LANyard")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Cancel", cancel)
        if (total > 0) {
            builder.setProgress(100, ((done * 100) / total).toInt().coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    companion object {
        private const val NOTIFICATION_ID = 0x1a4

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
        }
    }
}

/** Handles the notification's Cancel action. */
class TransferCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ACTION_CANCEL) TransferManager.cancelAllRunning()
    }

    companion object {
        const val ACTION_CANCEL = "io.github.tuscani712.lanyard.CANCEL_TRANSFERS"
    }
}
