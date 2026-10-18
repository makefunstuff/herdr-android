package com.makefunstuff.herdr.push

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import android.app.NotificationManager
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import com.makefunstuff.herdr.AppContainer
import com.makefunstuff.herdr.HerdrApp
import com.makefunstuff.herdr.R
import com.makefunstuff.herdr.brief.AndroidVoice
import com.makefunstuff.herdr.brief.Reading
import com.makefunstuff.herdr.core.guard.Guarded
import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.LiveItem
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Work that has to finish while the app is closed: a notification's button (Approve, a reply, Approve
 * and merge…, a Live Update's Stop), the app's own look at its machines when an alarm rings, the
 * scheduled brief, and the brief read aloud. A foreground service, so Android lets it connect over
 * SSH and keeps it alive until the work is done (and the voice going with the screen off).
 */
class WorkService : Service() {
    private val jobs = AtomicInteger()
    @Volatile private var lastStart = 0
    /** The foreground types of the work under way: a reading and a look can run at once. */
    @Volatile private var types = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        val container = (application as HerdrApp).container
        val action = intent?.action
        val voice = container.briefWork.voice as AndroidVoice
        // The reading's own buttons, on its notification: the service is in the foreground already.
        when (action) {
            READ_STOP -> voice.stop()
            READ_NEXT -> voice.next()
        }
        // A scheduled brief that may be read aloud as it comes plays sound from the start, as a reading does.
        val aloud = action == READ || (action == BRIEF && container.briefWork.readsAloud())
        val words = when (action) {
            ACT -> "Answering…"
            LIVE_STOP -> "Stopping…"
            CHECK -> "Looking at your machines…"
            BRIEF -> "Making your brief…"
            READ -> "Reading the brief"
            else -> null
        }
        if (words == null) {
            if (jobs.get() == 0) stopSelf(startId)
            return START_NOT_STICKY
        }
        val note = NotificationCompat.Builder(this, AndroidAlerts.WORKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(words)
            .setSilent(true)
            .build()
        val type = when {
            Build.VERSION.SDK_INT < 29 -> 0
            action == READ -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            aloud -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        types = types or type
        ServiceCompat.startForeground(this, ID, note, types)
        jobs.incrementAndGet()
        container.scope.launch {
            try {
                when (action) {
                    ACT -> {
                        val alert = intent.getStringExtra(ALERT)?.let { runCatching { AndroidAlerts.json.decodeFromString(Alert.serializer(), it) }.getOrNull() }
                        val chosen = alert?.actions?.getOrNull(intent.getIntExtra(INDEX, -1))
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY)?.toString()
                        // A button that asks for the fingerprint comes through ConfirmActivity; one posted before
                        // that was turned on comes here straight, and is shown again with the button that asks.
                        val guarded = chosen?.guard?.let(Guarded::of)
                        if (alert != null && guarded != null && container.guard.wants(guarded) && !intent.getBooleanExtra(CONFIRMED, false)) {
                            container.inbox.show(alert, quiet = true, note = "Tap ${chosen.label} again to confirm it with your fingerprint.")
                        } else if (alert != null && chosen != null) container.alertWork.perform(alert, chosen, text)
                    }
                    LIVE_STOP -> {
                        val item = intent.getStringExtra(LIVE)?.let { runCatching { AndroidAlerts.json.decodeFromString(LiveItem.serializer(), it) }.getOrNull() }
                        if (item != null) container.liveWork.stop(item)
                    }
                    CHECK -> container.alertWork.checkAll()
                    BRIEF -> {
                        val brief = container.briefWork.scheduled()
                        if (brief != null && aloud) {
                            voice.prepare(container.briefWork.spoken(brief), brief.at)
                            readAloud(container, voice)
                        }
                    }
                    READ -> {
                        // The notification's Read aloud names its brief; the screen's has set what to read already.
                        val at = intent.getLongExtra(BRIEF_AT, 0)
                        val last = container.briefWork.store.state.value.last
                        if (at != 0L && last != null) voice.prepare(container.briefWork.spoken(last), last.at)
                        readAloud(container, voice)
                    }
                }
            } finally {
                if (action == CHECK) Wakes.plan(this@WorkService)
                if (jobs.decrementAndGet() == 0) {
                    types = 0
                    ServiceCompat.stopForeground(this@WorkService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf(lastStart)
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Reads what [voice] has set, with what it reads now on the service's notification and Next and Stop on it. */
    private suspend fun readAloud(container: AppContainer, voice: AndroidVoice) = coroutineScope {
        val shown = launch {
            voice.reading.collectLatest { r -> if (r != null) getSystemService(NotificationManager::class.java).notify(ID, reading(r)) }
        }
        try {
            voice.speak()?.let { container.briefWork.seen(it) }
        } finally {
            shown.cancel()
        }
    }

    private fun reading(r: Reading): android.app.Notification {
        fun button(action: String, code: Int) = android.app.PendingIntent.getService(
            this, code, Intent(this, WorkService::class.java).setAction(action), FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, AndroidAlerts.WORKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Reading the brief · ${r.index + 1} of ${r.lines.size}")
            .setContentText(r.line?.text.orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.line?.text.orEmpty()))
            .setSilent(true)
            .setOngoing(true)
            .addAction(0, "Next", button(READ_NEXT, 43))
            .addAction(0, "Stop", button(READ_STOP, 42))
            .build()
    }

    companion object {
        const val ACT = "com.makefunstuff.herdr.ACT"
        const val CHECK = "com.makefunstuff.herdr.CHECK"
        /** A Live Update's Stop. */
        const val LIVE_STOP = "com.makefunstuff.herdr.LIVE_STOP"
        const val LIVE = "live"
        /** The scheduled brief: its alarm rang. */
        const val BRIEF = "com.makefunstuff.herdr.BRIEF_NOW"
        /** Reads the brief aloud: the one [BRIEF_AT] names (its notification), or what the screen set. */
        const val READ = "com.makefunstuff.herdr.READ"
        const val READ_STOP = "com.makefunstuff.herdr.READ_STOP"
        const val READ_NEXT = "com.makefunstuff.herdr.READ_NEXT"
        const val BRIEF_AT = "brief_at"
        const val ALERT = "alert"
        const val INDEX = "index"
        /** Set by [com.makefunstuff.herdr.guard.ConfirmActivity] once the fingerprint was given. */
        const val CONFIRMED = "confirmed"
        const val REPLY = "reply"
        private const val ID = 2

        /** Carries out button [index] of the notification [alert] (its JSON). */
        fun act(context: Context, alert: String, index: Int, confirmed: Boolean = false): Intent =
            Intent(context, WorkService::class.java)
                .setAction(ACT)
                .setData(android.net.Uri.parse("herdr://alert/act/${alert.hashCode()}/$index"))
                .putExtra(ALERT, alert)
                .putExtra(INDEX, index)
                .putExtra(CONFIRMED, confirmed)
    }
}

/**
 * The app's own looks at its machines while it is closed: one exact alarm at a time, set again after
 * each look, as the app leaves the screen and after a restart. An exact alarm may start the
 * foreground service from the background; an inexact one may not.
 */
object Wakes {
    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, WakeReceiver::class.java).setAction(WorkService.CHECK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun plan(context: Context) {
        val container = (context.applicationContext as HerdrApp).container
        val st = container.push.state.value
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pi = intent(context)
        if (!st.on || st.checkMinutes <= 0 || container.machines.machines.value.none { it.enabled }) {
            alarms.cancel(pi)
            return
        }
        val at = System.currentTimeMillis() + st.checkMinutes * 60_000L
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

/** The alarm for a look, and the phone restarting or the app updating (which drop alarms). */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as HerdrApp).container
        if (intent.action != WorkService.CHECK || container.hosts.isForeground) {
            // On screen the app follows its machines live; after a restart, plan the next look.
            Wakes.plan(context)
            return
        }
        try {
            context.startForegroundService(Intent(context, WorkService::class.java).setAction(WorkService.CHECK))
        } catch (_: Exception) {
            // Android refused a start from the background (battery saver): try again at the next alarm.
            Wakes.plan(context)
        }
    }
}

/**
 * The alarm that looks whether quiet hours ended, so what was held shows: at the end of the schedule,
 * and every quarter of an hour while the phone is in Do Not Disturb or car mode (a closed app isn't
 * told when those end). It only posts a notification, so it needs no service.
 */
object QuietWakes {
    const val QUIET = "com.makefunstuff.herdr.QUIET"

    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context, 1, Intent(context, QuietReceiver::class.java).setAction(QUIET),
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

/** That alarm, and what moves it: a restart or update (which drop alarms), and the clock or time zone changing. */
class QuietReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as HerdrApp).container.quietWork.replan()
    }
}
