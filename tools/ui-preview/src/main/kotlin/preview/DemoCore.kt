// herdr-core's demo data: the terminals the Herdr desktop app keeps in herdr-core on devbox (next to
// classic herdr) and on mac-mini (the desktop app alone), as hc lists them.
package preview

import preview.Demo.NOW

object DemoCore {
    /** devbox's Claude in the desktop app, asking to run a migration. */
    const val CLAUDE = "hc:t1"
    const val SHELL = "hc:t2"
    const val CODEX = "hc:t3"

    private const val MIN = 60_000L
    private const val HOME = "/home/demo"

    private fun status(state: String, app: String, kind: String? = null, msg: String = "", title: String = "") =
        obj("state" to state, "app" to app, "kind" to kind, "msg" to msg.ifEmpty { null }, "title" to title.ifEmpty { null })

    private val PROMPT = "\u001b[1;32mdemo@devbox\u001b[0m:\u001b[1;34m~/src/herdr-android\u001b[0m$ "

    /** devbox's desktop app: herdr-android (review: Claude and a shell; docs: Codex), notes (pi), and a dev server `hc run` started. */
    fun devbox() = FakeCore(
        terminals = listOf(
            FakeCore.terminal(
                "t1", listOf("claude"), "$HOME/src/herdr-android", NOW - 50 * MIN, space = "sp1" to "herdr-android", tab = "tb1" to "review",
                status = status("blocked", "claude-code", kind = "permission", msg = "Run npm run db:migrate?"), statusMs = NOW - 2 * MIN,
                title = "Jobs migration", turn = 3,
            ),
            FakeCore.terminal("t2", listOf("zsh"), "$HOME/src/herdr-android", NOW - 49 * MIN, space = "sp1" to "herdr-android", tab = "tb1" to "review"),
            FakeCore.terminal(
                "t3", listOf("codex"), "$HOME/src/herdr-android", NOW - 30 * MIN, space = "sp1" to "herdr-android", tab = "tb2" to "docs",
                status = status("working", "codex", title = "Updating the README"), statusMs = NOW - 4 * MIN, turn = 1,
            ),
            FakeCore.terminal(
                "t4", listOf("pi"), "$HOME/notes", NOW - 20 * MIN, space = "sp2" to "notes", tab = "tb3" to "main",
                status = status("done", "pi", msg = "Summed up this week's notes"), statusMs = NOW - 6 * MIN, turn = 2,
            ),
            FakeCore.terminal("t5", listOf("npm", "run", "dev"), "$HOME/src/website", NOW - 10 * MIN, name = "website-dev", foreground = "node"),
        ),
        screens = mapOf(
            "t1" to Demo.resource("screen-claude-api.txt"),
            "t2" to PROMPT + "git status --short\n M app/src/main/kotlin/demo/herdr/data/Jobs.kt\n?? migrations/0042_jobs_created_at.sql\n" + PROMPT,
            "t3" to Demo.resource("screen-codex.txt"),
            "t4" to "Summed up this week's notes in notes/2026-w42.md.\n",
            "t5" to "\u001b[32m  VITE v6.2.0\u001b[0m  ready in 412 ms\n\n  ➜  Local:   http://localhost:5173/\n",
        ),
        name = "devbox",
    )

    /** mac-mini with the desktop app and no classic herdr: the website's Claude at work, Codex done. */
    fun mini() = FakeCore(
        terminals = listOf(
            FakeCore.terminal(
                "t1", listOf("claude"), "/Users/jpl/src/website", NOW - 15 * MIN, space = "sp1" to "website", tab = "tb1" to "main",
                status = status("working", "claude-code", title = "Checking every link"), statusMs = NOW - 3 * MIN, turn = 1,
            ),
            FakeCore.terminal(
                "t2", listOf("codex"), "/Users/jpl/src/website", NOW - 40 * MIN, space = "sp1" to "website", tab = "tb2" to "blog",
                status = status("done", "codex", title = "Draft of the release post"), statusMs = NOW - 9 * MIN, turn = 1,
            ),
        ),
        screens = mapOf("t1" to Demo.resource("screen-claude-app.txt"), "t2" to Demo.resource("screen-codex.txt")),
        name = "mac-mini",
    )
}

/** devbox runs the desktop app's herdr-core next to classic herdr; mac-mini runs only the desktop app. */
fun SceneData.desktopApp() {
    host(Demo.DEVBOX).core = DemoCore.devbox()
    host(Demo.MINI).apply {
        refuse = null
        classic = false
        core = DemoCore.mini()
    }
}
