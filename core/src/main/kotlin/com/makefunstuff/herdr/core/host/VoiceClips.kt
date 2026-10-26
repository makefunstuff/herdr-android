package com.makefunstuff.herdr.core.host

import com.makefunstuff.herdr.core.ssh.HerdrCommands

/**
 * Voice messages for an agent: the recording itself (Ogg Opus, or AAC on Android 9), copied to the
 * agent's machine and named in the message as an `@path` mention, like any file ([Attachments]).
 * herdr takes only text and keys, and the phone turns nothing into text: the agent's own harness
 * reads the file as it reads any (Gemini CLI hears it; Claude Code, Codex in its terminal and pi
 * get the path). The file goes into the agent's own folder, in `.herdr-voice/` (which tells Git to
 * ignore it), as some harnesses read only files in their workspace; with no such folder, to the
 * uploads folder in the home folder's cache.
 */
object VoiceClips {
    /** What counts as a voice message in a message: the audio files the agents' harnesses take. */
    val EXTENSIONS = setOf("ogg", "opus", "oga", "m4a", "aac", "mp3", "wav", "webm", "flac")

    /** The folder in the agent's folder that voice messages go to. */
    const val FOLDER = ".herdr-voice"

    /** The most a voice message is fetched back from the machine, to play it. */
    const val MAX_FETCH_BYTES = 16L * 1024 * 1024

    fun isAudio(name: String): Boolean = name.substringAfterLast('/').substringAfterLast('.', "").lowercase() in EXTENSIONS

    /** The file a recording is sent as: `voice-message-20261026-091204-3f2a.ogg`, which says what it is. */
    fun fileName(stamp: String, extension: String): String = Attachments.safeName("voice-message-$stamp.$extension")

    /**
     * Writes stdin to [name] (from [fileName]) in [cwd]'s `.herdr-voice/`, and prints its absolute
     * path. That folder holds a `.gitignore` of `*`, so Git leaves it out. Without [cwd], or when it
     * isn't a folder that can be written to, the file goes to the uploads folder ([Attachments]).
     */
    fun uploadCommand(cwd: String?, name: String, stamp: String): String {
        val fallback = "d=\"\$HOME/.cache/herdr-android/uploads/\"" + HerdrCommands.quote(stamp) + " && mkdir -p \"\$d\""
        val file = "\"\$d\"/" + HerdrCommands.quote(name)
        val place = if (cwd.isNullOrBlank()) fallback else {
            val dir = HerdrCommands.quote(cwd)
            "d=$dir/$FOLDER; if [ -d $dir ] && mkdir -p \"\$d\" 2>/dev/null && [ -w \"\$d\" ]; then " +
                "{ [ -f \"\$d/.gitignore\" ] || printf '*\\n' > \"\$d/.gitignore\"; }; else $fallback; fi"
        }
        return "$place && cat > $file && printf '%s' $file"
    }

    /** A message's voice messages ([paths], in order) and its words without their mentions ([text]). */
    data class Split(val paths: List<String>, val text: String)

    /** Takes the mentions of audio files on the machine (by their full path) out of [text]. */
    fun split(text: String): Split = Attachments.split(text, ::isAudio).let { (paths, words) -> Split(paths, words) }

    /** Prints the voice message at [path] on the machine as base64, when it is there and not too large. */
    fun fetchCommand(path: String): String = Attachments.fetchCommand(path, MAX_FETCH_BYTES)
}
