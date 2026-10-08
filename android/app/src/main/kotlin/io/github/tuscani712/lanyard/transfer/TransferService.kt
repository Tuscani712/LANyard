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
import io.github.tuscani712.lanyard.core.TransferRecord
import io.github.tuscani712.lanyard.core.TransferState
import io.github.tuscani712.lanyard.core.TransferTuning
import io.github.tuscani712.lanyard.core.estimateEtaSeconds
import io.github.tuscani712.lanyard.core.formatBytes
import io.github.tuscani712.lanyard.core.formatRateAndEta
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
            // The active set is rebuilt on every state emission, but a progress
            // emission only redraws the notification text about once a second.
            // A change in which rows are active (start/finish) is always shown.
            var lastStructure = ""
            var lastNotifyAt = 0L
            TransferManager.state.collect { list ->
                val active = list.filter { it.state == TransferState.Running || it.state == TransferState.Queued }
                if (active.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val now = System.currentTimeMillis()
                    val structure = active.joinToString("|") { "${it.id}:${it.state}" }
                    if (structure == lastStructure && now - lastNotifyAt < TransferTuning.DISPLAY_REFRESH_MS) return@collect
                    lastStructure = structure
                    lastNotifyAt = now
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
        val unit = SettingsHolder.settings.value.speedUnit
        val text = when {
            active.isEmpty() -> "Preparing…"
            active.size == 1 -> {
                val a = active[0]
                // Every byte is in/out and the row is hashing, copying or waiting
                // for the receiver's confirmation: say so, with the size, rather
                // than a silent 100% (or, for a send, the honest <100% cap).
                if (a.finishing) {
                    val size = formatBytes(a.finishingBytes ?: a.total)
                    "Finishing… " + a.label + " · " + size
                } else {
                    val rate = formatRateAndEta(a.speed, a.etaSeconds, unit)
                    val suffix = if (rate.isNotEmpty()) " · $rate" else ""
                    (if (sending) "Sending " else "Receiving ") + a.label + suffix
                }
            }
            else -> {
                // Keep a rate + ETA visible even with several transfers in
                // flight: combined remaining at the combined smoothed rate.
                val combined = active.sumOf { it.speed }
                val remaining = active.sumOf { (it.total - it.done).coerceAtLeast(0L) }
                val rate = formatRateAndEta(combined, estimateEtaSeconds(remaining, combined), unit)
                val suffix = if (rate.isNotEmpty()) " · $rate" else ""
                val finishing = if (active.any { it.finishing }) " · Finishing…" else ""
                "${active.size} transfers$suffix$finishing"
            }
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
