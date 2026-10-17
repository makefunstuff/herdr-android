package com.makefunstuff.herdr.core.host

import com.makefunstuff.herdr.core.ssh.HerdrCommands
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Files you hand an agent from the phone, the way the herdr web UI attaches them: the file is copied
 * over the app's SSH connection into a folder on the agent's machine
 * (`~/.cache/herdr-android/uploads/<stamp>/<name>`), and the message names it as an `@path`
 * mention, which the agents read as "look at this file". herdr itself takes only text and keys.
 */
object Attachments {
    /** The largest file the composer copies (the web UI's cap is the same order). */
    const val MAX_BYTES = 25L * 1024 * 1024

    /** A file name that is safe in a shell and in an `@` mention: letters, digits, `.`, `_`, `-`. */
    fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.map { if (it.isLetterOrDigit() && it.code < 128 || it in "._-") it else '-' }.joinToString("")
            .replace(Regex("-{2,}"), "-")
            .trimStart('.', '-')
            .trimEnd('-')
        if (cleaned.isEmpty() || cleaned.all { it == '.' }) return "file"
        if (cleaned.length <= 80) return cleaned
        val ext = cleaned.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 10 }
        return if (ext == null) cleaned.take(80) else cleaned.take(79 - ext.length) + "." + ext
    }

    /** A folder per pick, so two files of the same name never overwrite each other. */
    fun stamp(now: LocalDateTime = LocalDateTime.now(), random: Random = Random.Default): String =
        now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + random.nextInt(0x10000).toString(16).padStart(4, '0')

    /** Writes stdin to the file and prints its absolute path. [name] must come from [safeName]. */
    fun uploadCommand(name: String, stamp: String): String {
        val dir = "\"\$HOME/.cache/herdr-android/uploads/\"" + HerdrCommands.quote(stamp)
        return "d=$dir && mkdir -p \"\$d\" && cat > \"\$d\"/${HerdrCommands.quote(name)} && printf '%s' \"\$d\"/${HerdrCommands.quote(name)}"
    }

    /** How a message names [path]: `@path`, quoted when the path has a space in it. */
    fun mention(path: String): String = if (path.any { it.isWhitespace() }) "@\"$path\"" else "@$path"

    /**
     * [draft] with the mention of [path] added: at its start, after any mentions already there and
     * after a leading `/command`, so the words you typed keep their place.
     */
    fun withMention(draft: String, path: String): String {
        val m = mention(path)
        val command = Regex("^/\\S+\\s*").find(draft)?.value.orEmpty()
        var rest = draft.removePrefix(command)
        val mentions = Regex("^(@(\"[^\"]*\"|\\S+)\\s+)*").find(rest)?.value.orEmpty()
        rest = rest.removePrefix(mentions)
        val head = (if (command.isNotEmpty()) command.trimEnd() + " " else "") + mentions + m
        return if (rest.isEmpty()) "$head " else "$head $rest"
    }
}
