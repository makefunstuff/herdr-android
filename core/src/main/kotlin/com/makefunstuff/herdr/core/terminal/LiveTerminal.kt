package com.makefunstuff.herdr.core.terminal

import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable

/**
 * A live, writable terminal the Terminal screen draws: a classic herdr pane ([TerminalStream]) or
 * a herdr-core terminal ([com.makefunstuff.herdr.core.hc.HcTerminalStream]).
 */
interface LiveTerminal : Closeable {
    val emulator: TerminalEmulator
    val revision: StateFlow<Long>
    val status: StateFlow<TerminalStream.Status>

    fun open()
    fun send(bytes: ByteArray)
    fun send(text: String)
    fun resize(cols: Int, rows: Int)

    /** Scrolls the view [lines] back ([up]) or forward, where the backend can. */
    fun scroll(up: Boolean, lines: Int)
}
