// Every scene, in the order they render. Each renders twice: <name>.png (dark) and <name>-light.png.
// A scene starts from Demo's machines; `route` opens a screen, `actions` taps on what it shows.
package preview

import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.data.IdentityStore
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
)
