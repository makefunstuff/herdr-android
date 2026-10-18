package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.activity.ActivityCommands
import com.makefunstuff.herdr.core.activity.Durations
import com.makefunstuff.herdr.core.activity.Entry
import com.makefunstuff.herdr.core.activity.RecordReader
import com.makefunstuff.herdr.core.activity.SessionsReader
import com.makefunstuff.herdr.core.activity.Timeline
import com.makefunstuff.herdr.core.ssh.Duplex
import com.makefunstuff.herdr.core.ssh.Machine
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reads a real task's timeline from this machine's board, the way the app does over SSH (here the
 * commands run locally). Runs only with HERDR_ACTIVITY_LIVE=1; HERDR_ACTIVITY_TASK names the task
 * (default herdr-android/34). It prints the timeline, to look at.
 *
 * HERDR_ACTIVITY_LIVE=1 ./gradlew -p core test --tests '*LiveActivityTest' -i
 */
class LiveActivityTest {
    @Test
    fun readsARealTasksTimeline() = runBlocking {
        assumeTrue(System.getenv("HERDR_ACTIVITY_LIVE") == "1")
        val task = System.getenv("HERDR_ACTIVITY_TASK") ?: "herdr-android/34"
        val launcher = File(System.getProperty("user.home"), ".herdr-projects/data/bin/herdr-projects").path
        val out = ProcessBuilder("sh", "-c", ActivityCommands.record(Machine(id = "m", label = "m", host = "h", user = "u"), launcher, task)).start()
        val text = out.inputStream.readBytes().toString(Charsets.UTF_8)
        val record = RecordReader.parse(text)
        assertTrue(record != null, "no record for $task")
        val reader = SessionsReader(record.sessions)
        val started = System.currentTimeMillis()
        SessionsReader.stream({ cmd -> local(cmd) }, ActivityCommands.sessions(record.sessions, follow = false), reader) {}
        val timeline = Timeline.of(reader.turns(), record.events, reader.truncated, reader.missing)
        println("read in ${System.currentTimeMillis() - started} ms: ${record.sessions.size} sessions, ${reader.sessions.size} found, ${reader.missing} missing, cut ${reader.truncated}")
        for (e in timeline.entries) {
            when (e) {
                is Entry.Event -> println("◆ ${e.step.kind} ${e.step.title} ${e.step.note.orEmpty()}")
                is Entry.TurnEntry -> {
                    val t = e.turn
                    println("▶ [${t.role.label}] ${t.from} ${t.prompt} · ${t.agent} · ${t.took?.let(Durations::short)} · ${t.steps.size} steps, ${t.failures} failed")
                    t.steps.take(400).forEach { s ->
                        println("    ${s.kind} ${s.state} ${s.exit ?: ""} ${s.took?.let(Durations::short) ?: ""} ${s.title.take(90)} ${s.note.orEmpty()} ${if (s.diff != null) "[diff ${s.diff!!.hunks.size}]" else ""}")
                    }
                }
            }
        }
        assertTrue(timeline.entries.isNotEmpty())
    }

    private fun local(cmd: String): Duplex {
        val p = ProcessBuilder("sh", "-c", cmd).start()
        return object : Duplex {
            override val input: InputStream = p.inputStream
            override val output: OutputStream = p.outputStream
            override fun close() { p.destroy() }
        }
    }
}
