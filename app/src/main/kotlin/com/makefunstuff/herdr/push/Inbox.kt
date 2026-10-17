package com.makefunstuff.herdr.push

import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.AlertChange
import com.makefunstuff.herdr.core.push.PhoneQuiet
import com.makefunstuff.herdr.core.push.QuietHours
import com.makefunstuff.herdr.core.push.QuietSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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

    /** The notifications held while it was quiet ([held]), as one. */
    fun summary(held: List<Alert>, summary: QuietSummary, quiet: Boolean)

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

    /** Whether Android shows the app's Live Updates as such (Android 16 and later; the person can turn them off). Null before Android 16. */
    fun liveAllowed(): Boolean?

    /** Opens Android's switch for the app's Live Updates. */
    fun openLiveSettings()

    /** Opens the store (or ntfy's page) to get a push app. */
    fun getPushApp()

    /** Sets or clears the alarm for the next look at the machines. */
    fun planChecks()

    /** What the phone is in now that makes it quiet: Do Not Disturb (Bedtime turns it on) or car mode; null when neither. */
    fun quietMode(): PhoneQuiet?

    /** Sets the alarm that looks whether quiet hours ended at [at] (ms), or clears it when null. */
    fun planQuiet(at: Long?)
}

/**
 * Turns alert changes into notifications, each state once: a push and the app's own look can both
 * report an agent that asks, and a notification swiped away stays away until something new happens.
 * During quiet hours what can wait is held instead ([QuietHours.holds]), and [release]d as one.
 */
class Inbox(private val file: File, private val sink: AlertSink) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Seen.serializer())
    private val seen: MutableMap<String, Seen> = load()
    private val heldFile = File(file.parentFile, "held.json")
    private val heldSerializer = ListSerializer(Alert.serializer())
    /** Held while it is quiet, oldest first, one per tag; kept in a file, since a night outlasts the app's process. */
    private val held: LinkedHashMap<String, Alert> = loadHeld()
    private val _heldCount = MutableStateFlow(held.size)
    /** How many notifications wait for quiet hours to end. */
    val heldCount: StateFlow<Int> = _heldCount.asStateFlow()

    @Serializable
    data class Seen(val sig: String, val at: Long)

    /**
     * Shows what is new and takes down what is over. [quiet] while the app is on screen. [hold] during
     * quiet hours: what can wait is held for [release], and only what needs you shows.
     */
    @Synchronized
    fun apply(changes: List<AlertChange>, quiet: Boolean, hold: Boolean = false, now: Long = System.currentTimeMillis()) {
        var dirty = false
        var heldDirty = false
        for (c in changes) when (c) {
            is AlertChange.Show -> {
                val a = c.alert
                if (a.sig.isNotEmpty() && seen[a.tag]?.sig == a.sig) continue
                // A newer state replaces a held one under the same tag, held or shown.
                if (held.remove(a.tag) != null) heldDirty = true
                if (hold && QuietHours.holds(a)) {
                    held[a.tag] = a
                    heldDirty = true
                } else {
                    sink.show(a, quiet)
                }
                if (a.sig.isNotEmpty()) {
                    seen[a.tag] = Seen(a.sig, now)
                    dirty = true
                }
            }
            is AlertChange.Clear -> {
                // Over before quiet hours ended (an agent that finished is at work again): nothing to tell.
                if (held.remove(c.tag) != null) heldDirty = true
                if (seen.remove(c.tag) != null) {
                    sink.cancel(c.tag)
                    dirty = true
                }
            }
        }
        if (dirty) save(now)
        if (heldDirty) saveHeld()
    }

    /**
     * Quiet hours ended: what was held shows, as it would have (with its buttons) when it is one, or as
     * one summary. False when nothing was held.
     */
    @Synchronized
    fun release(quiet: Boolean): Boolean {
        if (held.isEmpty()) return false
        val all = held.values.toList()
        held.clear()
        saveHeld()
        if (all.size == 1) sink.show(all.single(), quiet) else sink.summary(all, QuietSummary.of(all), quiet)
        return true
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

    private fun loadHeld(): LinkedHashMap<String, Alert> {
        val list = runCatching { if (heldFile.exists()) json.decodeFromString(heldSerializer, heldFile.readText()) else null }.getOrNull().orEmpty()
        return LinkedHashMap<String, Alert>().apply { list.forEach { put(it.tag, it) } }
    }

    private fun saveHeld() {
        _heldCount.value = held.size
        runCatching {
            val tmp = File(file.parentFile, "held.json.tmp")
            tmp.writeText(json.encodeToString(heldSerializer, held.values.toList()))
            tmp.renameTo(heldFile)
        }
    }

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
