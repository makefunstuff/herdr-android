package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.WorkCard
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.shepherdr.SpaceChatItem
import com.makefunstuff.herdr.core.shepherdr.V2Choice
import com.makefunstuff.herdr.core.shepherdr.V2Question
import com.makefunstuff.herdr.core.voice.EdgeVoice
import com.makefunstuff.herdr.core.voice.Jitter
import com.makefunstuff.herdr.core.voice.PcmFloats
import com.makefunstuff.herdr.core.voice.ReadQueue
import com.makefunstuff.herdr.core.voice.SpeechText
import com.makefunstuff.herdr.core.voice.VoiceState
import com.makefunstuff.herdr.core.voice.simulatePlayback
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceRelayTest {
    private fun words(id: String, text: String, you: Boolean = false) = SpaceChatItem.Words(id, you, text)

    @Test
    fun codeBlocksAreNamedNotRead() {
        val said = SpeechText.plain("Here's the fix:\n```kotlin\nfun a() {\n  b()\n}\n\nval c = 1\n```\nTests pass.")
        assertEquals("Here's the fix: A code block, 4 lines. Tests pass.", said)
        assertEquals("Run it: A line of code.", SpeechText.plain("Run it:\n```\n./gradlew test\n```"))
        assertEquals("Then: A code block, 2 lines. Done.", SpeechText.plain("Then:\n~~~\nval x = foo(bar)\nprint(x)\n~~~\nDone."))
        assertEquals("Like this: A code block, 2 lines. Done.", SpeechText.plain("Like this:\n\n    val x = foo(bar)\n    print(x)\n\nDone."))
    }

    @Test
    fun pathsAndLinksAreNamed() {
        assertEquals(
            "I changed a file, VoiceBar.kt and a file, SpaceCoordinator.kt.",
            SpeechText.plain("I changed `app/src/main/kotlin/ui/design/VoiceBar.kt` and app/src/main/kotlin/ui/coordinator/SpaceCoordinator.kt."),
        )
        assertEquals("The PR is at a link.", SpeechText.plain("The PR is at https://github.com/makefunstuff/herdr-android/pull/67."))
        assertEquals("See the docs.", SpeechText.plain("See [the docs](https://example.com/docs)."))
        assertEquals("It lives in a folder.", SpeechText.plain("It lives in `~/.shepherdr/plugins/`."))
        // Short code words are read as they are.
        assertEquals("Run gradle test now.", SpeechText.plain("Run `gradle test` now."))
        assertEquals("Call some code first.", SpeechText.plain("Call `session.awaitWorkBoard(timeoutMs = 20_000)` first."))
    }

    @Test
    fun markdownMarksGoAndListsAreSentences() {
        assertEquals(
            "Done. Merged HA-3. CI is green. Next: HA-5.",
            SpeechText.plain("## Done\n- Merged **HA-3**\n- CI is _green_\n\nNext: HA-5"),
        )
        assertEquals("Status: A table, 2 rows.", SpeechText.plain("Status:\n| Task | State |\n|---|---|\n| HA-1 | done |\n| HA-2 | ready |"))
    }

    @Test
    fun longRepliesStopWithMoreInTheChat() {
        val long = (1..12).joinToString(" ") { "Sentence number $it says a few more words about the work." }
        val said = SpeechText.reply(long)
        assertTrue(said.endsWith("More in the chat."), said)
        assertTrue(said.length <= SpeechText.MAX_CHARS + 20, said)
        assertTrue(said.startsWith("Sentence number 1 says"))
        assertEquals("Short and done.", SpeechText.reply("Short and done."))
    }

    @Test
    fun chatItemsAsSpeech() {
        assertNull(SpeechText.of(words("1", "mine", you = true)))
        assertEquals("HA-5 is ready to merge.", SpeechText.of(SpaceChatItem.Update("2", "HA-5", WorkStatus.Ready, "is ready to merge")))
        val q = V2Question("q1", title = "Rerun CI on main", choices = listOf(V2Choice("Rerun it"), V2Choice("Skip the flaky test"), V2Choice("Leave it")))
        assertEquals(
            "CI went red. Rerun CI on main? Rerun it, Skip the flaky test, or Leave it.",
            SpeechText.of(SpaceChatItem.Question("3", WorkCard("chat", q), intro = "CI went red.")),
        )
        assertEquals("It sent a file, report.pdf.", SpeechText.of(SpaceChatItem.File("4", false, "report.pdf", "/x/report.pdf")))
        assertNull(SpeechText.of(SpaceChatItem.Voice("5", false, "/x/v.m4a", 3000)))
    }

    @Test
    fun onlyWhatComesAfterVoiceIsOnIsRead() {
        val q = ReadQueue()
        val before = listOf(words("1", "Old news."), words("2", "Older."))
        assertEquals(emptyList(), q.next(before))
        val later = before + words("3", "Mine.", you = true) + words("4", "New reply.") +
            SpaceChatItem.Update("5", "HA-2", WorkStatus.Done, "is merged")
        assertEquals(listOf(ReadQueue.Line("4", "New reply."), ReadQueue.Line("5", "HA-2 is merged.")), q.next(later))
        // Each once.
        assertEquals(emptyList(), q.next(later))
        assertFalse(q.next(later + words("6", "")).isNotEmpty())
    }

    @Test
    fun chunksStartShort() {
        val text = "First a short one. " + (1..8).joinToString(" ") { "Then sentence $it, which runs on a while to fill the piece." }
        val c = SpeechText.chunks(text)
        assertTrue(c.first().length <= 90, c.first())
        assertTrue(c.all { it.length <= 220 })
        assertEquals(text, c.joinToString(" "))
    }

    @Test
    fun serverAddresses() {
        assertEquals("http://192.168.1.138:18101", EdgeVoice.normalize("192.168.1.138:18101/v1/"))
        assertEquals("https://voice.home", EdgeVoice.normalize("https://voice.home"))
        assertNull(EdgeVoice.normalize("ftp://x"))
        assertNull(EdgeVoice.normalize("http://x:99999"))
        assertNull(EdgeVoice.normalize("http://x/y"))
    }

    @Test
    fun pcmAcrossCuts() {
        val floats = floatArrayOf(0.5f, -0.25f, 1f)
        val bytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply { floats.forEach { putFloat(it) } }.array()
        val p = PcmFloats()
        val got = p.feed(bytes.copyOfRange(0, 5)).toList() + p.feed(bytes.copyOfRange(5, 9)).toList() + p.feed(bytes.copyOfRange(9, 12)).toList()
        assertEquals(floats.toList(), got)
        val wav = EdgeVoice.wav16(floatArrayOf(0f, 1f), 16_000)
        assertEquals(48, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
    }

    @Test
    fun serverFasterThanRealTimeStartsAtOnce() {
        // The server makes 4.6 s of speech in 0.5 s: the buffer starts after its base wait, with no gaps.
        val chunk = FloatArray(2400) { (kotlin.math.sin(it / 5.0) * 0.3).toFloat() }
        val chunks = (0 until 46).map { i -> 20.0 + i * 10.0 to chunk }
        val sim = simulatePlayback(Jitter(shared = Jitter.Prior()), chunks)
        assertEquals(0, sim.underruns)
        assertTrue(sim.startMs < 300, "started at ${sim.startMs}")
    }

    @Test
    fun speedSteps() {
        assertEquals(1.2f, VoiceState.nextSpeed(1f))
        assertEquals(0.8f, VoiceState.nextSpeed(1.6f))
        assertEquals(1f, VoiceState.nextSpeed(0.8f))
        assertEquals("Hold to talk", VoiceState(on = true).words)
        assertEquals("Talk any time", VoiceState(on = true, handsFree = true).words)
    }
}
