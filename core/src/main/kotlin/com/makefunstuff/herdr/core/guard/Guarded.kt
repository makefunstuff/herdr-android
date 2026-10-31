package com.makefunstuff.herdr.core.guard

/**
 * The actions that can't be taken back, which ask for the fingerprint (or the phone's PIN) first
 * when the person wants that (Settings → Fingerprint): on their screen and from a notification's
 * button alike. [key] is how settings and notifications name it.
 *
 * [confirmed]: the app already asks with a dialog of its own before it, so on a phone with no screen
 * lock (nothing to ask with) the dialog is the check; the one-tap ones get an in-app confirmation instead.
 */
enum class Guarded(val key: String, val title: String, val detail: String, val confirmed: Boolean) {
    AlwaysAllow("always", "Always allow", "“Yes, and don't ask again” on an agent's question: it changes what the agent may do for good", confirmed = false),
    Merge("merge", "Approve and merge", "A board task's work goes into its main branch, also from a notification", confirmed = false),
    StopProcess("stop", "Stop a process", "From a machine's health: a process, the leftovers of done tasks, Gradle daemons", confirmed = true),
    RemoveWorktree("worktree", "Remove a worktree", "Its checkout is deleted, with changes that aren't committed", confirmed = true),
    Login("login", "Agent logins", "Signing an agent CLI in or out on a machine, or giving it a key", confirmed = false),
    NewKey("key", "Replace the SSH key", "A new key, or restoring another, deletes the one your machines know", confirmed = true),
    ;

    companion object {
        fun of(key: String): Guarded? = entries.firstOrNull { it.key == key }
    }
}
