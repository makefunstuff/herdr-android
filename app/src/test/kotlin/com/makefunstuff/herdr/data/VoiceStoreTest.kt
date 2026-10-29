package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.chat.VoiceNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class VoiceStoreTest {
    @Test fun keepsTheVoiceMessagesAndTheirStateAcrossARestart() {
        val file = Files.createTempDirectory("voice").resolve("voice.json").toFile()
        val store = VoiceStore(file)
        val a = store.newFile().apply { writeText("a") }
        val b = store.newFile().apply { writeText("b") }
        store.add(VoiceNote("1", "agent/devbox/w1:p1", a.path, 7_000, 1_000))
        store.add(VoiceNote("2", "coordinator/devbox", b.path, 3_000, 2_000))
        store.update("1") { it.copy(remote = "/home/u/x/voice-message-0m07s.m4a", error = "Couldn't reach devbox") }

        val again = VoiceStore(file)
        assertEquals(listOf("1", "2"), again.notes.value.map { it.id })
        assertEquals("Couldn't reach devbox", again.note("1")?.error)
        assertEquals("/home/u/x/voice-message-0m07s.m4a", again.note("1")?.remote)
    }

    @Test fun thrownAwayItsFileGoesToo() {
        val file = Files.createTempDirectory("voice").resolve("voice.json").toFile()
        val store = VoiceStore(file)
        val a = store.newFile().apply { writeText("a") }
        store.add(VoiceNote("1", "chat", a.path, 7_000, 1_000))
        store.drop("1")
        assertFalse(a.exists())
        assertTrue(VoiceStore(file).notes.value.isEmpty())
    }

    @Test fun aNoteWhoseFileIsGoneAndTheOldWordsOnlyFileReadAsNone() {
        val file = Files.createTempDirectory("voice").resolve("voice.json").toFile()
        // As AND-53 left it: the words of what went, by chat.
        file.writeText("""{"agent/devbox/w1:p1":[{"text":"Ship it","at":1000}]}""")
        assertTrue(VoiceStore(file).notes.value.isEmpty())
        val store = VoiceStore(file)
        store.add(VoiceNote("1", "chat", "/nowhere/1.m4a", 7_000, 1_000))
        assertTrue(VoiceStore(file).notes.value.isEmpty())
    }
}
