package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.chat.Spoken
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class VoiceStoreTest {
    @Test fun keepsTheVoiceMessagesPerChatAcrossARestart() {
        val file = Files.createTempDirectory("voice").resolve("voice.json").toFile()
        val store = VoiceStore(file)
        store.add("agent/devbox/w1:p1", "Run the tests again", 1_000)
        store.add("agent/devbox/w2:p1", "Ship it", 2_000)
        store.add("agent/devbox/w1:p1", "Then open a PR", 3_000)

        val again = VoiceStore(file)
        assertEquals(
            listOf(Spoken.Sent("Run the tests again", 1_000), Spoken.Sent("Then open a PR", 3_000)),
            again.sent.value["agent/devbox/w1:p1"],
        )
        assertEquals(listOf(Spoken.Sent("Ship it", 2_000)), again.sent.value["agent/devbox/w2:p1"])
    }

    @Test fun aMessageThatDidntGoIsTakenBack() {
        val file = Files.createTempDirectory("voice").resolve("voice.json").toFile()
        val store = VoiceStore(file)
        store.add("chat", "Ship it", 1_000)
        store.add("chat", "Ship it", 2_000)
        store.drop("chat", "Ship it", 2_000)
        store.drop("other", "Ship it", 1_000)
        assertEquals(listOf(Spoken.Sent("Ship it", 1_000)), VoiceStore(file).sent.value["chat"])
    }
}
