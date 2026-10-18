package com.makefunstuff.herdr.core.chat

/**
 * A terminal that waits for a secret: sudo, ssh, a key's passphrase, a card's PIN. What stands
 * before the cursor ends in a colon after one of those words ("[sudo] password for jpl:", "Enter
 * passphrase for key '/home/jpl/.ssh/id_ed25519':"), and the phone offers a masked field for the
 * answer instead of the keyboard's own, which would show it and learn it. A line that is your own
 * typing after a prompt (`$ echo Password:`, `> the db password:`) asks nothing.
 */
object PasswordPrompt {
    private val SECRET = Regex("(?i)\\b(pass ?word|pass ?phrase|passcode)s?\\b|\\bPIN\\b")
    /** "Password changed:" or "password: invalid" say something about one; they don't ask. */
    private val NOT_ASKING = Regex("(?i)\\b(changed|updated|incorrect|invalid|failed|expired|saved|set)\\b\\s*:?$")
    /** A shell's or a TUI's prompt (`$ `, `# `, `% `, `> `, `❯ `, `› `): what follows it is being typed. */
    private val TYPED = Regex("[$#%>❯›»]\\s")
    private const val LONGEST = 160

    /** The line that asks, when [line] (what stands before the cursor) asks for a secret; else null. */
    fun ask(line: String): String? {
        val l = line.trimEnd()
        if (l.isEmpty() || l.length > LONGEST || !l.endsWith(":")) return null
        val secret = SECRET.find(l) ?: return null
        if (NOT_ASKING.containsMatchIn(l) || TYPED.containsMatchIn(l.substring(0, secret.range.first))) return null
        return l.trim()
    }
}
