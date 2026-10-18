package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.terminal.BarKey
import com.makefunstuff.herdr.core.terminal.KeyBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class KeyBarStoreTest {
    private fun file() = Files.createTempDirectory("keys").resolve("key-bar.json").toFile()
    private fun key(spec: String) = BarKey.parse(spec)!!

    @Test fun startsWithTheDefaultBarAndKeepsChangesAcrossARestart() {
        val file = file()
        val store = KeyBarStore(file)
        assertEquals(KeyBar.DEFAULT, store.state.value.keys)
        assertFalse(store.state.value.keepScreenOn)
        store.add(key("C-d"))
        store.remove(key("~"))
        store.move(key("C-d"), -1)
        store.setKeepScreenOn(true)

        val again = KeyBarStore(file).state.value
        assertEquals(KeyBar.DEFAULT.filter { it != "~" }.let { it.dropLast(1) + "C-d" + it.last() }, again.keys)
        assertTrue(again.keepScreenOn)
    }

    @Test fun aKeyIsOnTheBarOnceAndMovesStayInside() {
        val store = KeyBarStore(file())
        store.add(key("Esc"))
        assertEquals(KeyBar.DEFAULT, store.state.value.keys)
        store.move(key("Esc"), -1)
        assertEquals("Esc", store.state.value.keys.first())
        store.move(key("Right"), 1)
        assertEquals("Right", store.state.value.keys.last())
        store.reset()
        assertEquals(KeyBar.DEFAULT, store.state.value.keys)
    }

    @Test fun keysThisAppDoesNotKnowAreLeftOut() {
        val file = file()
        file.parentFile.mkdirs()
        file.writeText("""{"keys":["Esc","@teleport","C-r","Esc"],"keepScreenOn":true,"later":1}""")
        val saved = KeyBarStore(file).state.value
        assertEquals(listOf(key("Esc"), key("C-r")), saved.bar)
        assertTrue(saved.keepScreenOn)
    }
}
