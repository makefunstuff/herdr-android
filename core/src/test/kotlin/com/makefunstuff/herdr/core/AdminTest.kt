package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.admin.HerdrCli
import com.makefunstuff.herdr.core.admin.HerdrCliException
import com.makefunstuff.herdr.core.admin.UpdateChannel
import com.makefunstuff.herdr.core.api.ApiCaller
import com.makefunstuff.herdr.core.api.ApiClient
import com.makefunstuff.herdr.core.api.ApiConnection
import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.api.HerdrApiException
import com.makefunstuff.herdr.core.api.plainMessage
import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.Duplex
import com.makefunstuff.herdr.core.ssh.Machine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Answers with canned herdr 0.9.5 responses and records what was asked. */
private class FakeHerdr(private val replies: Map<String, String>) : ApiCaller {
    val calls = mutableListOf<Pair<String, JsonObject>>()
    override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
        calls += method to params
        val reply = replies[method] ?: error("unexpected $method")
        return ApiConnection.unwrap(Json.parseToJsonElement("""{"id":"1",$reply}""").jsonObject)
    }
}

class AdminApiTest {
    // Responses captured from herdr 0.9.5.
    private val plugin = """{"plugin_id":"test.hello","name":"hello","version":"0.1.0","min_herdr_version":"0.9.0",
        "description":"Test plugin","manifest_path":"/p/herdr-plugin.toml","plugin_root":"/p","enabled":false,
        "actions":[{"id":"greet","title":"Say hello","command":["sh","-c","echo hi"]}],
        "source":{"kind":"github","owner":"ChmaraX","repo":"herdr-nvim"},"warnings":["manifest does not declare platforms"]}"""
    private val fake = FakeHerdr(
        mapOf(
            "plugin.list" to """"result":{"type":"plugin_list","plugins":[$plugin]}""",
            "plugin.disable" to """"result":{"type":"plugin_disabled","plugin":$plugin}""",
            "plugin.enable" to """"error":{"code":"plugin_not_found","message":"plugin not found"}""",
            "plugin.action.list" to """"result":{"type":"plugin_action_list","actions":[
                {"plugin_id":"a","action_id":"phone","title":"Phone setup","contexts":["workspace"],"command":["x"],"platforms":["linux","macos"]},
                {"plugin_id":"a","action_id":"win","title":"Phone setup","command":["x"],"platforms":["windows"]}]}""",
            "plugin.action.invoke" to """"result":{"type":"plugin_action_invoked","action":{"plugin_id":"test.hello","action_id":"greet","title":"Say hello","command":[]},
                "context":{"invocation_source":"api"},"log":{"log_id":"plugin-log-1","plugin_id":"test.hello","action_id":"greet","command":[],"status":"running","started_unix_ms":1}}""",
            "plugin.log.list" to """"result":{"type":"plugin_log_list","logs":[
                {"log_id":"plugin-log-1","plugin_id":"test.hello","action_id":"greet","command":["sh"],"status":"succeeded","started_unix_ms":1,"finished_unix_ms":2,"exit_code":0,"stdout":"hello\n","stderr":"oops\n"},
                {"log_id":"plugin-log-2","plugin_id":"herdr-projects","event":"pane.agent_status_changed","command":["sh"],"status":"failed","started_unix_ms":5,"exit_code":3}]}""",
            "integration.list" to """"result":{"type":"integration_list","integrations":[
                {"target":"pi","label":"pi","command":"pi","available":true,"state":"current"},
                {"target":"codex","label":"codex","command":"codex","available":true,"state":"outdated"},
                {"target":"kimi","label":"kimi","command":"kimi","available":false,"state":"not_installed"}]}""",
            "integration.install" to """"result":{"type":"integration_install","target":"grok","details":{"messages":["installed grok integration hook"]}}""",
            "integration.uninstall" to """"error":{"code":"integration_uninstall_failed","message":"no hook here"}""",
            "server.reload_config" to """"result":{"type":"config_reload","status":"partial","diagnostics":["unknown key foo"]}""",
            "server.agent_manifests" to """"result":{"type":"agent_manifest_status","last_check_unix":1,"last_result":"checked","manifests":[
                {"agent":"pi","source":"remote:/x/pi.toml","source_kind":"remote","active_version":"2026.10.01.1","local_override_shadowing_remote":false}]}""",
            "server.reload_agent_manifests" to """"result":{"type":"agent_manifest_reload","manifests":[]}""",
            "server.stop" to """"result":{"type":"ok"}""",
            "ping" to """"result":{"type":"pong","version":"0.9.5","protocol":22,"capabilities":{"live_handoff":true,"health_check":true}}""",
        ),
    )
    private val api = HerdrApi(fake)

    @Test
    fun plugins() = runBlocking {
        val p = api.plugins().single()
        assertEquals("hello", p.title)
        assertEquals("ChmaraX/herdr-nvim", p.origin)
        assertFalse(p.enabled)
        assertEquals("greet", p.actions.single().id)

        assertFalse(api.setPluginEnabled("test.hello", false).enabled)
        assertEquals("plugin.disable", fake.calls.last().first)
        assertEquals("\"test.hello\"", fake.calls.last().second["plugin_id"].toString())

        val e = assertFailsWith<HerdrApiException> { api.setPluginEnabled("nope", true) }
        assertEquals("plugin_not_found", e.code)
        assertEquals("Plugin not found", e.plainMessage())
    }

    @Test
    fun actionsAndLogs() = runBlocking {
        val actions = api.pluginActions()
        assertEquals(listOf(true, false), actions.map { it.runsOn("linux") })
        assertNull(fake.calls.last().second["plugin_id"])

        val log = api.invokePluginAction("test.hello", "greet")!!
        assertEquals("running", log.status)
        val sent = fake.calls.last().second
        assertEquals("\"greet\"", sent["action_id"].toString())
        assertEquals("\"herdr-android\"", sent["context"]!!.jsonObject["invocation_source"].toString())

        val logs = api.pluginLogs("test.hello", 20)
        assertEquals(listOf("plugin-log-2", "plugin-log-1"), logs.map { it.logId }) // newest first
        assertEquals("pane.agent_status_changed", logs[0].trigger)
        assertEquals("hello\n", logs[1].stdout)
        assertEquals("20", fake.calls.last().second["limit"].toString())
    }

    @Test
    fun integrations() = runBlocking {
        val list = api.integrations()
        assertEquals(listOf(true, true, false), list.map { it.installed })
        assertTrue(list[1].outdated)
        assertFalse(list[2].available)
        assertEquals(listOf("installed grok integration hook"), api.installIntegration("grok"))
        assertEquals("No hook here", assertFailsWith<HerdrApiException> { api.uninstallIntegration("grok") }.plainMessage())
    }

    @Test
    fun server() = runBlocking {
        val ping = api.ping()
        assertTrue(ping.capabilities.liveHandoff)
        val reload = api.reloadConfig()
        assertEquals("partial", reload.status)
        assertEquals(listOf("unknown key foo"), reload.diagnostics)
        assertEquals("2026.10.01.1", api.agentManifests().single().activeVersion)
        assertTrue(api.reloadAgentManifests().isEmpty())
        api.stopServer()
        assertEquals("server.stop", fake.calls.last().first)
    }
}

class HerdrCliTest {
    private val machine = Machine("m", "box", "box.lan", user = "me", session = "work")
    private val commands = mutableListOf<String>()

    private fun cli(vararg results: CommandResult) = HerdrCli(machine) { cmd, _ ->
        commands += cmd
        results[commands.size - 1]
    }

    private fun ok(out: String = "") = CommandResult(0, out, "")

    @Test
    fun versionAndChannel() = runBlocking {
        val c = cli(ok("herdr 0.9.5\n"), ok("preview\n"), ok())
        assertEquals("0.9.5", c.version())
        assertEquals(UpdateChannel.Preview, c.channel())
        c.setChannel(UpdateChannel.Stable)
        assertTrue(commands[0].endsWith("'--version'"))
        assertTrue(commands[2].endsWith("'channel' 'set' 'stable'"))
        // CLI admin commands are global, not per session.
        assertFalse(commands.any { "--session" in it })
        assertEquals("0.9.4-preview.2026-10-14-fbd2ae23927f", HerdrCli.parseVersion("herdr 0.9.4-preview.2026-10-14-fbd2ae23927f"))
    }

    @Test
    fun update() = runBlocking {
        val c = cli(CommandResult(0, "installed 0.9.6\n", ""), CommandResult(1, "", "update failed: no network\n\nAre you an AI? blah\n"))
        assertEquals("installed 0.9.6", c.update(handoff = true))
        assertTrue(commands[0].endsWith("'update' '--handoff'"))
        assertEquals("update failed: no network", assertFailsWith<HerdrCliException> { c.update(false) }.message)
        assertTrue(commands[1].endsWith("'update'"))
    }

    @Test
    fun machines() = runBlocking {
        val c = cli(
            ok("""[{"id":"0123456789abcdef0123456789abcdef","label":"nas","target":"ssh://me@nas","session":"default","enabled":true,"extra":1}]"""),
            ok("saved"),
            CommandResult(2, "", "Error: endpoint profile id must be 32 lowercase hexadecimal characters\n"),
            ok("not json"),
        )
        val list = c.machines()
        assertEquals("nas", list.single().label)
        c.addMachine("me@nas it's", "My NAS", "work")
        assertTrue(commands[1].endsWith("'machine' 'add' '--label' 'My NAS' '--remote-session' 'work' 'me@nas it'\\''s'"))
        val e = assertFailsWith<HerdrCliException> { c.removeMachine("bad") }
        assertEquals("endpoint profile id must be 32 lowercase hexadecimal characters", e.message)
        assertTrue(commands[2].endsWith("'machine' 'remove' 'bad'"))
        assertFailsWith<HerdrCliException> { c.machines() }
    }

    @Test
    fun missingHerdr() {
        assertEquals("herdr was not found on this machine", HerdrCli.failureMessage(CommandResult(127, "", "")))
        assertEquals("herdr didn't finish in time", HerdrCli.failureMessage(CommandResult(null, "", "")))
    }
}

/**
 * Plugin and server calls against a real herdr server's API socket on this computer. Runs only when
 * HERDR_ADMIN_SOCKET points at the socket of a throwaway herdr server (it links a plugin, toggles
 * it, runs its actions and reloads config there).
 */
class LiveAdminTest {
    private val socket = System.getenv("HERDR_ADMIN_SOCKET")

    private fun open(): Duplex {
        val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
        ch.connect(UnixDomainSocketAddress.of(socket))
        return object : Duplex {
            override val input: InputStream = Channels.newInputStream(ch)
            override val output: OutputStream = Channels.newOutputStream(ch)
            override fun close() = ch.close()
        }
    }

    @Test
    fun pluginsIntegrationsAndServer() = runBlocking {
        if (socket.isNullOrEmpty()) return@runBlocking
        val api = HerdrApi(ApiClient { open() })
        val dir = Files.createTempDirectory("herdr-plugin")
        Files.writeString(
            dir.resolve("herdr-plugin.toml"),
            """
            id = "android.test"
            name = "android test"
            version = "0.1.0"
            min_herdr_version = "0.9.0"
            [[actions]]
            id = "greet"
            title = "Say hello"
            command = ["sh", "-c", "echo hello-from-plugin"]
            """.trimIndent(),
        )
        link(api, dir)
        assertEquals("0.9.5", api.ping().version)
        assertTrue(api.plugins().any { it.pluginId == "android.test" && it.enabled })

        assertFalse(api.setPluginEnabled("android.test", false).enabled)
        val disabled = assertFailsWith<HerdrApiException> { api.invokePluginAction("android.test", "greet") }
        assertEquals("plugin_disabled", disabled.code)
        assertTrue(api.setPluginEnabled("android.test", true).enabled)

        assertTrue(api.pluginActions("android.test").any { it.actionId == "greet" })
        val started = api.invokePluginAction("android.test", "greet")!!
        var log = started
        repeat(50) {
            log = api.pluginLogs("android.test").first { it.logId == started.logId }
            if (log.status != "running") return@repeat
            delay(100)
        }
        assertEquals("succeeded", log.status)
        assertEquals("hello-from-plugin\n", log.stdout)

        assertTrue(api.integrations().any { it.target == "claude" })
        assertEquals("applied", api.reloadConfig().status)
        assertTrue(api.agentManifests().isNotEmpty())
        assertTrue(api.reloadAgentManifests().isNotEmpty())
        assertEquals("plugin_not_found", assertFailsWith<HerdrApiException> { api.setPluginEnabled("nope.nope", true) }.code)
    }

    /** `plugin.link` isn't part of the app; the test uses it to install its plugin. */
    private suspend fun link(api: HerdrApi, dir: Path) {
        val caller = ApiClient { open() }
        runCatching {
            caller.call("plugin.link", Json.parseToJsonElement("""{"path":"$dir"}""").jsonObject)
        }
        check(api.plugins().any { it.pluginId == "android.test" })
    }
}
