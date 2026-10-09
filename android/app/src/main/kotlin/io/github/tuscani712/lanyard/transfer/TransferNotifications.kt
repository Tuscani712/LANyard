package io.github.tuscani712.lanyard.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.tuscani712.lanyard.MainActivity
import io.github.tuscani712.lanyard.SettingsHolder
import io.github.tuscani712.lanyard.core.OpenFolder
import io.github.tuscani712.lanyard.core.TransferRecord
import io.github.tuscani712.lanyard.core.TransferState
import io.github.tuscani712.lanyard.core.formatSpeed

/**
 * The two notification channels transfers use: an ongoing, silent progress
 * channel, and a completion channel whose sound follows the user's setting.
 * Channel settings are immutable once created, so [ensure] updates them on
 * every service start; if the user has customized a channel Android ignores the
 * update rather than throwing.
 */
object TransferChannels {
    const val PROGRESS = "lanyard.transfers"
    const val DONE = "lanyard.transfers.done"

    fun ensure(context: Context) {
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return

        val progress = NotificationChannel(PROGRESS, "Transfer progress", NotificationManager.IMPORTANCE_LOW).apply {
            description = "File transfers in progress"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }

        val withSound = SettingsHolder.settings.value.soundOnComplete
        val done = NotificationChannel(DONE, "Transfer finished", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A transfer finished or failed"
        }
        if (withSound) {
            done.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), Notification.AUDIO_ATTRIBUTES_DEFAULT)
            done.enableVibration(true)
        } else {
            done.setSound(null, null)
            done.enableVibration(false)
        }

        runCatching { manager.createNotificationChannel(progress) }
        runCatching { manager.createNotificationChannel(done) }
    }
}

/** Posts a one-shot notification when a transfer finishes or fails. */
object TransferNotifications {
    private const val DONE_ID = 0x1a5

    fun completion(context: Context, record: TransferRecord) {
        if (!SettingsHolder.settings.value.notifications) return
        TransferChannels.ensure(context)
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return

        val ok = record.state == TransferState.Done
        val title = if (ok) "Transfer finished" else "Transfer failed"
        val text = buildString {
            append(if (record.direction == "send") "Sent " else "Received ")
            append(record.label.ifEmpty { "file" })
            // Name the device (its local alias when set, else the broadcast name),
            // so a completion is unambiguous when several peers are paired.
            record.peerName.takeIf { it.isNotBlank() && it != "A device" }?.let {
                append(if (record.direction == "send") " to " else " from ")
                append(it)
            }
            if (ok && record.averageSpeed > 0) {
                append(" · ")
                append(formatSpeed(record.averageSpeed, SettingsHolder.settings.value.speedUnit))
            }
            if (!ok) record.message?.takeIf { it.isNotBlank() }?.let { append(" · "); append(it) }
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, TransferChannels.DONE)
            .setSmallIcon(
                if (record.direction == "send") android.R.drawable.stat_sys_upload_done
                else android.R.drawable.stat_sys_download_done,
            )
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        // A finished receive gets a second action to open the folder it landed
        // in. A send, or a receive with no openable location, gets no action.
        if (ok && record.direction == "receive" && OpenFolder.targetFor(record.destinationUri) != null) {
            val openFolder = PendingIntent.getBroadcast(
                context, 1,
                Intent(context, TransferOpenFolderReceiver::class.java).apply {
                    action = TransferOpenFolderReceiver.ACTION_OPEN_FOLDER
                    putExtra(TransferOpenFolderReceiver.EXTRA_FOLDER, record.destinationUri)
                    putExtra(TransferOpenFolderReceiver.EXTRA_LABEL, record.destinationFolder)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            notification.addAction(0, "Open folder", openFolder)
        }
        manager.notify(DONE_ID, notification.build())
    }
}
