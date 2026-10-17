// Stand-in for app/…/HerdrApp.kt (an Android Application): the same AppContainer, on a preview Context.
package com.makefunstuff.herdr

import android.content.Context
import com.makefunstuff.herdr.data.DraftStore
import com.makefunstuff.herdr.data.HostManager
import com.makefunstuff.herdr.data.IdentityStore
import com.makefunstuff.herdr.data.MachineStore
import com.makefunstuff.herdr.push.AlertWork
import com.makefunstuff.herdr.push.Inbox
import com.makefunstuff.herdr.push.PushHosts
import com.makefunstuff.herdr.push.PushStore
import preview.PreviewAlerts
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
    val push = PushStore(app)
    val inbox = Inbox(java.io.File(app.filesDir, "alerts.json"), PreviewAlerts)
    val phone = PreviewPhone
    val pushHosts = PushHosts(scope, hosts, machines, push, phone)
    val alertWork = AlertWork(hosts, machines, push, inbox)

    init {
        // The app is in front for the whole scene.
        hosts.setForeground(true)
    }

    /** Ends the scene: every machine session and the container's coroutines. */
    fun close() {
        hosts.setForeground(false)
        scope.cancel()
    }
}
