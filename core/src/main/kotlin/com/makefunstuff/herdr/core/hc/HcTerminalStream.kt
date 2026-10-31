package com.makefunstuff.herdr.core.hc

import com.makefunstuff.herdr.core.terminal.LiveTerminal
import com.makefunstuff.herdr.core.terminal.TerminalEmulator
import com.makefunstuff.herdr.core.terminal.TerminalStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A live, writable view of one herdr-core terminal, the counterpart of [TerminalStream] for a
 * classic pane: attached on the machine's one [HcClient] connection, its bytes fed to [emulator],
 * [revision] ticking after each read. The core sends raw output (it never re-encodes it), so the
 * app's own emulator draws it as it draws any terminal.
 *
 * Attaching takes the terminal's size at once (hc: the last active viewer sets it), as classic
 * herdr's takeover does; with [observe] it only watches, never typing or resizing. hc has no
 * scroll request and the emulator keeps no scrollback, so [scroll] does nothing yet.
 */
class HcTerminalStream(
    private val client: HcClient,
    private val address: String,
    cols: Int,
    rows: Int,
    private val scope: CoroutineScope,
    private val observe: Boolean = false,
) : LiveTerminal {
    override val emulator = TerminalEmulator(cols, rows)
    private val _revision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = _revision.asStateFlow()
    private val _status = MutableStateFlow<TerminalStream.Status>(TerminalStream.Status.Opening)
    override val status: StateFlow<TerminalStream.Status> = _status.asStateFlow()

    private var job: Job? = null
    private val outbound = Channel<suspend (HcAttach) -> Unit>(Channel.UNLIMITED)
    @Volatile private var cols = cols
    @Volatile private var rows = rows

    override fun open() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            var a: HcAttach? = null
            try {
                // Not cut short: the core keeps a viewer whose reply was dropped, so it is detached below instead.
                a = withContext(NonCancellable) { client.attach(address, cols, rows, observe = observe) }
                ensureActive()
                val stream = a
                if (observe) {
                    // Drawn at the size the person's window keeps.
                    if (stream.terminal.cols > 0 && stream.terminal.rows > 0) synchronized(emulator.lock) { emulator.resize(stream.terminal.cols, stream.terminal.rows) }
                }
                val writer = launch { for (op in outbound) runCatching { op(stream) } }
                while (true) {
                    val bytes = stream.output() ?: break
                    synchronized(emulator.lock) { emulator.feed(bytes) }
                    if (_status.value != TerminalStream.Status.Live) _status.value = TerminalStream.Status.Live
                    _revision.update { it + 1 }
                }
                writer.cancel()
                val reason = stream.closeReason()
                // `signaled`: a kept terminal lost its program and gets a new shell; reattach after `restarted`.
                _status.value = TerminalStream.Status.Closed(reason ?: "The terminal stream ended", isRemoteExit = reason != null && reason != "signaled")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _status.value = TerminalStream.Status.Closed(t.message ?: "The terminal stream failed", isRemoteExit = false)
            } finally {
                a?.close()
            }
        }
    }

    override fun send(bytes: ByteArray) {
        if (!observe) outbound.trySend { it.write(bytes) }
    }

    override fun send(text: String) = send(text.toByteArray(Charsets.UTF_8))

    override fun resize(cols: Int, rows: Int) {
        if (cols == this.cols && rows == this.rows) return
        this.cols = cols
        this.rows = rows
        // Watching, the program keeps drawing at the person's size: only the viewer's changes.
        if (observe) return
        synchronized(emulator.lock) { emulator.resize(cols, rows) }
        outbound.trySend { it.resize(cols, rows) }
    }

    override fun scroll(up: Boolean, lines: Int) {}

    /**
     * The program's size changed elsewhere (hc's `size` event: someone typed in the desktop window,
     * which then sets the size): the emulator follows, so the phone draws what the program draws.
     */
    fun programResized(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        // Noted as the viewer's too: the phone's next own size then differs from it and is sent.
        this.cols = cols
        this.rows = rows
        synchronized(emulator.lock) { emulator.resize(cols, rows) }
        _revision.update { it + 1 }
    }

    override fun close() {
        outbound.close()
        job?.cancel()
        job = null
    }
}
