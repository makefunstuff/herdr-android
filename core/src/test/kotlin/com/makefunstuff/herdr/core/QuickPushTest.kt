package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.AlertChange
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.core.push.HerdrPush
import com.makefunstuff.herdr.core.push.PushEvent
import com.makefunstuff.herdr.core.push.PushMessages
import com.makefunstuff.herdr.core.push.PushSeal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** herdr-push telling the phone that an AI quick command ended, and the notification it makes. */
class QuickPushTest {
    private fun python(): Boolean = runCatching {
        ProcessBuilder("python3", "--version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
    }.getOrDefault(false)

    private fun sh(dir: File, env: Map<String, String>, command: String, input: String = ""): String {
        val pb = ProcessBuilder("sh", "-c", command).directory(dir)
        pb.environment().putAll(env)
        val p = pb.start()
        p.outputStream.use { it.write(input.toByteArray()) }
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.errorStream.readBytes()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        return out
    }

    @Test
    fun tellsOfAnAiRunThatEnded() {
        assumeTrue(python(), "python3 isn't installed")
        val dir = Files.createTempDirectory("herdr-quick-push").toFile()
        try {
            val runs = File(dir, "state/herdr-android/quick")
            val env = mapOf("HOME" to dir.path, "HERDR_PUSH_HOME" to File(dir, "push").path, "HERDR_PUSH_HEALTH_EVERY" to "0", "XDG_STATE_HOME" to File(dir, "state").path)
            assertEquals("", sh(dir, env, HerdrPush.installCommand(), HerdrPush.script).trim())
            sh(dir, env, HerdrPush.command("subscribe"), HerdrPush.subscription("phone1:m1", "Pixel", "http://127.0.0.1:9/up", PushSeal.newKey(), "default", "/nowhere/herdr", null))
            fun run(id: String, kind: String, state: String, ended: Long, answer: String = "", error: String = "") {
                val d = File(runs, id).apply { mkdirs() }
                File(d, "meta.json").writeText("""{"name":"Find TODOs","kind":"$kind","cwd":"/home/demo/app"}""" + "\n")
                File(d, "status.json").writeText("""{"state":"$state","ended":$ended,"error":"$error"}""" + "\n")
                if (answer.isNotEmpty()) File(d, "answer.md").writeText(answer)
            }
            val now = System.currentTimeMillis() / 1000
            fun quick(): JsonArray? {
                val o = assertNotNull(HerdrPush.answer(sh(dir, env, HerdrPush.command("once", "--dry-run"))))
                return (o["events"] as JsonObject)["@quick"] as? JsonArray
            }
            // What ended before the first look is old news.
            run("q1-a", "prompt", "done", now - 30, "old")
            assertEquals(null, quick())
            // Then: one done, one at its limit, one stopped by hand, a script, one still running.
            run("q2-b", "prompt", "done", now, "## Three TODOs\n\n- a\n- b\n")
            run("q3-c", "prompt", "limit", now, error = "Stopped at its time limit (10 min)")
            run("q4-d", "prompt", "stopped", now)
            run("q5-e", "script", "done", now)
            run("q6-f", "prompt", "running", 0)
            val events = assertNotNull(quick())
            assertEquals(2, events.size)
            // Told once.
            assertEquals(null, quick())

            val msg = assertNotNull(PushMessages.parse(buildJsonObject { put("v", 1); put("host", "devbox"); put("at", now); put("events", events) }.toString()))
            val done = assertIs<PushEvent.Quick>(msg.events[0])
            assertEquals("q2-b", done.id)
            assertEquals("done", done.state)
            val alert = assertIs<AlertChange.Show>(Alerts.of("m1", "devbox", done)).alert
            assertEquals("quick:m1:q2-b", alert.tag)
            assertEquals("Find TODOs is done", alert.title)
            assertEquals("Three TODOs", alert.text)
            assertEquals("q2-b", alert.quickRun)
            assertEquals(Alert.Kind.Finished, alert.kind)
            assertTrue(alert.detail.endsWith("devbox · app"), alert.detail)
            val limit = assertIs<AlertChange.Show>(Alerts.of("m1", "devbox", msg.events[1])).alert
            assertEquals("Find TODOs stopped at its limit", limit.title)
            assertEquals("Stopped at its time limit (10 min)", limit.text)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aStoppedOneSaysNothing() {
        assertIs<AlertChange.Clear>(Alerts.quick("m1", "devbox", PushEvent.Quick("q1", "x", "stopped")))
        val failed = assertIs<AlertChange.Show>(Alerts.quick("m1", "devbox", PushEvent.Quick("q1", "Explain", "failed", error = "No key for deepseek"))).alert
        assertEquals("Explain failed", failed.title)
        assertEquals("No key for deepseek", failed.text)
    }
}
