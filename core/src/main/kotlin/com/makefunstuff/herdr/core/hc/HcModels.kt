package com.makefunstuff.herdr.core.hc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/*
 * herdr-core's API (`hc schema`, OpenRPC, protocol generation 1) as the app reads it: only the
 * fields it uses, every one optional where the schema says so. Unknown fields are skipped, so a
 * newer hc (the API only grows) still decodes.
 */

/** A core: its permanent random [id] and display [name] (the hostname by default). */
@Serializable
data class HcCore(val id: String = "", val name: String = "")

/** `hello`'s answer. */
@Serializable
data class HcHello(
    val core: HcCore = HcCore(),
    val features: List<String> = emptyList(),
    val framed: Boolean = false,
    val generation: Int = 0,
    val methods: List<String> = emptyList(),
    val version: String = "",
)

/**
 * One OSC 7501 record, the program's own words: [state] is idle, working, done, blocked or error;
 * [kind] (blocked only) permission, question or auth; [app] who reports (`claude-code`, `codex`).
 */
@Serializable
data class HcStatus(
    val state: String = "",
    val kind: String? = null,
    val progress: Int? = null,
    val app: String = "",
    val title: String = "",
    val msg: String = "",
)

/** What runs in a terminal right now (its foreground process group's leader) and where. */
@Serializable
data class HcForeground(
    val argv: List<String> = emptyList(),
    val cwd: String = "",
    val name: String = "",
    val pid: Long = 0,
)

/** A terminal record (`Terminal` in the schema), live or finished. */
@Serializable
data class HcTerminal(
    /** `t7` here; `rohan/t7` for one on a machine this core added. */
    val address: String,
    val argv: List<String> = emptyList(),
    val cols: Int = 0,
    val rows: Int = 0,
    @SerialName("created_ms") val createdMs: Long = 0,
    val cwd: String = "",
    @SerialName("exit_code") val exitCode: Int? = null,
    @SerialName("finished_ms") val finishedMs: Long? = null,
    val foreground: HcForeground? = null,
    val labels: Map<String, String> = emptyMap(),
    val name: String? = null,
    val parent: String? = null,
    val pid: Long? = null,
    val reason: String? = null,
    val records: Map<String, HcStatus>? = null,
    val reported: Boolean = false,
    /** `live` or `finished`. */
    val state: String = "live",
    /** The root 7501 record; null if it never said, or after it exited. */
    val status: HcStatus? = null,
    @SerialName("status_ms") val statusMs: Long? = null,
    val template: String? = null,
    val title: String = "",
    /** Goes up each time the status leaves `working`. */
    val turn: Long = 0,
) {
    val live: Boolean get() = state == "live"
}

/** A machine this core added, and its line now. */
@Serializable
data class HcMachine(
    val name: String = "",
    val host: String = "",
    val id: String = "",
    /** connecting, connected, needs_login or unreachable. */
    val state: String = "",
    val target: String = "",
    val version: String = "",
    val why: String? = null,
)

/** `terminal.list`. */
@Serializable
data class HcList(
    val terminals: List<HcTerminal> = emptyList(),
    val machines: List<HcMachine> = emptyList(),
    @SerialName("hidden_finished") val hiddenFinished: Int = 0,
)

/** `terminal.read`: the screen plus history as text. */
@Serializable
data class HcRead(
    val text: String = "",
    val complete: Boolean = true,
    /** screen, history or stitched (a full-screen app, read by scrolling it). */
    val how: String = "",
    val why: String? = null,
)

/** `terminal.send`: [started] null when the program doesn't report status. */
@Serializable
data class HcSent(val started: Boolean? = null, val status: String? = null, val turn: Long = 0)

/** `terminal.keys`. */
@Serializable
data class HcKeysSent(val status: String? = null, val turn: Long = 0)

/** `terminal.interrupt`: [outcome] stopped, exited, still_working or unknown. */
@Serializable
data class HcInterrupted(val key: String = "", val outcome: String = "")

/** `events.subscribe`'s snapshot: events after [seq] follow on [channel]. */
@Serializable
data class HcSubscribed(val channel: Int? = null, val seq: Long = 0, val terminals: List<HcTerminal> = emptyList())

/** `terminal.attach`: the stream's [channel] and the terminal's record. */
@Serializable
data class HcAttached(val channel: Int, val terminal: HcTerminal)

/**
 * One notification on the event channel: `event` (a fact change, [type] as in the schema's README),
 * `heartbeat` (every 15 s, [seq] only) or `missed` (fell behind: [terminals] is a fresh snapshot).
 */
data class HcEvent(
    val method: String,
    val seq: Long,
    val type: String? = null,
    val terminal: String? = null,
    val machine: String? = null,
    val data: JsonElement? = null,
    val terminals: List<HcTerminal>? = null,
)

/** The data of `lost` and `finished` events. */
@Serializable
internal data class HcEnded(
    val reason: String? = null,
    @SerialName("exit_code") val exitCode: Int? = null,
    @SerialName("finished_ms") val finishedMs: Long? = null,
)

/** The data of a `status` event: all three replace the record's. */
@Serializable
internal data class HcStatusChange(val status: HcStatus? = null, val records: Map<String, HcStatus>? = null, val reported: Boolean = false)

internal val HcJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    coerceInputValues = true
}

internal inline fun <reified T> JsonObject.decode(): T = HcJson.decodeFromJsonElement(this)
