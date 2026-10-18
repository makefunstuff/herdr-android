package com.makefunstuff.herdr.core.usage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/** What a provider sells: a subscription with limits, an API account that is paid for, or a model run locally. */
enum class ProviderKind { Subscription, Api, Local }

/**
 * One limit of a provider: [percent] used (0–100, more when over), until [resets] (epoch seconds;
 * null for a rolling window). [estimated]: worked out from what this machine spent, not the provider's own count.
 */
data class UsageLimit(
    val id: String,
    val name: String,
    val percent: Double,
    val resets: Long? = null,
    val window: Long? = null,
    val estimated: Boolean = false,
)

/** An API account's money, in [currency]; what isn't known is null. [estimated]: from this machine's sessions. */
data class Spend(
    val currency: String = "USD",
    val today: Double? = null,
    val week: Double? = null,
    val month: Double? = null,
    val balance: Double? = null,
    /** A spending limit and what is left of it (OpenRouter's key limit). */
    val limit: Double? = null,
    val left: Double? = null,
    val estimated: Boolean = false,
)

/** What the agents used on one day (the machine's date). Tokens leave out cache reads. */
data class DayUse(val day: String, val tokens: Long, val cost: Double)

/** A board task's (`<repo>/<n>`, or `@coordinator`) use of a provider over the week. */
data class TaskUse(val id: String, val title: String, val tokens: Long, val cost: Double) {
    val coordinator: Boolean get() = id == "@coordinator"
}

/** An agent's use: its harness and model, `claude · claude-opus-5-5`. */
data class AgentUse(val name: String, val tokens: Long, val cost: Double)

/** One card of the usage page: a provider as the machine sees it. */
data class ProviderUsage(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val plan: String = "",
    val limits: List<UsageLimit> = emptyList(),
    val spend: Spend? = null,
    val days: List<DayUse> = emptyList(),
    val tasks: List<TaskUse> = emptyList(),
    val agents: List<AgentUse> = emptyList(),
    /** Where the reading came from, in words. */
    val source: String = "",
    /** Why it couldn't be read (a key refused, a login expired). */
    val error: String = "",
    /** A remark on the reading, such as why an older one is shown. */
    val note: String = "",
    /** The reading is an older one: the provider couldn't be asked now. */
    val stale: Boolean = false,
    /** When the provider was asked (epoch seconds, the machine's clock). */
    val readAt: Long = 0,
    /** Where its key was found: `pi`, `opencode`, `$DEEPSEEK_API_KEY`. */
    val key: String = "",
    /** A local model server: whether it answered, and the models it serves. */
    val running: Boolean? = null,
    val models: List<String> = emptyList(),
    val url: String = "",
    /** The board's agent names that run on it (`claude`, `pi/deepseek`): an agent `pi/deepseek/deepseek-flash` matches `pi/deepseek`. */
    val match: List<String> = emptyList(),
) {
    /** The limit closest to its end. */
    val tightest: UsageLimit? get() = limits.maxByOrNull { it.percent }

    /** The name the glance uses: "Claude" rather than "Claude Code". */
    val short: String get() = when (id) {
        "claude" -> "Claude"
        else -> name
    }

    val weekTokens: Long get() = days.sumOf { it.tokens }
    val weekCost: Double get() = days.sumOf { it.cost }
}

/** One machine's providers, as `herdr-usage read` (or `limits`) answered. [at]: the machine's clock, seconds. */
data class UsageReport(
    val at: Long,
    val today: String = "",
    val providers: List<ProviderUsage> = emptyList(),
    /** The session records weren't all read in time: the next look goes on from there. */
    val partial: Boolean = false,
    /** The answer it was read from, kept as the app's cache. */
    val raw: String = "",
)

object UsageReader {
    private val json = Json { ignoreUnknownKeys = true }

    /** The script's one JSON line, wherever a shell's start-up files printed around it. */
    fun answer(stdout: String): JsonObject? = stdout.lines().asReversed().firstNotNullOfOrNull { l ->
        l.trim().takeIf { it.startsWith("{") }?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.takeIf { it["ok"] != null }
    }

    /** `herdr-usage read` or `limits`; its refusal as an [IOException]. */
    fun parse(stdout: String): UsageReport {
        val o = answer(stdout) ?: throw IOException("herdr-usage gave no answer")
        if ((o["ok"] as? JsonPrimitive)?.booleanOrNull != true) throw IOException(o.str("msg").ifEmpty { "herdr-usage failed" })
        return report(o)
    }

    fun report(o: JsonObject): UsageReport = UsageReport(
        at = o.long("at"),
        today = o.str("today"),
        providers = (o["providers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::provider) },
        partial = o.bool("partial"),
        raw = o.toString(),
    )

    /** A report the app kept, or null when it can't be read any more. */
    fun cached(raw: String): UsageReport? = runCatching { report(json.parseToJsonElement(raw).jsonObject) }.getOrNull()

    private fun provider(o: JsonObject): ProviderUsage? {
        val id = o.str("id").ifEmpty { return null }
        val kind = when (o.str("kind")) {
            "subscription" -> ProviderKind.Subscription
            "local" -> ProviderKind.Local
            else -> if (id.startsWith("local:")) ProviderKind.Local else ProviderKind.Api
        }
        return ProviderUsage(
            id = id,
            name = o.str("name").ifEmpty { id.removePrefix("local:") },
            kind = kind,
            plan = o.str("plan"),
            limits = (o["limits"] as? JsonArray).orEmpty().mapNotNull { e ->
                val l = e as? JsonObject ?: return@mapNotNull null
                UsageLimit(
                    id = l.str("id"), name = l.str("name").ifEmpty { l.str("id") }, percent = l.num("percent") ?: return@mapNotNull null,
                    resets = l.num("resets")?.toLong()?.takeIf { it > 0 }, window = l.num("window")?.toLong(), estimated = l.bool("estimated"),
                )
            },
            spend = (o["spend"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { s ->
                Spend(
                    currency = s.str("currency").ifEmpty { "USD" }, today = s.num("today"), week = s.num("week"), month = s.num("month"),
                    balance = s.num("balance"), limit = s.num("limit"), left = s.num("left"), estimated = s.bool("estimated"),
                )
            },
            days = (o["days"] as? JsonArray).orEmpty().mapNotNull { e ->
                (e as? JsonObject)?.let { DayUse(it.str("day"), it.long("tokens"), it.num("cost") ?: 0.0) }
            },
            tasks = (o["tasks"] as? JsonArray).orEmpty().mapNotNull { e ->
                (e as? JsonObject)?.let { t -> t.str("id").takeIf { it.isNotEmpty() }?.let { TaskUse(it, t.str("title"), t.long("tokens"), t.num("cost") ?: 0.0) } }
            },
            agents = (o["agents"] as? JsonArray).orEmpty().mapNotNull { e ->
                (e as? JsonObject)?.let { a -> a.str("name").takeIf { it.isNotEmpty() }?.let { AgentUse(it, a.long("tokens"), a.num("cost") ?: 0.0) } }
            },
            source = o.str("source"),
            error = o.str("error"),
            note = o.str("note"),
            stale = o.bool("stale"),
            readAt = o.long("readAt"),
            key = o.str("key"),
            running = (o["running"] as? JsonPrimitive)?.booleanOrNull,
            models = o.strings("models"),
            url = o.str("url"),
            match = o.strings("match"),
        )
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    private fun JsonObject.long(key: String): Long = num(key)?.toLong() ?: 0
    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
}
