// Every scene, in the order they render. Each renders twice: <name>.png (dark) and <name>-light.png.
// A scene starts from Demo's machines; `route` opens a screen, `actions` taps on what it shows.
package preview

import com.makefunstuff.herdr.Links
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.bubble.BubbleChat
import com.makefunstuff.herdr.core.push.PhoneQuiet
import com.makefunstuff.herdr.core.push.QuietHours
import com.makefunstuff.herdr.data.CodeView
import com.makefunstuff.herdr.data.IdentityStore
import com.makefunstuff.herdr.ui.code.CheckoutTab
import com.makefunstuff.herdr.ui.code.CodeSpot
import com.makefunstuff.herdr.ui.code.DiffSource
import com.makefunstuff.herdr.ui.share.Ask
import preview.Demo.API_AGENT
import preview.Demo.APP_AGENT
import preview.Demo.APP_SHELL
import preview.Demo.DEVBOX
import preview.Demo.GPU
import preview.Demo.SITE_AGENT

/** Task AND-9's worktree on devbox, opened from the task (its agent works in devbox's herdr-android workspace). */
private val TASK_CODE = CodeSpot(DEVBOX, FakeCode.TASK, task = "herdr-android/9", taskName = "AND-9", pane = APP_AGENT)
private const val CARD = "app/src/main/kotlin/demo/herdr/ui/machines/MachineCard.kt"

private const val SHOT_1 = "Screenshot_20261018_101204.png"

/** Two screenshots waiting in the app agent's composer, with words for them. */
private fun SceneData.pictures() {
    images[com.makefunstuff.herdr.data.DraftStore.agent(DEVBOX, APP_AGENT)] = listOf(
        SHOT_1 to Demo.picture("board"),
        "Screenshot_20261018_101230.png" to Demo.picture("board-light"),
    )
    drafts[com.makefunstuff.herdr.data.DraftStore.agent(DEVBOX, APP_AGENT)] = "The Board card jumps when a machine reconnects. Keep it where it was?"
}

/** The app agent's conversation after two screenshots were sent to it, which devbox still has. */
private fun SceneData.sentPictures() {
    host(DEVBOX).transcripts = host(DEVBOX).transcripts + (Demo.APP_SESSION to Demo.appTranscriptWithPictures())
    host(DEVBOX).pictures = Demo.appPictures()
}

/**
 * The Agents screen's world: devbox's agents named for their board tasks (and one more at work on
 * AND-6), gpu-box's board with a task waiting for a pick, and a profile it finished in the night.
 */
private fun SceneData.home() {
    host(DEVBOX).snapshot = Demo.devbox(home = true)
    host(GPU).snapshot = Demo.gpu(home = true)
    host(GPU).board = Demo.gpuBoard()
}

/** Where a 540×1200 screenshot is drawn in the editor's [w]×[h] canvas: as large as fits, in the middle. */
private fun screenshotIn(w: Float, h: Float): androidx.compose.ui.geometry.Rect {
    val scale = minOf(w / 540f, h / 1200f)
    val sw = 540f * scale
    val sh = 1200f * scale
    return androidx.compose.ui.geometry.Rect((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2)
}

fun scenes(): List<Scene> = listOf(
    // Agents: what needs you across the machines first, answerable on the card (an agent's question, a merge, a fix,
    // a pick); the agents at work as one line with a bar, finished ones new until seen. The tall one shows all of it;
    // then answering from the card, the machine filter, Mark seen, a calm morning, and the agent's long-press sheet.
    Scene("agents", setup = { home() }),
    Scene("agents-all", heightDp = 1500, setup = { home() }),
    Scene("agents-reply", setup = { home() }, actions = { tapText("Reply") }),
    // Answered from its card: the question is read again and answered, the approved task leaves the list.
    Scene("agents-yes", setup = { home() }, actions = { tapText("Yes") }),
    Scene("agents-approved", setup = { home() }, actions = { tapText("Approve and merge") }),
    // The board refuses a plain approve (its local check failed): the review card can approve anyway.
    Scene("agents-refused", setup = {
        home()
        host(DEVBOX).board?.refuse = mapOf("approve" to "AND-9's check failed: ./gradlew test, 3 tests failed. Approve it anyway, or send it back.")
    }, actions = { tapText("Approve and merge") }),
    // The same merge on a phone with no screen lock: the app's own confirmation stands in for the fingerprint.
    Scene("agents-merge-confirm", setup = { home(); screenLock = false }, actions = { tapText("Approve and merge") }),
    // A two-option menu with a permanent allow on a card: it carries the fingerprint, and asks before it presses.
    Scene("agents-always-confirm", setup = {
        home(); screenLock = false
        host(DEVBOX).screens += API_AGENT to " Allow the extension to run git push?\n\n→ Always allow\n  Deny\n\n ↑↓ navigate · enter select\n"
    }, actions = { tapText("Always allow") }),
    Scene("agents-filter", setup = { home() }, actions = { tapText("All machines") }),
    Scene("agents-seen", heightDp = 1500, setup = { home() }, actions = { tapText("Mark all seen") }),
    // Triage: the eval pinned on top (and muted), AND-6 snoozed for three hours, the profile still new in Done.
    Scene("agents-triage", heightDp = 1500, setup = { home(); triage = Demo.triage() }),
    // A row swiped left shows Pin, Snooze and Mute under it; one swiped right is seen and leaves Done.
    Scene("agents-swipe", heightDp = 1500, setup = { home() }, actions = { drag("Long-context eval", 0.85f to 0.5f, 0.15f to 0.5f) }),
    Scene("agents-swiped-seen", heightDp = 1500, setup = { home() }, actions = { drag("Profile the data loader", 0.1f to 0.5f, 0.7f to 0.5f) }),
    // The same in the long-press sheet, and how long to snooze.
    Scene("agent-triage", heightDp = 1500, setup = { home() }, actions = { longPressText("Profile the data loader") }),
    Scene("agent-snooze", heightDp = 1500, setup = { home() }, actions = { longPressText("Profile the data loader"); tapText("Snooze") }),
    Scene("agents-calm", setup = { host(DEVBOX).snapshot = Demo.devbox(apiStatus = "working"); host(DEVBOX).board = Demo.calmBoard() }),
    Scene("agent-actions", setup = { home() }, actions = { longPressText("Speed up /jobs") }),
    // A one-shot watch from the long-press sheet: tell me once it finishes.
    Scene("agent-watch", setup = { home() }, actions = { longPressText("Speed up /jobs"); tapText("Notify me when it finishes") }),
    // In a chat, another agent starts asking: one line drops from the top (never for the chat that is open).
    Scene("pill", route = Routes.agent(DEVBOX, APP_AGENT), setup = { host(DEVBOX).snapshot = Demo.devbox(apiStatus = "working") }, actions = { ctx ->
        ctx.data.host(DEVBOX).snapshot = Demo.devbox()
        ctx.refresh(DEVBOX)
        settle(4)
    }),
    // A watch fires while the app is open: it shows the same way.
    Scene("pill-watch", route = Routes.agent(DEVBOX, APP_AGENT), actions = { ctx ->
        ctx.container.watchWork.add(Demo.watches().first())
        ctx.container.watchWork.look(DEVBOX, "devbox", null, listOf(Demo.and6Reviewing()))
        settle(4)
    }),
    Scene("notifications", actions = { ctx ->
        // An agent finishes while the app is open, which the bell then lists.
        ctx.data.host(DEVBOX).snapshot = Demo.devbox(appStatus = "done")
        ctx.refresh(DEVBOX)
        settle()
        tapLabel("Notifications")
    }),

    // Machines and one machine's workspaces.
    Scene("machines", route = Routes.MACHINES),
    Scene("machine", route = Routes.machine(DEVBOX)),
    Scene("machine-new", route = Routes.machine(DEVBOX), actions = { tapText("New") }),
    Scene("add-machine", route = Routes.edit()),

    // A machine's health: meters, the last hour, the leftovers of a finished task, the biggest processes.
    // Taller, to show the whole screen (scrolling never settles in the desktop harness).
    Scene("health", route = Routes.health(DEVBOX), heightDp = 2000),
    // Low on memory: the warning names what uses the most.
    Scene("health-low", route = Routes.health(DEVBOX), setup = { host(DEVBOX).health.availKb = 3_600_000 }),
    Scene("health-leftover", route = Routes.health(DEVBOX), actions = { tapText("AND-2 · Replace agent terminals with native chat views") }),
    Scene("health-stop", route = Routes.health(DEVBOX), actions = { tapText("Stop all leftovers") }),
    Scene("health-process", route = Routes.health(DEVBOX), actions = {
        tapText("AND-2 · Replace agent terminals with native chat views")
        tapText("Android emulator · Pixel_8")
    }),

    // An agent's chat: its transcript, a blocked agent's question, a Codex transcript.
    Scene("chat", route = Routes.agent(DEVBOX, APP_AGENT)),
    Scene("chat-blocked", route = Routes.agent(DEVBOX, API_AGENT)),
    // The question docked over the composer, made for thumbs: "No, tell it…" folds it while the reply is written;
    // on a phone with no screen lock, "Always for npm run" asks for a tap on Confirm instead of the fingerprint.
    Scene("prompt-tell", route = Routes.agent(DEVBOX, API_AGENT), actions = { tapText("No, tell it…") }),
    Scene("prompt-confirm", route = Routes.agent(DEVBOX, API_AGENT), setup = { screenLock = false }, actions = { tapText("Always for npm run") }),
    // Claude's own questions: one with descriptions and a recommended pick, and a form of three with a multiple choice.
    Scene("prompt-question", route = Routes.agent(DEVBOX, API_AGENT), setup = { host(DEVBOX).screens += API_AGENT to Demo.resource("screen-claude-question.txt") }),
    Scene("prompt-checks", route = Routes.agent(DEVBOX, API_AGENT), setup = { host(DEVBOX).screens += API_AGENT to Demo.resource("screen-claude-checks.txt") }),
    Scene("chat-codex", route = Routes.agent(DEVBOX, SITE_AGENT)),

    // Pictures for an agent: what the attach button offers, two screenshots in the composer (copied to devbox,
    // each with its ×), one opened to mark up (an arrow, a highlight, a ring drawn round), one being cropped;
    // then the message sent with them, as thumbnails in the chat, and one shown full size.
    Scene("attach-menu", route = Routes.agent(DEVBOX, APP_AGENT), actions = { tapLabel("Attach files") }),
    Scene("chat-pictures", route = Routes.agent(DEVBOX, APP_AGENT), setup = { pictures() }),
    Scene("picture-markup", route = Routes.agent(DEVBOX, APP_AGENT), setup = { pictures() }, actions = {
        tapLabel("Picture 1 of 2, $SHOT_1")
        tapText("Arrow")
        drag("The picture", 0.85f to 0.62f, 0.55f to 0.42f, within = ::screenshotIn)
        tapText("Highlight")
        drag("The picture", 0.06f to 0.335f, 0.6f to 0.335f, within = ::screenshotIn)
        tapText("Draw")
        drag(
            "The picture", 0.5f to 0.2f, 0.85f to 0.22f, 0.95f to 0.28f, 0.85f to 0.34f, 0.5f to 0.35f, 0.08f to 0.33f,
            0.04f to 0.27f, 0.12f to 0.21f, 0.52f to 0.19f, within = ::screenshotIn,
        )
    }),
    Scene("picture-crop", route = Routes.agent(DEVBOX, APP_AGENT), setup = { pictures() }, actions = {
        tapLabel("Picture 1 of 2, $SHOT_1")
        tapText("Crop")
        // The top left corner down and in, the bottom edge up: the cards around the Board card are kept.
        drag("The picture", 0.01f to 0.01f, 0.05f to 0.13f, within = ::screenshotIn)
        drag("The picture", 0.5f to 0.99f, 0.5f to 0.55f, within = ::screenshotIn)
    }),
    Scene("chat-sent-pictures", route = Routes.agent(DEVBOX, APP_AGENT), setup = { sentPictures() }),
    Scene("picture-view", route = Routes.agent(DEVBOX, APP_AGENT), setup = { sentPictures() }, actions = {
        tapLabel("Picture 2 of 2, Screenshot_20261018_101230.png")
    }),

    // The work board and its Coordinator.
    Scene("coordinator", route = Routes.coordinator(DEVBOX)),
    // Reaching the Coordinator from outside the app: a share (a log line and a screenshot) choosing its machine,
    // the tile or widget when it can't tell which, and the chat it lands in with the shared words in its box.
    Scene("share", route = Routes.ask(Ask.SHARE), setup = { shared = Demo.shared() }),
    Scene("ask", route = Routes.ask(Ask.COORDINATOR)),
    Scene(
        "share-sent", route = Routes.ask(Ask.SHARE),
        setup = { shared = Demo.shared().let { s -> s.copy(files = s.files.map { f -> f.copy(path = Demo.sharedFile().path) }) } },
        actions = { tapText("devbox"); settle() },
    ),
    // Shared to a machine that isn't connected: the words wait in the box, the screenshot to be copied once it is.
    Scene(
        "share-waiting", route = Routes.ask(Ask.SHARE),
        setup = { shared = Demo.shared().let { s -> s.copy(files = s.files.map { f -> f.copy(path = Demo.sharedFile().path) }) } },
        actions = { tapText("mac-mini"); settle() },
    ),
    // The Board tab: every machine's tasks in one list, grouped by machine; one machine; a task opened from it.
    Scene("boards", route = Routes.BOARDS, setup = { home() }),
    Scene("boards-tall", route = Routes.BOARDS, heightDp = 1500, setup = { home() }),
    Scene("boards-gpu", route = Routes.BOARDS, setup = { home() }, actions = { tapText("gpu-box") }),
    Scene("boards-task", route = Routes.BOARDS, setup = { home() }, actions = { tapText("Rewrite the install page") }),
    Scene("board", route = Routes.board(DEVBOX)),
    Scene("task", route = Routes.board(DEVBOX), actions = { tapText("Show when each machine last answered") }),
    Scene("task-review-failed", route = Routes.board(DEVBOX), actions = { tapText("Rewrite the install page") }),
    Scene("task-actions", route = Routes.board(DEVBOX), actions = { longPressText("Rewrite the install page") }),
    // A task at work offers watches: tell me once its checks pass, once it's done.
    Scene("task-watch", route = Routes.board(DEVBOX), heightDp = 1000, actions = { longPressText("Add app screenshots to the README") }),
    Scene("board-add", route = Routes.board(DEVBOX), actions = { tapLabel("Add a task") }),
    // Finished work, one card a task across the boards: what changed and the agent's note, the review, the checks, the
    // pull request; its full diff a tap away; Approve anyway saying what failed; sending it back.
    Scene("review", route = Routes.review(DEVBOX)),
    Scene("review-full", route = Routes.review(DEVBOX), heightDp = 1500),
    Scene("review-failed", route = Routes.review(DEVBOX, "website/4"), heightDp = 1500),
    Scene("review-diff", route = Routes.review(DEVBOX), heightDp = 1300, actions = { tapText("What changed") }),
    Scene("review-file", route = Routes.review(DEVBOX), actions = { tapText("What changed"); tapText("MachineCard.kt") }),
    Scene("review-anyway", route = Routes.review(DEVBOX, "website/4"), actions = { tapText("Approve anyway") }),
    Scene("review-send-back", route = Routes.review(DEVBOX, "website/4"), actions = { tapText("Send back") }),
    // gpu-box has a board too: its task waits for a pick, among devbox's cards, and says whose it is.
    Scene("review-pick", route = Routes.review(GPU, "llm-eval/3"), heightDp = 1300, setup = { host(GPU).board = Demo.gpuBoard() }),
    Scene("review-empty", route = Routes.review(DEVBOX), setup = { host(DEVBOX).board = Demo.nothingToReview() }),

    // A chat floating over other apps as a bubble: offered in the agent's menu and the Coordinator's bar, Android's
    // bubbles not yet allowed for the app, and the bubble's own window (an agent's chat, the Coordinator), which
    // has no back arrow and opens the chat in the app instead.
    Scene("chat-float", route = Routes.agent(DEVBOX, APP_AGENT), actions = { tapLabel("More") }),

    // The activity timeline: a board task's (every session its agents had, and the board's events), and one agent's, live.
    Scene("activity", route = Routes.activity(DEVBOX, task = "herdr-android/9", name = "AND-9"), heightDp = 2400),
    Scene("activity-failures", route = Routes.activity(DEVBOX, task = "herdr-android/9", name = "AND-9"), heightDp = 1100, actions = { tapText("Failures") }),
    Scene("activity-open", route = Routes.activity(DEVBOX, task = "herdr-android/9", name = "AND-9"), heightDp = 1500, actions = {
        tapText("Commands")
        tapText("Run the core tests")
    }),
    Scene("activity-diff", route = Routes.activity(DEVBOX, task = "herdr-android/9", name = "AND-9"), actions = {
        tapText("Edits")
        tapText("core/src/main/kotlin/demo/herdr/core/host/HostSession.kt")
    }),
    Scene("activity-review", route = Routes.activity(DEVBOX, task = "herdr-android/9", name = "AND-9"), heightDp = 2700, actions = {
        tapText("Review round 1 passed")
    }),
    // A jump to a failure the Edits filter hides: the filter gives way and the failed step shows.
    Scene("activity-jump", route = Routes.activity(DEVBOX, task = "herdr-android/9", name = "AND-9"), actions = {
        tapText("Edits")
        tapText("Jump to a failure")
    }),
    Scene("activity-agent", route = Routes.activity(DEVBOX, pane = APP_AGENT), heightDp = 1600),
    Scene(
        "float-off", route = Routes.coordinator(DEVBOX), setup = { bubblesAllowed = false },
        actions = { tapLabel("Float over other apps") },
    ),
    Scene("bubble", bubble = BubbleChat.Agent(DEVBOX, APP_AGENT), widthDp = 392, heightDp = 640),
    Scene("bubble-menu", bubble = BubbleChat.Agent(DEVBOX, APP_AGENT), widthDp = 392, heightDp = 640, actions = { tapLabel("More") }),
    Scene("bubble-coordinator", bubble = BubbleChat.Coordinator(DEVBOX), widthDp = 392, heightDp = 640),

    // The brief: what happened overnight across the machines (devbox's and gpu-box's herdr-push journals; ci-runner
    // compared with what the app showed at 23:10; mac-mini refuses the key), taller to show all of it. Then read
    // aloud, on its fourth sentence (the review that failed, marked in the list), and what devbox shows without a
    // journal. When it comes: two times on weekdays, read aloud in the car; adding a time.
    Scene("brief", route = Routes.BRIEF, heightDp = 2100, setup = { brief = Demo.brief() }, actions = { settle() }),
    Scene(
        "brief-reading", route = Routes.BRIEF, setup = { brief = Demo.brief() },
        actions = { ctx ->
            settle()
            tapText("Read aloud")
            repeat(2) { ctx.container.briefWork.voice.next() }
            settle()
        },
    ),
    Scene(
        "brief-no-journal", route = Routes.BRIEF, heightDp = 1500,
        setup = { brief = Demo.brief(); host(DEVBOX).journal = null; host(GPU).journal = null },
        actions = { settle() },
    ),
    Scene("brief-settings", route = Routes.BRIEF_SETTINGS, heightDp = 1100, setup = { brief = Demo.brief() }),
    Scene("brief-add-time", route = Routes.BRIEF_SETTINGS, setup = { brief = Demo.brief() }, actions = { tapText("Add a time") }),

    // The code on the machines: which machine, a machine's repositories and checkouts (the agents at work in each);
    // task AND-9's worktree: its files, Go to file, a file, two lines chosen and a comment on them, the view's
    // settings, wrapped at a larger size, a Markdown file as it reads; its changes against main and one file's diff;
    // its history and a commit; a search across it; the comments kept to send together.
    Scene("code-machines", route = Routes.CODE),
    Scene("code-repos", route = Routes.codeRepos(DEVBOX)),
    Scene("code-files", route = Routes.codeAt(TASK_CODE)),
    Scene("code-goto", route = Routes.codeAt(TASK_CODE), actions = { type("mcard") }),
    Scene("code-file", route = Routes.codeFile(TASK_CODE, CARD)),
    Scene("code-lines", route = Routes.codeFile(TASK_CODE, CARD, line = 33), actions = { tapLabel("line 37"); tapLabel("line 38") }),
    Scene(
        "code-comment", route = Routes.codeFile(TASK_CODE, CARD, line = 33),
        actions = {
            tapLabel("line 37"); tapLabel("line 38"); tapText("Comment")
            type("Show this in the notice colour when it is older than an hour, so a stale card stands out.")
        },
    ),
    Scene("code-view", route = Routes.codeFile(TASK_CODE, CARD), actions = { tapLabel("Text size and wrapping") }),
    Scene("code-wrapped", route = Routes.codeFile(TASK_CODE, CARD), setup = { codeView = CodeView(wrap = true, textSize = 15) }),
    Scene("code-markdown", route = Routes.codeFile(TASK_CODE, "README.md")),
    Scene("code-changes", route = Routes.codeAt(TASK_CODE, CheckoutTab.Changes)),
    Scene("code-diff", route = Routes.codeDiff(TASK_CODE, DiffSource.base("main"), CARD)),
    Scene("code-history", route = Routes.codeAt(TASK_CODE, CheckoutTab.History)),
    Scene("code-commit", route = Routes.codeCommit(TASK_CODE, FakeCode.commit(1))),
    Scene("code-search", route = Routes.codeSearch(TASK_CODE, "answered")),
    Scene("code-notes", route = Routes.codeNotes(TASK_CODE), heightDp = 1000, setup = { notes = Demo.notes() }),

    // Quick commands: the machine's own from its screen; an agent's project's and the machine's, one pinned; a command
    // that asks for its arguments, and its menu; a script that failed, an AI one's answer and one still at work, and
    // the runs (a notification of an AI one that ended opens it there); asking an agent for a new one; a machine
    // without any yet.
    Scene("quick", route = Routes.machine(DEVBOX), actions = { tapLabel("Quick commands") }),
    Scene(
        "quick-project", route = Routes.agent(DEVBOX, APP_AGENT), heightDp = 1100,
        setup = { quickPins = mapOf(DEVBOX to listOf("Project:Run unit tests")) },
        actions = { tapLabel("More"); tapText("Quick commands") },
    ),
    Scene("quick-args", route = Routes.agent(DEVBOX, APP_AGENT), heightDp = 1100, actions = {
        tapLabel("More"); tapText("Quick commands"); tapText("Draft a PR description"); typeInto("Anything to point out?", "Mention that the card's colour follows the theme")
    }),
    Scene("quick-menu", route = Routes.agent(DEVBOX, APP_AGENT), heightDp = 1100, actions = {
        tapLabel("More"); tapText("Quick commands"); tapLabel("More for Render the screens")
    }),
    Scene("quick-run", route = Routes.agent(DEVBOX, APP_AGENT), heightDp = 1100, actions = {
        tapLabel("More"); tapText("Quick commands"); tapText("Run unit tests"); settle()
    }),
    Scene("quick-failed", route = Routes.quickRuns(DEVBOX, FakeQuick.TESTS), heightDp = 1100),
    Scene("quick-answer", route = Routes.quickRuns(DEVBOX, FakeQuick.TODOS), heightDp = 1100),
    // A long build log: the sheet shows its end, where the error is, and says how much of it is left out.
    Scene("quick-long", route = Routes.quickRuns(DEVBOX, FakeQuick.BUILD), heightDp = 1100),
    Scene("quick-working", route = Routes.quickRuns(DEVBOX, FakeQuick.TODAY)),
    Scene("quick-runs", route = Routes.quickRuns(DEVBOX)),
    Scene("quick-new", route = Routes.agent(DEVBOX, APP_AGENT), heightDp = 1100, actions = {
        tapLabel("More"); tapText("Quick commands"); tapText("New command"); tapText("AI prompt")
        typeInto("What should it do?", "Check why the last CI run of this branch failed and say how to fix it")
    }),
    Scene("quick-empty", route = Routes.machine(GPU), actions = { tapLabel("Quick commands") }),
    // A task's quick commands, under its code.
    Scene("task-quick", route = Routes.board(DEVBOX), heightDp = 1400, actions = { tapText("Show when each machine last answered") }),

    // A pane as a live terminal.
    Scene("terminal", route = Routes.terminal(DEVBOX, APP_SHELL)),

    // A window 840 dp wide or more (a tablet, an open foldable, a large window): the agents list, the chat or
    // terminal, the board, side by side. A landscape tablet before anything is opened, then with a chat open
    // (tapped on the left, so it is chosen there); an open foldable with a terminal; the Coordinator in the
    // middle with a task open on the right.
    Scene("wide-empty", widthDp = 1280, heightDp = 800, setup = { home() }),
    Scene("wide", widthDp = 1280, heightDp = 800, setup = { home() }, actions = { tapText("Speed up /jobs") }),
    // The Board tab in the middle: a task tapped there opens on the right.
    Scene("wide-boards", widthDp = 1280, heightDp = 800, setup = { home() }, actions = { tapLabel("Board, 4 need you"); tapText("Taking the Coordinator screenshot") }),
    Scene("wide-terminal", route = Routes.terminal(DEVBOX, APP_SHELL), widthDp = 840, heightDp = 940),
    Scene("wide-task", route = Routes.coordinator(DEVBOX), widthDp = 1280, heightDp = 800, setup = { home() }, actions = { tapText("Taking the Coordinator screenshot") }),
    // A board opened as its own screen on a phone, the window then made wide: a notification's link to one of its
    // tasks moves the board to the right, with that task open.
    Scene(
        "wide-link-task", route = Routes.board(DEVBOX), widthDp = 1280, heightDp = 800,
        // On the UI thread, as a tapped notification reaches the app.
        actions = { runOnUiThread { Links.open(Routes.board(DEVBOX, "herdr-android/9")) }; settle() },
    ),
    // Machines in the middle: the nav bar is under the list.
    Scene("wide-machines", widthDp = 1280, heightDp = 800, setup = { home() }, actions = { tapLabel("Machines") }),
    // Rotating across the line keeps what is open as it was: a chat with its key row shown, turned from a phone
    // to a tablet's width and from the tablet back to the phone.
    Scene(
        "rotate-wide", route = Routes.agent(DEVBOX, APP_AGENT), resize = 1280 to 800,
        actions = { tapLabel("More"); tapText("Show keys") },
    ),
    Scene(
        "rotate-narrow", widthDp = 1280, heightDp = 800, resize = 412 to 892,
        actions = { tapText("Speed up /jobs"); tapLabel("More"); tapText("Show keys") },
    ),

    // Settings (a gear in the Machines bar) and the SSH key's dialogs.
    Scene("settings", route = Routes.MACHINES, actions = { tapLabel("Settings") }),
    Scene("settings-new-key", route = Routes.SETTINGS, actions = { tapText("New key") }),
    Scene("key-setup", key = IdentityStore.State.Missing),
    // The fingerprint before what can't be undone, and the app lock: its settings (one action turned off, the lock
    // on), the same on a phone with no screen lock, the app locked, and a merge confirmed in the app without a screen lock.
    Scene(
        "settings-guard", route = Routes.GUARD,
        setup = { guard = com.makefunstuff.herdr.data.GuardState(off = setOf("stop"), appLock = true, lockAfter = 5) },
    ),
    Scene("settings-guard-nolock", route = Routes.GUARD, setup = { screenLock = false }),
    Scene("app-lock", setup = { guard = com.makefunstuff.herdr.data.GuardState(appLock = true); unlockAnswer = null }),
    Scene("review-merge-confirm", route = Routes.review(DEVBOX), setup = { screenLock = false }, actions = { tapText("Approve and merge") }),

    // Notifications while the app is closed: each machine's herdr-push, and before a push app is there.
    // A taller window shows the whole section under the SSH key (scrolling never settles in the desktop test harness).
    Scene("settings-notifications", route = Routes.SETTINGS, heightDp = 1580, setup = { push = Demo.push() }),
    // What reaches the phone: a switch per kind (Finished off), silent while you are at a machine (devbox, now), the watches.
    Scene("settings-rules", heightDp = 1100, setup = { push = Demo.push().copy(watches = Demo.watches()) }, actions = { ctx ->
        ctx.container.quietWork.presence.record(DEVBOX, "devbox", com.makefunstuff.herdr.core.push.Presence(typedAgo = 20), System.currentTimeMillis())
        runOnUiThread { ctx.nav.navigate(Routes.RULES) }
        settle()
    }),
    Scene("settings-rules-empty", route = Routes.RULES, setup = { push = Demo.push() }),
    // Live Updates turned off for the app in Android's settings: they show as ordinary notifications.
    Scene("settings-live-off", route = Routes.SETTINGS, heightDp = 1580, setup = { push = Demo.push(); liveAllowed = false }),
    // Quiet hours: off; then on, the phone in Do Not Disturb with two finished agents held, and picking a time.
    Scene("quiet-hours-off", route = Routes.QUIET, setup = { push = Demo.push() }),
    Scene(
        "quiet-hours", route = Routes.QUIET, heightDp = 1100,
        setup = { push = Demo.push().copy(quiet = Demo.quiet()); quietMode = PhoneQuiet.DoNotDisturb; held = Demo.held() },
    ),
    Scene(
        "quiet-hours-time", route = Routes.QUIET,
        setup = { push = Demo.push().copy(quiet = Demo.quiet()); quietMode = PhoneQuiet.DoNotDisturb },
        actions = { tapText("From") },
    ),
    Scene(
        "settings-no-push-app", route = Routes.SETTINGS, heightDp = 1320,
        setup = { push = Demo.push().copy(endpoint = null, distributor = null); pushApps = emptyList(); notificationsAllowed = false },
    ),
)
