# herdr-core as a backend

The Herdr desktop app (`herdr-app`) runs its terminals and agents in **herdr-core** (`hc`), not in the classic `herdr server` the app has always talked to. A machine may run either or both. This note says how the app reaches herdr-core and how hc's model maps onto the app's screens. The code is in `core/src/main/kotlin/.../core/hc/`; the screens use it from HA-6 on.

Built against `hc` 0.1.14 (`hc schema`: OpenRPC, protocol generation 1). Its answers, as a real `hc connect` gave them, are the test fixtures in `core/src/test/resources/hc/` (paths and names scrubbed).

## Reaching it

- **Detection** (`HcDetection`), per machine, after the SSH login and beside classic herdr's own: one shell probe lists the `herdr-core` executables (the running core's own build first, then `~/.local/bin`, `PATH`, the usual install places, the login shell's `PATH`) and whether a core runs (`pgrep` for `herdr-core serve`, or its socket where there is no `pgrep`). An `hc` on its own isn't taken for it: herbstluftwm ships one too.
- **Only a running core counts.** `hc connect` starts a core when none listens, and the app mustn't start a runtime on a machine whose owner doesn't use it. A core found missing is looked for again every minute (the desktop app started later).
- **Transport** (`HcConnection`): one `<herdr-core> connect` exec channel per machine on the existing sshj connection, kept for the connection's life and closed with it. `hello {generation: 1, framed: true}` as one JSON line, then framed mode: `[channel u32 LE][kind u8][len u32 LE][payload]`. Channel 0 carries JSON-RPC calls side by side; the event subscription and each attached terminal get a channel of their own, credited back as the app reads, so the one channel carries everything.
- **Client** (`HcClient`): `list`, `get`, `read`, `send`, `keys`, `interrupt`, `label`, `subscribe` (snapshot, then events) and `attach` (raw terminal bytes, keys, resize). Errors come back as `HcException` with hc's stable `kind` (`not_found`, `blocked`, …).
- **In the app** (`HostSession`): a machine running both shows both. `HostState.herdrCore` holds herdr-core's version, the core's name and its terminals as a `SessionSnapshot`, next to classic herdr's `HostState.snapshot`, which doesn't change; `awaitHerdrCore()` gives the client. A herdr-core failure never fails the classic session.

## The model

hc itself has terminals only. The desktop app adds its spaces and tabs as labels on each terminal it shows: `herdr.space` and `herdr.tab` (ids) and `where` (`<space> / <tab>`, the names, which `hc ls` prints). `HcMapping.snapshot` turns live terminals into the app's own `SessionSnapshot`, so the screens read herdr-core as they read classic herdr. Every id starts with `hc:` so both can sit side by side.

| herdr-core | The app | How |
| --- | --- | --- |
| A space (`herdr.space`, name from `where`) | Workspace | `hc:<space>`; numbered in the order its first terminal started |
| A tab (`herdr.tab`, name from `where`) | Tab | `hc:<space>/<tab>` |
| A terminal (`t7`, `rohan/t7` on a machine hc added) | Pane | `hc:t7`; `HcMapping.address` gives `t7` back for hc's calls |
| A helper an agent started (`parent`) | Pane in its parent's tab | as the desktop app places it |
| A terminal the app doesn't show (`hc run`) | Pane in **Other terminals** | one workspace per machine, after the spaces |
| A program that reports status (OSC 7501), or a known agent before it reports | Agent | `agent` from 7501's `app` (`claude-code` → `claude`), else the program's name |
| Status `idle` `working` `done` `blocked` | Same status | the program's own word, nothing guessed from the screen |
| Status `error` | Blocked, labelled "error" | it needs the human too |
| `blocked` with `kind` question or auth | Blocked, labelled "question" or "needs sign-in" | permission keeps the plain label |
| 7501 `msg` (else `title`) | The agent's subtitle | "Showing current date and time" |
| Terminal title | `terminalTitle` | Claude Code's "Greeting reply" |
| `status_ms` (when the word changed) | `stateChangeSeq` | snoozes, ordering, notice ids |
| `turn` (up each time it leaves working) | `completionSeq` | which finish was seen |
| `foreground.cwd`, else `cwd` | Agent `cwd` | |

What follows on the screens:

- **Agents and Needs you**: hc's agents appear with the classic ones (`HomeAgent` reads `SessionSnapshot`). A blocked one can be answered with its keys (`terminal.keys`: `y`, `enter`, `esc`). `terminal.send` refuses a blocked program (kind `blocked`) unless forced.
- **Notifications**: the app derives them from status changes it follows (`Notices.between`), so hc's working → done and → blocked raise the same notices; the events stream makes them live.
- **Terminals**: `HcTerminalStream` has `TerminalStream`'s surface (emulator, revision, status, send, resize, close), fed by `terminal.attach`. hc sends the program's raw bytes, so the app's own emulator draws them. A watch-only attach (`observe`) never types or resizes the person's window.
- **Chats**: hc reports no transcript, so an hc agent's chat shows its screen (`terminal.read`), as a classic agent without a session does. Following Claude Code's transcript from the agent's `cwd` is a follow-up.

## No match

- **Layouts**: hc has no splits, zoom or focus; each tab's panes are a list. The desktop app keeps its layout to itself.
- **Workspace metadata and worktrees**: hc has no `workspace.report_metadata` tokens (branch, PR) and no `worktree.*`.
- **Plugins, integrations, agent manifests, config reload**: herdr's; hc has templates (`templates.list`) instead.
- **Scrolling a pane on the host**: hc has no scroll request; the attach's first paint carries history lines and the phone scrolls those.
- **Creating workspaces and tabs**: spaces and tabs are the desktop app's (`herdr-app do`, a GUI control socket); hc can start a terminal (`terminal.run`) and label it into a space.
- **Agent sessions** (`agent_session`): no transcript path; see Chats.
- **herdr's "seen"** (done becomes idle once looked at): hc can't tell; `turn` is the closest.

## Left for HA-6

Wiring the screens: merging `HostState.herdrCore.snapshot` into the Agents list, Needs you, notices and the machine screen; routing a pane's reads, keys and terminal to `HcClient` when its id starts with `hc:`; and a machine that runs herdr-core alone, which still stops at "herdr isn't installed" today because the session needs classic herdr to come online.
