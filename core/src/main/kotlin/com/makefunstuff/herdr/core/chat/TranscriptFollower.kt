package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.api.AgentSessionRef
import com.makefunstuff.herdr.core.ssh.HerdrCommands
import com.makefunstuff.herdr.core.ssh.MachineConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException

/**
 * Follows an agent's transcript file on the machine: finds it from the session herdr reports, sends
 * its last [TAIL_BYTES], then keeps streaming lines as the agent writes them (`tail -F`), all on one
 * SSH channel.
 */
object TranscriptFollower {
    const val TAIL_BYTES = 2_000_000

    /** Lines longer than this (pasted screenshots, mostly) are skipped rather than parsed. */
    private const val MAX_LINE = 4_000_000

    private const val MISSING = "@@missing"
    private const val FILE = "@@file "

    // $1 kind (path|id), $2 value, $3 agent kind, $4 bytes. The trailing `cat` waits for the app to
    // close the channel, then stops tail, so no tail outlives the chat screen.
    private val SCRIPT = """
        kind="${'$'}1"; value="${'$'}2"; agent="${'$'}3"; bytes="${'$'}4"; f=""
        if [ "${'$'}kind" = path ]; then
          f="${'$'}value"
          case "${'$'}f" in "~/"*) f="${'$'}HOME/${'$'}{f#\~/}" ;; esac
        else
          case "${'$'}agent" in
            claude) for c in "${'$'}{CLAUDE_CONFIG_DIR:-${'$'}HOME/.claude}"/projects/*/"${'$'}value".jsonl; do [ -f "${'$'}c" ] && f="${'$'}c" && break; done ;;
            codex) f=${'$'}(find "${'$'}{CODEX_HOME:-${'$'}HOME/.codex}/sessions" -name "*${'$'}value.jsonl" 2>/dev/null | head -n 1) ;;
            pi) for c in "${'$'}HOME"/.pi/agent/sessions/*/*_"${'$'}value".jsonl; do [ -f "${'$'}c" ] && f="${'$'}c" && break; done ;;
            omp) for c in "${'$'}HOME"/.omp/agent/sessions/*/*_"${'$'}value".jsonl; do [ -f "${'$'}c" ] && f="${'$'}c" && break; done ;;
          esac
        fi
        if [ -z "${'$'}f" ] || [ ! -f "${'$'}f" ]; then echo "$MISSING"; exit 0; fi
        size=${'$'}(wc -c < "${'$'}f" | tr -d ' ')
        echo "$FILE${'$'}size ${'$'}f"
        tail -c "${'$'}bytes" -F "${'$'}f" 2>/dev/null &
        t=${'$'}!
        cat > /dev/null
        kill "${'$'}t" 2>/dev/null
    """.trimIndent()

    fun command(ref: AgentSessionRef, bytes: Int = TAIL_BYTES): String =
        "sh -c ${HerdrCommands.quote(SCRIPT)} follow " +
            listOf(ref.kind, ref.value, ref.agent, bytes.toString()).joinToString(" ") { HerdrCommands.quote(it) }

    sealed interface Result {
        /** No transcript the app can find for this session. */
        data object Missing : Result

        /** The stream ended (the connection dropped, or the file went away). */
        data object Ended : Result
    }

    /**
     * Streams [ref]'s transcript into [transcript], calling [onUpdate] after each burst of lines.
     * [onUpdate]'s flag says the start of the file was cut off. Returns when the stream ends;
     * cancelling the caller closes the channel.
     */
    suspend fun follow(
        connection: MachineConnection,
        ref: AgentSessionRef,
        transcript: Transcript,
        onUpdate: (truncated: Boolean) -> Unit,
    ): Result = coroutineScope {
        val process = connection.start(command(ref))
        try {
            val reader = process.stdout.bufferedReader(Charsets.UTF_8)
            val header = withContext(Dispatchers.IO) { reader.readLine() } ?: throw IOException("transcript stream closed")
            if (header.startsWith(MISSING)) return@coroutineScope Result.Missing
            val size = header.removePrefix(FILE).substringBefore(' ').toLongOrNull() ?: 0
            val truncated = size > TAIL_BYTES
            val job = launch(Dispatchers.IO) { pump(reader, transcript, truncated, onUpdate) }
            job.join()
            Result.Ended
        } finally {
            withContext(Dispatchers.IO) { runCatching { process.close() } }
        }
    }

    private fun pump(reader: BufferedReader, transcript: Transcript, truncated: Boolean, onUpdate: (Boolean) -> Unit) {
        // tail -c starts mid-line when it cut the file; that first piece is not JSON.
        var skipFirst = truncated
        var published = transcript.revision
        onUpdate(truncated)
        while (true) {
            val line = reader.readLine() ?: break
            if (skipFirst) {
                skipFirst = false
                continue
            }
            if (line.length <= MAX_LINE) transcript.feed(line)
            if (!reader.ready() && transcript.revision != published) {
                published = transcript.revision
                onUpdate(truncated)
            }
        }
    }
}
