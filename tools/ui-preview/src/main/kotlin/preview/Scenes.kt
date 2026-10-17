// Every scene, in the order they render. Each renders twice: <name>.png (dark) and <name>-light.png.
// A scene starts from Demo's machines; `route` opens a screen, `actions` taps on what it shows.
package preview

import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.push.PhoneQuiet
import com.makefunstuff.herdr.core.push.QuietHours
import com.makefunstuff.herdr.data.IdentityStore
import com.makefunstuff.herdr.ui.share.Ask
import preview.Demo.API_AGENT
import preview.Demo.APP_AGENT
import preview.Demo.APP_SHELL
import preview.Demo.DEVBOX
import preview.Demo.SITE_AGENT

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

    // A pane as a live terminal.
    Scene("terminal", route = Routes.terminal(DEVBOX, APP_SHELL)),

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
