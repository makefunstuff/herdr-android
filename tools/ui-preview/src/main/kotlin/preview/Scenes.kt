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

fun scenes(): List<Scene> = listOf(
    // Agents: every agent on every machine, the blocked one first.
    Scene("agents"),
    Scene("agent-actions", actions = { longPressText("Speed up /jobs") }),
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
    Scene("chat-codex", route = Routes.agent(DEVBOX, SITE_AGENT)),

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
    Scene("board", route = Routes.board(DEVBOX)),
    Scene("task", route = Routes.board(DEVBOX), actions = { tapText("Show when each machine last answered") }),
    Scene("task-review-failed", route = Routes.board(DEVBOX), actions = { tapText("Rewrite the install page") }),
    Scene("task-actions", route = Routes.board(DEVBOX), actions = { longPressText("Rewrite the install page") }),
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
    Scene("wide-empty", widthDp = 1280, heightDp = 800),
    Scene("wide", widthDp = 1280, heightDp = 800, actions = { tapText("Speed up /jobs") }),
    Scene("wide-terminal", route = Routes.terminal(DEVBOX, APP_SHELL), widthDp = 840, heightDp = 940),
    Scene("wide-task", widthDp = 1280, heightDp = 800, actions = { tapText("Coordinator"); tapText("Review passed. Ready to merge.") }),
    // A board opened as its own screen on a phone, the window then made wide: a notification's link to one of its
    // tasks moves the board to the right, with that task open.
    Scene(
        "wide-link-task", route = Routes.board(DEVBOX), widthDp = 1280, heightDp = 800,
        // On the UI thread, as a tapped notification reaches the app.
        actions = { runOnUiThread { Links.open(Routes.board(DEVBOX, "herdr-android/9")) }; settle() },
    ),
    // Machines in the middle: the nav bar is under the list.
    Scene("wide-machines", widthDp = 1280, heightDp = 800, actions = { tapLabel("Machines") }),
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

    // Settings and the SSH key's dialogs.
    Scene("settings", route = Routes.SETTINGS),
    Scene("settings-new-key", route = Routes.SETTINGS, actions = { tapText("New key") }),
    Scene("key-setup", key = IdentityStore.State.Missing),

    // Notifications while the app is closed: each machine's herdr-push, and before a push app is there.
    // A taller window shows the whole section under the SSH key (scrolling never settles in the desktop test harness).
    Scene("settings-notifications", route = Routes.SETTINGS, heightDp = 1460, setup = { push = Demo.push() }),
    // Live Updates turned off for the app in Android's settings: they show as ordinary notifications.
    Scene("settings-live-off", route = Routes.SETTINGS, heightDp = 1460, setup = { push = Demo.push(); liveAllowed = false }),
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
        "settings-no-push-app", route = Routes.SETTINGS, heightDp = 1200,
        setup = { push = Demo.push().copy(endpoint = null, distributor = null); pushApps = emptyList(); notificationsAllowed = false },
    ),
)
