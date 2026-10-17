package com.makefunstuff.herdr.brief

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.makefunstuff.herdr.HerdrApp
import com.makefunstuff.herdr.MainActivity
import com.makefunstuff.herdr.R
import com.makefunstuff.herdr.core.brief.Brief
import com.makefunstuff.herdr.core.brief.BriefWords
import com.makefunstuff.herdr.push.WorkService
import java.time.ZoneId

/** [BriefSink] on Android: the brief's notification on its own channel, its alarm, and car mode. */
class AndroidBrief(private val context: Context) : BriefSink {
    private val nm = NotificationManagerCompat.from(context)

    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Brief", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "What your agents and the board did, at the times you chose"
            },
        )
    }

    override fun show(brief: Brief, quiet: Boolean) {
        val zone = ZoneId.systemDefault()
        val headline = BriefWords.headline(brief)
        val style = NotificationCompat.InboxStyle().setBigContentTitle(BriefWords.title(brief.at, zone)).setSummaryText(BriefWords.since(brief, zone))
        BriefWords.lines(brief).forEach { style.addLine(it) }
        val open = Intent(context, MainActivity::class.java)
            .setData(Uri.parse("herdr://brief"))
            .putExtra(MainActivity.OPEN_BRIEF, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFF0A830.toInt())
            .setContentTitle(BriefWords.title(brief.at, zone))
            .setContentText(headline)
            .setSubText(BriefWords.since(brief, zone))
            .setStyle(style)
            .setContentIntent(PendingIntent.getActivity(context, TAG.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(0, "Read aloud", readIntent(context, brief.at))
            .setAutoCancel(true)
            .setSilent(quiet)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { nm.notify(TAG, ID, b.build()) }
    }

    override fun cancel() = nm.cancel(TAG, ID)

    override fun plan(at: Long?) = BriefWakes.plan(context, at)

    override fun inCar(): Boolean = context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_CAR

    companion object {
        const val CHANNEL = "brief"
        private const val TAG = "brief"
        private const val ID = 1

        /** Read aloud, from the brief's notification: the service reads the brief made at [at]. */
        fun readIntent(context: Context, at: Long): PendingIntent = PendingIntent.getForegroundService(
            context, 41,
            Intent(context, WorkService::class.java).setAction(WorkService.READ).putExtra(WorkService.BRIEF_AT, at),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/**
 * The alarm for the next scheduled brief: exact, as it starts the service that reads the machines
 * from the background (an inexact alarm may not), and set again after each brief.
 */
object BriefWakes {
    const val BRIEF = "com.makefunstuff.herdr.BRIEF"

    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context, 3, Intent(context, BriefReceiver::class.java).setAction(BRIEF),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun plan(context: Context, at: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pi = intent(context)
        if (at == null) return alarms.cancel(pi)
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

/** The brief's alarm, and what drops or moves it: a restart, an update, a change of the clock or time zone. */
class BriefReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val work = (context.applicationContext as HerdrApp).container.briefWork
        if (intent.action != BriefWakes.BRIEF) return work.plan()
        try {
            context.startForegroundService(Intent(context, WorkService::class.java).setAction(WorkService.BRIEF))
        } catch (_: Exception) {
            // Android refused a start from the background: the next brief's alarm.
            work.plan()
        }
    }
}
