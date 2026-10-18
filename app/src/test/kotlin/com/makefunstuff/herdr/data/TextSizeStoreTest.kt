package com.makefunstuff.herdr.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class TextSizeStoreTest {
    private fun file() = Files.createTempDirectory("sizes").resolve("text-sizes.json").toFile()

    @Test fun keepsBothSizesApartAcrossARestart() {
        val file = file()
        val store = TextSizeStore(file)
        assertEquals(TextSizeStore.Sizes(1f, 11f), store.state.value)
        store.setChat(1.3f)
        store.setTerminal(14f)

        val again = TextSizeStore(file)
        assertEquals(1.3f, again.state.value.chat)
        assertEquals(14f, again.state.value.terminal)
    }

    @Test fun chatSnapsToAStepAndTheTerminalStaysInItsRange() {
        val store = TextSizeStore(file())
        store.setChat(1.2f)
        assertEquals(1.15f, store.state.value.chat)
        store.setTerminal(60f)
        assertEquals(24f, store.state.value.terminal)
        store.setTerminal(1f)
        assertEquals(5f, store.state.value.terminal)
    }

    @Test fun takesTheTerminalsSizeFromBeforeOnlyTheFirstTime() {
        val file = file()
        assertEquals(13.5f, TextSizeStore(file) { 13.5f }.state.value.terminal)
        TextSizeStore(file) { 13.5f }.setChat(0.85f)
        // Saved now: what came before is no longer read.
        assertEquals(13.5f, TextSizeStore(file) { 9f }.state.value.terminal)
    }

    @Test fun aPinchSavesOffTheCallersThreadTheLatestOnly() {
        val file = file()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val store = TextSizeStore(file, scope)
        (0..40).forEach { store.setTerminal(8f + it * 0.1f) }
        assertEquals(12f, store.state.value.terminal, 0.001f)
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until && kotlin.math.abs(TextSizeStore(file).state.value.terminal - 12f) > 0.001f) Thread.sleep(20)
        assertEquals(12f, TextSizeStore(file).state.value.terminal, 0.001f)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
