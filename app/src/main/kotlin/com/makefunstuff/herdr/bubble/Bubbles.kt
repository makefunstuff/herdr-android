package com.makefunstuff.herdr.bubble

import androidx.compose.runtime.staticCompositionLocalOf
import com.makefunstuff.herdr.Routes

/** A chat that can float over other apps as an Android bubble: one agent's, or a machine's Coordinator. */
sealed class BubbleChat {
    abstract val machineId: String

    data class Agent(override val machineId: String, val paneId: String) : BubbleChat()

    data class Coordinator(override val machineId: String) : BubbleChat()

    /** One per chat: its shortcut, its conversation in Android's settings, and its notification's tag. */
    val id: String
        get() = when (this) {
            is Agent -> "agent:$machineId:$paneId"
            is Coordinator -> "coordinator:$machineId"
        }

    /** Its screen in the app. */
    val route: String
        get() = when (this) {
            is Agent -> Routes.agent(machineId, paneId)
            is Coordinator -> Routes.coordinator(machineId)
        }

    companion object {
        /** The chat an [id] names; null for anything else. A machine's id never has a colon, a pane's may. */
        fun parse(id: String?): BubbleChat? {
            val parts = id?.split(':', limit = 3) ?: return null
            return when {
                parts.size == 3 && parts[0] == "agent" && parts[1].isNotEmpty() && parts[2].isNotEmpty() -> Agent(parts[1], parts[2])
                parts.size == 2 && parts[0] == "coordinator" && parts[1].isNotEmpty() -> Coordinator(parts[1])
                else -> null
            }
        }
    }
}

/** What came of asking for a bubble. */
enum class Floated {
    /** It floats (expanded, when asked from the app). */
    Yes,
    /** Android lets no bubbles from the app, or not this chat: it shows as a notification, and the app's bubble settings can change that. */
    BubblesOff,
    /** Notifications are off for the app, so there is nothing a bubble can come from. */
    NotificationsOff,
}

/**
 * Chats floating as Android bubbles (Android 11 and newer), so one agent or the Coordinator can be
 * steered while reading something else. Each bubble is a conversation notification with its own
 * shortcut; Android draws it over every app, and its expanded window is the chat itself.
 */
interface Bubbles {
    /** This phone has bubbles at all. */
    val supported: Boolean

    /** Android would let [chat] float now. */
    fun allowed(chat: BubbleChat): Boolean

    /** Floats [chat], named [title] ("api", "Coordinator") on [machine] (its label). */
    fun float(chat: BubbleChat, title: String, machine: String): Floated

    /** Android's bubble settings for the app. */
    fun openSettings()

    /** Android's notification settings for the app. */
    fun openNotificationSettings()

    /** Opens [chat] in the app itself, out of its bubble. */
    fun openApp(chat: BubbleChat)
}

/** The chat a bubble opened on, around that chat's screen; null in the app itself. */
val LocalBubble = staticCompositionLocalOf<BubbleChat?> { null }
