package com.makefunstuff.herdr.core.chat

import kotlinx.serialization.Serializable

/**
 * A voice message recorded on the phone ([VoiceRecorder]), by the chat it is for (its `DraftStore`
 * key). It goes in two steps: the audio file is copied to the agent's machine ([remote]), then handed
 * to the agent ([sent]). One that failed keeps [error] and waits on the phone for a retry; one sent is
 * kept a while, so it plays from the phone without fetching it back.
 */
@Serializable
data class VoiceNote(
    val id: String,
    val chat: String,
    /** The audio file on the phone. */
    val file: String,
    val millis: Long,
    /** When it was recorded, by the phone's clock. */
    val at: Long,
    /** Where it is on the machine, once copied. */
    val remote: String? = null,
    /** Handed to the agent (or held for it with the messages that wait). */
    val sent: Boolean = false,
    /** When it was handed over, by the phone's clock. */
    val sentAt: Long = 0,
    /** Why the last try failed; null while it goes. */
    val error: String? = null,
) {
    val failed: Boolean get() = error != null
}

/** Voice messages' files and the phone's list of them. */
object VoiceNotes {
    /** How many sent ones are kept per chat; those not sent yet are all kept. */
    const val KEEP = 50

    /** How long one sent still shows on its own where the chat doesn't have it yet (on its way in). */
    const val ARRIVING_MS = 60_000L

    /** Whether [note] shows on its own after a chat that doesn't have it ([now] the phone's clock): not sent, or just sent. */
    fun waiting(note: VoiceNote, now: Long): Boolean = !note.sent || now - note.sentAt < ARRIVING_MS

    /** The audio's type: AAC in an MP4 file, which every phone records and every machine plays. */
    const val MIME = "audio/mp4"

    private val voiceName = Regex("""voice-message-(\d+)m(\d{2})s\.m4a$""")
    private val mention = Regex("""^@"?(\S[^"]*voice-message-\d+m\d{2}s\.m4a)"?$""")

    /** The name a voice message's file goes by on the machine: what it is, and how long (`voice-message-0m07s.m4a`). */
    fun fileName(millis: Long): String {
        val s = (millis / 1000).coerceAtLeast(0)
        return "voice-message-${s / 60}m${(s % 60).toString().padStart(2, '0')}s.m4a"
    }

    /** Whether the file [name] (or path) is a voice message's. */
    fun isVoice(name: String): Boolean = voiceName.containsMatchIn(name)

    /** How long the voice message in [name] (or path) is, by its name; null when it isn't one. */
    fun millis(name: String): Long? = voiceName.find(name)?.let { m ->
        (m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000
    }

    /** A message that is nothing but a voice message's file named (`@path`, as sent to a herdr agent): its path. */
    fun mentioned(text: String): String? = mention.matchEntire(text.trim())?.groupValues?.get(1)

    /** [notes] with [note] added; the sent ones of its chat past [KEEP] go (the second: their files, to delete). */
    fun add(notes: List<VoiceNote>, note: VoiceNote): Pair<List<VoiceNote>, List<VoiceNote>> {
        val all = notes + note
        val sent = all.filter { it.chat == note.chat && it.sent }
        val gone = sent.take((sent.size - KEEP).coerceAtLeast(0)).toSet()
        return all.filterNot { it in gone } to gone.toList()
    }

    /** [notes] with note [id] changed by [f]. */
    fun update(notes: List<VoiceNote>, id: String, f: (VoiceNote) -> VoiceNote): List<VoiceNote> =
        notes.map { if (it.id == id) f(it) else it }

    /** The one copied to [remote] on the machine, to play from the phone. */
    fun byRemote(notes: List<VoiceNote>, remote: String): VoiceNote? = notes.lastOrNull { it.remote == remote }
}
