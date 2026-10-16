# Design

How Herdr for Android looks and why. herdr-android keeps its own identity: its navigation (Agents,
Machines, Settings), its screens and flows, its layouts (agent and machine cards, the work board's
cards with a status stripe, workspace cards with Terminal buttons, floating "Add" buttons, the
tabbed admin screen) and its own components. This pass changed how those look, not what they are.

Everything below lives in code in two places, and screens use nothing else:

- `ui/theme/Theme.kt`: colours (`Herdr.colors`, the Material scheme), type, spacing (`Space`), corners (`Radius`).
- `ui/design/`: the components (`Components.kt`, `Chat.kt`, `NavBar.kt`) and the icon set (`HerdrIcons.kt`).

A screen that needs a number, a colour or a shape that isn't there adds it here first.

## Puggie as a quality bar, not a model

Puggie (its Android and iPad apps) was the reference for the *level* of polish only. What was
taken from it, as general principles:

- **Restraint**: few colours, and colour only where it means something.
- **Surfaces instead of lines**: grouping by a raised surface, not by outlines and dividers.
- **A spacing rhythm**: one grid, generous rows, consistent margins.
- **A short type scale** with clear weight differences between titles and secondary text.
- **Quiet, short motion**, and finished empty, loading and error states.

What herdr-android does *not* take from Puggie, and how its look differs:

| | Puggie | herdr-android |
| --- | --- | --- |
| Ground | Pure black / pure white | herdr's green-tinted neutrals (`#0F1412` dark, `#F4F7F5` light) |
| Primary action | White pill on black (black on white) | herdr's mint pill (`#7DD3B6` / `#1E6B58`) |
| Accent | Amber, for "needs you" | Mint for actions and selection; "needs you" is the board's red |
| Cards | Grey cards on the ground | Cards one step lighter in dark, white cards on a soft ground in light |
| Lists | Avatar rows (pets), tab pills | Status-dot cards per agent and machine, the board's stripe cards |
| Adding things | A round "+" in the title bar | herdr-android's floating pill ("Add machine", "New") |
| Chat | Plain replies, composer card with buttons inside | Mint bubbles for you, card bubbles for the Coordinator, the agent's replies as text; a field with its Send button beside it |
| Navigation | Bots / Routines / Setup | Agents / Machines / Settings, the admin screen's tabs |

No Puggie screen, layout, component, wording, pet or branding is used.

## Principles

1. **Surfaces, not lines.** Grouping comes from a card one step off the ground, not from outlines.
   Borders are kept for meaning: a question an agent waits on, the selected answer.
2. **Colour means state or action.** Text and icons use three neutral strengths. Colour appears
   as a status (the board's tones), or as herdr's mint on what you can do (primary buttons, links,
   selection, progress, the cursor).
3. **One primary action per screen**: a mint pill or the floating button. Everything else is a
   soft-filled pill or mint text.
4. **Lists breathe.** Cards and rows are at least 48–56dp with a 16dp inset; titles and secondary
   text never look alike.
5. **Sentence case, plain words**, herdr's own sentences when it refuses something.

## Colour

| Token | Dark | Light | Use |
| --- | --- | --- | --- |
| `ground` | `#0F1412` | `#F4F7F5` | Screens and bars |
| `card` | `#171D1A` | `#FFFFFF` | Cards, the nav bar, sheets and dialogs |
| `field` | `#222926` | `#E7EDE9` | Fields, chips, secondary buttons, tiles, code |
| `fieldFocused` | `#2B3330` | `#DCE4DF` | A field being typed in, a sheet's handle, a key |
| `line` | `#232A27` | `#E5EBE7` | The rare hairline (menu groups, the tab row, a repo's header) |
| `text` | `#E4EAE6` | `#141A17` | Titles and body |
| `muted` | `#A7B1AC` | `#55605A` | Secondary lines, icons, section headings |
| `faint` | `#8D9792` | `#5F6964` | Meta: counts, times, hints (still 4.5:1 on a card) |
| `off` | `#56605B` | `#B2BBB6` | Disabled, idle dots (not used for text that must be read) |
| `accent` / `primary` | `#7DD3B6` | `#1E6B58` | herdr's mint: primary buttons, the FAB, links, quiet buttons, selection, progress, cursor |
| `primaryContainer` | `#173F33` | `#CDEDE0` | Selected chips and nav item, your chat bubbles |

Sheets and dialogs sit on `card`, so they read as raised in both themes.

**Status colours** are the board's tones (herdr-projects `docs/colours.md`) and herdr's sidebar
colours, one set for both. Light mode uses deeper versions so they read as text on white.

| Tone | Dark | Light | Means |
| --- | --- | --- | --- |
| red | `#F0857A` | `#B3342A` | Needs you, blocked, failed, destructive |
| amber | `#E2B64A` | `#8A5800` | Working, outdated, minor finding |
| green | `#6CC993` | `#22744A` | Idle, online, ready to approve, passed |
| blue | `#7AA2F7` | `#3260C2` | Done (agent), reviewing |
| teal | `#4DC4B8` | `#0B7268` | Done (task) |
| pink | `#E58FC0` | `#A0407A` | Queued |
| grey | `#8E9692` | `#606567` | To do, stopped, unknown |

A status appears as a dot (7–8dp) or a `StatusBadge` (dot + label on the colour's tint: 16% in
dark, 12% in light). Never colour alone: a dot always has a word next to it or in its row.
`ContrastTest` checks every pair above (text 4.5:1, marks 3:1).

## Type

Roboto, six sizes (24, 20, 16, 14, 13, 12), three weights. Nothing else.

| Style | Size / line | Weight | Use |
| --- | --- | --- | --- |
| `headlineMedium` | 24 / 32 | SemiBold | Top-level screen titles (Agents, Machines, Settings), a task's title |
| `titleLarge` | 20 / 28 | SemiBold | Detail bar titles, sheet and dialog titles |
| `titleMedium` | 16 / 24 | SemiBold | Row and card titles |
| `bodyLarge` | 16 / 24 | Regular | Chat messages, menu rows, form values |
| `titleSmall` / `labelLarge` | 14 / 20 | SemiBold | Section headings (muted), buttons, chips |
| `bodyMedium` | 14 / 21 | Regular | Secondary lines, descriptions |
| `bodySmall` | 13 / 18 | Regular | Bar subtitles, hints |
| `labelMedium` | 12 / 16 | Medium | Badges, meta, counts |

Monospace only for code, paths in tool calls, keys and fingerprints.

## Spacing

A 4/8dp grid: `Space.xxs` 2, `xs` 4, `s` 8, `m` 12, `l` 16, `xl` 24, `xxl` 32.

- Screen side margin 16; card padding 16; sheet and dialog margin 24.
- Between cards 8; between a section heading and its content 8; above a section heading 24 (16
  inside lists).
- Rows: 16 horizontal, 12 vertical; a 40dp leading tile and 12 before the text.

## Shape and elevation

`Radius.s` 8 (badges, code), `m` 12 (fields, tiles, option rows), `l` 20 (cards), `xl` 28
(sheets, dialogs). Buttons, chips, the composer and toasts are pills.

Flat: no shadows, no tonal elevation. Depth comes from the grey steps; the scrim (black 55%)
lifts sheets and dialogs; only the toast has a shadow, because it floats over anything.

## Icons

Material Symbols Rounded, outlined, weight 400 (`HerdrIcons`, drawn from the SVG paths so the APK
stays small). 24dp in bars and the nav bar, 20–22dp in rows and menus, 18dp inside buttons.
Icons in rows are `muted`; a destructive row's icon is red. Arrows mirror in right-to-left.

The nav bar keeps herdr-android's icons: Agents (home), Machines (list), Settings (gear).

## Components

| Component | Anatomy |
| --- | --- |
| `HerdrTopBar` | On the ground. Top level: 24sp title + actions. Detail: back arrow, 20sp title, muted 13sp subtitle, actions (icons, or a mint text action such as "Terminal"). Never changes colour on scroll. |
| `BarIcon` | 48dp round target, 24dp icon, optional red count badge. |
| `HerdrFab` | The screen's "add": a floating mint pill with icon and label ("Add machine", "New", "Add"), or round with only + (the board). Lists leave 96dp at the bottom for it. |
| `HerdrNavBar` | On the card surface, the selected icon on a mint pill, 12sp labels, red count badge. |
| `QuietCard` | `card`, 20dp corners, no border. Agent, machine, workspace, task and board-entry cards; groups of rows. |
| Agent / machine cards | A status dot, the title (16 SemiBold), a status pill or badge on the right; quieter lines indented to the title; count pills; the blocked agent's question in red monospace. |
| Task cards | The board's status stripe (wider when it needs you), key + `StatusBadge` + agent, title, what it is doing, progress bar, the PR in mint. |
| Workspace cards | Dot + name + tab pill + actions; the tab name or tab chips; the layout thumbnail; one row per pane (dot, name · state) with a secondary "Terminal" pill. |
| `ListRow` | Optional leading (a dot or a tile), title (16 SemiBold), one muted line, optional extra line, trailing content or chevron. ≥56dp, the whole row is the target. |
| `ActionRow`, `MenuRow` | Sheet rows. Management sheets lead with a 36dp tinted icon tile (herdr-android's own), menus with a 22dp icon; 16sp label, optional muted line, chevron for sub-screens, ≥56dp. Destructive rows are red. |
| `SectionHeading`, `MenuSection` | 14sp SemiBold muted, over a group. |
| `PrimaryButton` | Mint pill, 48dp, 14sp SemiBold. |
| `SecondaryButton` | `field` pill (Terminal, Retry, Copy, Reload config). |
| `QuietButton` | Mint text; in a dialog's confirm slot it becomes the mint pill (red if destructive). |
| `DangerButton` | Red pill: only to confirm something that can't be undone. |
| `HerdrChip` | Filter or choice: `field` pill, `primaryContainer` when selected, 36dp drawn in a 48dp target. |
| `SegmentedPills` | The New sheet's and the update channel's segmented choice, as one track; wraps into chips at large font sizes. |
| `HerdrField` / `HerdrTextField` | Filled, no outline: `field`, 12dp corners, the label inside, hint or error under it, mint cursor. |
| `SwitchRow`, radio rows | The whole row toggles; switches and radios in mint. |
| `StatusBadge` / `StatusLabel` / `Pill` | Dot + label on the colour's tint / dot + muted label / a small neutral or tinted tag. |
| `InfoLine` | Label left (muted), value right. |
| `MonoBlock`, `CodeBlock` | Monospace on `field`, 12dp corners; code scrolls sideways and has a copy icon. |
| `ChatComposer` | A rounded field on the card surface, and beside it the round mint Send button (or "Stop" while the agent works); the Coordinator's "Send and start agents" under it. |
| Chat | Your messages in mint bubbles on the right; an agent's replies as text with tool calls folded into a card; the Coordinator's replies in card bubbles on the left; a waiting question is a red-tinted card with a red edge, its choices outlined rows (the current one in red). |
| Admin | Plugins / Integrations / Server / Machines as a scrollable tab row with a mint indicator. |

### Sheets and dialogs

- **Sheet** (`HerdrSheet`, `HerdrMenu`, `ManageSheet`): `card`, 28dp top corners, a 32×4 handle,
  `SheetTitle` (20sp title, muted subtitle) on a 24dp margin, then rows inset 8dp so their content
  lines up with the title. Scrolls when long, drags away.
- **Dialog** (`HerdrDialog`): only for confirmations and short forms. 28dp corners, 24dp padding,
  title, muted body, buttons bottom-right: mint-text Cancel, then the mint (or red) pill.
- **Board panels** (`BoardPanel`) follow the sheet anatomy, drawn inside the screen.

## States

- **Empty** (`EmptyState`, `StateBlock`): a 56dp round `field` tile with a muted icon, a 16sp
  title, one muted sentence, and the button that fixes it when there is one.
- **Loading** (`LoadingState`, `Spinner`): a 24dp mint spinner and a muted "Reading…" line;
  never a bare progress bar. A running action shows a 3dp mint bar with what it is.
- **Error**: inline `ErrorMessage` (red tint tile, error icon, one sentence, Retry); full-area
  `ErrorState` (red icon tile, title, sentence, Try again). Notices (`Notice`) use the same tile in
  amber or grey.
- **Offline**: an amber notice "Offline · showing the last known state" over the stale content.

## Motion

Short and purposeful, nothing decorative:

- Navigation: the new screen fades in (200ms) while moving 1/12 of the width; back reverses it.
- Sheets slide up with the platform's sheet motion; dialogs fade in (150ms) and rise 1/10.
- Toasts drop in from the top (200ms) and leave on their own (3s, errors 5s) or on a tap.
- Sections and tool groups expand with `animateContentSize`; the nav pill cross-fades (180ms).
- "Working" pulses a dot (700ms), nothing else moves on its own.

## Accessibility

- Every target is at least 48dp (keys above the terminal keyboard are 52×48).
- Text contrast 4.5:1 and marks 3:1 in both themes (`ContrastTest`).
- Layouts hold at 200% font scale (`FontScaleTest`): rows wrap, segmented choices become chips,
  bars ellipsise their subtitles.
- Icon-only buttons have content descriptions; rows that open something say what
  (`onClickLabel`); headings are marked as headings; toasts are polite live regions.
