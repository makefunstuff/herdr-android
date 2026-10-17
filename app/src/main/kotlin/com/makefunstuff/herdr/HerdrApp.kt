package com.makefunstuff.herdr

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.makefunstuff.herdr.data.DraftStore
import com.makefunstuff.herdr.data.HostManager
import com.makefunstuff.herdr.data.IdentityStore
import com.makefunstuff.herdr.data.MachineStore
import com.makefunstuff.herdr.push.AlertWork
import com.makefunstuff.herdr.push.AndroidAlerts
import com.makefunstuff.herdr.push.AndroidLive
import com.makefunstuff.herdr.push.AndroidPhone
import com.makefunstuff.herdr.push.Inbox
import com.makefunstuff.herdr.push.LiveWork
import com.makefunstuff.herdr.push.PushHosts
import com.makefunstuff.herdr.push.PushStore
import com.makefunstuff.herdr.push.Wakes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
            override fun onStart(owner: LifecycleOwner) = container.hosts.setForeground(true)
            override fun onStop(owner: LifecycleOwner) {
                // What the screens showed was seen; the next look while closed reports only what is new.
                container.alertWork.leaving()
                container.hosts.setForeground(false)
                Wakes.plan(this@HerdrApp)
            }
        })
    }
}

class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val machines = MachineStore(app)
    val identity = IdentityStore(app, scope)
    val hosts = HostManager(scope, machines, identity)
    val drafts = DraftStore(app)
    val push = PushStore(app)
    val inbox = Inbox(java.io.File(app.filesDir, "alerts.json"), AndroidAlerts(app))
    val phone = AndroidPhone(app, push)
    val pushHosts = PushHosts(scope, hosts, machines, push, phone)
    val liveWork = LiveWork(scope, hosts, machines, push, AndroidLive(app))
    val alertWork = AlertWork(hosts, machines, push, inbox, liveWork)

    init {
        // Read the key up front so the public key is ready to show. A missing key is never made
        // here: the first launch offers to restore one first.
        scope.launch { runCatching { identity.load() } }
        pushHosts.start()
        liveWork.start()
        // The push app's address may have lapsed while the app was closed: ask again, it answers with the same or a new one.
        if (push.state.value.on) phone.register()
    }
}
