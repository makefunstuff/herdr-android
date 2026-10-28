# Design

How Herdr for Android looks and why. herdr-android keeps its own structure: its navigation
(Agents, Board, Machines; Settings behind a gear), its screens and flows, its layouts (Needs you cards with their answers, one-line agent rows, machine cards, the
work board's cards with a status stripe, workspace cards with a 48 dp row and a terminal icon per pane, floating "Add"
buttons, the tabbed admin screen) and its own components. Its **visual language** is the
[herdr web UI](https://github.com/devswha/herdr-web-ui)'s (MIT, by devswha): the same palette,
type, icons, shapes and surface rules, carried over to Compose so the phone app looks like it
belongs with it.

Everything below lives in code in two places, and screens use nothing else:

- `ui/theme/Theme.kt`: colours (`Herdr.colors`, the Material scheme), type (`Pretendard`, `Mono`,
  `CodeStyle`), spacing (`Space`), corners (`Radius`), the terminal's colours.
- `ui/design/`: the components (`Components.kt`, `Chat.kt`, `NavBar.kt`), the icon set
  (`HerdrIcons.kt`) and the agents' marks (`AgentMarks.kt`).

A screen that needs a number, a colour or a shape that isn't there adds it here first.

## Credit

The design tokens (every colour, the type scale, spacing, radii and sizes), the surface rules and
the component patterns below come from the herdr web UI's design system (its `DESIGN.md` and the
`:root` block of `src/styles.css`), © devswha, under the MIT licence. The agents' brand marks are
taken from its `AgentMark.tsx` and `agentSvgMarks.ts` (several by way of lobe-icons, MIT; the
marks are their owners'). Icons are [Lucide](https://lucide.dev) (ISC). The faces are Pretendard
and JetBrains Mono (SIL OFL 1.1). The notices are in [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md).

## Identity: a warm terminal

An amber-phosphor console on lamp-lit graphite (dark, the default when the phone is dark) or on
ledger paper (light), with chat-app clarity.

1. **One chrome colour: amber.** It marks focus (a field being typed in), the chosen lens (the
   current screen's nav glyph, the admin tab's underline, an armed terminal modifier), the
   terminal cursor, links, and **your own action**: Send, the primary button, the floating "Add".
   Nothing else is amber.
2. **States carry the other saturated colours**, and none of them is amber: working blue, blocked
   red, done green, idle dim. A state always has its word too.
3. **Tonal surfaces and hairlines**, not heavy borders: a card is one step lighter than the ground
   (`--bg-elevated`) with a 1dp `--border` hairline. Shadows only for what floats: the composer,
   the floating "Add", dialogs, toasts.
4. **Hover and selection are one neutral fill at two strengths**: a pressed row, the chosen chip
   or segment, the agent's current menu choice sit on `--bg-hover`; nothing chosen turns amber.
5. **Sections are parted by space and a dim heading**, never by a rule.
6. **Your chat turns are neutral raised cards**; the agent's (and the Coordinator's) answers are
   plain prose, so a long thread never becomes a wall of colour.

## Colour

The web UI's amber palette, value for value (`ContrastTest.tokensAreTheWebUiTokens` checks them).
Each token keeps herdr-android's name in code; the web UI's is beside it.

| Token (`Herdr.colors`) | Web UI | Dark | Light | Use |
| --- | --- | --- | --- | --- |
| `ground` | `--bg` | `#12100E` | `#EEEAE2` | Screens, the chat canvas |
| `panel` | `--bg-panel` | `#181613` | `#FAF8F3` | Bars, the nav bar, sheets, dialogs, the terminal, code blocks |
| `card` | `--bg-elevated` | `#211E1A` | `#F2EEE6` | Cards, chips, secondary buttons, tracks, your chat turns |
| `hover` | `--bg-hover` | `#2A2621` | `#E8E2D6` | Pressed and chosen rows, chips and segments; icon tiles; inline code |
| `input` | `--bg-input` | `#1C1916` | `#FFFDF9` | Text fields |
| `line` | `--border` | `#2D2924` | `#DCD4C6` | Hairlines: cards, fields, bars, buttons, chips |
| `lineStrong` | `--border-strong` | `#3E3830` | `#C5BAA8` | The prompt card's edge, keycaps, a sheet's grip, a chosen chip |
| `text` | `--text` | `#D8D0C3` | `#2A251F` | Body and row text |
| `strong` | `--text-strong` | `#F2EBDF` | `#16120D` | Titles, a chosen segment, emphasis, field values |
| `muted` | `--text-dim` | `#9B9183` | `#685E52` | Secondary lines, icons, section heads, meta |
| `accent` | `--accent` | `#F0A830` | `#8C5000` | Amber that reads as text: links, the chosen lens, focus, the cursor |
| `primary` / `onPrimary` | `--primary` / `--primary-text` | `#F0A830` / `#1B1407` | `#C57D12` / `#1B1407` | The fill of your own action, with ink text |
| `off` | (55% opacity) | `#615A51` | `#ADA496` | Disabled controls (not text that must be read) |
| `notice` | `--notice` | `#E8C55C` | `#7A5A00` | A warning: offline and stale, outdated, a minor finding |
| `scrim` | `--scrim` | `rgba(8,6,4,.55)` | `rgba(40,32,22,.35)` | Under sheets and dialogs |

`accentTint` (13% / 10%), `dangerTint` (12% / 10%) and `tint(state)` (14% / 12%) are the web UI's
named tints. In light, `accent` is the darker text-safe ochre and `primary` the brighter fill that
carries ink, as in the web UI.

### States

| State | Web UI | Dark | Light | Agent | Board |
| --- | --- | --- | --- | --- | --- |
| Working | `--status-working` | `#6CB8D6` | `#155A72` | working | working, reviewing |
| Blocked | `--status-blocked` | `#FF7B70` | `#A82323` | needs you (blocked) | asks you, plan ready, pick one, review failed, needs a login, stopped, went wrong |
| Done | `--status-done` | `#93C36B` | `#2F6317` | done | ready to approve, done |
| Idle | `--status-idle` | `#9B9183` | `#685E52` | idle, other | to do, queued, archived |

Code takes the web UI's `--syntax-*` colours (`HerdrColors.syntax(role)`): keywords `#E8875F` /
`#A33A17`, strings `#A8C17C` / `#4B6B18`, numbers and literals `#D6A0C9` / `#8A3F7A`, types
`#6CB8D6` / `#155A72`, variables and keys `#E0C08F` / `#7A5418`, meta (annotations, attributes)
`#B49DD6` / `#5D4791`; comments are `muted`, functions `accent`. Line numbers are `muted`
(`--line-number`); a marked or chosen line sits on `accentTint` with its number amber. A diff's
added and removed lines sit on `lineTint` (the done and blocked colours at 10%, a little under a
badge's tint so a dim comment keeps 4.5:1), with a `+` or `−` in that colour; a hunk's heading on
`hover`. Every syntax colour holds 4.5:1 on all of these (`ContrastTest`).

A chart's series (a machine's RAM and CPU over the hour) take `chart1` (blue) and `chart2`
(orange): not states and not amber, checked for colour-blind separation and 3:1 on `card` in both
themes. The second series is also dashed, and a legend names both.

The board's task statuses fold onto the same four (`HerdrColors.tone`), so a task and an agent
in the same state look the same; the board's written status always tells them apart. A machine's
dot follows the web UI's PC dot: done = connected, working = connecting, blocked = error, idle =
paused. Red (`red`) is also the colour of destructive actions.

A state appears as a dot (6–8dp), as a `StatusBadge` (dot and word in the state's colour on its
tint, `--radius-sm`; idle dim on `--bg-elevated` with a hairline), or as coloured words beside a
name. Never colour alone.

## Type

The web UI's faces, bundled (`res/font`, Latin subsets; other scripts fall back to the phone's):
**Pretendard** for the interface and prose, **JetBrains Mono** for code, paths, keys, task keys,
fingerprints and the terminal. Its scale, in sp so it follows the phone's font size:

| Style | Size / line | Weight | Web UI | Use |
| --- | --- | --- | --- | --- |
| `headlineMedium` | 22 / 27.5, −0.02em | SemiBold | `--fs-display` | Top-level screen titles (Agents, Board, Machines) |
| `headlineSmall` | 18 / 23.4, −0.01em | SemiBold | `--fs-xl` | A task's title, Markdown h1 |
| `titleLarge` | 16 / 21.6, −0.01em | SemiBold | `--fs-lg` | A detail bar's title, sheet and dialog titles, state titles, Markdown h2 |
| `titleMedium` | 14 / 21 | Medium | `--fs-md` | Row and card titles |
| `titleSmall` | 13 / 18 | SemiBold | `--fs-sm` | Section heads (dim), "Needs you" |
| `bodyLarge` | 15 / 24 | Regular | `--fs-chat` on `--lh-prose` | Chat prose, your turns, menu rows, field values |
| `bodyMedium` | 14 / 21 | Regular | `--fs-md` | Body and dialog copy |
| `bodySmall` | 12 / 18 | Regular | `--fs-xs` | Subtitles, hints, the place under a title |
| `labelLarge` | 13 / 18 | Medium | `--fs-sm`, `--fw-medium` | Buttons, chips, segments |
| `labelMedium` | 12 / 16 | Medium | `--fs-xs` | Badges, pills, counts, nav labels |
| `labelSmall` | 11 / 14, +0.06em | SemiBold | `--fs-2xs`, caps | Field labels and menu headings, in capitals |
| `CodeStyle` | 13 / 20 | Regular | `--font-mono`, `--lh-code` | Code, the terminal (its own size, pinch to change) |

Bold text is semibold in `strong`, as the web UI's `strong`; inline code is 0.87em of its line.

## Spacing, size and shape

`Space`: the web UI's 4dp grid, `xs` 4, `s` 8, `m` 12, `l` 16, `xl` 20, `xxl` 24, `xxxl` 32.
Screen margin 16, card padding 16, sheet margin 20, between cards 8, above a section head 24.

`Radius`: `s` 6 (badges, keycaps, segments), `m` 8 (buttons, fields, rows, tiles), `l` 12 (cards,
menus, code blocks, chat turns), `xl` 16 (sheets, dialogs, the prompt card), `xxl` 24 (the
composer). Pills (chips, neutral tags, the floating "Add", Send) are fully round.

Sizes from the web UI's coarse-pointer rules, grown to Android's 48dp: bars 56dp (`--header-h`
52px), every target ≥48dp, rows 48–56dp (`--row-h`), icons 20dp (`--icon-size` 18px, one step up
for a phone), agent marks 22dp (`--mark-size`), dots 7dp (`--dot-size`), hairlines 1dp.

A window 840dp wide or wider (`WIDE`: a tablet, an open foldable, a large or desktop window) shows
three panes on `ground`, parted by 1dp `line` hairlines: the agents list with the nav bar under it
(27% of the width, 256–360dp), the screen that is open (the rest), and the work board (30%,
280–420dp; left out when no machine has one). What the middle shows is chosen in the list on the
`hover` fill with a `lineStrong` edge, as a chosen row is. Narrower windows show one screen at a time.

## Icons

**Lucide** (`HerdrIcons`), the set the web UI uses: generated from lucide-react's icon nodes into
`ImageVector`s at Lucide's own geometry (24-unit grid, 2-unit round strokes, no fill), named as
Lucide names them (`Trash2`, `SquareTerminal`, `MessagesSquare`…). 20dp in bars, rows, menus and
the nav bar; 18dp in notices, chevrons and checks; 16dp inside chips. Icons are `muted` in rows and
`text` in bars; a destructive row's icon is red; the chosen nav glyph is amber. Arrows and chevrons
mirror in right-to-left. `IconsTest` keeps every icon a Lucide stroke icon.

**Agent marks** (`AgentMark`): where the web UI draws a coding agent's brand mark, in the leading
cell of agent rows, so does the app: the Agents list's cards and a workspace's pane rows. claude,
codex, pi, opencode, omp, cursor, amp, copilot, droid, gemini and grok have their marks (codex,
cursor, pi, copilot and grok in the text colour, the rest in their brand colours); any other agent
gets its initial in a quiet disc; a shell pane gets Lucide's terminal glyph.

## Surfaces

| Surface | Fill | Edge | Shadow |
| --- | --- | --- | --- |
| Screen | `ground` | | |
| Top bar, nav bar, bottom action bar | `panel` | hairline on the content side | |
| Card (`QuietCard`) | `card` | `line` hairline, `Radius.l` | |
| Sheet (`HerdrSheet`, `HerdrMenu`, `ManageSheet`, `BoardPanel`) | `panel` | `Radius.xl` top corners | scrim |
| Dialog (`HerdrDialog`) | `panel` | `line` hairline, `Radius.xl` | `--shadow-pop` |
| Composer | `card`, floating over the transcript's faded end | `line`, amber while typing; `Radius.xxl` | `--shadow-card` |
| Floating "Add" | `primary` | | `--shadow-card` |
| Toast | black (the web UI's in-app alert, both themes) | pill | yes |
| In-app alert (`AlertPill`) | black like the toast, with the agent's mark; one line, drops from the top, a tap opens it, gone after 5 s | pill | yes |
| Code block | `panel` | `line`, `Radius.l` | |
| Code viewer, diff | `panel` | | |
| Terminal | `panel` (`--term-bg`) | | |

## Components

| Component | Anatomy |
| --- | --- |
| `HerdrTopBar` | `panel`, hairline under it. Top level: 22sp strong title + actions. Detail: back arrow, 16sp strong title, the place under it in 12sp dim, actions (icons, or a text action such as "Terminal"). A pane (the board on a wide window): the detail bar without the arrow. |
| `BarIcon` | `.icon-button`: 48dp target, 20dp glyph in `text`, pressed on `hover` with `Radius.m`; optional red count. |
| `HerdrFab` | The screen's "add": an amber pill (`primary`, ink text), icon and label, or round with only +. Lists leave 96dp for it. |
| `HerdrNavBar` | `panel` with a hairline above; the chosen screen's glyph amber on a `hover` pill, its label strong; the others dim. |
| `QuietCard` | `card` + hairline, `Radius.l`, no shadow. Agent, machine, workspace and task cards, a machine's place tiles; groups of rows. |
| Agent cards | The agent's mark, what it works on (14sp medium, strong), under it the agent and where (12sp dim), its `StatusBadge` at the right; a blocked agent's question in red mono. |
| Task cards | The board's status stripe (wider when it needs you), the key in mono, `StatusBadge`, agent; title; what it is doing; progress; the PR as an amber link. |
| Workspace cards | Dot + name + tab pill + `⋯`; tab chips; layout thumbnail; one row per pane: the agent's mark (or the terminal glyph), name · state in its colour, a secondary "Terminal" button. |
| `ListRow` | Optional leading cell, title (14sp medium), one dim 12sp line, trailing content or a chevron. ≥56dp. |
| `ActionRow`, `MenuRow` | `.row-sheet-item`: a dim 20dp icon, the 15sp label, an optional dim line, chevron for sub-screens; ≥48–52dp; pressed or chosen on `hover` (chosen with an amber check). Destructive rows are red. |
| `SectionHeading` | 13sp semibold dim, 24dp above. `MenuSection` and `FieldLabel`: 11sp semibold dim capitals. |
| `PrimaryButton` | `.btn-primary`: `primary` fill, ink text, `Radius.m`, 48dp, 13sp medium. |
| `SecondaryButton` | `.btn`: `card` fill, hairline, strong text. |
| `QuietButton` | `.btn-ghost`: no fill, `text` (or `accent` for a link). In a dialog's confirm slot it becomes the primary button (the danger one if red). |
| `DangerButton` | `.btn-danger`: `dangerTint` fill, red hairline, `dangerText`. |
| `HerdrChip` | Neutral pill: `card` + hairline; chosen: `hover` + strong hairline + strong text. 34dp drawn in a 48dp target. |
| `SegmentedPills` | `.segmented`: one `card` track with a hairline, `Radius.m`; the chosen segment on `hover` in strong text, the rest dim. Wraps into chips at large font sizes. |
| `HerdrField` / `HerdrTextField` | `.field`: the label over it in dim capitals, the box on `input` with a hairline that turns amber while focused, `Radius.m`, strong 15sp text, the hint or error under it. |
| `SwitchRow` | Strong label, dim line, a switch with the amber track when on. |
| `StatusBadge` / `StatusLabel` / `Pill` | State word on its tint / dot + dim word / `.pill`: dim text in a hairline pill. |
| `MonoBlock`, `CodeBlock` | JetBrains Mono in a hairline box (`ground` or `panel`), code scrolls sideways and has a copy icon. |
| `ChatComposer` | The web UI's input card (`.composer-surface`), one component for both chats: `card`, hairline (amber while typing, no underline), `Radius.xxl`, `--shadow-card`. The message box is a row of its own (16sp, `--fs-input`), growing from 1 line to 8 (never past 40% of the room) and then scrolling; under it ONE row of controls: on the left the attach button (`ComposerIconButton`, Lucide `Plus`, "Attach files", a dim 20dp glyph in a 48dp target), on the right a dim status text ("Sending…", "Not connected") and ONE round button, a 32dp circle in a 48dp target, the same place for every state: Send (amber, `ArrowUp`, faded while the draft is empty), Stop (strong, a square; while the agent works and nothing is typed), a progress ring while a message is on its way (the text stays in the box, dimmed). Attach opens a menu (`AttachMenu`: Take a photo, Photos, Latest screenshot, File). A file is copied over SSH to `~/.cache/herdr-android/uploads/<stamp>/` on the agent's machine (`Attachments`, `HostSession.upload`) and an `@path` mention goes at the start of the draft, as the web UI does; while it copies, the status says "Uploading file…" and Send waits. Pictures go to the card's tray instead (below), and with pictures Send works with no words. Both chats put a mic beside the round button (`ComposerMic`, Lucide `Mic`, "Record a voice message", a dim 20dp glyph like attach): held, it hears until the finger lets go, and sliding 96dp to the left cancels; a quick tap starts it hands-free and the next tap finishes. While it hears, the mic is a `red` 32dp circle (the Mic glyph while held, a square while tapped) with a soft `red` ring that swells with your voice; the message box gives way to the words as they come (`bodyLarge`, "Listening…" in `muted` before the first), and the left of the controls to a pulsing `red` dot, the time in mono (`0:07`) and what letting go does ("‹ Slide to cancel" in `muted`, "Let go to cancel" in `red`; tapped: an X to cancel and "Tap the mic to finish"); Send stays faded, a ring in the mic while the last words become text; the controls the screen adds on the right (the Coordinator's Start agents) step aside meanwhile. The words then go into the draft where the cursor stands (a space on either side, the cursor after them; `ComposerCursor`, `Dictation.insert`) to check and edit, under a `VoiceTake` line (an amber Mic, "Voice message · 0:07", "Check the words, then send" in `muted`, an X that takes them out). The phone's own speech recognition does it, on the phone where it can, in the language it has closest to the app's or the phone's, else its own (`ui/common/Mic.kt`, `Hearing`); the microphone is asked for on first use, and while Android's question is up a card over the composer (`MicNotice`, an amber `Mic`, "Allow the microphone") says why it is needed; refused, the card (`MicOff`, "The microphone is off") says so again, with **Allow** or, once Android won't ask again, **Open settings**. What went wrong while it heard (nothing heard, no network, busy) is the same card with `CircleAlert` in `notice` and **OK**, and goes by itself after 8 seconds. No mic on a phone without speech recognition. Enter is a new line; Ctrl/⌘+Enter on a hardware keyboard sends; Send gives a light haptic. Placeholder in `muted` ("Message claude…", "Tell the Coordinator…"); cursor and selection handles amber. Drafts are kept per chat (`DraftStore`) across navigation and restarts. |
| Picture tray (`TrayRow`) | The pictures going with the message, inside the composer's card over the message box: 72dp tiles (`Radius.m`, `hover` under, hairline), the picture cropped to fill, a × on the corner (a 22dp `strong` circle with a `ground` glyph, 32dp target). Over a tile on a 45% black veil: a spinner while it is copied, a white clock while it waits (its turn, or the machine), a red `CircleAlert` when it couldn't be (a tap tries again). A tap otherwise opens the editor. In the chat, your turn shows the pictures it names over its words: one at its shape within 220×300dp, several as 88dp squares; a tap shows it full size (`ImageViewer`: `ground`, a `panel` bar at 92% with ×, the name and its size; pinch and double-tap zoom). |
| Image editor (`ImageEditor`) | Full screen on `ground`: a `panel` bar (×, "Picture" over the name and size, Undo, the bin, the primary **Done**), the picture as large as fits, and a `panel` tool bar: Crop, Draw, Arrow, Highlight as glyph-over-word buttons (the chosen one on `hover` in strong text, as a chosen chip; never amber), and over them the chosen tool's colours (six 28dp swatches in 48dp targets, the chosen one ringed in `strong`) or what it does. Marks are drawn in the picture's own colours (red, yellow, blue, green, white, black: bright marks on a screenshot, not the app's palette), thickness a fixed part of the picture's long side so they look the same once sent; the highlighter at 40%. Cropping dims what goes, with a hairline frame, thirds, and thick corners to hold. |
| `ComposerToggle` | A switch in the composer's row beside Send, drawn as a 32dp pill in a 48dp target: off, a hairline and dim text; on, amber like `SwitchRow`'s track (`accentTint` fill, `accent` edge, glyph and words). The Coordinator's **Start agents** (the board's Tab): Send then reads "Send and start agents" to a screen reader, and the switch turns off once that message is sent. |
| `ChatSurface` | A chat's body as on the web UI's phone layout: the transcript (a reversed list, so it stays at its end when the keyboard opens or the box grows) with the composer floating over its end; the region over the card fades the transcript into `ground`, and the list leaves the composer's measured height free under its newest line. A phone on its side with the keyboard up puts the bars aside while you type (`typingInShortWindow`). |
| Chat | Your turns: neutral `card` bubbles (hairline in light), `Radius.l`, on the right. One sent by voice has a 12dp Mic and "Voice message" in `labelSmall` under its words (the phone keeps which they were, `VoiceStore`). Answers: plain prose. Tool calls: the web UI's work block, one quiet row each (state, verb, object in mono, a short path from the repo with `+12 −2` in `done`/`red`), a hairline under the run. An edit opened shows its diff, not its arguments: a `panel` block with a hairline and `Radius.l`, the file's path in 12sp mono, then its lines wrapped, removed on a `red` tint and added on a `done` tint with a `−`/`+` in those colours, coloured as code; past 15 lines it shows 12 and an accent **Show all N lines**. Each turn's work folds under one header row (`▸ Worked for 2m 36s · 1 edit · 1 command`, `bodySmall` in `muted`, a hairline under it while folded; the turn at work behind a breathing `working` dot, its title in `working` and medium weight; **Needs you** behind a still `red` dot; `· 1 failed` in `red`, never cut), a `labelMedium` time over it. Narration inside a settled turn's open work is quiet (`bodySmall`, `muted`). "While you were away" is a `QuietCard` docked under the status strip (History icon, `titleSmall` title, an accent **Hide**, bullet lines in `bodyMedium`); "2 new messages" is a `card` pill with a hairline and a shadow, centred over the composer. |
| Password card (`PasswordCard`) | Over the terminal's keys while the terminal asks for a password: `card`, a `lineStrong` hairline, `Radius.xl`, `--shadow-card`; Lucide `Lock` and *Asks for a password* in `notice` (`labelMedium`), the line that asks in `CodeStyle` `text`, an X (*Type it in the terminal instead*) that puts it away until the terminal asks again; then a masked `HerdrField` (*Password*, an `Eye`/`EyeOff` to show it) and **Send**. A polite live region, so TalkBack reads the ask as it comes. |
| Text size (`TextSizeCard`, Settings) | *Chats* and *Terminal*, each a `titleMedium` name, a − and a + (36dp `hover` circles in 48dp targets, dim at the ends) round its value in `labelLarge` (*130%*, *14*), and a line of each at that size under it: chat prose in `bodyLarge`, `$ git status` in mono on `panel`. |
| Composer extras (`ComposerParts.kt`) | Over the agent chat's composer, in its `above` slot, top to bottom: the held messages (`HeldList`: `card`, a `line` hairline (`accent` once the agent is ready), `Radius.xl`, `--shadow-card`; a caption in `labelMedium` behind Lucide `Hourglass`, *Held until claude is ready* in `muted`, *claude is ready: review and send* in `text` with the glyph and **Send now** in `accent`; a row per message, its words quoted in `bodyLarge` `strong`, a quiet **Send now** and an X named Discard; at most about two and a half rows, then they scroll; folded to the caption while a prompt card shows), the prompt card, the key row, and the quick replies (`QuickReplyRow`: neutral pills, `card` with a hairline, `labelLarge`, one row that scrolls sideways, a dim `Pencil` at its end). The `/` and `@` menu (`CompletionMenu`, the composer's `completion` slot, over all of them): the same card, a small-caps heading in `muted` (*CLAUDE COMMANDS*, *FILES IN HERDR-ANDROID*), 48dp rows: the command or the file's name in mono `strong`, its description or folder in `muted`; a note in their place while they load or when none match. The model pill (`ModelPillButton`) sits after **+** in the controls row: a 32dp `hover` pill in a 48dp target, the context ring (`ContextRing`, 14dp: a `text` arc at 22% for the track, the fill in `text`, `notice` from 80%) and *Opus 5.5 · 62%* in `labelLarge`; the model by name only where its id follows the vendor's regular naming (`ModelUse.name`), else the id. Its sheet (`ModelSheet`): the model as the title, the ring and *Context 62% full*, a 6dp bar (`accent`, `notice` from 80%), `InfoLine`s, and a **Usage and limits** row. |
| Prompt card (`PromptCard`) | Docked over the composer (in its `above` slot, at most 60% of the room; the words scroll, the buttons stay). `card`, `lineStrong` hairline, `Radius.xl`, `--shadow-card`; "Needs you" is its one red, the action tag at the right (a `Pill`: red when it can't be undone, `notice` when it reaches outside the checkout, neutral otherwise); the question in strong prose; Claude's tool header as a dim label over the command's mono box. A permission's choices are 48dp buttons: Yes the primary one, then secondary buttons with a Lucide glyph (fingerprint on a guarded "always", `Reply` for "No, tell it…", `Pencil` Amend, `X` Cancel), three to a row. A question's options are flat rows with a keycap (a box to tick in a multiple choice), the agent's current one on `hover`; step chips over it. Folded to one 52dp line (`PromptBar`) while you type. |
| Lock screen | The app lock: the ground, an `accentTint` circle with the `Lock` glyph, "Herdr is locked" (`titleLarge`), a dim line, the primary Unlock with the fingerprint glyph. |
| `CodeLines` | The code viewer's and the diff's lines (`ui/code`): a right-aligned `muted` number column (two for a diff, old and new), the code in JetBrains Mono at the size you set (9–22sp, 1.5 line height), coloured by role; wrapped, or the whole list scrolled sideways. A tap on a number (or a long press on the line) chooses it, a tap on another chooses the lines between; then a `BottomActionBar` says which, with Copy and the primary **Comment**. |
| Terminal | `panel` ground, `text`, amber cursor, xterm's default ANSI colours, any text held at 4.5:1 on its background (`TerminalPalette.minimumContrast`, as the web UI's xterm `minimumContrastRatio`); keys are `.key`s: hairline, `Radius.s`, an armed modifier on `card` with an amber edge. |
| Admin | Plugins / Integrations / Server / Machines as a scrollable tab row on `panel`, the active tab underlined in amber. |

### Sheets and dialogs

- **Sheet**: `panel`, 16dp top corners, a 36×4 grip in `lineStrong`, `SheetTitle` (16sp strong
  title, dim line) on a 20dp margin, then rows inset 8dp. Scrolls when long, drags away.
- **Dialog**: only for confirmations and short forms. `panel`, hairline, 16dp corners, 20dp
  padding, title, dim body, buttons bottom-right: ghost Cancel, then the primary (or danger) one.

## States

- **Empty** (`EmptyState`, `StateBlock`): a 52dp round `card` tile with a hairline and a dim icon, a
  16sp strong title, one dim sentence, and the button that fixes it when there is one.
- **Loading** (`LoadingState`, `Spinner`): a 24dp dim spinner on a hairline track and a dim
  "Reading…" line. A running action shows a 3dp amber bar (your action in flight) with what it is.
- **Error**: inline `ErrorMessage` (danger tint, red icon, one sentence, Retry); full-area
  `ErrorState`. `Notice`s use the same shape: a state's tint, or `card` with a hairline when neutral.
- **Offline**: a `notice` "Offline · showing the last known state" over the stale content.

## Motion

Short and purposeful, as the web UI's: navigation fades and slides (200ms); sheets rise with the
platform's sheet motion; dialogs fade in (150ms); toasts drop in from the top; sections expand with
`animateContentSize`; the nav pill cross-fades (180ms, `--dur-base`). "Working" breathes a dot,
nothing else moves on its own.

## Accessibility

- Every target is at least 48dp (terminal keys 52×48).
- Text 4.5:1 and marks 3:1 in both themes, on every surface text sits on (`ground`, `panel`,
  `card`, `hover`, `input`) and on the state tints (`ContrastTest`). The terminal keeps any cell's
  text at 4.5:1 whatever colours the program picks.
- Layouts hold at 200% font scale (`FontScaleTest`, `DesignSystemTest`): rows wrap, segmented
  choices become chips, bars ellipsise their subtitles; all type is in sp.
- Icon-only buttons have content descriptions; rows that open something say what
  (`onClickLabel`); headings are marked as headings; a field is named by its label; toasts are
  polite live regions; states are words, not only colours.
- Nothing is only behind a long press. A card or row that has a long-press sheet says so
  (`onLongClickLabel`, "double-tap and hold to show more actions") and offers the sheet's actions
  as TalkBack custom actions on itself: a question's card its answers (*Answer yes*, *Answer no*,
  or the menu's two), *Reply…*, *Open terminal*, *Why this state?*, *Mute notifications* and
  *More actions* (the sheet); an agent's row its triage, then the same; a board card its board
  actions and its review card. The previews draw them as TalkBack's Actions menu
  (`agent-talkback`, `agent-line-talkback`, `task-talkback`).
- Back is predictive (target SDK 36, `enableOnBackInvokedCallback`): the app's own back steps are
  Compose `BackHandler`s, never `onBackPressed`.
- Haptics confirm what was given away: an answer to an agent (card, chat), a password, an approval
  (approve and merge, approve plan, approve anyway), a message sent (`confirmTap`: the phone's
  CONFIRM, a key's tap before Android 11). Nothing else buzzes.
- Chats and the terminal have their own text sizes (Settings → **Text size**, `TextSizeStore`), on
  top of the phone's font scale: the chats' scale (85–150%) applies to a chat's transcript and
  composer (`ChatSurface`), not its bars, and multiplies what the phone's own font scaling makes of
  each size (`withTextScale`: Android 14's curve, where large text grows less, is kept; at 100% a
  chat reads as the rest of the app); the terminal's size is in sp (5–24, pinch sets it too).
- A terminal that asks for a password (`PasswordPrompt`: what stands before the cursor ends in a
  colon after *password*, *passphrase*, *passcode* or *PIN*, and no shell or TUI prompt stands
  before that word) gets a masked field (`PasswordCard`) over its keys. Dots, the keyboard's
  password mode, an eye to show it; Send types it with Enter. It is kept nowhere, and it never
  takes the keyboard by itself. An agent's chat has none: what you type there goes to the agent's
  own input box, not to a command it runs (its ⋯ has *Open terminal*).
