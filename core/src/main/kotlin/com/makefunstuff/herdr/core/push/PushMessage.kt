package com.makefunstuff.herdr.core.push

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.HerdrJson
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.BoardTasks
import com.makefunstuff.herdr.core.health.Disk
import com.makefunstuff.herdr.core.health.HealthReport
import com.makefunstuff.herdr.core.health.Memory
import com.makefunstuff.herdr.core.usage.UsageLimit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** One thing a machine tells the phone: an agent's or a task's new state, or a test. */
sealed interface PushEvent {
    /** [agent] as `session.snapshot` gives it; [screen] is the bottom of a blocked agent's screen. */
    data class Agent(val agent: AgentInfo, val where: String, val screen: String? = null) : PushEvent

    data class Task(val task: BoardTask) : PushEvent

    data object Test : PushEvent

    /**
     * What runs on the machine now, for Live Updates: every working agent and, where the machine has
     * the board, every running task. A list that is null wasn't looked at; the one before still holds.
     */
    data class Live(val agents: List<LiveAgent>?, val tasks: List<LiveTask>?) : PushEvent

    /** The machine runs low on RAM or a disk, or (with nothing in [HealthReport.low]) has room again. */
    data class Health(val report: HealthReport) : PushEvent

    /**
     * An AI quick command ended there: run [id] of [name], in [state] (`done`, `failed`, `limit`),
     * with the start of its answer ([text]) or why it failed ([error]).
     */
    data class Quick(val id: String, val name: String, val state: String, val text: String = "", val error: String = "", val cwd: String = "") : PushEvent

    /**
     * A provider's [limit] passed a mark ([level] 80, 95 or 100), or (level 0) is back under them after
     * a reset: herdr-usage's readings, as herdr-push watches them.
     */
    data class Usage(val provider: String, val name: String, val limit: UsageLimit, val level: Int) : PushEvent

    /**
     * The board's fallback took a task over from one agent to another because of a limit ([why], in the
     * board's words: "out of allowance until 17:00"). [role]: `worker` or `reviewer`; [at] the board's time, ms.
     */
    data class Takeover(
        val taskId: String, val key: String, val title: String, val role: String,
        val from: String, val to: String, val why: String, val at: Long,
    ) : PushEvent {
        val name: String get() = key.ifEmpty { taskId }
    }
}

/** A working agent; [since] is when it started working (the machine's clock, seconds; 0 unknown). [workspace]: its workspace's label. */
data class LiveAgent(val agent: AgentInfo, val where: String, val since: Long = 0, val workspace: String = "")

/** A running task; [reviews] is how many reviews it gets at most, when known. [since]: when its current part began. */
data class LiveTask(val task: BoardTask, val reviews: Int? = null, val since: Long = 0)

/** A push from herdr-push (host/herdr-push): `{"v":1,"host":…,"at":…,"events":[…]}`, sealed. */
data class PushMessage(val host: String, val at: Long, val events: List<PushEvent>)

object PushMessages {
    /** The machine whose key opens [text], and what it says; null when none does. */
    fun open(text: String, keys: Map<String, ByteArray>): Pair<String, PushMessage>? {
        for ((id, key) in keys) {
            val plain = PushSeal.openText(key, text) ?: continue
            return id to (parse(plain) ?: return null)
        }
        return null
    }

    /** Pushes older than this are old news (phone off, push app asleep): the app's next look catches up. */
    const val MAX_AGE_SECONDS = 2L * 24 * 3600

    /**
     * Whether a push sent at [at] (the machine's clock, seconds; 0 when it didn't say) is taken, given
     * the newest one taken from that machine, [lastAt]. An older one is a push sent again by someone
     * who saw it, or one overtaken by a newer state; one from the same second is not.
     */
    fun fresh(at: Long, lastAt: Long, nowSeconds: Long): Boolean {
        if (at <= 0) return true
        if (nowSeconds - at > MAX_AGE_SECONDS) return false
        return at >= lastAt
    }

    fun parse(json: String): PushMessage? {
        val o = runCatching { HerdrJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val events = (o["events"] as? JsonArray).orEmpty().mapNotNull { e -> event(e as? JsonObject ?: return@mapNotNull null) }
        return PushMessage(o.str("host"), (o["at"] as? JsonPrimitive)?.longOrNull ?: 0, events)
    }

    private fun event(e: JsonObject): PushEvent? = when (e.str("type")) {
        "agent" -> (e["agent"] as? JsonObject)?.let { a ->
            runCatching { HerdrJson.decodeFromJsonElement(AgentInfo.serializer(), a) }.getOrNull()
        }?.let { PushEvent.Agent(it, e.str("where"), e.str("screen").ifEmpty { null }) }
        "task" -> (e["task"] as? JsonObject)?.let { BoardTasks.task(it, e.str("key")) }?.let { PushEvent.Task(it) }
        "test" -> PushEvent.Test
        "live" -> PushEvent.Live(
            agents = (e["agents"] as? JsonArray)?.mapNotNull { x ->
                val o = x as? JsonObject ?: return@mapNotNull null
                val a = (o["agent"] as? JsonObject)?.let { runCatching { HerdrJson.decodeFromJsonElement(AgentInfo.serializer(), it) }.getOrNull() }
                a?.let { LiveAgent(it, o.str("where"), o.long("since"), o.str("workspace")) }
            },
            tasks = (e["tasks"] as? JsonArray)?.mapNotNull { x ->
                val o = x as? JsonObject ?: return@mapNotNull null
                (o["task"] as? JsonObject)?.let { BoardTasks.task(it, o.str("key")) }
                    ?.let { LiveTask(it, o.long("reviews").takeIf { r -> r > 0 }?.toInt(), o.long("since")) }
            },
        )
        "health" -> PushEvent.Health(health(e))
        "quick" -> e.str("id").takeIf { it.isNotEmpty() }?.let {
            PushEvent.Quick(it, e.str("name").ifEmpty { it }, e.str("state"), e.str("text"), e.str("error"), e.str("cwd"))
        }
        "usage" -> (e["limit"] as? JsonObject)?.let { l ->
            val percent = (l["percent"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return@let null
            val limit = UsageLimit(
                id = l.str("id"), name = l.str("name").ifEmpty { l.str("id") }, percent = percent,
                resets = l.long("resets").takeIf { it > 0 }, window = l.long("window").takeIf { it > 0 },
                estimated = (l["estimated"] as? JsonPrimitive)?.booleanOrNull == true,
            )
            e.str("provider").takeIf { it.isNotEmpty() }?.let { PushEvent.Usage(it, e.str("name").ifEmpty { it }, limit, e.long("level").toInt()) }
        }
        "takeover" -> (e["task"] as? JsonObject)?.let { t ->
            t.str("id").takeIf { it.isNotEmpty() }?.let {
                PushEvent.Takeover(it, e.str("key"), t.str("title"), e.str("role"), e.str("from"), e.str("to"), e.str("why"), e.long("at"))
            }
        }
        else -> null
    }

    /** `{"low": ["ram", "disk:/"], "memory": {"total", "available", …}, "disks": [{"mount", "total", "used", "avail"}], "top": [[name, kb]]}`. */
    private fun health(e: JsonObject): HealthReport {
        val m = e["memory"] as? JsonObject
        return HealthReport(
            low = (e["low"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
            memory = m?.let { Memory(it.long("total"), it.long("available"), it.long("swap_total"), it.long("swap_free")) }?.takeIf { it.totalKb > 0 },
            disks = (e["disks"] as? JsonArray).orEmpty().mapNotNull { d ->
                val o = d as? JsonObject ?: return@mapNotNull null
                Disk(o.str("mount"), o.str("device"), "", o.long("total"), o.long("used"), o.long("avail"))
            },
            top = (e["top"] as? JsonArray).orEmpty().mapNotNull { t ->
                val a = t as? JsonArray ?: return@mapNotNull null
                val name = (a.getOrNull(0) as? JsonPrimitive)?.content ?: return@mapNotNull null
                name to ((a.getOrNull(1) as? JsonPrimitive)?.longOrNull ?: 0)
            },
        )
    }

    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
}
