// Stand-in for app/…/HerdrApp.kt (an Android Application): the same AppContainer, on a preview Context.
package com.makefunstuff.herdr

import android.content.Context
import com.makefunstuff.herdr.data.DraftStore
import com.makefunstuff.herdr.data.HealthStore
import com.makefunstuff.herdr.data.HostManager
import com.makefunstuff.herdr.data.IdentityStore
import com.makefunstuff.herdr.data.MachineStore
import com.makefunstuff.herdr.data.Shares
import com.makefunstuff.herdr.push.AlertWork
import com.makefunstuff.herdr.push.Inbox
import com.makefunstuff.herdr.push.LiveWork
import com.makefunstuff.herdr.push.PushHosts
import com.makefunstuff.herdr.push.PushStore
import com.makefunstuff.herdr.push.QuietWork
import com.makefunstuff.herdr.push.TallyStore
import com.makefunstuff.herdr.bubble.Bubbles
import com.makefunstuff.herdr.brief.BriefStore
import com.makefunstuff.herdr.brief.BriefWork
import preview.Demo
import preview.PreviewAlerts
import preview.PreviewBrief
import preview.PreviewVoice
import preview.PreviewBubbles
import preview.PreviewLive
import preview.PreviewPhone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class AppContainer(app: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val machines = MachineStore(app)
    val identity = IdentityStore(app, scope)
    val hosts = HostManager(scope, machines, identity)
    val drafts = DraftStore(app)
    // At the demo's fixed time, so "2 h ago" reads the same every run.
    val code = com.makefunstuff.herdr.data.CodeStore(java.io.File(app.filesDir, "code.json"), now = { Demo.NOW })
    val health = HealthStore()
    val quick = com.makefunstuff.herdr.data.QuickStore(java.io.File(app.filesDir, "quick.json"))
    val shares = Shares(java.io.File(app.filesDir, "shares.json"), java.io.File(app.filesDir, "shared"))
    val push = PushStore(app)
    val inbox = Inbox(java.io.File(app.filesDir, "alerts.json"), PreviewAlerts)
    val phone = PreviewPhone
    val pushHosts = PushHosts(scope, hosts, machines, push, phone)
    val quietWork = QuietWork(scope, push.state, phone, inbox, foreground = { hosts.isForeground })
    val liveWork = LiveWork(scope, hosts, machines, push, PreviewLive, quietWork)
    val tally = TallyStore(java.io.File(app.filesDir, "tally.json"), machines)
    val alertWork = AlertWork(hosts, machines, push, inbox, liveWork, quietWork, tally, quick)
    val bubbles: Bubbles = PreviewBubbles
    // The brief at the demo's fixed time, on UTC, so it reads the same every run.
    val briefWork = BriefWork(
        scope, hosts, machines, BriefStore(java.io.File(app.filesDir, "brief.json")), PreviewBrief, PreviewVoice(),
        zone = { java.time.ZoneOffset.UTC }, clock = { Demo.NOW },
    )

    init {
        // The app is in front for the whole scene.
        hosts.setForeground(true)
        // What quiet hours mean now, before the first frame.
        quietWork.check()
        briefWork.start()
    }

    /** Ends the scene: every machine session and the container's coroutines. */
    fun close() {
        hosts.setForeground(false)
        scope.cancel()
    }
}
