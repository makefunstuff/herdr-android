package com.makefunstuff.herdr.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The chats' and the terminal's text sizes, each its own and apart from the phone's: [Sizes.chat]
 * scales a chat's messages and composer over the phone's font size (an agent's chat, the
 * Coordinator, a bubble), [Sizes.terminal] is the terminal's font in sp (pinching the terminal
 * sets it too). Kept through restarts; [terminalBefore] is the terminal's size from before there
 * was this store, taken the first time.
 */
class TextSizeStore(private val file: File, terminalBefore: () -> Float? = { null }) {
    @Serializable
    data class Sizes(val chat: Float = 1f, val terminal: Float = TERMINAL_DEFAULT)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(load() ?: Sizes(terminal = terminalBefore()?.let(::terminalIn) ?: TERMINAL_DEFAULT))
    val state: StateFlow<Sizes> = _state.asStateFlow()

    /** The chats' scale, one of [CHAT_STEPS]. */
    fun setChat(scale: Float) = change { it.copy(chat = CHAT_STEPS.minBy { s -> kotlin.math.abs(s - scale) }) }

    /** The terminal's font, in sp, kept within [TERMINAL_RANGE]. */
    fun setTerminal(sp: Float) = change { it.copy(terminal = terminalIn(sp)) }

    @Synchronized
    private fun change(f: (Sizes) -> Sizes) {
        val before = _state.value
        _state.update(f)
        val next = _state.value
        if (next == before) return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(Sizes.serializer(), next))
            tmp.renameTo(file)
        }
    }

    private fun load(): Sizes? =
        runCatching { if (file.exists()) json.decodeFromString(Sizes.serializer(), file.readText()) else null }.getOrNull()

    companion object {
        const val TERMINAL_DEFAULT = 11f
        /** What pinching the terminal reaches. */
        val TERMINAL_RANGE = 5f..24f
        /** The chats' sizes Settings steps through, from a little smaller than the phone's to half again. */
        val CHAT_STEPS = listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f)

        private fun terminalIn(sp: Float) = sp.coerceIn(TERMINAL_RANGE)
    }
}
