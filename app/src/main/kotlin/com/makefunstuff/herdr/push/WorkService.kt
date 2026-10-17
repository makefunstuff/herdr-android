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
import com.makefunstuff.herdr.HerdrApp
import com.makefunstuff.herdr.R
import com.makefunstuff.herdr.core.push.Alert
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Work that has to finish while the app is closed: a notification's button (Approve, a reply, Approve
 * and merge…) and the app's own look at its machines when an alarm rings. A foreground service, so
 * Android lets it connect over SSH and keeps it alive until the work is done.
 */
class WorkService : Service() {
    private val jobs = AtomicInteger()
    @Volatile private var lastStart = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        val container = (application as HerdrApp).container
        val action = intent?.action
        val words = when (action) {
            ACT -> "Answering…"
            CHECK -> "Looking at your machines…"
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
        ServiceCompat.startForeground(this, ID, note, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        jobs.incrementAndGet()
        container.scope.launch {
            try {
                when (action) {
                    ACT -> {
                        val alert = intent.getStringExtra(ALERT)?.let { runCatching { AndroidAlerts.json.decodeFromString(Alert.serializer(), it) }.getOrNull() }
                        val chosen = alert?.actions?.getOrNull(intent.getIntExtra(INDEX, -1))
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY)?.toString()
                        if (alert != null && chosen != null) container.alertWork.perform(alert, chosen, text)
                    }
                    CHECK -> container.alertWork.checkAll()
                }
            } finally {
                if (action == CHECK) Wakes.plan(this@WorkService)
                if (jobs.decrementAndGet() == 0) {
                    ServiceCompat.stopForeground(this@WorkService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf(lastStart)
                }
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        const val ACT = "com.makefunstuff.herdr.ACT"
        const val CHECK = "com.makefunstuff.herdr.CHECK"
        const val ALERT = "alert"
        const val INDEX = "index"
        const val REPLY = "reply"
        private const val ID = 2
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
