package com.makefunstuff.herdr.core.quick

/**
 * A run's output as the run sheet shows it, read on from where the last read stopped. A script's
 * is kept to its last [keep] bytes or so: one that printed more than that (a long build log) is read
 * from there, so its end, where an error is, always shows, and [skipped] says how much is left out.
 * A prompt's answer is read whole each time.
 */
class QuickOutput(private val keep: Long = KEEP) {
    /** The byte of the output the next read starts at. */
    var offset = 0L
        private set

    var text = ""
        private set

    /** Bytes at the start that aren't in [text]. */
    var skipped = 0L
        private set

    /** Reads what came since the last read. */
    suspend fun read(q: QuickClient, id: String): RunRead {
        var read = q.read(id, offset)
        val run = read.run ?: return read
        if (run.kind == QuickKind.Prompt) {
            text = read.text
            return read
        }
        // Far behind (it printed a lot, or the sheet opened late): go on from near its end.
        if (run.size - offset > keep) {
            offset = run.size - keep
            skipped = offset
            text = ""
            read = q.read(id, offset)
            // From the start of a line, not halfway into one (or into a character).
            val cut = read.text.indexOf('\n').takeIf { it >= 0 && read.bytes > 0 }
            if (cut != null) {
                val dropped = read.text.substring(0, cut + 1).toByteArray().size.toLong()
                offset += dropped
                skipped += dropped
                read = read.copy(text = read.text.substring(cut + 1), bytes = read.bytes - dropped)
            }
        }
        text += read.text
        offset += read.bytes
        // Still too long (it kept printing): the start goes.
        if (text.length > keep) {
            val cut = text.indexOf('\n', text.length - keep.toInt()).let { if (it < 0) text.length - keep.toInt() else it + 1 }
            skipped += text.substring(0, cut).toByteArray().size
            text = text.substring(cut)
        }
        return read
    }

    /** Everything [run] printed has been read: it ended, and the reads reached the end of its output. */
    fun done(run: QuickRun, last: RunRead): Boolean =
        !run.state.running && (run.kind == QuickKind.Prompt || offset >= run.size || last.bytes == 0L)

    companion object {
        const val KEEP = 256L * 1024
    }
}
