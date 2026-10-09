package io.github.tuscani712.lanyard.transfer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.tuscani712.lanyard.SettingsHolder
import io.github.tuscani712.lanyard.core.ForegroundTransferPolicy
import io.github.tuscani712.lanyard.core.ForegroundTransferState
import io.github.tuscani712.lanyard.core.TransferLock
import io.github.tuscani712.lanyard.core.TransferTuning
import io.github.tuscani712.lanyard.core.isLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A `dataSync` foreground service that keeps transfers alive with the screen off
 * and mirrors their progress into an ongoing notification with a Cancel action.
 * It stops itself as soon as nothing is running or queued.
 *
 * While anything is active it also holds a partial wake lock (the CPU must not
 * sleep) and a Wi-Fi lock (the radio must not sleep between packets). Both are
 * acquired for the first active transfer and released when the last one ends —
 * the acquisition policy itself lives in the pure
 * [ForegroundTransferPolicy] so it is unit-tested without Android.
 *
 * A swipe-away is not a cancel: [onTaskRemoved] deliberately leaves the service
 * (and its locks) running while a transfer is in flight, and the service is
 * `START_STICKY`, so a transfer survives the task being removed from Recents.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val policy = ForegroundTransferPolicy()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TransferChannels.ensure(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val decision = policy.decide(TransferManager.running(), SettingsHolder.settings.value.speedUnit)
        val notification = buildNotification(decision)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        applyLocks(decision)
        scope.launch {
            // The active set is rebuilt on every state emission, but a progress
            // emission only redraws the notification text about once a second.
            // A change in which rows are active (start/finish) is always shown.
            var lastStructure = ""
            var lastNotifyAt = 0L
            TransferManager.state.collect { list ->
                val state = policy.decide(list, SettingsHolder.settings.value.speedUnit)
                if (!state.serviceRunning) {
                    releaseLocks()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    applyLocks(state)
                    val active = list.filter { it.state.isLive }
                    val now = System.currentTimeMillis()
                    val structure = active.joinToString("|") { "${it.id}:${it.state}" }
                    if (structure == lastStructure && now - lastNotifyAt < TransferTuning.DISPLAY_REFRESH_MS) return@collect
                    lastStructure = structure
                    lastNotifyAt = now
                    val notification = buildNotification(state)
                    ContextCompat.getSystemService(this@TransferService, NotificationManager::class.java)
                        ?.notify(NOTIFICATION_ID, notification)
                }
            }
        }
        // START_STICKY: if the system does kill the process, the service comes
        // back; a row that was live is marked interrupted and can resume.
        return START_STICKY
    }

    /**
     * The task was swiped away from Recents. A running transfer must not be
     * cancelled by that: the foreground service simply keeps going (and so do
     * the locks and the ongoing notification). Only an explicit Cancel, or the
     * transfer finishing, stops it.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Intentionally keep running. Nothing to do.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    /** Acquires exactly the locks [state] calls for, releasing the rest. */
    private fun applyLocks(state: ForegroundTransferState) {
        if (TransferLock.WAKE in state.locks) acquireWakeLock() else releaseWakeLock()
        if (TransferLock.WIFI in state.locks) acquireWifiLock() else releaseWifiLock()
    }

    // No timeout on purpose: the lock is held exactly as long as the foreground
    // service has active transfers, and is released the moment the last one ends
    // or the service is destroyed. A timeout would silently drop a long transfer.
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = ContextCompat.getSystemService(this, PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lanyard:transfer").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lanyard:transfer").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock = null
    }

    private fun releaseLocks() {
        releaseWakeLock()
        releaseWifiLock()
    }

    private fun buildNotification(state: ForegroundTransferState): Notification {
        val cancel = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, TransferCancelReceiver::class.java).setAction(TransferCancelReceiver.ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, TransferChannels.PROGRESS)
            .setSmallIcon(if (state.sending) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
            .setContentTitle(state.title)
            .setContentText(state.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Cancel", cancel)
        if (state.total > 0) {
            builder.setProgress(100, ((state.done * 100) / state.total).toInt().coerceIn(0, 100), false)
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
