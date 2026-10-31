package com.makefunstuff.herdr.core.voice

import com.makefunstuff.herdr.core.shepherdr.SpaceChatItem

/** What the coordinator's voice is doing, as the chat's voice bar and the notification show it. */
data class VoiceState(
    /** On for a chat: [machineId]'s space [space]. */
    val on: Boolean = false,
    val machineId: String = "",
    val space: String = "",
    /** The space as people say it ("Home"). */
    val spaceName: String = "",
    val phase: Phase = Phase.Ready,
    /** It listens on its own and sends when you stop talking. */
    val handsFree: Boolean = false,
    /** Nothing is read aloud; you can still talk. */
    val muted: Boolean = false,
    /** How fast it speaks, 0.8 to 1.6. */
    val speed: Float = 1f,
    /** Whose voice: the server's, or the phone's while the server can't be reached. */
    val engine: Engine = Engine.Server,
    /** What it says or heard last, for the notification. */
    val line: String = "",
    /** How loud the voice or your voice is now, 0..1, for the bars. */
    val level: Float = 0f,
    /** Something went wrong it can't get past alone: no microphone, the server refused the key. */
    val problem: String? = null,
) {
    enum class Phase {
        /** Waiting for the coordinator, or for you. */
        Ready,
        /** Reading a reply aloud. */
        Speaking,
        /** Hearing you. */
        Listening,
        /** Making words of what you said. */
        Hearing,
        /** Sending your words to the chat. */
        Sending,
    }

    enum class Engine { Server, Phone }

    /** The bar's words for it. */
    val words: String
        get() = when (phase) {
            Phase.Speaking -> "Speaking"
            Phase.Listening -> "Listening"
            Phase.Hearing -> "Hearing you…"
            Phase.Sending -> "Sending"
            Phase.Ready -> when {
                muted -> "Muted"
                handsFree -> "Talk any time"
                else -> "Hold to talk"
            }
        }

    companion object {
        const val MIN_SPEED = 0.8f
        const val MAX_SPEED = 1.6f
        /** The speeds a tap steps through. */
        val SPEEDS = listOf(1f, 1.2f, 1.4f, 1.6f, 0.8f)

        fun nextSpeed(now: Float): Float = SPEEDS[(SPEEDS.indexOfFirst { kotlin.math.abs(it - now) < 0.05f } + 1) % SPEEDS.size]
    }
}

/**
 * Which of a chat's items are new to read aloud: only what came after voice was turned on, each once,
 * oldest first, never your own words. [start] takes the chat as it is then.
 */
class ReadQueue {
    private val seen = HashSet<String>()
    private var started = false

    /** Line [id], read as [text]. */
    data class Line(val id: String, val text: String)

    /** The chat as it is when voice comes on: none of it is read. */
    fun start(items: List<SpaceChatItem>) {
        seen.clear()
        items.forEach { seen += it.id }
        started = true
    }

    val isStarted get() = started

    /** What is new in [items] to read, oldest first; each is read once. */
    fun next(items: List<SpaceChatItem>): List<Line> {
        if (!started) { start(items); return emptyList() }
        val out = ArrayList<Line>()
        for (item in items) {
            if (!seen.add(item.id)) continue
            // Yours that waits to go in has an id of its own until the chat carries it; it never speaks.
            SpeechText.of(item)?.let { out += Line(item.id, it) }
        }
        return out
    }
}
