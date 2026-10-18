package com.makefunstuff.herdr.core.usage

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * When a limit is worth a word, the same in the app and in herdr-push (host/herdr-push, `usage_level`):
 * near it from 80%, almost out from 95%, out at 100%.
 */
object UsageRules {
    const val NEAR = 80.0
    const val ALMOST = 95.0
    const val OUT = 100.0

    /** 0, 80, 95 or 100: the mark [percent] has passed. */
    fun level(percent: Double): Int = when {
        percent >= OUT -> 100
        percent >= ALMOST -> 95
        percent >= NEAR -> 80
        else -> 0
    }

    /** One limit somewhere: [machineId]'s [provider], its [limit]. */
    data class Tight(val machineId: String, val provider: ProviderUsage, val limit: UsageLimit) {
        val level: Int get() = level(limit.percent)
    }

    /**
     * The limit closest to its end across [reports] (machine id → its report): the glance in the top
     * bar and the status widget. The provider's own counts before estimates; null when nothing has a limit.
     */
    fun tightest(reports: Map<String, UsageReport>): Tight? =
        reports.flatMap { (id, r) -> r.providers.flatMap { p -> p.limits.map { Tight(id, p, it) } } }
            .sortedWith(compareBy<Tight> { it.limit.estimated }.thenByDescending { it.limit.percent })
            .let { all -> all.firstOrNull { !it.limit.estimated } ?: all.firstOrNull() }

    /** "Claude 82% · resets 17:00". */
    fun glance(t: Tight, now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        listOfNotNull("${t.provider.short} ${percent(t.limit.percent)}", t.limit.resets?.let { "resets ${UsageWords.at(it, now, zone)}" })
            .joinToString(" · ")

    /** The provider an agent of the board runs on (`claude/opus`, `pi/deepseek/deepseek-flash`): the longest [ProviderUsage.match]. */
    fun providerFor(agent: String, report: UsageReport?): ProviderUsage? {
        val a = agent.trim().lowercase()
        if (a.isEmpty() || report == null) return null
        return report.providers
            .flatMap { p -> p.match.map { m -> m.lowercase() to p } }
            .filter { (m, _) -> a == m || a.startsWith("$m/") }
            .maxByOrNull { it.first.length }?.second
    }

    /** What is left, as a few words next to an agent: "18% left · resets 17:00", "$7.63 left", "local, no limit". */
    fun left(p: ProviderUsage, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (p.error.isNotEmpty() && p.limits.isEmpty() && p.spend?.balance == null) return p.error.substringBefore(':').substringBefore(';')
        if (p.kind == ProviderKind.Local) return if (p.running == false) "local, not answering" else "local, no limit"
        p.tightest?.let { l ->
            val left = (100 - l.percent).coerceAtLeast(0.0)
            val reset = l.resets?.let { UsageWords.at(it, now, zone) }
            return when {
                left <= 0 -> "out" + (reset?.let { " until $it" } ?: "")
                else -> "${percent(left)} left" + (reset?.let { " · resets $it" } ?: "") + if (l.estimated) " (est.)" else ""
            }
        }
        val s = p.spend ?: return ""
        return when {
            s.balance != null -> "${UsageWords.money(s.balance, s.currency)} left"
            s.left != null && s.limit != null -> "${UsageWords.money(s.left, s.currency)} of ${UsageWords.money(s.limit, s.currency)} left"
            s.month != null -> "${UsageWords.money(s.month, s.currency)} this month"
            else -> ""
        }
    }

    /** What an agent has left: its provider, in words, and how tight ([pressure]). */
    data class Left(val provider: ProviderUsage, val words: String, val level: Int)

    /** [left] for a board agent (`claude/opus`), or null when [report] has nothing on its provider. */
    fun leftFor(agent: String, report: UsageReport?, now: Long, zone: ZoneId = ZoneId.systemDefault()): Left? =
        providerFor(agent, report)?.let { p -> Left(p, left(p, now, zone), pressure(p)) }

    /** How tight [p] is for an agent about to start: 0, or its tightest limit's [level]. An empty balance counts as out. */
    fun pressure(p: ProviderUsage): Int = when {
        p.tightest != null -> level(p.tightest!!.percent)
        p.spend?.balance != null && p.spend.balance <= 0.0 -> 100
        else -> 0
    }

    fun percent(p: Double): String = "${p.toInt().coerceIn(0, 999)}%"
}

/** How usage is put in words: times of day, money, token counts. */
object UsageWords {
    /**
     * When a limit resets, as the phone's clock says it: "17:00" today, "Fri 14:00" within a week,
     * "Oct 24" later. [now] and [at] in epoch seconds.
     */
    fun at(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochSecond(at), zone)
        val n = ZonedDateTime.ofInstant(Instant.ofEpochSecond(now), zone)
        val clock = "%02d:%02d".format(t.hour, t.minute)
        return when {
            t.toLocalDate() == n.toLocalDate() -> clock
            at - now < 6 * 86400 -> t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + clock
            else -> t.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + t.dayOfMonth
        }
    }

    /** "$7.63", "$0.04", "€12", "1,234 credits". */
    fun money(v: Double, currency: String): String {
        val n = if (v != 0.0 && kotlin.math.abs(v) < 10) String.format(Locale.ROOT, "%.2f", v) else String.format(Locale.ROOT, "%,.0f", v)
        return when (currency.uppercase()) {
            "USD" -> "$$n"
            "EUR" -> "€$n"
            "CNY" -> "¥$n"
            "GBP" -> "£$n"
            else -> "$n ${currency.lowercase()}"
        }
    }

    /** "812", "12k", "3.4M". */
    fun tokens(n: Long): String = when {
        n < 1000 -> "$n"
        n < 100_000 -> String.format(Locale.ROOT, "%.1fk", n / 1000.0).replace(".0k", "k")
        n < 1_000_000 -> "${n / 1000}k"
        n < 100_000_000 -> String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
        else -> "${n / 1_000_000}M"
    }

    /** A limit's notification: title and text. [level] 80, 95 or 100. */
    fun warning(provider: String, limit: UsageLimit, level: Int, machine: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<String, String> {
        val name = limit.name.replaceFirstChar { it.lowercase() }.replace(" (est.)", "")
        val reset = limit.resets?.let { at(it, now, zone) }
        val title = when (level) {
            100 -> "$provider is out of its $name allowance" + (reset?.let { " until $it" } ?: "")
            95 -> "$provider is almost out: ${UsageRules.percent(limit.percent)} of its $name limit"
            else -> "$provider at ${UsageRules.percent(limit.percent)} of its $name limit"
        }
        val text = listOfNotNull(reset?.let { "Resets $it" } ?: if (limit.window != null && limit.resets == null) "Rolling window" else null,
            if (limit.estimated) "estimated" else null, machine).joinToString(" · ")
        return title to text
    }
}
