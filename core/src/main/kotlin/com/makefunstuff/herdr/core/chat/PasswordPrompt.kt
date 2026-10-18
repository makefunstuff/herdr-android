package com.makefunstuff.herdr.core.chat

/**
 * A pane that waits for a secret: sudo, ssh, a key's passphrase, a card's PIN. What it typed last
 * ends in a colon after one of those words ("[sudo] password for jpl:", "Enter passphrase for key
 * '/home/jpl/.ssh/id_ed25519':"), and the phone offers a masked field for the answer instead of
 * the keyboard's own, which would show it and learn it.
 */
object PasswordPrompt {
    private val SECRET = Regex("(?i)\\b(pass ?word|pass ?phrase|passcode)s?\\b|\\bPIN\\b")
    /** "Password changed:" or "password: invalid" say something about one; they don't ask. */
    private val NOT_ASKING = Regex("(?i)\\b(changed|updated|incorrect|invalid|failed|expired|saved|set)\\b\\s*:?$")
    private const val LONGEST = 160

    /** The line that asks, when [line] (what stands before the cursor) asks for a secret; else null. */
    fun ask(line: String): String? {
        val l = line.trimEnd()
        if (l.isEmpty() || l.length > LONGEST || !l.endsWith(":")) return null
        if (!SECRET.containsMatchIn(l) || NOT_ASKING.containsMatchIn(l)) return null
        return l.trim()
    }

    /** The asking line at the end of a pane's [screen] text (its last line that isn't blank); null when the pane asks for nothing. */
    fun find(screen: String): String? =
        screen.lineSequence().map { it.trimEnd() }.lastOrNull { it.isNotEmpty() }?.let(::ask)
}
