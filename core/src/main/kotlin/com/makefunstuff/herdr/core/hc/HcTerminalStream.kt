package com.makefunstuff.herdr.core.hc

import com.makefunstuff.herdr.core.terminal.TerminalEmulator
import com.makefunstuff.herdr.core.terminal.TerminalStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * A live, writable view of one herdr-core terminal, the counterpart of [TerminalStream] for a
 * classic pane: attached on the machine's one [HcClient] connection, its bytes fed to [emulator],
 * [revision] ticking after each read. The core sends raw output (it never re-encodes it), so the
 * app's own emulator draws it as it draws any terminal.
 *
 * Unlike herdr's pane stream there is no scroll request: the first paint carries [history] lines,
 * and the emulator scrolls back through those.
 */
class HcTerminalStream(
    private val client: HcClient,
    private val address: String,
    cols: Int,
    rows: Int,
    private val scope: CoroutineScope,
    private val history: Int = 1000,
    /** Watch only: never types or resizes the terminal (the person's own window keeps its size). */
    private val observe: Boolean = false,
) : Closeable {
    val emulator = TerminalEmulator(cols, rows)
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()
    private val _status = MutableStateFlow<TerminalStream.Status>(TerminalStream.Status.Opening)
    val status: StateFlow<TerminalStream.Status> = _status.asStateFlow()

    private var attach: HcAttach? = null
    private var job: Job? = null
    private val outbound = Channel<suspend (HcAttach) -> Unit>(Channel.UNLIMITED)
    private var cols = cols
    private var rows = rows

    fun open() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            var a: HcAttach? = null
            try {
                a = client.attach(address, cols, rows, history, observe)
                attach = a
                val stream = a
                val writer = launch { for (op in outbound) runCatching { op(stream) } }
                while (true) {
                    val bytes = stream.output() ?: break
                    synchronized(emulator.lock) { emulator.feed(bytes) }
                    if (_status.value != TerminalStream.Status.Live) _status.value = TerminalStream.Status.Live
                    _revision.update { it + 1 }
                }
                writer.cancel()
                val reason = stream.closeReason()
                _status.value = TerminalStream.Status.Closed(reason ?: "The terminal stream ended", isRemoteExit = reason != null)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _status.value = TerminalStream.Status.Closed(t.message ?: "The terminal stream failed", isRemoteExit = false)
            } finally {
                a?.close()
            }
        }
    }

    fun send(bytes: ByteArray) {
        outbound.trySend { it.write(bytes) }
    }

    fun send(text: String) = send(text.toByteArray(Charsets.UTF_8))

    fun resize(cols: Int, rows: Int) {
        if (cols == this.cols && rows == this.rows) return
        this.cols = cols
        this.rows = rows
        synchronized(emulator.lock) { emulator.resize(cols, rows) }
        outbound.trySend { it.resize(cols, rows) }
    }

    override fun close() {
        outbound.close()
        attach?.close()
        attach = null
        job?.cancel()
        job = null
    }
}
