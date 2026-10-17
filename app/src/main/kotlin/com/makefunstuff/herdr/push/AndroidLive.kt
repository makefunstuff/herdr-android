package com.makefunstuff.herdr.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.makefunstuff.herdr.HerdrApp
import com.makefunstuff.herdr.MainActivity
import com.makefunstuff.herdr.R
import com.makefunstuff.herdr.core.push.LiveItem
import com.makefunstuff.herdr.core.push.LiveItems

/**
 * [LiveSink] on Android: an ongoing notification with a segmented progress bar (ProgressStyle) that
 * asks to be promoted, so Android 16 shows it as a Live Update with a chip in the status bar. Older
 * Android versions show the same notification with a plain progress bar.
 */
class AndroidLive(private val context: Context) : LiveSink {
    private val nm = NotificationManagerCompat.from(context)

    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            // Not IMPORTANCE_MIN, which Android never promotes; silent, since it changes often.
            NotificationChannel(CHANNEL, "Running", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Board tasks and agents at work: their stage, progress and a Stop button (Live Updates)"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    override fun show(item: LiveItem, look: LiveLook, note: String?) {
        val ask = look == LiveLook.Ask
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(AMBER)
            .setContentTitle(item.title)
            .setContentText(
                when {
                    ask -> "Stop ${item.taskName}'s agent? Its run ends here; Continue on the board starts it again."
                    else -> note ?: item.text
                },
            )
            .setSubText(item.machineLabel)
            .setContentIntent(openIntent(item))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setRequestPromotedOngoing(true)
            .setTimeoutAfter(LiveWork.TIMEOUT_MS)
            .setStyle(progress(item))
        if (item.chip.isNotEmpty()) b.setShortCriticalText(item.chip)
        // Without words for the chip, the time it has run: since this part of the run began.
        if (item.since > 0) b.setWhen(item.since).setShowWhen(true).setUsesChronometer(true) else b.setShowWhen(false)
        when (look) {
            LiveLook.Normal -> b.addAction(0, "Stop", if (item.stopAsks) receiverIntent(item, LiveReceiver.ASK) else stopIntent(item))
            LiveLook.Ask -> {
                b.addAction(0, "Stop it", stopIntent(item))
                b.addAction(0, "Keep going", receiverIntent(item, LiveReceiver.KEEP))
            }
            LiveLook.Busy -> {}
        }
        // Without the permission there is nothing to post; the app's Settings say so.
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { nm.notify(item.tag, ID, b.build()) }
    }

    override fun cancel(tag: String) = nm.cancel(tag, ID)

    override fun active(): List<String> =
        context.getSystemService(NotificationManager::class.java).activeNotifications.mapNotNull { it.tag }.filter(LiveItems::isLive)

    /** A task: its four parts in the board's colours, filled up to where it is. An agent: going, with no end known. */
    private fun progress(item: LiveItem): NotificationCompat.ProgressStyle {
        val style = NotificationCompat.ProgressStyle()
        if (item.kind == LiveItem.Kind.Task) {
            style.setProgressSegments(LiveItems.Part.entries.map { NotificationCompat.ProgressStyle.Segment(it.length).setColor((0xFF000000 or it.tone.rgb).toInt()) })
            style.setProgress(item.progress ?: 0)
        } else {
            style.setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(LiveItems.MAX).setColor(AMBER)))
            style.setProgressIndeterminate(true)
        }
        return style
    }

    /** A tap: the task on the board, or the agent's chat. */
    private fun openIntent(item: LiveItem): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setData(Uri.parse("herdr://live/${Uri.encode(item.tag)}"))
            .putExtra(MainActivity.OPEN_MACHINE, item.machineId)
            .putExtra(MainActivity.OPEN_PANE, item.paneId)
            .putExtra(MainActivity.OPEN_TASK, item.taskId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, item.tag.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Stop: carried out by [WorkService], which may start from a notification while the app is closed. */
    private fun stopIntent(item: LiveItem): PendingIntent {
        val intent = Intent(context, WorkService::class.java)
            .setAction(WorkService.LIVE_STOP)
            .setData(Uri.parse("herdr://live/${Uri.encode(item.tag)}/stop"))
            .putExtra(WorkService.LIVE, AndroidAlerts.json.encodeToString(LiveItem.serializer(), item))
        return PendingIntent.getForegroundService(context, item.tag.hashCode() * 31 + 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Asking first, and Keep going: only the notification changes, so no service. */
    private fun receiverIntent(item: LiveItem, action: String): PendingIntent {
        val intent = Intent(context, LiveReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("herdr://live/${Uri.encode(item.tag)}/$action"))
            .putExtra(WorkService.LIVE, AndroidAlerts.json.encodeToString(LiveItem.serializer(), item))
        val code = item.tag.hashCode() * 31 + if (action == LiveReceiver.ASK) 2 else 3
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL = "live"
        private const val ID = 3
        private const val AMBER = 0xFFF0A830.toInt()
    }
}

/** A task's Stop (which asks first) and Keep going. */
class LiveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val live = (context.applicationContext as HerdrApp).container.liveWork
        val item = intent.getStringExtra(WorkService.LIVE)?.let { runCatching { AndroidAlerts.json.decodeFromString(LiveItem.serializer(), it) }.getOrNull() } ?: return
        when (intent.action) {
            ASK -> live.ask(item)
            KEEP -> live.keep(item)
        }
    }

    companion object {
        const val ASK = "com.makefunstuff.herdr.LIVE_ASK"
        const val KEEP = "com.makefunstuff.herdr.LIVE_KEEP"
    }
}
