package com.makefunstuff.herdr.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class LookedStoreTest {
    @Test fun keepsWhenEachChatWasLastOnScreenAcrossARestart() {
        val file = Files.createTempDirectory("looked").resolve("looked.json").toFile()
        val store = LookedStore(file)
        assertEquals(0L, store.at("devbox", "w1:p1"))
        store.mark("devbox", "w1:p1", 1_000)
        store.mark("devbox", "w2:p1", 2_000)
        store.mark("devbox", "w1:p1", 3_000)

        val again = LookedStore(file)
        assertEquals(3_000L, again.at("devbox", "w1:p1"))
        assertEquals(2_000L, again.at("devbox", "w2:p1"))
        assertEquals(0L, again.at("gpu-box", "w1:p1"))
    }
}
