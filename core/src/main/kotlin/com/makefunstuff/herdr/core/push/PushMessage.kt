package com.makefunstuff.herdr.core.push

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.HerdrJson
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.BoardTasks
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** One thing a machine tells the phone: an agent's or a task's new state, or a test. */
sealed interface PushEvent {
    /** [agent] as `session.snapshot` gives it; [screen] is the bottom of a blocked agent's screen. */
    data class Agent(val agent: AgentInfo, val where: String, val screen: String? = null) : PushEvent

    data class Task(val task: BoardTask) : PushEvent

    data object Test : PushEvent
}

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
        val events = (o["events"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e -> event(e as? JsonObject ?: return@mapNotNull null) }
        return PushMessage(o.str("host"), (o["at"] as? JsonPrimitive)?.longOrNull ?: 0, events)
    }

    private fun event(e: JsonObject): PushEvent? = when (e.str("type")) {
        "agent" -> (e["agent"] as? JsonObject)?.let { a ->
            runCatching { HerdrJson.decodeFromJsonElement(AgentInfo.serializer(), a) }.getOrNull()
        }?.let { PushEvent.Agent(it, e.str("where"), e.str("screen").ifEmpty { null }) }
        "task" -> (e["task"] as? JsonObject)?.let { BoardTasks.task(it, e.str("key")) }?.let { PushEvent.Task(it) }
        "test" -> PushEvent.Test
        else -> null
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
}
