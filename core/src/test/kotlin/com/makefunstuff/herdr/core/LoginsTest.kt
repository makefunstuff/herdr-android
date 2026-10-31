package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.logins.CliLogin
import com.makefunstuff.herdr.core.logins.KeySource
import com.makefunstuff.herdr.core.logins.LoginAction
import com.makefunstuff.herdr.core.logins.LoginEvent
import com.makefunstuff.herdr.core.logins.LoginState
import com.makefunstuff.herdr.core.logins.LoginWords
import com.makefunstuff.herdr.core.logins.LoginsReader
import com.makefunstuff.herdr.core.logins.HerdrLogins
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A machine's agent logins: what herdr-logins reads there and how the app words it, and each
 * sign-in, key and sign-out run against fake CLIs (never the real ones, never a real login).
 */
class LoginsTest {
    private val script = File(System.getProperty("herdrLogins") ?: "../host/herdr-logins/herdr-logins")
    private val utc = ZoneOffset.UTC

    /** 2026-10-31 12:00 UTC. */
    private val now = 1793448000L

    private fun python(): Boolean = runCatching {
        ProcessBuilder("python3", "--version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
    }.getOrDefault(false)

    // ---- reading and words ------------------------------------------------------------------------

    private val sample = "motd\n" + """
        {"ok": true, "version": "1", "at": $now, "clis": [
          {"id": "claude", "name": "Claude Code", "installed": true, "state": "signed_in", "how": "Claude subscription (Max)", "expires": ${now + 13 * 86400}, "actions": ["login", "logout"]},
          {"id": "codex", "name": "Codex", "installed": true, "state": "signed_in", "how": "ChatGPT login", "renewed": ${now - 8 * 86400}, "actions": ["login", "key", "logout"]},
          {"id": "pi", "name": "pi", "installed": true, "state": "signed_in", "providers": [
            {"id": "basalt", "source": "command"}, {"id": "deepseek", "source": "command"}, {"id": "llama.cpp", "source": "typed"}], "actions": ["key", "logout"]},
          {"id": "opencode", "name": "opencode", "installed": true, "state": "signed_in", "providers": [
            {"id": "OpenAI", "source": "oauth", "method": "OAuth"}, {"id": "llama.cpp", "source": "typed", "method": "API key"}], "how": null, "actions": ["login", "logout"]},
          {"id": "gemini", "name": "Gemini CLI", "installed": true, "state": "signed_out", "how": "Installed, no login", "actions": []},
          {"id": "crush", "name": "Crush", "installed": false, "state": "missing"}],
         "profile": [{"file": "~/.bashrc", "line": 16, "name": "OPENAI_API_KEY", "placeholder": true},
                     {"file": "~/.bashrc", "line": 34, "name": "HF_TOKEN", "placeholder": false}]}
    """.trimIndent().replace("\n", " ") + "\n"

    @Test
    fun readsEachCliAndWordsIt() {
        val r = LoginsReader.parse(sample)
        assertEquals(5, r.installed.size)
        val (claude, codex, pi, opencode, gemini) = r.installed
        assertEquals(setOf(LoginAction.Login, LoginAction.Logout), claude.actions)
        assertEquals("Claude subscription (Max) · good until 13 Nov, then sign in again", LoginWords.line(claude, now, utc))
        assertEquals("ChatGPT login · last renewed 8 days ago", LoginWords.line(codex, now, utc))
        assertEquals("3 providers: 2 keys from a command (keyring), 1 typed in", LoginWords.line(pi, now, utc))
        assertEquals("3 keys", LoginWords.badge(pi, now))
        assertEquals("OpenAI login · llama.cpp key", LoginWords.line(opencode, now, utc))
        assertEquals("Installed, no login", LoginWords.line(gemini, now, utc))
        assertNull(LoginWords.badge(gemini, now))
        assertEquals(KeySource.OAuth, opencode.providers[0].source)
        assertEquals("4 of 5 signed in · a key in ~/.bashrc", LoginWords.glance(r, now))
        assertEquals(
            "~/.bashrc exports HF_TOKEN (and a short placeholder OPENAI_API_KEY), so every program and agent started from a shell gets it.",
            LoginWords.profileWarning(r),
        )
        val steps = LoginWords.moveSteps(r.profileKeys.single())
        assertTrue(steps[0].contains("hf auth login"))
        assertEquals("Open ~/.bashrc on the machine and delete line 34 (export HF_TOKEN=…)", steps[1])
    }

    @Test
    fun aLoginThatRanOutAsksForASignIn() {
        val c = CliLogin("claude", "Claude Code", LoginState.SignedIn, how = "Claude subscription", expires = now - 86400, actions = setOf(LoginAction.Login))
        assertEquals("Claude subscription · ran out 30 Oct: sign in again", LoginWords.line(c, now, utc))
        assertEquals("ran out", LoginWords.badge(c, now))
        assertTrue(LoginWords.wants(c, now))
        assertTrue(LoginWords.soon(c.copy(expires = now + 86400), now))
        assertFalse(LoginWords.wants(c.copy(expires = now + 86400), now))
    }

    @Test
    fun readsARunsEvents() {
        assertEquals(LoginEvent.Link("https://auth.openai.com/codex/device"), LoginsReader.event("""{"event": "url", "url": "https://auth.openai.com/codex/device"}"""))
        assertEquals(LoginEvent.Code("QK7F-29XD"), LoginsReader.event("""{"event": "code", "code": "QK7F-29XD"}"""))
        assertEquals(LoginEvent.Ask("Paste code here >", true), LoginsReader.event("""{"event": "ask", "text": "Paste code here >", "secret": true}"""))
        val done = assertIs<LoginEvent.Done>(LoginsReader.event("""{"event": "done", "ok": true, "msg": null, "status": {"id": "codex", "state": "signed_out"}}"""))
        assertEquals(LoginState.SignedOut, done.status?.state)
        assertNull(LoginsReader.event("noise"))
    }

    @Test
    fun theCommandTakesNoSecret() {
        val c = HerdrLogins.command("run", "pi", "key", "groq")
        assertTrue(c.endsWith(" sh 'run' 'pi' 'key' 'groq'"), c)
    }

    // ---- the script, against fake CLIs ------------------------------------------------------------

    /** A machine of its own: a home and a bin with fake CLIs that write what they were given to [log]. */
    private class Box(val home: File) {
        val bin = File(home, "bin").apply { mkdirs() }
        val log = File(home, "calls.log")

        fun cli(name: String, body: String) {
            val f = File(bin, name)
            f.writeText("#!/bin/sh\necho \"$name \$*\" >> '${log.absolutePath}'\n$body\n")
            f.setExecutable(true)
        }

        fun file(rel: String, text: String) = File(home, rel).apply { parentFile.mkdirs(); writeText(text) }
    }

    private fun box(): Box = Box(Files.createTempDirectory("herdr-logins").toFile())

    private fun process(b: Box, vararg args: String): Process {
        val pb = ProcessBuilder(listOf("python3", script.absolutePath) + args).directory(b.home)
        pb.environment().apply {
            clear()
            put("HOME", b.home.absolutePath)
            put("PATH", b.bin.absolutePath + ":/usr/bin:/bin")
            put("HERDR_LOGINS_NO_SHELL_PATH", "1")
            put("HERDR_LOGINS_TIMEOUT", "20")
        }
        return pb.start()
    }

    private fun run(b: Box, vararg args: String, input: String = ""): List<String> {
        val p = process(b, *args)
        p.outputStream.use { it.write(input.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(30, TimeUnit.SECONDS)
        return out.lines().filter { it.isNotBlank() }
    }

    @Test
    fun statusReadsWithoutSecrets() {
        assumeTrue(python(), "python3 isn't installed")
        val b = box()
        b.cli("claude", """[ "$1 $2" = "auth status" ] && echo '{"loggedIn": true, "authMethod": "claude.ai", "subscriptionType": "max", "email": "x@y"}'""")
        b.cli("codex", """[ "$1 $2" = "login status" ] && echo 'Logged in using an API key - sk-proj-***ABCD'""")
        b.cli("pi", "exit 0")
        b.cli("opencode", """printf '\033[1mOpenAI\033[0m        OAuth                       stored\nHugging Face  HF_TOKEN                    environment\n'""")
        b.file(".claude/.credentials.json", """{"claudeAiOauth": {"accessToken": "sekrit-access", "refreshToken": "sekrit-refresh", "expiresAt": 1793496535793, "refreshTokenExpiresAt": 1794581605793}}""")
        b.file(".pi/agent/auth.json", """{"deepseek": {"type": "api_key", "key": "!pass show deepseek"}, "groq": {"type": "api_key", "key": "gsk_typed_secret_value"}, "anthropic": {"type": "oauth", "access": "a", "refresh": "r", "expires": 1793496535793}}""")
        b.file(".bashrc", "export PATH=\$HOME/bin:\$PATH\nexport HF_TOKEN=\"hf_realLookingTokenValue123\"\nexport OPENAI_API_KEY=dummy\n# export ANTHROPIC_API_KEY=sk-commented-out-1234567\nexport GH_TOKEN=\$(pass show gh)\n")
        val out = run(b, "status")
        val text = out.last()
        listOf("sekrit", "gsk_typed", "hf_real", "ABCD", "dummy", "pass show").forEach { assertFalse(it in text, "$it came back: $text") }
        val r = LoginsReader.parse(text)
        val claude = r.clis.first { it.id == "claude" }
        assertEquals("Claude subscription (Max)", claude.how)
        assertEquals(1794581605L, claude.expires)
        assertEquals("OpenAI API key", r.clis.first { it.id == "codex" }.how)
        assertEquals(
            listOf("anthropic" to KeySource.OAuth, "deepseek" to KeySource.Command, "groq" to KeySource.Typed),
            r.clis.first { it.id == "pi" }.providers.map { it.id to it.source },
        )
        assertEquals(listOf("OpenAI" to KeySource.OAuth, "Hugging Face" to KeySource.Env), r.clis.first { it.id == "opencode" }.providers.map { it.id to it.source })
        assertEquals(LoginState.Missing, r.clis.first { it.id == "gemini" }.state)
        assertEquals(listOf("HF_TOKEN" to false, "OPENAI_API_KEY" to true), r.profile.map { it.name to it.placeholder })
        assertEquals(2, r.profile[0].line)
        // Only status commands ran.
        assertEquals(setOf("claude auth status --json", "codex login status", "opencode auth list"), b.log.readLines().toSet())
    }

    @Test
    fun aKeyGoesInOnStdinOnly() {
        assumeTrue(python(), "python3 isn't installed")
        val b = box()
        val got = File(b.home, "got")
        b.cli("codex", """if [ "$1 $2" = "login --with-api-key" ]; then cat > '${got.absolutePath}'; echo "Successfully logged in"; exit 0; fi; [ "$1 $2" = "login status" ] && echo 'Logged in using an API key - sk-***'""")
        val out = run(b, "run", "codex", "key", input = "sk-test-secret-key\n")
        assertEquals("sk-test-secret-key\n", got.readText())
        assertTrue(b.log.readLines().none { "sk-test" in it }, "the key was on a command line")
        assertTrue(out.none { "sk-test" in it })
        val done = assertIs<LoginEvent.Done>(LoginsReader.event(out.last()))
        assertTrue(done.ok)
        assertEquals("OpenAI API key", done.status?.how)
    }

    @Test
    fun aDeviceCodeComesToThePhone() {
        assumeTrue(python(), "python3 isn't installed")
        val b = box()
        b.cli(
            "codex",
            """if [ "$1 $2" = "login --device-auth" ]; then
              printf 'Follow these steps to sign in with ChatGPT using device code authorization:\n\n1. Open this link in your browser and sign in to your account\n   \033[94mhttps://auth.openai.com/codex/device\033[0m\n\n2. Enter this one-time code \033[90m(expires in 15 minutes)\033[0m\n   \033[94mQK7F-29XD\033[0m\n'
              sleep 1; echo 'Successfully logged in'; exit 0; fi
            echo 'Logged in using ChatGPT'""",
        )
        val events = run(b, "run", "codex", "login").mapNotNull(LoginsReader::event)
        assertTrue(LoginEvent.Link("https://auth.openai.com/codex/device") in events, events.toString())
        assertTrue(LoginEvent.Code("QK7F-29XD") in events, events.toString())
        val done = assertIs<LoginEvent.Done>(events.last())
        assertTrue(done.ok)
        assertEquals("ChatGPT login", done.status?.how)
    }

    @Test
    fun claudesBrowserCodeIsTypedBackAndMasked() {
        assumeTrue(python(), "python3 isn't installed")
        val b = box()
        val got = File(b.home, "got")
        b.cli(
            "claude",
            """if [ "$1 $2" = "auth login" ]; then
              echo 'Opening browser to sign in…'
              echo 'If the browser didn'"'"'t open, visit: https://claude.ai/oauth/authorize?code=true&client_id=x&state=abc'
              printf 'Paste code here if prompted > '
              read code; echo "${'$'}code" > '${got.absolutePath}'
              echo 'Login successful.'; exit 0; fi
            echo '{"loggedIn": true, "authMethod": "claude.ai"}'""",
        )
        val p = process(b, "run", "claude", "login")
        val reader = p.inputStream.bufferedReader()
        val seen = mutableListOf<LoginEvent>()
        while (true) {
            val e = LoginsReader.event(reader.readLine() ?: break) ?: continue
            seen += e
            if (e is LoginEvent.Ask) {
                assertTrue(e.secret)
                p.outputStream.write("browser-code-1234#state\n".toByteArray()); p.outputStream.flush()
            }
            if (e is LoginEvent.Done) break
        }
        p.waitFor(20, TimeUnit.SECONDS)
        assertTrue(seen.any { it is LoginEvent.Link && it.url.startsWith("https://claude.ai/oauth/authorize") }, seen.toString())
        assertEquals("browser-code-1234#state", got.readText().trim())
        assertTrue(seen.none { it is LoginEvent.Line && "browser-code-1234" in it.text }, "the code came back: $seen")
        assertTrue(assertIs<LoginEvent.Done>(seen.last()).ok)
    }

    @Test
    fun piTakesANewKeyButNeverReplacesAKeyringOne() {
        assumeTrue(python(), "python3 isn't installed")
        val b = box()
        b.cli("pi", """[ "$1 $2" = "auth check" ] && echo '{"status":"ready","provider":"groq","authType":"api_key"}'""")
        val auth = b.file(".pi/agent/auth.json", """{"deepseek": {"type": "api_key", "key": "!pass show deepseek"}, "llama.cpp": {"type": "api_key", "key": "xx", "env": {"LLAMA_BASE_URL": "http://localhost:8080"}}}""")
        val done = assertIs<LoginEvent.Done>(LoginsReader.event(run(b, "run", "pi", "key", "groq", input = "gsk_new_key\n").last()))
        assertTrue(done.ok, done.msg)
        val json = Json.parseToJsonElement(auth.readText()).jsonObject
        assertEquals("gsk_new_key", json["groq"]!!.jsonObject["key"]!!.jsonPrimitive.content)
        assertEquals("!pass show deepseek", json["deepseek"]!!.jsonObject["key"]!!.jsonPrimitive.content)
        assertEquals("http://localhost:8080", json["llama.cpp"]!!.jsonObject["env"]!!.jsonObject["LLAMA_BASE_URL"]!!.jsonPrimitive.content)
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(auth.toPath()))
        assertFalse(File(auth.path + ".lock").exists())
        assertTrue(b.log.readLines().none { "gsk" in it })

        val refused = assertIs<LoginEvent.Done>(LoginsReader.event(run(b, "run", "pi", "key", "deepseek", input = "typed-over\n").last()))
        assertFalse(refused.ok)
        assertTrue(refused.msg!!.contains("a command (a keyring)"), refused.msg)
        assertFalse("typed-over" in auth.readText())

        // Sign out takes only that entry out; a keyring one stays.
        assertTrue(assertIs<LoginEvent.Done>(LoginsReader.event(run(b, "run", "pi", "logout", "groq").last())).ok)
        assertFalse("groq" in auth.readText())
        assertFalse(assertIs<LoginEvent.Done>(LoginsReader.event(run(b, "run", "pi", "logout", "deepseek").last())).ok)
        assertTrue("deepseek" in auth.readText())
    }

    @Test
    fun piWaitsForItsOwnLock() {
        assumeTrue(python(), "python3 isn't installed")
        val b = box()
        b.cli("pi", "exit 0")
        val auth = b.file(".pi/agent/auth.json", "{}")
        File(auth.path + ".lock").mkdirs()
        val done = assertIs<LoginEvent.Done>(LoginsReader.event(run(b, "run", "pi", "key", "groq", input = "k-123456\n").last()))
        assertFalse(done.ok)
        assertTrue(done.msg!!.contains("pi is changing its logins"), done.msg)
        assertEquals("{}", auth.readText())
    }

    @Test
    fun signOutRunsTheClisOwnCommand() {
        assumeTrue(python(), "python3 isn't installed")
        val b = box()
        b.cli("claude", """[ "$1 $2" = "auth logout" ] && { echo 'Successfully logged out'; exit 0; }; echo '{"loggedIn": false}'""")
        val done = assertIs<LoginEvent.Done>(LoginsReader.event(run(b, "run", "claude", "logout").last()))
        assertTrue(done.ok)
        assertEquals(LoginState.SignedOut, done.status?.state)
        assertNotNull(b.log.readLines().firstOrNull { it == "claude auth logout" })
    }
}
