package preview

import com.makefunstuff.herdr.brief.BriefSink
import com.makefunstuff.herdr.brief.BriefVoice
import com.makefunstuff.herdr.brief.Reading
import com.makefunstuff.herdr.bubble.BubbleChat
import com.makefunstuff.herdr.core.brief.Brief
import com.makefunstuff.herdr.core.brief.BriefLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.makefunstuff.herdr.bubble.Bubbles
import com.makefunstuff.herdr.bubble.Floated
import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.LiveItem
import com.makefunstuff.herdr.core.push.PhoneQuiet
import com.makefunstuff.herdr.core.push.QuietSummary
import com.makefunstuff.herdr.push.AlertSink
import com.makefunstuff.herdr.push.LiveLook
import com.makefunstuff.herdr.push.LiveSink
import com.makefunstuff.herdr.push.PushPhone

/** The phone's push side in the previews: by default ntfy installed, notifications and Live Updates allowed, no Do Not Disturb; no alarms. */
object PreviewPhone : PushPhone {
    var apps: List<String> = listOf("io.heckel.ntfy")
    var allowed = true
    var liveAllowed: Boolean? = true
    var quietMode: PhoneQuiet? = null
    override val name = "Pixel 8"
    override fun distributors() = apps
    override fun label(pkg: String) = if (pkg == "io.heckel.ntfy") "ntfy" else pkg
    override fun register() = true
    override fun unregister() {}
    override fun notificationsAllowed() = allowed
    override fun openNotificationSettings() {}
    override fun liveAllowed() = liveAllowed
    override fun openLiveSettings() {}
    override fun getPushApp() {}
    override fun planChecks() {}
    override fun quietMode() = quietMode
    override fun planQuiet(at: Long?) {}
}

/** Notifications go nowhere in the previews. */
object PreviewAlerts : AlertSink {
    override fun show(alert: Alert, quiet: Boolean, note: String?) {}
    override fun working(alert: Alert, words: String) {}
    override fun finished(alert: Alert, words: String) {}
    override fun summary(held: List<Alert>, summary: QuietSummary, quiet: Boolean) {}
    override fun cancel(tag: String) {}
}

/** Live Updates go nowhere in the previews either. */
object PreviewLive : LiveSink {
    override fun show(item: LiveItem, look: LiveLook, note: String?) {}
    override fun cancel(tag: String) {}
    override fun active(): List<String> = emptyList()
}

/** Bubbles in the previews: a phone that has them, allowed unless a scene says otherwise; nothing floats. */
object PreviewBubbles : Bubbles {
    var allowed = true
    override val supported = true
    override fun allowed(chat: BubbleChat) = allowed
    override fun float(chat: BubbleChat, title: String, machine: String) = if (allowed) Floated.Yes else Floated.BubblesOff
    override fun openSettings() {}
    override fun openNotificationSettings() {}
    override fun openApp(chat: BubbleChat) {}
}

/** The brief's notification and alarm in the previews: nothing posted, no alarm, not in a car. */
object PreviewBrief : BriefSink {
    override fun show(brief: Brief, quiet: Boolean) {}
    override fun cancel() {}
    override fun plan(at: Long?) {}
    override fun inCar() = false
}

/** Reading aloud in the previews: what would be read and where it is, without a sound. */
class PreviewVoice : BriefVoice {
    private val _reading = MutableStateFlow<Reading?>(null)
    override val reading: StateFlow<Reading?> = _reading.asStateFlow()
    override fun read(lines: List<BriefLine>, briefAt: Long) { _reading.value = Reading(lines, 0, briefAt) }
    override fun next() = _reading.update { it?.copy(index = it.index + 1) }
    override fun stop() { _reading.value = null }
}
