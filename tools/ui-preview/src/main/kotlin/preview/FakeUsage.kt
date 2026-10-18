// A machine's AI providers as herdr-usage reads them there (host/herdr-usage), from fixed demo data:
// Claude Code near its 5-hour limit, Codex, two API accounts, OpenCode Go's estimate and a local model.
package preview

import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.usage.HerdrUsage

class FakeUsage(
    /** Claude Code's 5-hour session, % used. */
    var claudeSession: Double = 82.0,
    var claudeWeekly: Double = 47.0,
    var codexSession: Double = 25.0,
    var codexWeekly: Double = 61.0,
    var deepseekBalance: Double = 7.63,
    /** Set to make reading fail as herdr-usage says it ("ok": false), e.g. no python3. */
    var refuse: String? = null,
    /** Only these providers (by id); null: all of them. */
    var only: Set<String>? = null,
) {
    fun run(command: String): CommandResult? {
        if (!command.contains(HerdrUsage.FILE.substringAfterLast('/'))) return null
        refuse?.let { return CommandResult(1, """{"ok": false, "version": "${HerdrUsage.VERSION}", "msg": "$it"}""" + "\n", "") }
        return CommandResult(0, report() + "\n", "")
    }

    private val now = Demo.NOW / 1000

    /** Epoch seconds at [h]:[m] UTC on the demo's day plus [days]. */
    private fun at(h: Int, m: Int = 0, days: Int = 0) =
        java.time.LocalDateTime.of(2026, 10, 17, h, m).plusDays(days.toLong()).toEpochSecond(java.time.ZoneOffset.UTC)

    private fun days(vararg tokens: Long, cost: List<Double> = emptyList()) = tokens.mapIndexed { i, t ->
        """{"day": "2026-10-${11 + i}", "tokens": $t, "cost": ${cost.getOrElse(i) { 0.0 }}}"""
    }.joinToString(", ", "[", "]")

    private fun report(): String {
        val providers = listOf(
            "claude" to """{"id": "claude", "name": "Claude Code", "kind": "subscription", "plan": "Max 5x",
              "source": "Anthropic's usage API, with Claude Code's login", "readAt": ${now - 120},
              "limits": [
                {"id": "session", "name": "5-hour session", "percent": $claudeSession, "resets": ${at(11)}, "window": 18000},
                {"id": "weekly", "name": "Weekly", "percent": $claudeWeekly, "resets": ${at(13, days = 3)}, "window": 604800},
                {"id": "weekly-opus", "name": "Weekly · Opus", "percent": 12.0, "resets": ${at(13, days = 3)}, "window": 604800}],
              "days": ${days(1_850_000, 2_420_000, 960_000, 310_000, 2_910_000, 3_540_000, 2_120_000)},
              "tasks": [
                {"id": "herdr-android/9", "title": "Show when each machine last answered", "tokens": 4_210_000, "cost": 0},
                {"id": "api-server/3", "title": "Speed up /jobs on large tables", "tokens": 2_960_000, "cost": 0},
                {"id": "@coordinator", "title": "The Coordinator", "tokens": 1_080_000, "cost": 0}],
              "agents": [
                {"name": "claude · claude-opus-5-5", "tokens": 9_640_000, "cost": 0},
                {"name": "claude · claude-sonnet-5-5", "tokens": 3_210_000, "cost": 0},
                {"name": "pi · claude-opus-5-5", "tokens": 1_250_000, "cost": 0}],
              "match": ["claude", "pi/anthropic"]}""",
            "codex" to """{"id": "codex", "name": "Codex", "kind": "subscription", "plan": "Plus",
              "source": "ChatGPT's usage API, with Codex's login", "readAt": ${now - 120},
              "limits": [
                {"id": "session", "name": "5-hour session", "percent": $codexSession, "resets": ${at(9, 30)}, "window": 18000},
                {"id": "weekly", "name": "Weekly", "percent": $codexWeekly, "resets": ${at(8, days = 2)}, "window": 604800}],
              "days": ${days(420_000, 0, 1_130_000, 880_000, 0, 640_000, 510_000)},
              "tasks": [{"id": "website/4", "title": "Rewrite the install page", "tokens": 2_210_000, "cost": 0},
                {"id": "herdr-android/9", "title": "Show when each machine last answered", "tokens": 640_000, "cost": 0}],
              "agents": [{"name": "codex · gpt-6.1-sol", "tokens": 3_580_000, "cost": 0}],
              "match": ["codex", "pi/openai-codex"]}""",
            "deepseek" to """{"id": "deepseek", "name": "DeepSeek", "kind": "api", "key": "pi", "readAt": ${now - 120},
              "source": "DeepSeek's balance API",
              "spend": {"currency": "USD", "balance": $deepseekBalance, "today": 0.31, "month": 4.12, "estimated": true},
              "days": ${days(210_000, 380_000, 90_000, 0, 520_000, 610_000, 330_000, cost = listOf(0.18, 0.33, 0.07, 0.0, 0.44, 0.52, 0.31))},
              "tasks": [{"id": "@coordinator", "title": "The Coordinator", "tokens": 1_410_000, "cost": 1.28},
                {"id": "api-server/5", "title": "Rate-limit the export endpoint per tenant", "tokens": 520_000, "cost": 0.44}],
              "agents": [{"name": "pi · deepseek-flash", "tokens": 2_140_000, "cost": 1.85}],
              "match": ["pi/deepseek"]}""",
            "openrouter" to """{"id": "openrouter", "name": "OpenRouter", "kind": "api", "key": "pi", "readAt": ${now - 120},
              "source": "OpenRouter's key API",
              "spend": {"currency": "USD", "today": 0.42, "month": 8.64, "limit": 50, "left": 49.58, "balance": 8.0},
              "limits": [{"id": "key", "name": "Key, daily", "percent": 0.8, "resets": ${at(0, days = 1)}}],
              "days": ${days(0, 150_000, 0, 60_000, 0, 0, 90_000, cost = listOf(0.0, 1.9, 0.0, 0.72, 0.0, 0.0, 0.42))},
              "tasks": [], "agents": [{"name": "omp · qwen3-coder", "tokens": 300_000, "cost": 3.04}],
              "match": ["pi/openrouter", "omp/openrouter"]}""",
            "opencode-go" to """{"id": "opencode-go", "name": "OpenCode Go", "kind": "api", "key": "pi", "readAt": ${now - 120},
              "source": "OpenCode Go's key; its limits estimated from what this machine's agents spent",
              "spend": {"currency": "USD", "today": 0.0, "month": 6.2, "estimated": true},
              "limits": [{"id": "5h", "name": "5-hour (est.)", "percent": 0.0, "window": 18000, "estimated": true},
                {"id": "week", "name": "Weekly (est.)", "percent": 34.0, "window": 604800, "estimated": true},
                {"id": "month", "name": "Monthly (est.)", "percent": 10.3, "window": 2592000, "estimated": true}],
              "days": ${days(0, 0, 640_000, 0, 1_210_000, 0, 0, cost = listOf(0.0, 0.0, 3.1, 0.0, 7.1, 0.0, 0.0))},
              "tasks": [{"id": "herdr-android/2", "title": "Replace agent terminals with native chat views", "tokens": 1_850_000, "cost": 10.2}],
              "agents": [{"name": "pi · deepseek-v4.1-flash", "tokens": 1_850_000, "cost": 10.2}],
              "match": ["pi/opencode-go"]}""",
            "local:llama.cpp" to """{"id": "local:llama.cpp", "name": "llama.cpp", "kind": "local", "url": "http://127.0.0.1:8080",
              "source": "pi's models.json", "running": true, "models": ["qwen3.8-27b"], "readAt": ${now - 120},
              "days": ${days(0, 280_000, 120_000, 0, 0, 90_000, 0)},
              "tasks": [{"id": "herdr-android/10", "title": "Haptics when an agent needs you", "tokens": 410_000, "cost": 0}],
              "agents": [{"name": "pi · qwen3.8-27b", "tokens": 490_000, "cost": 0}],
              "match": ["pi/llama.cpp"]}""",
        ).filter { only == null || it.first in only!! }.map { it.second }
        return """{"ok": true, "v": 1, "version": "${HerdrUsage.VERSION}", "at": $now, "today": "2026-10-17", "providers": [${providers.joinToString(", ")}]}"""
            .replace(Regex("\\s*\\n\\s*"), " ").replace(Regex("(?<=\\d)_(?=\\d)"), "")
    }
}
