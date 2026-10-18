package com.makefunstuff.herdr.core.terminal

/** Ctrl, Alt and Shift held with a key. */
data class Mods(val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false) {
    val any: Boolean get() = ctrl || alt || shift

    operator fun plus(o: Mods) = Mods(ctrl || o.ctrl, alt || o.alt, shift || o.shift)

    /** xterm's modifier parameter (`ESC [ 1 ; m A`): 1, plus 1 for Shift, 2 for Alt, 4 for Ctrl. */
    val xterm: Int get() = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

    companion object {
        val NONE = Mods()
    }
}

/**
 * A key of the terminal's key bar, kept as its [spec]: a key to send, with or without modifiers
 * (`Esc`, `C-r`, `M-b`, `S-Tab`, `C-Left`, `F5`, `|`), or one of the bar's own keys (`@ctrl`,
 * `@alt` and `@shift`, sticky; `@fn`, the page of F-keys; `@keyboard`). Named keys are tmux's
 * names; `C-`, `M-` and `S-` are Ctrl, Alt (Meta) and Shift.
 */
sealed interface BarKey {
    val spec: String

    /** [key] (one of [NAMED], or one character) with [mods]. */
    data class Send(val key: String, val mods: Mods = Mods.NONE) : BarKey {
        override val spec: String
            get() = (if (mods.ctrl) "C-" else "") + (if (mods.alt) "M-" else "") + (if (mods.shift) "S-" else "") + key

        /** What it sends, with the sticky [held] modifiers added to its own. */
        fun bytes(held: Mods = Mods.NONE): String = KeyCodes.encode(key, mods + held)

        val isSpace: Boolean get() = key == "Space" && !mods.any
    }

    /** A sticky modifier: armed for the next key with a tap, kept with a second. */
    data class Modifier(val which: Mod) : BarKey {
        override val spec: String get() = "@" + which.name.lowercase()
    }

    /** Turns the bar to its page of F-keys and back. */
    data object FKeys : BarKey {
        override val spec = "@fn"
    }

    /** Brings up the phone's keyboard. */
    data object Keyboard : BarKey {
        override val spec = "@keyboard"
    }

    enum class Mod { Ctrl, Alt, Shift }

    companion object {
        /** The keys with a name, as tmux names them, in the order the editor offers them. */
        val NAMED = listOf(
            "Esc", "Tab", "Enter", "Space", "BSpace", "Del", "Ins",
            "Up", "Down", "Left", "Right", "Home", "End", "PgUp", "PgDn",
        ) + (1..12).map { "F$it" }

        /** The key [spec] stands for; null for one this app doesn't know. */
        fun parse(spec: String): BarKey? {
            when (spec) {
                "@ctrl" -> return Modifier(Mod.Ctrl)
                "@alt" -> return Modifier(Mod.Alt)
                "@shift" -> return Modifier(Mod.Shift)
                "@fn" -> return FKeys
                "@keyboard" -> return Keyboard
            }
            var rest = spec
            var mods = Mods.NONE
            while (rest.length > 2 && rest[1] == '-' && rest[0] in "CMS") {
                mods += when (rest[0]) {
                    'C' -> Mods(ctrl = true)
                    'M' -> Mods(alt = true)
                    else -> Mods(shift = true)
                }
                rest = rest.substring(2)
            }
            val key = NAMED.firstOrNull { it.equals(rest, ignoreCase = true) }
                ?: rest.takeIf { it.codePointCount(0, it.length) == 1 && !it[0].isISOControl() && it != " " }
                ?: return null
            // A letter with Shift is its capital: Ctrl and Alt send the same either way.
            if (mods.shift && key.length == 1 && key[0].isLetter()) return Send(key.uppercase(), mods.copy(shift = false))
            return Send(key, mods)
        }
    }
}

/** The bytes a key sends to the pane, as xterm sends them. */
object KeyCodes {
    private const val ESC = "\u001b"

    fun encode(key: String, mods: Mods = Mods.NONE): String {
        val m = mods.xterm
        val alt = if (mods.alt) ESC else ""
        return when (key) {
            "Esc" -> alt + ESC
            "Tab" -> if (mods.shift) "$ESC[Z" else alt + "\t"
            "Enter" -> alt + "\r"
            "Space" -> alt + if (mods.ctrl) "\u0000" else " "
            "BSpace" -> alt + if (mods.ctrl) "\b" else "\u007f"
            "Up", "Down", "Right", "Left", "Home", "End" -> {
                val c = when (key) { "Up" -> 'A'; "Down" -> 'B'; "Right" -> 'C'; "Left" -> 'D'; "Home" -> 'H'; else -> 'F' }
                if (m == 1) "$ESC[$c" else "$ESC[1;$m$c"
            }
            "Ins", "Del", "PgUp", "PgDn" -> tilde(when (key) { "Ins" -> 2; "Del" -> 3; "PgUp" -> 5; else -> 6 }, m)
            "F1", "F2", "F3", "F4" -> {
                val c = "PQRS"[key[1] - '1']
                if (m == 1) "${ESC}O$c" else "$ESC[1;$m$c"
            }
            "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12" -> tilde(F_CODES.getValue(key), m)
            else -> {
                val ch = if (mods.shift && key.length == 1) key.uppercase() else key
                Keys.withModifiers(ch, mods.ctrl, mods.alt)
            }
        }
    }

    private fun tilde(n: Int, m: Int) = if (m == 1) "$ESC[$n~" else "$ESC[$n;$m~"

    private val F_CODES = mapOf("F5" to 15, "F6" to 17, "F7" to 18, "F8" to 19, "F9" to 20, "F10" to 21, "F11" to 23, "F12" to 24)
}

/** How a sticky modifier stands: off, armed for the next key, or kept until tapped again. */
enum class Hold {
    Off, Once, Locked;

    val on: Boolean get() = this != Off

    /** A tap on its key: armed, then kept, then off. */
    fun tapped(): Hold = when (this) { Off -> Once; Once -> Locked; Locked -> Off }

    /** A key went out with it. */
    fun used(): Hold = if (this == Once) Off else this
}

/** The key bar's sticky Ctrl, Alt and Shift. */
data class Sticky(val ctrl: Hold = Hold.Off, val alt: Hold = Hold.Off, val shift: Hold = Hold.Off) {
    val mods: Mods get() = Mods(ctrl.on, alt.on, shift.on)

    operator fun get(m: BarKey.Mod): Hold = when (m) {
        BarKey.Mod.Ctrl -> ctrl
        BarKey.Mod.Alt -> alt
        BarKey.Mod.Shift -> shift
    }

    fun tapped(m: BarKey.Mod): Sticky = when (m) {
        BarKey.Mod.Ctrl -> copy(ctrl = ctrl.tapped())
        BarKey.Mod.Alt -> copy(alt = alt.tapped())
        BarKey.Mod.Shift -> copy(shift = shift.tapped())
    }

    /** After a key went out: the armed ones are off, the kept ones stay. */
    fun used(): Sticky = Sticky(ctrl.used(), alt.used(), shift.used())
}

/** The key bar: its keys, what each is called and does, and how they fill rows. */
object KeyBar {
    /** The keys a new bar has: two rows on a phone, with the edit key at the end. */
    val DEFAULT: List<String> = listOf(
        "Esc", "Tab", "C-c", "C-r", "C-w", "|", "~", "@fn",
        "@ctrl", "@alt", "Space", "Left", "Up", "Down", "Right",
    )

    /** The page of F-keys: F1 to F12, Page Up and Down; the `@fn` key turns back. */
    val F_PAGE: List<BarKey> = (1..12).map { BarKey.Send("F$it") } + listOf(BarKey.Send("PgUp"), BarKey.Send("PgDn"), BarKey.FKeys)

    /** The keys of [specs] this app knows, each once. */
    fun keys(specs: List<String>): List<BarKey> = specs.mapNotNull(BarKey::parse).distinct()

    /**
     * The keys in rows of [perRow]: the bar's keys, then (when [edit]) one more slot for the edit key,
     * which is the last of the last row. Rows are filled in order; null in a row is the edit key.
     */
    fun rows(keys: List<BarKey>, perRow: Int, edit: Boolean = true): List<List<BarKey?>> {
        val all: List<BarKey?> = if (edit) keys + null else keys
        return all.chunked(perRow.coerceAtLeast(1))
    }

    /**
     * How many keys go in a row of [widthDp]: one per 48dp; on a narrower phone still eight, down to
     * 40dp each.
     */
    fun perRow(widthDp: Float): Int {
        val roomy = (widthDp / 48f).toInt()
        return if (roomy >= 8) roomy else ((widthDp / 40f).toInt()).coerceIn(4, 8)
    }

    /** The short label on the key. */
    fun label(key: BarKey): String = when (key) {
        is BarKey.Modifier -> key.which.name.lowercase()
        BarKey.FKeys -> "fn"
        BarKey.Keyboard -> "kbd"
        is BarKey.Send -> {
            val k = key.key
            val m = key.mods
            when {
                k == "Tab" && m == Mods(shift = true) -> "⇧tab"
                m.ctrl && !m.alt && !m.shift && k.length == 1 -> "^" + k.uppercase()
                else -> (if (m.ctrl) "^" else "") + (if (m.alt) "M-" else "") + (if (m.shift) "⇧" else "") + keyLabel(k)
            }
        }
    }

    private fun keyLabel(k: String): String = when (k) {
        "Esc" -> "esc"
        "Tab" -> "tab"
        "Enter" -> "enter"
        "Space" -> "space"
        "BSpace" -> "bksp"
        "Del" -> "del"
        "Ins" -> "ins"
        "Up" -> "↑"
        "Down" -> "↓"
        "Left" -> "←"
        "Right" -> "→"
        "Home" -> "home"
        "End" -> "end"
        "PgUp" -> "pgup"
        "PgDn" -> "pgdn"
        else -> k
    }

    /** What the key is, in words: for the editor and for TalkBack ("Ctrl+R", "Up arrow"). */
    fun name(key: BarKey): String = when (key) {
        is BarKey.Modifier -> key.which.name
        BarKey.FKeys -> "F-keys"
        BarKey.Keyboard -> "Keyboard"
        is BarKey.Send -> {
            val m = key.mods
            (if (m.ctrl) "Ctrl+" else "") + (if (m.alt) "Alt+" else "") + (if (m.shift) "Shift+" else "") + keyName(key.key)
        }
    }

    private fun keyName(k: String): String = when (k) {
        "Esc" -> "Escape"
        "Enter" -> "Enter"
        "BSpace" -> "Backspace"
        "Del" -> "Delete"
        "Ins" -> "Insert"
        "Up", "Down", "Left", "Right" -> "$k arrow"
        "PgUp" -> "Page Up"
        "PgDn" -> "Page Down"
        else -> if (k.length == 1) CHAR_NAMES[k] ?: k.uppercase() else k
    }

    /** What the key is for, where it's worth saying: shells' and editors' usual meaning. */
    fun purpose(key: BarKey): String? = when (key) {
        is BarKey.Modifier -> "Sticky: a tap holds it for the next key, a second keeps it on"
        BarKey.FKeys -> "A page of F1 to F12"
        BarKey.Keyboard -> "Brings up the phone's keyboard"
        is BarKey.Send -> if (key.isSpace) "Hold and drag to move the cursor" else PURPOSES[key.spec]
    }

    private val PURPOSES = mapOf(
        "C-c" to "Stop what runs",
        "C-d" to "End of input, or log out",
        "C-z" to "Suspend what runs",
        "C-r" to "Search the shell's history",
        "C-w" to "Delete the word before the cursor",
        "C-u" to "Delete to the start of the line",
        "C-k" to "Delete to the end of the line",
        "C-a" to "Start of the line",
        "C-e" to "End of the line",
        "C-l" to "Clear the screen",
        "C-b" to "tmux's prefix",
        "M-b" to "A word back",
        "M-f" to "A word forward",
        "M-." to "The last command's last word",
        "S-Tab" to "Back a field, or Claude Code's mode",
    )

    private val CHAR_NAMES = mapOf(
        "|" to "Pipe", "/" to "Slash", "\\" to "Backslash", "-" to "Dash", "_" to "Underscore", "~" to "Tilde",
        "`" to "Backtick", "'" to "Quote", "\"" to "Double quote", "*" to "Star", "&" to "Ampersand", "$" to "Dollar",
        "#" to "Hash", "!" to "Bang", "?" to "Question mark", ":" to "Colon", ";" to "Semicolon", "=" to "Equals",
        "<" to "Less than", ">" to "Greater than", "{" to "Left brace", "}" to "Right brace", "[" to "Left bracket",
        "]" to "Right bracket", "(" to "Left paren", ")" to "Right paren", "%" to "Percent", "^" to "Caret", "@" to "At",
    )

    /** What the editor offers to add, by group. */
    val CATALOG: List<Pair<String, List<String>>> = listOf(
        "Modifiers and pages" to listOf("@ctrl", "@alt", "@shift", "@fn", "@keyboard"),
        "Shortcuts" to listOf("C-c", "C-d", "C-z", "C-r", "C-w", "C-u", "C-k", "C-a", "C-e", "C-l", "C-b", "M-b", "M-f", "M-.", "S-Tab"),
        "Keys" to listOf("Esc", "Tab", "Enter", "Space", "BSpace", "Del", "Ins", "Up", "Down", "Left", "Right", "Home", "End", "PgUp", "PgDn"),
        "Characters" to listOf("|", "/", "\\", "-", "_", "~", "`", "'", "\"", "*", "&", "$", "#", "!", "?", ":", ";", "=", "<", ">", "{", "}", "[", "]", "(", ")"),
    )
}

/**
 * Hold Space and drag: the cursor follows the finger, a cell for each [stepX] across and a line for
 * each [stepY] up or down, sent as arrow keys. [moved] takes each move and gives the keys it makes.
 */
class CursorDrag(private val stepX: Float, private val stepY: Float) {
    private var x = 0f
    private var y = 0f

    fun moved(dx: Float, dy: Float): String {
        x += dx
        y += dy
        val out = StringBuilder()
        val cols = (x / stepX).toInt()
        val rows = (y / stepY).toInt()
        x -= cols * stepX
        y -= rows * stepY
        repeat(kotlin.math.abs(cols)) { out.append(KeyCodes.encode(if (cols > 0) "Right" else "Left")) }
        repeat(kotlin.math.abs(rows)) { out.append(KeyCodes.encode(if (rows > 0) "Down" else "Up")) }
        return out.toString()
    }
}

/** Text selected in a terminal, made ready for a chat's message box. */
object TerminalQuote {
    /**
     * [text] in a fenced block (fenced longer than any run of backticks in it), its lines' trailing
     * blanks and the blank lines around it dropped, with where it came from ([from]) above it.
     */
    fun of(text: String, from: String? = null): String {
        val lines = text.replace("\r\n", "\n").lines().map { it.trimEnd() }
        val body = lines.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        if (body.isEmpty()) return ""
        val longest = Regex("`+").findAll(body.joinToString("\n")).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longest + 1))
        val head = from?.let { "From the terminal ($it):\n" }.orEmpty()
        return head + fence + "\n" + body.joinToString("\n") + "\n" + fence
    }
}
