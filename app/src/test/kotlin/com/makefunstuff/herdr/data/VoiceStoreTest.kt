package com.makefunstuff.herdr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VoiceStoreTest {
    private val dir = Files.createTempDirectory("voice").toFile()
    private fun store() = VoiceStore(File(dir, "voice-clips.json"), File(dir, "clips"), File(dir, "sent"))

    private fun VoiceStore.record(name: String, millis: Long): VoiceClip {
        val f = newFile("ogg").apply { writeText(name) }
        return VoiceClip(name, f.path, millis)
    }

    @Test fun aRecordingWaitsInItsComposerAcrossARestart() {
        val store = store()
        val a = store.record("a", 7_000)
        store.add("chat", a)
        store.add("other", store.record("b", 2_000))

        val again = store()
        assertEquals(listOf(a), again.current("chat"))
        assertEquals(1, again.current("other").size)
        // One whose file is gone isn't sent empty.
        File(a.path).delete()
        assertEquals(emptyList<VoiceClip>(), store().current("chat"))
    }

    @Test fun copiedItIsNamedInTheMessageAndKeptAsSent() {
        val store = store()
        val a = store.record("a", 7_000)
        store.add("chat", a)
        // Not on the machine yet: the message doesn't name it.
        assertEquals("look", store.message("chat", " look "))
        val remote = "/home/demo/src/app/.herdr-voice/voice-message-1.ogg"
        assertTrue(store.uploaded("chat", "a", "devbox", remote))
        assertEquals("@$remote look", store.message("chat", "look"))
        assertEquals("@$remote", store.message("chat", ""))
        assertEquals("/model @$remote", store.message("chat", "/model"))
        assertEquals(7_000L, store.millis("devbox", remote))
        assertNull(store.millis("gpu-box", remote))
        assertEquals("a", store.sentFile("devbox", remote).readText())

        // Recorded while the message was on its way: it stays for the next one.
        val b = store.record("b", 3_000)
        store.add("chat", b)
        store.sent("chat", listOf(store.current("chat").first()))
        assertEquals(listOf(b), store.current("chat"))
        assertFalse(File(a.path).exists())
        assertTrue(store.sentFile("devbox", remote).exists())
        assertEquals(7_000L, store().millis("devbox", remote))
    }

    @Test fun removedItLeavesThePhone() {
        val store = store()
        val a = store.record("a", 1_000)
        store.add("chat", a)
        store.remove("chat", "a")
        assertEquals(emptyList<VoiceClip>(), store.current("chat"))
        assertFalse(File(a.path).exists())
        // Copied after it was removed: nothing comes back.
        assertFalse(store.uploaded("chat", "a", "devbox", "/x.ogg"))
        assertEquals(emptyList<VoiceClip>(), store.current("chat"))
    }

    @Test fun aMessageTakesAFewAtMost() {
        val store = store()
        repeat(VoiceStore.MAX_CLIPS) { store.add("chat", store.record("c$it", 1_000)) }
        val extra = store.record("extra", 1_000)
        assertFalse(store.add("chat", extra))
        assertFalse(File(extra.path).exists())
    }

    @Test fun oneReadBackIsKept() {
        val store = store()
        val f = store.keepSent("devbox", "/x/v.aac", byteArrayOf(1, 2, 3))
        assertEquals(store.sentFile("devbox", "/x/v.aac"), f)
        assertTrue(f.name.endsWith(".aac"))
        assertEquals(3L, f.length())
    }
}
