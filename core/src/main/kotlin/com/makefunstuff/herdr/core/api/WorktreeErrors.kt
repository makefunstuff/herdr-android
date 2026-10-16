package com.makefunstuff.herdr.core.api

/** Turns herdr's worktree errors into what to tell the user, and what they can do about them. */
object WorktreeErrors {
    const val NOT_GIT = "not_git_worktree"
    const val DIRTY = "dirty_worktree_requires_force"
    const val NOT_LINKED = "not_linked_worktree"

    /** The checkout has changes; removing it anyway needs `force`. */
    fun needsForce(t: Throwable): Boolean = (t as? HerdrApiException)?.code == DIRTY

    /** Git won't work in the repository until the user vouches for it (an ownership or safe.directory check). */
    fun needsTrust(t: Throwable): Boolean {
        val e = t as? HerdrApiException ?: return false
        val text = "${e.code} ${e.message}".lowercase()
        return "trust" in text || "dubious ownership" in text || "safe.directory" in text
    }

    fun describe(t: Throwable): String {
        val e = t as? HerdrApiException ?: return t.message ?: "That didn't work"
        return when {
            e.code == NOT_GIT -> "That folder isn't inside a Git repository."
            e.code == DIRTY -> "This worktree has changes that aren't committed. Removing it deletes them."
            e.code == NOT_LINKED -> "Only worktrees added to a repository can be removed. Its main checkout stays."
            needsTrust(e) -> "Git doesn't trust this repository yet, usually because another user owns the folder."
            else -> e.detail.removePrefix("fatal: ").replaceFirstChar { it.uppercase() }
        }
    }
}
