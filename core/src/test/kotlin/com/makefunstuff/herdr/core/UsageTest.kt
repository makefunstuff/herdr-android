package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.AgentChoices
import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.AlertChange
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.core.push.HerdrPush
import com.makefunstuff.herdr.core.push.PushEvent
import com.makefunstuff.herdr.core.push.PushMessages
import com.makefunstuff.herdr.core.push.PushSeal
import com.makefunstuff.herdr.core.push.QuietHours
import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.usage.HerdrUsage
import com.makefunstuff.herdr.core.usage.ProviderKind
import com.makefunstuff.herdr.core.usage.UsageLimit
import com.makefunstuff.herdr.core.usage.UsageReader
import com.makefunstuff.herdr.core.usage.UsageReport
import com.makefunstuff.herdr.core.usage.UsageRules
import com.makefunstuff.herdr.core.usage.UsageWords
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A machine's AI providers: what herdr-usage reads there, how the app words it, and herdr-push's warnings. */
class UsageTest {
    private val usageScript = File(System.getProperty("herdrUsage") ?: "../host/herdr-usage/herdr-usage")
    private val utc = ZoneOffset.UTC

    /** 2026-10-17 07:42 UTC. */
    private val now = 1792222920L

    private fun python(): Boolean = runCatching {
        ProcessBuilder("python3", "--version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
    }.getOrDefault(false)

    /** As the script prints it: one line, after whatever a login shell printed first. */
    private val sample = "motd noise\n" + """
        {"ok": true, "v": 1, "version": "1", "at": $now, "today": "2026-10-17", "partial": true, "providers": [
          {"id": "claude", "name": "Claude Code", "kind": "subscription", "plan": "Max 5x", "readAt": $now,
           "limits": [{"id": "session", "name": "5-hour session", "percent": 82.0, "resets": ${now + 3 * 3600 + 18 * 60}, "window": 18000},
                      {"id": "weekly", "name": "Weekly", "percent": 47.0, "resets": ${now + 3 * 86400}, "window": 604800}],
           "days": [{"day": "2026-10-17", "tokens": 2100000, "cost": 0}],
           "tasks": [{"id": "herdr-android/9", "title": "Show when", "tokens": 4200000, "cost": 0}, {"id": "@coordinator", "title": "The Coordinator", "tokens": 5, "cost": 0}],
           "agents": [{"name": "claude · claude-opus-5-5", "tokens": 9600000, "cost": 0}],
           "match": ["claude", "pi/anthropic"]},
          {"id": "deepseek", "name": "DeepSeek", "kind": "api", "key": "pi",
           "spend": {"currency": "USD", "balance": 7.63, "today": 0.31, "month": 4.12, "estimated": true}, "match": ["pi/deepseek"]},
          {"id": "opencode-go", "name": "OpenCode Go", "kind": "api", "match": ["pi/opencode-go"],
           "limits": [{"id": "week", "name": "Weekly (est.)", "percent": 97.0, "window": 604800, "estimated": true}]},
          {"id": "local:llama.cpp", "name": "llama.cpp", "kind": "local", "running": true, "models": ["qwen3.8-27b"], "match": ["pi/llama.cpp"]}
        ]}
    """.trimIndent().lines().joinToString(" ")

    @Test
    fun bundledScriptIsTheOneInHost() {
        assertEquals(usageScript.readText(), HerdrUsage.script)
        // The app copies its own over a machine's copy that says another version: the two must agree.
        assertTrue(HerdrUsage.script.lineSequence().take(3).any { it == "# version: ${HerdrUsage.VERSION}" })
        assertTrue("VERSION = \"${HerdrUsage.VERSION}\"" in HerdrUsage.script)
    }

    @Test
    fun readsTheReport() {
        val r = UsageReader.parse(sample)
        assertEquals(now, r.at)
        assertTrue(r.partial)
        assertEquals(listOf("claude", "deepseek", "opencode-go", "local:llama.cpp"), r.providers.map { it.id })
        val claude = r.providers[0]
        assertEquals(ProviderKind.Subscription, claude.kind)
        assertEquals("Max 5x", claude.plan)
        assertEquals(82.0, claude.tightest?.percent)
        assertEquals(18000L, claude.limits[0].window)
        assertTrue(claude.tasks[1].coordinator)
        assertEquals("Claude", claude.short)
        assertEquals(7.63, r.providers[1].spend?.balance)
        assertTrue(r.providers[1].spend!!.estimated)
        assertEquals(ProviderKind.Local, r.providers[3].kind)
        assertEquals(true, r.providers[3].running)
        // Kept as the app's cache, it reads back the same.
        assertEquals(r.providers, UsageReader.cached(r.raw)?.providers)
        // A refusal says why.
        val e = runCatching { UsageReader.parse("""{"ok": false, "msg": "no python"}""") }.exceptionOrNull()
        assertEquals("no python", e?.message)
    }

    @Test
    fun levelsAndTheGlance() {
        assertEquals(listOf(0, 80, 80, 95, 100, 100), listOf(79.9, 80.0, 94.0, 95.0, 100.0, 130.0).map(UsageRules::level))
        val r = UsageReader.parse(sample)
        // The provider's own count before an estimate, though the estimate is higher.
        val t = assertNotNull(UsageRules.tightest(mapOf("devbox" to r)))
        assertEquals("devbox", t.machineId)
        assertEquals("Claude 82% · resets 11:00", UsageRules.glance(t, now, utc))
        assertEquals(80, t.level)
        // Only estimates: they count.
        val est = UsageReport(now, providers = listOf(r.providers[2]))
        assertEquals("OpenCode Go 97%", UsageRules.glance(UsageRules.tightest(mapOf("m" to est))!!, now, utc))
        assertNull(UsageRules.tightest(mapOf("m" to UsageReport(now))))
        // Reset times as the phone's clock says them.
        assertEquals("11:00", UsageWords.at(now + 3 * 3600 + 18 * 60, now, utc))
        assertEquals("Tue 07:42", UsageWords.at(now + 3 * 86400, now, utc))
        assertEquals("Oct 27", UsageWords.at(now + 10 * 86400, now, utc))
        assertEquals("$7.63", UsageWords.money(7.63, "USD"))
        assertEquals("$1,234", UsageWords.money(1234.4, "USD"))
        assertEquals("12 credits", UsageWords.money(12.0, "credits"))
        assertEquals(listOf("812", "12.5k", "123k", "3.4M", "120M"), listOf(812L, 12_500L, 123_456L, 3_400_000L, 120_000_000L).map(UsageWords::tokens))
    }

    @Test
    fun whatAnAgentHasLeft() {
        val r = UsageReader.parse(sample)
        fun left(agent: String) = UsageRules.leftFor(agent, r, now, utc)
        assertEquals("18% left · resets 11:00", left("claude/claude-opus-5-5")?.words)
        assertEquals(80, left("claude")?.level)
        // pi on Claude's subscription (an OAuth login there) counts on Claude's card.
        assertEquals("claude", left("pi/anthropic/claude-opus-5-5")?.provider?.id)
        assertEquals("$7.63 left", left("pi/deepseek/deepseek-flash")?.words)
        assertEquals("local, no limit", left("pi/llama.cpp/qwen3.8-27b")?.words)
        assertEquals("3% left (est.)", left("pi/opencode-go/deepseek-v4.1-flash")?.words)
        assertEquals(95, left("pi/opencode-go/x")?.level)
        // Not a prefix of a longer name, and nothing for what isn't known.
        assertNull(left("claudette"))
        assertNull(left("codex/gpt-6.1-sol"))
        assertNull(UsageRules.leftFor("claude", null, now, utc))
        val out = r.providers[0].copy(limits = listOf(UsageLimit("session", "5-hour session", 100.0, now + 3600)))
        assertEquals("out until 08:42", UsageRules.left(out, now, utc))
        assertEquals(100, UsageRules.pressure(r.providers[1].copy(spend = r.providers[1].spend!!.copy(balance = 0.0))))
    }

    @Test
    fun theBoardsAgents() {
        val c = AgentChoices.parse(
            """
            motd
            * worker.agent              claude/claude-opus-5-5              you
            * worker.fallback           pi/anthropic/claude-opus-5-5, pi/deepseek/deepseek-flash, pi/llama.cpp/qwen3.8-27b:medium  you
            * reviewer.agent            claude/claude-opus-5-5              you
              reviewer.fallback         none                                built in
              plan.agent                none                                built in
            """.trimIndent(),
        )
        assertEquals("claude/claude-opus-5-5", c.worker)
        assertEquals(listOf("pi/anthropic/claude-opus-5-5", "pi/deepseek/deepseek-flash", "pi/llama.cpp/qwen3.8-27b:medium"), c.workerFallback)
        assertEquals(emptyList(), c.reviewerFallback)
        assertEquals(4, c.workers.size)
        assertEquals(AgentChoices(), AgentChoices.parse("herdr-projects: unknown command"))
    }

    @Test
    fun warningsAndTakeovers() {
        val msg = PushMessages.parse(
            """{"v":1,"host":"devbox","at":5,"events":[
              {"type":"usage","provider":"claude","name":"Claude Code","level":95,
               "limit":{"id":"session","name":"5-hour session","percent":96.0,"resets":${now + 3 * 3600 + 18 * 60},"window":18000}},
              {"type":"usage","provider":"claude","name":"Claude Code","level":0,"limit":{"id":"session","name":"5-hour session","percent":3.0}},
              {"type":"takeover","key":"AND-49","task":{"id":"herdr-android/49","title":"Usage page"},"role":"worker",
               "from":"claude/opus","to":"codex/gpt-6.1-sol","why":"out of allowance until 17:00","at":1792222000000}]}""",
        )
        val (warn, back, take) = assertNotNull(msg).events
        val w = assertIs<PushEvent.Usage>(warn)
        assertEquals(95, w.level)
        val alert = assertIs<AlertChange.Show>(Alerts.usage("m1", "devbox", w, now)).alert
        assertEquals(Alerts.usageTag("m1", "claude", "session"), alert.tag)
        assertEquals("Claude Code is almost out: 96% of its 5-hour session limit", alert.title)
        assertTrue(alert.text.startsWith("Resets ") && alert.text.endsWith(" · devbox"), alert.text)
        assertTrue(alert.usage)
        // It can wait: quiet hours hold it.
        assertTrue(QuietHours.holds(alert))
        assertEquals(AlertChange.Clear(alert.tag), Alerts.of("m1", "devbox", back))
        val out = Alerts.usage("m1", "devbox", w.copy(level = 100, limit = w.limit.copy(percent = 100.0)), now) as AlertChange.Show
        assertTrue(out.alert.title.startsWith("Claude Code is out of its 5-hour session allowance until "), out.alert.title)
        assertFalse(out.alert.sig == alert.sig)
        val near = Alerts.usage("m1", "devbox", w.copy(level = 80, limit = w.limit.copy(percent = 81.0)), now) as AlertChange.Show
        assertEquals("Claude Code at 81% of its 5-hour session limit", near.alert.title)

        val t = assertIs<PushEvent.Takeover>(take)
        val ta = assertIs<AlertChange.Show>(Alerts.of("m1", "devbox", t)).alert
        assertEquals("AND-49: codex/gpt-6.1-sol took over from claude/opus", ta.title)
        assertEquals("out of allowance until 17:00", ta.text)
        assertEquals("herdr-android/49", ta.taskId)
        assertEquals(Alert.Kind.Limits, ta.kind)
        assertEquals(Alert.Kind.Limits, alert.kind)
    }

    // ---- herdr-usage itself, on a made-up home -----------------------------------------------------

    @Test
    fun herdrUsageReadsLoginsKeysAndSessions() {
        assumeTrue(python(), "python3 isn't installed")
        val dir = Files.createTempDirectory("herdr-usage").toFile()
        val asked = ConcurrentHashMap<String, String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun answer(path: String, body: String) = server.createContext(path) { ex ->
            asked[path] = ex.requestHeaders.getFirst("Authorization").orEmpty()
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        val t = System.currentTimeMillis() / 1000
        answer(
            "/api.anthropic.com/api/oauth/usage",
            """{"five_hour":{"utilization":82.0,"resets_at":"2026-10-18T08:59:59.9+00:00"},
               "limits":[{"kind":"session","group":"session","percent":82,"resets_at":"2026-10-18T08:59:59.939635+00:00"},
                         {"kind":"weekly_all","group":"weekly","percent":17,"resets_at":"2026-10-24T12:59:59+00:00"},
                         {"kind":"weekly_scoped","group":"weekly","percent":3,"resets_at":"2026-10-24T12:59:59+00:00","scope":{"model":{"display_name":"Fable"}}}]}""",
        )
        answer(
            "/chatgpt.com/backend-api/wham/usage",
            """{"plan_type":"plus","rate_limit":{"primary_window":{"used_percent":25,"limit_window_seconds":18000,"reset_at":${t + 3600}},
               "secondary_window":{"used_percent":61,"limit_window_seconds":604800,"reset_at":${t + 86400}}}}""",
        )
        answer("/api.deepseek.com/user/balance", """{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"7.63"}]}""")
        server.start()
        try {
            val stamp = Instant.ofEpochSecond(t).toString()
            fun write(path: String, text: String) = File(dir, path).apply { parentFile.mkdirs(); writeText(text) }
            write(".claude/.credentials.json", """{"claudeAiOauth":{"accessToken":"tok-claude","expiresAt":${(t + 86400) * 1000},"subscriptionType":"max","rateLimitTier":"default_claude_max_5x"}}""")
            val wt = "/home/x/.herdr/worktrees/herdr-android/work-7-fix-it"
            val reply = """{"type":"assistant","cwd":"$wt","timestamp":"$stamp","message":{"id":"msg_1","model":"claude-opus-5-5","usage":{"input_tokens":100,"output_tokens":50,"cache_creation_input_tokens":10,"cache_read_input_tokens":9999}}}"""
            // One reply is written once per content block: counted once.
            write(".claude/projects/p/s.jsonl", "$reply\n$reply\n")
            val jwt = "x." + Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":${t + 86400}}""".toByteArray()) + ".y"
            write(".codex/auth.json", """{"auth_mode":"chatgpt","tokens":{"access_token":"$jwt","account_id":"acc"}}""")
            write(
                ".codex/sessions/2026/10/17/rollout-1.jsonl",
                listOf(
                    """{"timestamp":"$stamp","type":"session_meta","payload":{"cwd":"/home/x/.herdr-projects/data/coordinator/default/chat"}}""",
                    """{"timestamp":"$stamp","type":"turn_context","payload":{"model":"gpt-6.1-sol"}}""",
                    """{"timestamp":"$stamp","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":1200,"cached_input_tokens":400,"output_tokens":200}}}}""",
                    """{"timestamp":"$stamp","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":1200,"cached_input_tokens":400,"output_tokens":200}}}}""",
                    """{"timestamp":"$stamp","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":1700,"cached_input_tokens":400,"output_tokens":200}}}}""",
                ).joinToString("\n", postfix = "\n"),
            )
            write(".pi/agent/auth.json", """{"deepseek":{"type":"api_key","key":"!echo sk-deep"},"llama.cpp":{"type":"api_key","key":"none"}}""")
            write(".pi/agent/models.json", """{"providers":{"llama.cpp":{"baseUrl":"http://127.0.0.1:9","api":"openai-completions"}}}""")
            write(
                ".pi/agent/sessions/--x--/s.jsonl",
                listOf(
                    """{"type":"session","cwd":"$wt"}""",
                    """{"type":"message","timestamp":"$stamp","message":{"role":"assistant","provider":"deepseek","model":"deepseek-flash","usage":{"input":200,"output":20,"cacheRead":5000,"cacheWrite":0,"cost":{"total":0.05}}}}""",
                    """{"type":"message","timestamp":"$stamp","message":{"role":"assistant","provider":"llama.cpp","model":"qwen3.8-27b","usage":{"input":70,"output":7,"cost":{"total":0}}}}""",
                ).joinToString("\n", postfix = "\n"),
            )
            write(".herdr-projects/data/tasks/herdr-android/7/task.json", """{"title":"Fix it"}""")
            val env = mapOf(
                "HOME" to dir.path, "XDG_CACHE_HOME" to File(dir, "cache").path, "XDG_DATA_HOME" to File(dir, "data").path,
                "HERDR_USAGE_API" to "http://127.0.0.1:${server.address.port}",
            )
            fun sh(command: String, input: String = "", extra: Map<String, String> = emptyMap()): CommandResult {
                val pb = ProcessBuilder("sh", "-c", command).directory(dir)
                pb.environment().putAll(env + extra)
                listOf("ANTHROPIC_API_KEY", "ANTHROPIC_ADMIN_KEY", "DEEPSEEK_API_KEY", "OPENROUTER_API_KEY", "OPENCODE_API_KEY", "CLAUDE_CONFIG_DIR", "CODEX_HOME",
                    "PI_CODING_AGENT_DIR", "HERDR_PROJECTS_DATA", "OPENCODE_GO_API_KEY").forEach {
                    pb.environment().remove(it)
                }
                val p = pb.start()
                p.outputStream.use { it.write(input.toByteArray()) }
                val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
                val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
                assertTrue(p.waitFor(90, TimeUnit.SECONDS))
                return CommandResult(p.exitValue(), out, err)
            }
            // The app's client: it copies the script there first (the machine has none), then reads.
            val client = HerdrUsage.Client({ cmd, _ -> sh(cmd) }, { cmd, input, _ -> sh(cmd, input) }, "testbox")
            val r = runBlocking { client.read() }
            assertTrue(File(dir, ".local/share/herdr-usage/herdr-usage").exists())
            val by = r.providers.associateBy { it.id }

            val claude = assertNotNull(by["claude"])
            assertEquals("Max 5x", claude.plan)
            assertEquals(listOf("session" to 82.0, "weekly" to 17.0, "weekly-fable" to 3.0), claude.limits.map { it.id to it.percent })
            assertEquals("Weekly · Fable", claude.limits[2].name)
            assertEquals(1792313999L, claude.limits[0].resets)
            assertEquals("Bearer tok-claude", asked["/api.anthropic.com/api/oauth/usage"])
            // Input, output and cache writes; cache reads left out; the repeated line once.
            assertEquals(160L, claude.days.last().tokens)
            assertEquals(7, claude.days.size)
            assertEquals(listOf("herdr-android/7" to "Fix it"), claude.tasks.map { it.id to it.title })
            assertEquals("claude · claude-opus-5-5", claude.agents.single().name)

            val codex = assertNotNull(by["codex"])
            assertEquals("Plus", codex.plan)
            assertEquals(listOf("session" to 25.0, "weekly" to 61.0), codex.limits.map { it.id to it.percent })
            assertEquals(listOf(18000L, 604800L), codex.limits.map { it.window })
            // Counted by how the session's total grew: 1000, nothing, then 500.
            assertEquals(1500L, codex.days.last().tokens)
            assertEquals("@coordinator", codex.tasks.single().id)
            assertEquals("codex · gpt-6.1-sol", codex.agents.single().name)

            val deepseek = assertNotNull(by["deepseek"])
            assertEquals(ProviderKind.Api, deepseek.kind)
            assertEquals("pi", deepseek.key)
            assertEquals("Bearer sk-deep", asked["/api.deepseek.com/user/balance"])
            assertEquals(7.63, deepseek.spend?.balance)
            assertEquals(0.05, deepseek.spend?.today)
            assertTrue(deepseek.spend!!.estimated)
            assertEquals(220L, deepseek.days.last().tokens)
            assertEquals(listOf("pi/deepseek", "omp/deepseek", "opencode/deepseek"), deepseek.match)

            val local = assertNotNull(by["local:llama.cpp"])
            assertEquals(ProviderKind.Local, local.kind)
            assertEquals(false, local.running)
            assertEquals(77L, local.days.last().tokens)

            // Read again: the providers aren't asked twice within minutes, and nothing is counted twice.
            asked.clear()
            val again = runBlocking { client.read() }
            assertTrue(asked.isEmpty(), "$asked")
            assertEquals(160L, again.providers.first { it.id == "claude" }.days.last().tokens)

            // Claude Code's login expired: it says so, and nothing is renewed (the file is left as it was).
            val expired = """{"claudeAiOauth":{"accessToken":"old","expiresAt":${(t - 60) * 1000},"subscriptionType":"pro"}}"""
            write(".claude/.credentials.json", expired)
            val stale = runBlocking {
                HerdrUsage.Client({ cmd, _ -> sh(cmd, extra = mapOf("HERDR_USAGE_TTL" to "0")) }, { cmd, input, _ -> sh(cmd, input) }, "testbox").limits()
            }
            assertTrue(stale.providers.first { it.id == "claude" }.error.contains("expired"))
            assertEquals(expired, File(dir, ".claude/.credentials.json").readText())
        } finally {
            server.stop(0)
            dir.deleteRecursively()
        }
    }

    // ---- herdr-push's warnings -----------------------------------------------------------------------

    @Test
    fun herdrPushWarnsAtEachMarkOnceAndTellsTakeovers() {
        assumeTrue(python(), "python3 isn't installed")
        val dir = Files.createTempDirectory("herdr-push-usage").toFile()
        val received = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/up") { ex ->
            received += ex.requestBody.readBytes().toString(Charsets.UTF_8)
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        server.start()
        try {
            val fake = File(dir, "fake").apply { mkdirs() }
            val herdr = File(fake, "herdr").apply { writeText("#!/bin/sh\necho '{\"result\":{\"snapshot\":{\"agents\":[]}}}'\n"); setExecutable(true) }
            // The board's launcher in its data folder, as the board installs it; one task at work.
            val data = File(dir, "board/data")
            val board = File(data, "bin/herdr-projects").apply {
                parentFile.mkdirs()
                writeText("#!/bin/sh\necho '[{\"id\":\"herdr-android/49\",\"title\":\"Usage page\",\"state\":\"working\",\"stage\":\"working\"}]'\n")
                setExecutable(true)
            }
            val record = File(data, "tasks/herdr-android/49/task.json").apply { parentFile.mkdirs(); writeText("""{"handovers":[]}""") }
            val percent = File(fake, "percent").apply { writeText("50") }
            val usage = File(fake, "usage").apply {
                writeText(
                    "#!/usr/bin/env python3\nimport json\np = float(open('${percent.path}').read())\n" +
                        "print(json.dumps({'ok': True, 'providers': [{'id': 'claude', 'name': 'Claude Code', 'limits': " +
                        "[{'id': 'session', 'name': '5-hour session', 'percent': p, 'resets': 1792400000}]}]}))\n",
                )
                setExecutable(true)
            }
            val env = mapOf(
                "HOME" to dir.path, "HERDR_PUSH_HOME" to File(dir, "state").path, "HERDR_PUSH_HEALTH_EVERY" to "0",
                "HERDR_PUSH_USAGE_EVERY" to "0.01", "HERDR_PUSH_USAGE" to usage.path, "HERDR_PUSH_BOARD_EVERY" to "1",
            )
            fun sh(command: String, input: String = ""): Int {
                val pb = ProcessBuilder("sh", "-c", command).directory(dir)
                pb.environment().putAll(env)
                pb.environment().remove("HERDR_SOCKET_PATH")
                val p = pb.start()
                p.outputStream.use { it.write(input.toByteArray()) }
                p.inputStream.readBytes()
                val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
                assertTrue(p.waitFor(60, TimeUnit.SECONDS))
                if (err.isNotBlank()) System.err.println(err)
                return p.exitValue()
            }
            assertEquals(0, sh(HerdrPush.installCommand(), HerdrPush.script))
            val key = PushSeal.newKey()
            val sub = HerdrPush.subscription(HerdrPush.subscriberId("phone1", "m1"), "Pixel", "http://127.0.0.1:${server.address.port}/up", key, "default", herdr.path, board.path)
            assertEquals(0, sh(HerdrPush.command("subscribe"), sub))
            fun once() { Thread.sleep(30); assertEquals(0, sh(HerdrPush.command("once"))) }
            fun events(i: Int) = assertNotNull(PushMessages.open(received[i], mapOf("m1" to key))).second.events
            fun level(i: Int) = assertIs<PushEvent.Usage>(events(i).single()).level

            once()
            assertTrue(received.isEmpty())
            percent.writeText("82"); once(); once()
            assertEquals(listOf(80), received.indices.map(::level))
            percent.writeText("96"); once()
            percent.writeText("100"); once(); once()
            assertEquals(listOf(80, 95, 100), received.indices.map(::level))
            // The window reset: the notification comes down.
            percent.writeText("4"); once()
            assertEquals(listOf(80, 95, 100, 0), received.indices.map(::level))

            // The board's fallback hands the task over because of a limit: told once. Running out of room isn't a limit.
            val ms = System.currentTimeMillis()
            record.writeText(
                """{"handovers":[{"at":${ms - 1000},"role":"worker","from":"claude","to":"claude/opus","why":"out of allowance"},
                   {"at":$ms,"role":"worker","from":"claude/opus","to":"codex/gpt-6.1-sol","why":"out of allowance until 17:00"}]}""",
            )
            once(); once()
            assertEquals(5, received.size)
            val t = assertIs<PushEvent.Takeover>(events(4).single())
            assertEquals(listOf("herdr-android/49", "claude/opus", "codex/gpt-6.1-sol", "out of allowance until 17:00"), listOf(t.taskId, t.from, t.to, t.why))
            record.writeText("""{"handovers":[{"at":${ms + 5},"role":"worker","from":"codex","to":"pi","why":"it ran out of room (context or output limit)"}]}""")
            record.setLastModified(System.currentTimeMillis() + 2000)
            once()
            assertEquals(5, received.size)
        } finally {
            server.stop(0)
            dir.deleteRecursively()
        }
    }
}
