package preview

import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.push.AlertSink
import com.makefunstuff.herdr.push.PushPhone

/** The phone's push side in the previews: by default ntfy installed and notifications allowed; no alarms. */
object PreviewPhone : PushPhone {
    var apps: List<String> = listOf("io.heckel.ntfy")
    var allowed = true
    override val name = "Pixel 8"
    override fun distributors() = apps
    override fun label(pkg: String) = if (pkg == "io.heckel.ntfy") "ntfy" else pkg
    override fun register() = true
    override fun unregister() {}
    override fun notificationsAllowed() = allowed
    override fun openNotificationSettings() {}
    override fun getPushApp() {}
    override fun planChecks() {}
}

/** Notifications go nowhere in the previews. */
object PreviewAlerts : AlertSink {
    override fun show(alert: Alert, quiet: Boolean, note: String?) {}
    override fun working(alert: Alert, words: String) {}
    override fun finished(alert: Alert, words: String) {}
    override fun cancel(tag: String) {}
}
