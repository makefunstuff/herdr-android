# <img src="docs/screenshots/icon.webp" alt="" width="48" align="center"> Herdr for Android

A native Android client for [herdr](https://herdr.dev) hosts: see your agents across machines, answer the blocked ones, and open any pane as a live terminal. Kotlin and Jetpack Compose. It wears the [herdr web UI](https://github.com/devswha/herdr-web-ui)'s look ("a warm terminal": amber on lamp-lit graphite or ledger paper, Lucide icons, the agents' own marks), carried over from its design tokens (MIT) into the app's own design system ([`docs/DESIGN.md`](docs/DESIGN.md)).

<table>
  <tr>
    <td align="center" width="25%"><img src="docs/screenshots/agents.webp" alt="Agents list" width="200"><br><sub><b>Agents</b>: every agent on every machine, the blocked ones first</sub></td>
    <td align="center" width="25%"><img src="docs/screenshots/chat.webp" alt="An agent's chat" width="200"><br><sub><b>Agent chat</b>: the agent's own transcript, with tool calls folded</sub></td>
    <td align="center" width="25%"><img src="docs/screenshots/blocked.webp" alt="A blocked agent asking for approval" width="200"><br><sub><b>Needs you</b>: answer a blocked agent with one tap</sub></td>
    <td align="center" width="25%"><img src="docs/screenshots/coordinator.webp" alt="Coordinator chat" width="200"><br><sub><b>Coordinator</b>: talk to the work board</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/machines.webp" alt="Machines list" width="200"><br><sub><b>Machines</b>: status per host, also through a jump host</sub></td>
    <td align="center"><img src="docs/screenshots/machine.webp" alt="A machine's workspaces" width="200"><br><sub><b>A machine</b>: its workspaces, tabs and panes</sub></td>
    <td align="center"><img src="docs/screenshots/terminal.webp" alt="Terminal" width="200"><br><sub><b>Terminal</b>: any pane, live, sized to the phone</sub></td>
    <td align="center"><img src="docs/screenshots/settings.webp" alt="Settings" width="200"><br><sub><b>Settings</b>: the SSH key, with backup and restore</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/board.webp" alt="The work board" width="200"><br><sub><b>Board</b>: tasks in the board's colours, the ones that need you first</sub></td>
    <td align="center"><img src="docs/screenshots/task.webp" alt="A task's details" width="200"><br><sub><b>A task</b>: next step, reviews, links, one tap to approve</sub></td>
    <td align="center"><img src="docs/screenshots/agents-light.webp" alt="Agents in light mode" width="200"><br><sub><b>Light mode</b>: follows the phone</sub></td>
    <td align="center"><img src="docs/screenshots/board-light.webp" alt="The board in light mode" width="200"><br><sub><b>Board, light</b></sub></td>
  </tr>
</table>

The screenshots come from a demo host with made-up projects (see [`docs/screenshots/demo-host`](docs/screenshots/demo-host)). The look (colours, type, icons, spacing, components) is described in [`docs/DESIGN.md`](docs/DESIGN.md); the design tokens are the herdr web UI's (MIT, by devswha), and the fonts, icons and marks are credited in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Getting the app

On your phone, open the repo's [latest release](https://github.com/makefunstuff/herdr-android/releases/latest) and tap **herdr-android.apk** (the direct link is `https://github.com/makefunstuff/herdr-android/releases/latest/download/herdr-android.apk`). Android asks you once to allow installs from your browser or file manager.

To update, do the same again: every build is signed with the same key and has a higher version code, so it installs over the app you have and keeps its machines and SSH key. No uninstall needed.

Only the default branch publishes a release (tagged `v0.1.<run number>`). Other branches and pull requests build the APK too, as the `herdr-android-<run number>` artifact on their Actions run page, without a release.

## Keeping the SSH key

The key stays through updates. An uninstall or a new phone wipes it, so back it up first: **Settings → SSH key → Back up** saves it as an OpenSSH private key protected by a passphrase you choose (`ssh-keygen -y -f herdr-android-key` reads it too). After a reinstall the app offers **Restore or import** before it makes a new key; the same button is in Settings. It also takes an existing OpenSSH (or PEM) private key, with or without a passphrase.

The app never replaces a key on its own: it only makes one when it has none and you ask for it, and **New key** asks before throwing the old one away.

## Signing key (once, for the repo owner)

Releases must all be signed with one key, or Android refuses the update. Create it once and keep a copy somewhere safe; losing it means everyone has to uninstall (and restore their SSH key backup) once.

```sh
keytool -genkeypair -keystore herdr-release.jks -alias herdr \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Herdr for Android"
base64 -w0 herdr-release.jks > herdr-release.jks.b64
```

Then add these secrets under **Settings → Secrets and variables → Actions** (or with `gh secret set NAME`):

| Secret | Value |
| --- | --- |
| `HERDR_KEYSTORE_BASE64` | contents of `herdr-release.jks.b64` (`gh secret set HERDR_KEYSTORE_BASE64 < herdr-release.jks.b64`) |
| `HERDR_KEYSTORE_PASSWORD` | the keystore password |
| `HERDR_KEY_ALIAS` | `herdr` |
| `HERDR_KEY_PASSWORD` | optional: the key password, only if it differs from the keystore password (keytool uses the same one by default) |

Without these secrets, and for local builds, the APK is signed with the normal debug key and the default branch skips publishing a release (and keeps the old `apk-*` pre-releases until the first real one). To sign a local build with the real key, set `HERDR_KEYSTORE_FILE`, `HERDR_KEYSTORE_PASSWORD` and `HERDR_KEY_ALIAS` and run `./gradlew :app:assembleRelease`.

## Setting up a machine

1. Open **Settings** and copy the app's public key.
2. Add it to `~/.ssh/authorized_keys` on the machine running herdr.
3. In **Machines**, tap **Add machine** and enter `user@host[:port]`. The app pins the host key on first connect.

Machines reachable only through another one can use it as a jump host ("Connect through"). From a machine's menu, **Import herdr machines** adds the machines its herdr already knows (`herdr machine list`).

## How it talks to herdr

The app opens an SSH connection and reaches herdr's socket API through `direct-streamlocal` (falling back to `herdr remote-api-bridge`). herdr answers one request per socket connection, so each call opens a fresh channel; `events.subscribe` keeps one channel open for live updates. Terminals use `herdr terminal session control`.

## Managing from the phone

Everything that exists in a session is editable from the app, not just readable. A workspace or tab header has an actions button, and panes and agents answer to a long press: rename, focus, reorder, move, swap, zoom, clear a pane's scrollback, read what a pane is running, and ask `agent.explain` why an agent is in the state it shows. Closing a workspace, tab or pane asks first. herdr's own refusals ("There's no pane in that direction.") come back as plain sentences.

The screens follow herdr's `events.subscribe` stream, so a change made anywhere — desktop or phone — appears here. Notifications are the exception: herdr has no list of them, so the app derives them from agents finishing or blocking and shows them behind the bell in the top bar; the menu can also show a notification on the desktop running herdr.

## Machine health and cleanup

A machine's screen has a **Health** card (RAM, CPU and its fullest disk, red when it runs low), and **⋯ → Health and cleanup** opens the whole picture, read over the same SSH connection every 5 seconds while the screen is in front:

- **RAM, swap, CPU and every disk** (free space per mount), and a chart of RAM and CPU over the **last hour**. The hour comes from herdr-push, which notes a reading every 30 seconds on the machine; without it the chart fills while the screen is open.
- **The biggest processes** by memory or CPU, each with what it is and whose: Android emulators (with their AVD), QEMU VMs, Gradle and Kotlin daemons, dev servers (vite, next, uvicorn, …), node, the coding agents, herdr; and the board task whose worktree it runs in or was started from (`AND-22 · done`), or the herdr pane and agent it runs under. A tap shows its details and **Stop process** (after a confirmation: SIGTERM, then SIGKILL after 5 seconds, and only while that pid is still the same program).
- **Leftovers of finished tasks**: processes still running for a task that is done or archived, and its worktree still on disk, marked safe to clean. **Stop all leftovers** stops them in one go; each task's sheet stops its own and **Remove worktree** removes the checkout with git (asking again, and saying so, when it has uncommitted changes; through herdr when a workspace is open on it). What other work shares is never a leftover: adb's server, this machine's herdr server, and a Gradle daemon while it builds.
- **Stop Gradle daemons**: every Gradle and Kotlin daemon of yours; the next build starts one again.
- **A warning** when RAM or a disk runs low: under 10% of RAM available, or a disk with less than a tenth free (at most 20 GB, so a big disk isn't "low" with 50 GB left). It names what uses the most memory, on the screen and as a notification through herdr-push (and the app's own looks while closed), which comes down once there is room again (15% of RAM, one and a half times the disk's mark). It needs you, so it comes through quiet hours; a tap opens the machine's health.

The full readings and process labels are Linux's (`/proc`); on macOS the screen shows memory, load, disks and the processes from `ps`, without whose they are.

## Notifications while the app is closed

**Settings → Notifications → While the app is closed** makes your machines tell the phone when an agent needs you or finishes, or a work board task waits for you (ready to approve, review failed, plan ready, pick one, asks you, stopped at a limit, went wrong), like Puggie does. Each notification has its buttons: **Approve** or **Deny** a blocked agent's question, **Reply** to that agent (or to the task's agent, or to the Coordinator about the task), **Approve and merge**, **Approve plan**, **Continue**. A tap opens the agent's chat or the task. The buttons connect to the machine over SSH while the app stays closed; Approve and Deny read the agent's screen again first and press nothing if the question changed.

Two ways in, both on every machine the app knows:

- **Pushes**, within seconds. Each machine runs [herdr-push](host/herdr-push/README.md), a small Python 3 watcher that the app sets up over SSH as the machine connects. It sends to the phone's [UnifiedPush](https://unifiedpush.org) address, which a push app such as [ntfy](https://ntfy.sh) gives (no library, no Google services), sealed with a key only that machine and the phone know. How to check it, run it by hand or use your own push server: [`host/herdr-push/README.md`](host/herdr-push/README.md).
- **The app's own looks**: an exact alarm every 15 minutes (or hourly, or never) connects to each machine and notices what changed, for a machine without python3 or a push that got lost.

Each state is shown once, however it arrives, and comes down again when it is over (answered on the desktop, the agent back at work). The switch next to a machine in Settings leaves it out.

### Live Updates

With **Live Updates** on (Settings → Notifications, on by default), what is at work stays in the notifications while it runs: each running board task and each working agent, with a **Stop** button. On Android 16 (a Pixel 10, say) they are Live Updates: promoted to the top of the shade and the lock screen, with a chip in the status bar.

- **A board task** shows its part of the run on a bar in the board's colours (working, checking, in review, merging), its "doing now" line and minutes left, and the chip says where it is: `40%` while it works (or `12m` left), `Check`, `Rev 2/4` in its second of at most four reviews (the first, and one after each time the work went back, `review.rounds`), `Merge`. Stop asks first (**Stop it** or **Keep going**), then runs the board's `stop`: the task waits for you as stopped, and **Continue** starts it again.
- **An agent** shows what it does and how long it has worked (the chip is that time). Stop presses Esc, as the chat's stop button does. A board task's own agents (its worker, its reviewer, the one picking among attempts) are shown as the task, not beside it: they are known by the names the board gives them and by the task's workspace they work in.

They come from herdr-push, which also sends what runs to a phone that asked for it: at once when something starts, stops or moves on, its progress at most every 90 seconds, and again every 30 minutes while anything runs (a push server counts pushes; ntfy.sh allows a few hundred a day). While the app is open it follows its machines' agents itself too, and its own looks while closed count as well. One that nothing confirmed for 75 minutes comes down by itself, as its machine may be gone. On Android 15 and older they are ordinary ongoing notifications with a progress bar. If Live Updates are turned off for the app in Android's settings, Settings says so and has a button to turn them on.

### Quiet hours

**Settings → Notifications → Quiet hours** holds the notifications that can wait, like Puggie's quiet hours: while the phone is in Do Not Disturb or a mode that turns it on (Bedtime, Driving) or in car mode, or on a schedule you set (from, to, and the days a quiet stretch starts on; the same time twice makes those days quiet all day). An agent that finished waits, and Live Updates come down. What needs you still comes through: an agent that asks, a board task waiting for you, a test. When quiet hours end, what was held shows as one summary notification (or as itself, when it is only one), and the Live Updates come back; one that was over by then (the agent back at work) is left out. Android tells a closed app nothing when Do Not Disturb ends, so the app looks every quarter of an hour while the phone is quiet; a schedule's end has its own alarm. Whether what comes through makes a sound is still Android's to decide: allow Herdr in the Bedtime or Driving mode to hear it.

## The work board

Next to the Coordinator chat, a machine with herdr-projects gets a **Board** screen: the task list as the board shows it (short key such as `AND-7`, title, status in the board's colours, agent, the "doing now" line, progress and minutes left, `[research]` marks, PR links), with **Needs you** (approvals, plans, pick one, errors, questions) first. Search runs the board's `--query`; chips filter by repo and machine; the menu shows archived tasks. Tap a task for its details (failure and next step, reviews with findings and severity, handovers, plan, attempts, PR and report links, limits and usage); long-press for its actions: start, tell, stop, retry, fix, review, finish, approve (and approve anyway, which shows the failure and asks for a tick), approve or send back a plan, pick an attempt, archive/restore, clear, edit title/description. **+** adds a task. It reads `herdr-projects list --json` and `details <id>` and runs the board's own CLI commands over the app's SSH connection, every few seconds while the screen is in front and not at all when backgrounded; a refused command shows the board's own reason.

## The work board's Coordinator

On a machine with [herdr-projects](https://github.com/eliasstravik/herdr-projects) (its launcher at `~/.herdr-projects/data/bin/herdr-projects`, or `herdr-projects` on the PATH), the app shows a **Coordinator** chat above that machine's agents. It reaches the board the way another machine's board does, over the same SSH connection: `herdr-projects remote list` for the conversation and where the Coordinator stands, and `herdr-projects remote dispatch` for a message, which joins the same queue as the words typed in the board's own box. So the phone and the desktop show one conversation. **Start agents**, the switch beside Send, is the board's Tab: what the Coordinator adds from that message gets an agent at once. What you type stays in the box per chat, also when you leave the app, until it is sent. **+** attaches files from the phone, in both chats: each is copied to that machine (`~/.cache/herdr-android/uploads/`) and named in the message as `@path`, which the agents read.

## Asking from anywhere

The Coordinator is a tap away from outside the app too:

- **Share to it.** Share a link, some text, an error or a screenshot to **Herdr Coordinator** from any app's share sheet. It lands in the Coordinator chat's message box: the words at the end of what you had typed, each file copied to that machine and named as `@path`, as **+** does. You read it, add to it, and send it, so the Coordinator can make a task of it. With several machines the app asks which one first (the one used last on top); a machine without the work board can't be picked. A share waiting for a machine, and files waiting for a machine that isn't connected, are kept through the app being closed: the app asks again when it next opens, and the chat says which file waits and attaches it once the machine connects.
- **Send selected text.** **Send to Coordinator** sits next to Copy in the menu of selected text, and does the same with just the text.
- **The Quick Settings tile.** **Coordinator** opens the chat at once: the Coordinator used last, or the only machine that can have one; otherwise it asks. Under its name it says what needs you ("2 need you · 3 working").
- **Two home-screen widgets.** **Ask the Coordinator** opens the chat as the tile does. **Herdr** shows the counts across your machines, such as "2 need you · 3 working", and when they were last known; a tap opens the board with the most tasks waiting for you (or the agents, when it is an agent that asks).

The counts are what the app knows: what it sees while open (and each board's list, read every 5 minutes while the app is in front), its own looks while closed, and the machines' pushes (see Notifications above). An agent that needs you and the board task it works for count once, as do a task and its own agents at work, and another machine's task counts on that machine. What works and nothing confirmed for 75 minutes stops counting.

## Floating chats

One agent's chat or the Coordinator chat can float over other apps as an Android bubble (Android 11 and newer), to steer it while reading something else. **Float over other apps** is in an agent chat's **⋯** menu and in the Coordinator's top bar; an agent's notification has **Float chat** too, when it has room for a third button (a finished agent, one that asks something without a yes or no), and so has the Live Update of an agent at work (the Coordinator's own agent floats the Coordinator chat). The bubble is the chat itself, with its draft, attachments and terminal; **Open in the app** (in its menu, or the Coordinator's top bar) opens it full size. Each chat gets its own bubble, and Android keeps them as conversations: dragging one away closes it, and the launcher's long press on the app lists them.

Android lets an app's chats bubble only once you allow it. The first time, the app says so and opens Herdr's bubble settings: choose **All conversations can bubble**, and the chat floats as you come back. With **Selected conversations**, the chat shows as a notification instead, whose bubble button floats it (and marks it as one that may).

## Big screens

On a window 840 dp wide or wider (a tablet, an open foldable, a large window or desktop windowing), the app shows three panes side by side, like Puggie: the agents list (with Agents, Machines and Settings under it), then what you opened from it (an agent's chat, a terminal, the Coordinator, a machine, Settings), then the work board. What is open in the middle is marked in the list, and opening another agent replaces it instead of piling up; Back closes it, and then a task open on the board. A board opened from a notification, the widget or a Board card shows on the right, with its task open (a board still open in the middle, from before the window grew, moves there); with several machines that have a board, their Board cards choose which. A narrower window keeps the phone layout. Rotating or resizing across the line keeps what is open as it was: the chat, its draft and scroll, the board's search, filters and open task (kept while a narrow window hides the board, and back when it is wide again).

## Screen previews

UI changes are checked, and shown in review, with screen previews instead of an emulator: the app's real Compose screens rendered on the desktop JVM against a fake herdr machine with fixed demo data, in dark and light.

```sh
./gradlew -p tools/ui-preview render                     # every scene → tools/ui-preview/build/shots/*.png
./gradlew -p tools/ui-preview render -Ponly=board,chat   # some of them
```

It needs JDK 17, no Android SDK, and takes about 3 minutes for all 48 scenes (agents, machines, a machine, a machine's health and cleanup, agent chats, the Coordinator, floating chats, the board and a task, the terminal, settings with its notifications, the dialogs and sheets, and the three panes of a wide window, also across a rotation). CI renders them too and uploads them as the `herdr-screens-<run>` artifact. See [`tools/ui-preview/README.md`](tools/ui-preview/README.md) for how it works and how to add a scene, and [`AGENTS.md`](AGENTS.md) for how changes are reviewed.

## Layout

- `core/`: pure Kotlin/JVM: SSH, herdr API, host sessions, terminal emulator. `./gradlew -p core test`.
- `app/`: the Android app. `ui/theme` and `ui/design` hold its look and shared components ([`docs/DESIGN.md`](docs/DESIGN.md)).
- `tools/ui-preview/`: the screen previews. `./gradlew -p tools/ui-preview render`.
- `host/herdr-push/`: the watcher that runs on a machine and pushes to the phone (Python 3; core carries it as a resource, and its tests run it).
