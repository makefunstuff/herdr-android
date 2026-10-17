package com.makefunstuff.herdr.push

import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.AlertChange
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** Where notifications go: Android's notification shade on the phone, nowhere in the screen previews. */
interface AlertSink {
    /** Puts [alert] up, or replaces the one under its tag. [quiet]: no sound or pop-up. [note]: a line above its text. */
    fun show(alert: Alert, quiet: Boolean, note: String? = null)

    /** [alert] while one of its buttons is being carried out: [words] and no buttons. */
    fun working(alert: Alert, words: String)

    /** [alert] once its button was carried out: [words], gone after a moment. */
    fun finished(alert: Alert, words: String)

    fun cancel(tag: String)
}

/** The phone's other half: what only Android has (the push app, the permission, alarms). */
interface PushPhone {
    /** The phone's name, which machines show for this subscription. */
    val name: String

    /** Push apps (UnifiedPush distributors) on the phone, by package, ntfy first. */
    fun distributors(): List<String>

    fun label(pkg: String): String

    /** Asks the push app for an address (it answers later); false when there is none. */
    fun register(): Boolean

    fun unregister()

    /** Whether Android lets the app post notifications. */
    fun notificationsAllowed(): Boolean

    fun openNotificationSettings()

    /** Opens the store (or ntfy's page) to get a push app. */
    fun getPushApp()

    /** Sets or clears the alarm for the next look at the machines. */
    fun planChecks()
}

/**
 * Turns alert changes into notifications, each state once: a push and the app's own look can both
 * report an agent that asks, and a notification swiped away stays away until something new happens.
 */
class Inbox(private val file: File, private val sink: AlertSink) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Seen.serializer())
    private val seen: MutableMap<String, Seen> = load()

    @Serializable
    data class Seen(val sig: String, val at: Long)

    /** Shows what is new and takes down what is over. [quiet] while the app is on screen. */
    @Synchronized
    fun apply(changes: List<AlertChange>, quiet: Boolean, now: Long = System.currentTimeMillis()) {
        var dirty = false
        for (c in changes) when (c) {
            is AlertChange.Show -> {
                val a = c.alert
                if (a.sig.isNotEmpty() && seen[a.tag]?.sig == a.sig) continue
                sink.show(a, quiet)
                if (a.sig.isNotEmpty()) {
                    seen[a.tag] = Seen(a.sig, now)
                    dirty = true
                }
            }
            is AlertChange.Clear -> if (seen.remove(c.tag) != null) {
                sink.cancel(c.tag)
                dirty = true
            }
        }
        if (dirty) save(now)
    }

    /** Notes what the person already saw in the app, so a later look doesn't announce it. */
    @Synchronized
    fun markSeen(changes: List<AlertChange>, now: Long = System.currentTimeMillis()) {
        for (c in changes) if (c is AlertChange.Show && c.alert.sig.isNotEmpty()) seen[c.tag] = Seen(c.alert.sig, now)
        save(now)
    }

    /** Takes a notification down when its agent or task is opened in the app; it stays seen. */
    fun dismiss(tag: String) = sink.cancel(tag)

    fun show(alert: Alert, quiet: Boolean, note: String?) = sink.show(alert, quiet, note)
    fun working(alert: Alert, words: String) = sink.working(alert, words)
    fun finished(alert: Alert, words: String) = sink.finished(alert, words)

    private fun load(): MutableMap<String, Seen> =
        runCatching { if (file.exists()) json.decodeFromString(serializer, file.readText()).toMutableMap() else null }.getOrNull()
            ?: mutableMapOf()

    private fun save(now: Long) {
        // A week is plenty: an agent's state changes long before.
        seen.entries.removeAll { now - it.value.at > 7L * 24 * 3600 * 1000 }
        runCatching {
            val tmp = File(file.parentFile, "alerts.json.tmp")
            tmp.writeText(json.encodeToString(serializer, seen))
            tmp.renameTo(file)
        }
    }
}
