package com.makefunstuff.herdr

import android.app.Application
import android.app.NotificationManager
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.makefunstuff.herdr.brief.AndroidBrief
import com.makefunstuff.herdr.brief.AndroidVoice
import com.makefunstuff.herdr.brief.BriefStore
import com.makefunstuff.herdr.brief.BriefWork
import com.makefunstuff.herdr.bubble.AndroidBubbles
import com.makefunstuff.herdr.bubble.Bubbles
import com.makefunstuff.herdr.data.DraftStore
import com.makefunstuff.herdr.data.HealthStore
import com.makefunstuff.herdr.data.HostManager
import com.makefunstuff.herdr.data.IdentityStore
import com.makefunstuff.herdr.data.MachineStore
import com.makefunstuff.herdr.data.Shares
import com.makefunstuff.herdr.push.AlertWork
import com.makefunstuff.herdr.push.AndroidAlerts
import com.makefunstuff.herdr.push.AndroidLive
import com.makefunstuff.herdr.push.AndroidPhone
import com.makefunstuff.herdr.push.Inbox
import com.makefunstuff.herdr.push.LiveWork
import com.makefunstuff.herdr.push.PushHosts
import com.makefunstuff.herdr.push.PushStore
import com.makefunstuff.herdr.push.QuietWork
import com.makefunstuff.herdr.push.TallyStore
import com.makefunstuff.herdr.push.Wakes
import com.makefunstuff.herdr.share.Widgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class HerdrApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Android ships a cut-down "BC" provider; sshj needs the full one for Ed25519 and friends.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)

        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.guard.started()
                container.hosts.setForeground(true)
            }
            override fun onStop(owner: LifecycleOwner) {
                // The app lock counts the time away from here.
                container.guard.stopped()
                // What the screens showed was seen; the next look while closed reports only what is new.
                container.alertWork.leaving()
                container.briefWork.leaving()
                container.hosts.setForeground(false)
                Wakes.plan(this@HerdrApp)
            }
        })
        // While the app runs, the phone leaving Do Not Disturb or car mode ends quiet hours at once
        // (Android tells only running apps; a closed one looks every quarter of an hour).
        val modes = IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED).apply {
            addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
            addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
        }
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                container.quietWork.check()
            }
        }, modes, ContextCompat.RECEIVER_NOT_EXPORTED)
        // The widgets follow the counts (their time to the minute) and the Coordinator used last.
        container.scope.launch {
            combine(
                container.tally.counts.map { it.copy(at = it.at / 60_000) }.distinctUntilChanged(),
                container.shares.last,
            ) { _, _ -> }.collect { Widgets.update(this@HerdrApp) }
        }
    }
}

class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val machines = MachineStore(app)
    val identity = IdentityStore(app, scope)
    val hosts = HostManager(scope, machines, identity)
    val drafts = DraftStore(app)
    val code = com.makefunstuff.herdr.data.CodeStore(java.io.File(app.filesDir, "code.json"))
    val health = HealthStore()
    val quick = com.makefunstuff.herdr.data.QuickStore(java.io.File(app.filesDir, "quick.json"))
    val shares = Shares(java.io.File(app.filesDir, "shares.json"), java.io.File(app.cacheDir, "shared"))
    // Pictures waiting in a composer are the app's own files; pictures sent are a cache Android may clear.
    val images = com.makefunstuff.herdr.data.ImageStore(
        java.io.File(app.filesDir, "images.json"), java.io.File(app.filesDir, "images"), java.io.File(app.cacheDir, "sent-images"),
    )
    val push = PushStore(app)
    /** Which actions ask for the fingerprint, and the app lock. */
    val guard = com.makefunstuff.herdr.data.GuardStore(java.io.File(app.filesDir, "guard.json"), com.makefunstuff.herdr.guard.AndroidUnlock(app))
    // With the app lock on, a locked phone shows its notifications without what they are about.
    val inbox = Inbox(java.io.File(app.filesDir, "alerts.json"), AndroidAlerts(app, appLock = { guard.state.value.appLock }))
    val phone = AndroidPhone(app, push)
    val pushHosts = PushHosts(scope, hosts, machines, push, phone)
    val quietWork = QuietWork(scope, push.state, phone, inbox, foreground = { hosts.isForeground })
    val liveWork = LiveWork(scope, hosts, machines, push, AndroidLive(app), quietWork)
    val tally = TallyStore(java.io.File(app.filesDir, "tally.json"), machines)
    val alertWork = AlertWork(hosts, machines, push, inbox, liveWork, quietWork, tally, quick)
    /** Every board's list as last read, and which finished agents the phone has shown: the Agents screen's and the Board tab's. */
    val boards = com.makefunstuff.herdr.data.BoardLists(hosts, onRead = { id, tasks -> tally.tasks(id, tasks) })
    val seen = com.makefunstuff.herdr.data.SeenStore(java.io.File(app.filesDir, "seen.json"))
    val bubbles: Bubbles = AndroidBubbles(app)
    val briefWork = BriefWork(
        scope, hosts, machines, BriefStore(java.io.File(app.filesDir, "brief.json")), AndroidBrief(app), AndroidVoice(app),
        quiet = { quietWork.check() },
    )

    init {
        // Read the key up front so the public key is ready to show. A missing key is never made
        // here: the first launch offers to restore one first.
        scope.launch { runCatching { identity.load() } }
        pushHosts.start()
        liveWork.start()
        quietWork.start()
        // The boards the app reads while on screen are what the person saw, for the brief.
        tally.start(scope, hosts, onBoard = { id, tasks -> boards.put(id, tasks); briefWork.looked(id, tasks, null) })
        briefWork.start()
        // Shared files waiting for a machine taken out of the app go with it.
        scope.launch { machines.machines.collect { list -> shares.keep(list.map { it.id }.toSet()) } }
        // The push app's address may have lapsed while the app was closed: ask again, it answers with the same or a new one.
        if (push.state.value.on) phone.register()
    }
}
