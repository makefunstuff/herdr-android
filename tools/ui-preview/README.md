# Screen previews

Renders the app's real screens to PNGs on the desktop JVM, in dark and light, with no Android SDK and no emulator. This is how UI changes are checked and shown in review (see [AGENTS.md](../../AGENTS.md)).

```sh
./gradlew -p tools/ui-preview render                        # every scene → tools/ui-preview/build/shots/
./gradlew -p tools/ui-preview render -Ponly=board,chat      # some scenes (both themes)
./gradlew -p tools/ui-preview render -Ponly=board-light     # one shot
./gradlew -p tools/ui-preview render -Pout=/tmp/shots       # somewhere else
```

Needs JDK 17 and, the first time, network for Gradle to fetch Compose Desktop. Every scene takes about 2.5 s per theme, so all of them take about 2 minutes; nothing runs after it exits. Each scene is saved as `<scene>.png` (dark) and `<scene>-light.png`, 824×1784 px (a 412×892 dp phone at 2×). The same code and data give the same pixels every run. A scene that throws prints `FAIL <name>` and the task fails.

## How it works

- `build.gradle.kts` compiles `app/src/main/kotlin` and `core/src/main/kotlin` as they are, for Compose Multiplatform on the desktop. The androidx libraries the app uses (navigation, lifecycle) have desktop builds under the same package names.
- A few files only work on Android or over real SSH. They are listed in `androidOnly` in `build.gradle.kts` and replaced by stand-ins with the same API in `src/main/kotlin/standins/`:
  - `HerdrApp.kt` (the Application) → `AppContainer` on a preview `Context`;
  - `data/IdentityStore.kt` (Android Keystore) → a fixed demo key;
  - `ui/terminal/TerminalView.kt` (an Android View) → the same grid drawn by the shared `TerminalPainter` on Skia;
  - `ui/common/Attach.kt` (ContentResolver) → no file picker;
  - `core/…/ssh/SshConnector.kt` → connections to the fake machines below;
  - `push/AndroidPush.kt`, `push/AndroidAlerts.kt`, `push/AndroidLive.kt` and `push/WorkService.kt` (UnifiedPush, Android's notifications and Live Updates, the service and alarms) are left out: the container stand-in gives the shared push code `preview/PreviewPush.kt` instead (ntfy installed, notifications and Live Updates allowed, nothing posted);
  - `MainActivity.kt` is left out; the scenes render `HerdrRoot` (`AppNav.kt`), as the activity does.
  - `share/ShareActivity.kt`, `share/CoordinatorTile.kt` and `share/Widgets.kt` (the share target, the Quick Settings tile and the home-screen widgets) are left out: what they open, the `share` and `ask` scenes show. The widgets are Android `RemoteViews`, which Compose on the desktop can't draw.
  - `brief/AndroidBrief.kt` and `brief/AndroidVoice.kt` (the brief's notification and alarm, and text to speech) are left out: the container stand-in gives `PreviewBrief` (nothing posted, not in a car) and `PreviewVoice` (it keeps what would be read, and where, without a sound). The brief runs at the demo's fixed time (`Demo.NOW`, UTC), and devbox and gpu-box answer `herdr-push journal` with `Demo.devboxJournal()` and `Demo.gpuJournal()`.
  - `bubble/AndroidBubbles.kt` and `bubble/BubbleActivity.kt` (a chat floating as an Android bubble: its notification, shortcut and window) are left out: the container stand-in gives `PreviewBubbles` (bubbles allowed unless a scene says `bubblesAllowed = false`, nothing floats), and a scene with `bubble` renders the bubble's window (`BubbleRoot`) instead of the whole app.
- `src/main/kotlin/shims/` has the handful of `android.*` and Android-only androidx calls the shared code makes (`Context`, `SharedPreferences`, `Uri`, `LocalContext`, `BackHandler`, `AndroidView`, `Font(resId)`, `R.font`, `BuildConfig`). The app's own fonts come from `app/src/main/res/font`.
- `preview/FakeMachine.kt` is a herdr machine without SSH: it answers herdr's socket API (`session.snapshot`, `pane.read`, …), the shell commands the app runs (the herdr probe, `session list`, the work board's CLI in `FakeBoard.kt`, the health probe in `FakeHealth.kt`), and the streams it opens (an agent's transcript, a terminal). Everything above it, `HostSession`, `HerdrApi`, the board client, the transcript readers, is the app's real code.
- `preview/FakeCode.kt` is devbox's code: its repositories and checkouts, answering the code browser's commands (`CodeCommands`) with what they printed on a real repository: `code-demo/make.sh` builds a small made-up herdr-android repository with Git (task AND-9's worktree with two commits, a change not committed and a new file) and writes its files and those outputs to `src/main/resources/demo/code`. Run it again after changing `code-demo/files`.
- `preview/FakeQuick.kt` is devbox's quick commands: its commands folders (herdr-android's `.herdr/commands` and `~/.config/herdr/commands`) and the runs in its runs folder, answering the quick commands' scripts (`QuickCommands`) as they print on a real machine; a command started in a scene runs at once with fixed output.
- `preview/Demo.kt` is the data: the README screenshots' demo host (`docs/screenshots/demo-host`). devbox runs three agents and the work board; gpu-box and ci-runner are reached through it; mac-mini refuses the app's key. The transcripts, agent screens and board answers in `src/main/resources/demo/` were written by that demo host's `content.py` and `herdr-projects`. The board's record of a task as the review cards read it (`ApprovalCommands.facts`: the closing note, the checks, `gh pr checks`) is `demo/board/facts-<repo>-<n>.txt`, written by hand; `Demo.gpuBoard()` gives gpu-box a board with a task waiting for a pick.
- `preview/Main.kt` renders a scene: it saves the machines, starts the app's container, waits until every machine has answered, composes `HerdrRoot` in a 412×892 dp window, opens the scene's route, runs its actions, and saves the window with its dialogs and sheets.

## Adding a scene

Scenes are in `preview/Scenes.kt`, in render order:

```kotlin
Scene("board", route = Routes.board(DEVBOX)),
Scene("task", route = Routes.board(DEVBOX), actions = { tapText("Show when each machine last answered") }),
Scene("agent-actions", actions = { longPressText("Speed up /jobs") }),
Scene("key-setup", key = IdentityStore.State.Missing),
Scene("chat-blocked", route = Routes.agent(DEVBOX, API_AGENT), setup = { host(DEVBOX).screens += API_AGENT to "…" }),
```

- `bubble` shows a chat as its floating bubble does (`BubbleRoot`), in place of the app (`bubble*` scenes).
- `widthDp` and `heightDp` set the window (412×892 dp by default); 840 dp wide or more shows the three panes (`wide*` scenes). `resize` changes the window after the actions, as a rotation or a resize does, and the shot is the new size (`rotate-*` scenes).
- `route` opens a screen by its `Routes` entry; `actions` taps what is on screen: `tapText`, `tapLabel` (an icon's content description), `longPressText`, `type`, `typeInto` (a form's field, by its label), and `settle()` to let the app catch up. Scrolling never settles in the desktop harness: a scene that needs what is further down sets a taller `heightDp`. `ctx.data.host(id)` changes a machine while the app runs, and `ctx.refresh(id)` makes the app read it again (see the `notifications` scene).
- `setup` changes the demo data before the app starts: `notes` and `codeView` (comments kept for later and how code is shown, `CodeStore`), `quickPins` (quick commands pinned, `QuickStore`), `push` (the notification settings, `Demo.push()`), `brief` (the brief's schedule and when you last looked, `Demo.brief()`), `pushApps` and `notificationsAllowed` (the phone's side, `PreviewPhone`), `bubblesAllowed` (`PreviewBubbles`), `machines`, `hosts` (each a `FakeMachine`: `snapshot`, `screens`, `transcripts`, `board`, `refuse`, `health`, `journal`, or `on("method") { params -> result }` for one API call), `drafts`.
- Keep the data fixed: no clocks, random ids or files from outside the repo, so a scene renders the same every time.

## When it doesn't compile

A new Android-only call in `app/` or `core/` (a new `android.*` class, Activity API or system service) breaks the preview's build. Either keep it in an Android-only file, add that file to `androidOnly` and give it a stand-in in `src/main/kotlin/standins/` with the same API, or add the missing call to `src/main/kotlin/shims/`. A change to a stand-in's original (say, a new method on `IdentityStore`) needs the same change in its stand-in.
