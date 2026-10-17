package com.makefunstuff.herdr.data

import android.content.Context

/**
 * What you were typing in each chat, kept in the app's preferences so a draft survives leaving the
 * chat, a rotation and a restart of the app. A draft is removed when it is empty (sent or erased).
 */
class DraftStore(context: Context) {
    private val prefs = context.getSharedPreferences("drafts", Context.MODE_PRIVATE)

    fun get(key: String): String = prefs.getString(key, null).orEmpty()

    fun put(key: String, text: String) {
        if (text == get(key)) return
        prefs.edit().apply { if (text.isEmpty()) remove(key) else putString(key, text) }.apply()
    }

    companion object {
        /** An agent's chat: one draft per pane on each machine. */
        fun agent(machineId: String, paneId: String) = "agent/$machineId/$paneId"

        /** The work board's Coordinator chat on a machine. */
        fun coordinator(machineId: String) = "coordinator/$machineId"

        /**
         * The draft once [sent] has gone: empty, or what was typed on after it while it was on its
         * way (the box stays open while a message is sent). A draft edited in what was sent is kept whole.
         */
        fun afterSend(draft: String, sent: String): String = when {
            draft == sent -> ""
            draft.startsWith(sent) -> draft.removePrefix(sent).trimStart()
            else -> draft
        }
    }
}
