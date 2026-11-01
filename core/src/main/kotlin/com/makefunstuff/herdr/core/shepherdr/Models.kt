package com.makefunstuff.herdr.core.shepherdr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Shepherdr 2's view model as its server sends it (`shared/types.ts` in its checkout): only the parts
 * the app uses. Unknown fields are skipped and an unknown status reads as the field's default, so a
 * newer server still parses.
 */
val ShepherdrJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
}

/** A space's or an agent's mark (`Status`): red needs you, green ready, amber working, grey the rest. */
@Serializable
enum class V2Status {
    @SerialName("need") Need,
    @SerialName("ready") Ready,
    @SerialName("merging") Merging,
    @SerialName("work") Work,
    @SerialName("idle") Idle,
    @SerialName("queued") Queued,
    @SerialName("todo") Todo,
    @SerialName("done") Done,
    @SerialName("off") Off,
}

/** Where a task stands (`TaskStatus`). */
@Serializable
enum class V2TaskStatus {
    /** Its agent asks or waits for you. */
    @SerialName("need") Need,
    /** Finished, waits for you to merge it. */
    @SerialName("ready") Ready,
    /** On its way into main: nothing for you to do. */
    @SerialName("merging") Merging,
    @SerialName("work") Work,
    /** Waits for other tasks ([V2Task.after]). */
    @SerialName("queued") Queued,
    /** Its agent waits for a go. */
    @SerialName("todo") Todo,
    @SerialName("done") Done,
}

/** `GET /api/version`: which Shepherdr answers. */
@Serializable
data class V2Version(val root: String = "", val version: String = "", val supervisor: Long? = null) {
    /** The major version, 0 when it can't be read. */
    val major: Int get() = version.trim().removePrefix("v").substringBefore('.').toIntOrNull() ?: 0

    /** The minor version, 0 when it can't be read. */
    val minor: Int get() = version.trim().removePrefix("v").split('.').getOrNull(1)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0

    /**
     * Shepherdr 2.1 or later, the standalone app on herdr-core: its API takes no `shepherdr` command
     * (`/api/cli`), so a merge is the human's answer on the task's merge card.
     */
    val standalone: Boolean get() = major > 2 || major == 2 && minor >= 1
}

/** `GET /api/waiting`: how many things wait on you, and their alert tags. */
@Serializable
data class V2Waiting(val count: Int = 0, val tags: List<String> = emptyList())

/** A space (`Place`): a folder on a machine with its own coordinator, whose chat key is [chat]. */
@Serializable
data class V2Place(
    val id: String,
    /** Slack-channel style ("herdr-android"), unique on its machine. */
    val name: String = "",
    /** Its machine's key. */
    val machine: String = "",
    /** The machine's home space (`~`). */
    val home: Boolean = false,
    /** 2: its own tasks; 1: its machine's (a home space); 0: every machine's. */
    val level: Int = 2,
    val status: V2Status = V2Status.Idle,
    /** `regular` or `autopilot`. */
    val mode: String = "regular",
    /** Its coordinator's chat key. */
    val chat: String = "",
    val unreachableSince: String? = null,
    val connecting: Boolean = false,
    val githubRemote: Boolean = false,
    val herdrDown: String? = null,
)

/** A machine (`Machine`), as a heading over its spaces. */
@Serializable
data class V2Machine(
    /** `m:<key>`. */
    val id: String,
    /** Its key: herdr's label for it. */
    val machine: String = "",
    val name: String = "",
    val emoji: String? = null,
    /** `idle`, or `off` when Shepherdr can't reach it. */
    val status: V2Status = V2Status.Idle,
    val unreachableSince: String? = null,
    val connecting: Boolean = false,
    val herdrDown: String? = null,
)

@Serializable
data class V2Pr(val number: Int = 0, val url: String = "")

/** What a task's agent last reported (`shepherdr status`). */
@Serializable
data class V2Report(val msg: String = "", val progress: Int = 0, val eta: Int? = null, val paused: Boolean = false)

/** A task's one agent: its chat is [chat] (`HA-1/Agent`). */
@Serializable
data class V2TaskAgent(
    val chat: String,
    val role: String = "",
    val cli: String = "",
    val status: V2Status = V2Status.Idle,
    val age: String = "",
    val note: String = "",
)

@Serializable
data class V2Task(
    /** Its short name, as people say it: `HA-1`. */
    val id: String,
    /** Its title. */
    val name: String = "",
    val status: V2TaskStatus = V2TaskStatus.Todo,
    /** Its space's id. */
    val place: String = "",
    /** Its second line in lists. */
    val sub: String? = null,
    val age: String = "",
    val doneAt: Long? = null,
    val branch: String? = null,
    val plan: List<String>? = null,
    /** None until its agent starts. */
    val agent: V2TaskAgent? = null,
    /** Its brief. */
    val desc: String? = null,
    /** Its summary, once ready. */
    val said: String? = null,
    val facts: String? = null,
    val state: String = "",
    val on: String = "",
    val created: String = "",
    val report: V2Report? = null,
    val pr: V2Pr? = null,
    /** Queued: the tasks it still waits for. */
    val after: List<String>? = null,
    /** `done`, `stopped` or `failed`. */
    val outcome: String? = null,
    val archived: Long? = null,
    val pinned: Long? = null,
)

@Serializable
data class V2Choice(val label: String, val description: String? = null)

/** A question card (`Question`): a choice card, a CLI's own menu, or a plan. */
@Serializable
data class V2Question(
    val id: String,
    val title: String = "",
    /** `Form`, `Question` or `Plan`. */
    val kind: String = "Question",
    val steps: List<String>? = null,
    val screen: String? = null,
    val terminal: Boolean = false,
    val reject: String? = null,
    val choices: List<V2Choice> = emptyList(),
    val multi: Boolean = false,
    /** An "Other" answer in free text is allowed. */
    val other: Boolean = false,
    /** The choice that needs a note to be sent (a plan sent back). */
    val noteRequiredFor: Int? = null,
    val noNote: Boolean = false,
    val to: String = "",
    /** Index of the choice sent once answered; -1 answered without a pick. */
    val sent: Int? = null,
    val said: String? = null,
    /** `chat` or `terminal`: answered there, not on the card. */
    val via: String? = null,
    /** "Claude Code asks". */
    val asks: String? = null,
    /** Shepherdr 2.1: why it closed without the human's answer (expired, replaced); it was never answered. */
    val closed: String? = null,
) {
    /** Still waiting for an answer. */
    val open: Boolean get() = sent == null && said == null && via == null && closed == null
}

/** What a needs-you card in a space's chat asks, from its task's chat (`CardAsk`). */
@Serializable
data class V2CardAsk(
    /** The task's chat, where the answer goes. */
    val chat: String,
    val questions: List<V2Question>? = null,
    /** A plain question with no choices, answered with a reply to message [V2PlainAsk.id]. */
    val reply: V2PlainAsk? = null,
    val answered: V2Answered? = null,
    /** It still waits. */
    val open: Boolean = false,
)

@Serializable
data class V2PlainAsk(val id: String, val text: String = "")

@Serializable
data class V2Answered(val where: String = "", val text: String? = null)

/** Shepherdr's own update card in a space's chat (`UpdateCard`). */
@Serializable
data class V2UpdateCard(
    val id: String,
    val task: String? = null,
    val status: V2TaskStatus? = null,
    val mode: String? = null,
    val text: String = "",
    val pr: V2Pr? = null,
    /** The base branch went red on GitHub ("main"). */
    val red: String? = null,
    val restart: Boolean = false,
    val ask: V2CardAsk? = null,
)

@Serializable
data class V2Reaction(val emoji: String, val by: String = "", val pending: Boolean = false)

@Serializable
data class V2ReplyQuote(val id: String? = null, val who: String? = null, val text: String = "")

@Serializable
data class V2File(val name: String = "", val path: String = "")

@Serializable
data class V2ArtifactPost(val id: String, val v: Int = 0)

@Serializable
data class V2Message(
    val id: String,
    /** `you` or `agent`. */
    val who: String = "agent",
    /** Plain text (yours) or markdown (the agent's). */
    val text: String? = null,
    val answered: String? = null,
    val artifact: V2ArtifactPost? = null,
    val question: V2Question? = null,
    val reactions: List<V2Reaction>? = null,
    /** Waits to go in. */
    val waiting: Boolean = false,
    val file: V2File? = null,
    val cid: String? = null,
    val reply: V2ReplyQuote? = null,
    val newSession: Boolean = false,
    val card: V2UpdateCard? = null,
    /** A turn's tool steps, kept raw: the app shows them as one line at most. */
    val activity: JsonObject? = null,
    val at: Double? = null,
) {
    val you: Boolean get() = who == "you"
}

@Serializable
data class V2Chat(
    val key: String,
    val messages: List<V2Message> = emptyList(),
    /** The agent works: what you send goes in at its next step. */
    val working: Boolean = false,
    /** Only background work runs: no reply is on its way. */
    val background: Boolean = false,
    val failed: List<V2Failed>? = null,
    val finished: Boolean = false,
    val cli: String = "",
) {
    /** The question cards still waiting, oldest first. */
    val openQuestions: List<V2Question> get() = messages.mapNotNull { m -> m.question?.takeIf { it.open } }
}

@Serializable
data class V2Failed(val cid: String, val error: String = "")

@Serializable
data class V2Snapshot(
    val name: String = "",
    val host: String = "",
    /** Every space, in the sidebar's order. */
    val places: List<V2Place> = emptyList(),
    val machines: List<V2Machine> = emptyList(),
    val tasks: List<V2Task> = emptyList(),
    /** Open coordinator questions, by space id (a choice card's id, or `blocked`). */
    val asks: Map<String, String> = emptyMap(),
    val user: String = "",
    val demo: Boolean = false,
    /** `core` on Shepherdr 2.1, whose agents run in herdr-core; none on 2.0 (classic herdr). */
    val terminalTransport: String? = null,
    /** When each chat last had something new (ms), and how far each was read. */
    val last: Map<String, Double> = emptyMap(),
    val read: Map<String, Double> = emptyMap(),
) {
    fun place(id: String): V2Place? = places.firstOrNull { it.id == id }
    fun task(id: String): V2Task? = tasks.firstOrNull { it.id == id }
}

/** What the live stream (`/api/ws`) sends. */
sealed interface V2Event {
    data class Snapshot(val snapshot: V2Snapshot) : V2Event
    data class Chat(val chat: V2Chat) : V2Event
    /** Anything else (`build`, `update`, `terminal`, `pong`, …), as sent. */
    data class Other(val type: String, val raw: JsonElement) : V2Event
}
