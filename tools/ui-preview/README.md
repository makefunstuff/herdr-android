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
  - `MainActivity.kt` is left out; the scenes render `HerdrRoot` (`AppNav.kt`), as the activity does.
- `src/main/kotlin/shims/` has the handful of `android.*` and Android-only androidx calls the shared code makes (`Context`, `SharedPreferences`, `Uri`, `LocalContext`, `BackHandler`, `AndroidView`, `Font(resId)`, `R.font`, `BuildConfig`). The app's own fonts come from `app/src/main/res/font`.
- `preview/FakeMachine.kt` is a herdr machine without SSH: it answers herdr's socket API (`session.snapshot`, `pane.read`, …), the shell commands the app runs (the herdr probe, `session list`, the work board's CLI in `FakeBoard.kt`), and the streams it opens (an agent's transcript, a terminal). Everything above it, `HostSession`, `HerdrApi`, the board client, the transcript readers, is the app's real code.
- `preview/Demo.kt` is the data: the README screenshots' demo host (`docs/screenshots/demo-host`). devbox runs three agents and the work board; gpu-box and ci-runner are reached through it; mac-mini refuses the app's key. The transcripts, agent screens and board answers in `src/main/resources/demo/` were written by that demo host's `content.py` and `herdr-projects`.
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

- `route` opens a screen by its `Routes` entry; `actions` taps what is on screen: `tapText`, `tapLabel` (an icon's content description), `longPressText`, `type`, and `settle()` to let the app catch up. `ctx.data.host(id)` changes a machine while the app runs, and `ctx.refresh(id)` makes the app read it again (see the `notifications` scene).
- `setup` changes the demo data before the app starts: `machines`, `hosts` (each a `FakeMachine`: `snapshot`, `screens`, `transcripts`, `board`, `refuse`, or `on("method") { params -> result }` for one API call), `drafts`.
- Keep the data fixed: no clocks, random ids or files from outside the repo, so a scene renders the same every time.

## When it doesn't compile

A new Android-only call in `app/` or `core/` (a new `android.*` class, Activity API or system service) breaks the preview's build. Either keep it in an Android-only file, add that file to `androidOnly` and give it a stand-in in `src/main/kotlin/standins/` with the same API, or add the missing call to `src/main/kotlin/shims/`. A change to a stand-in's original (say, a new method on `IdentityStore`) needs the same change in its stand-in.
