# Design

How Herdr for Android looks and why. The bar is Puggie (its Android app and the iPad app): a
quiet, monochrome surface where the only colours are the ones that mean something. herdr's own
icon is grey on black, so the app is too.

Everything below lives in code in two places, and screens use nothing else:

- `ui/theme/Theme.kt`: colours (`Herdr.colors`, the Material scheme), type, spacing (`Space`), corners (`Radius`).
- `ui/design/`: the components (`Components.kt`, `Chat.kt`, `NavBar.kt`) and the icon set (`HerdrIcons.kt`).

A screen that needs a number, a colour or a shape that isn't there adds it here first.

## Principles

1. **Surfaces, not lines.** Grouping comes from a slightly raised grey card on the ground, never
   from outlines or dividers. The one border in the app is the red edge of a question an agent is
   waiting on.
2. **Colour means state.** Text and icons are near-black or near-white in three strengths. Colour
   appears only as a status (the board's tones) or as the accent on a link, a selection mark,
   progress or the cursor.
3. **One loud thing per screen.** The primary action is the inverse pill (black on white, white on
   black). Everything else is a grey pill or plain text.
4. **Lists breathe.** Rows are at least 56dp with a leading tile, a title and one quieter line.
   Titles and secondary text never look alike.
5. **Sentence case, plain words.** As before: herdr's own sentences when it refuses something.

## Colour

| Token | Dark | Light | Use |
| --- | --- | --- | --- |
| `ground` | `#000000` | `#FFFFFF` | Screens, bars, the nav bar |
| `card` | `#151515` | `#F3F3F4` | Cards, grouped lists, sheets and dialogs in dark |
| `field` | `#212121` | `#E9E9EB` | Fields, chips, secondary buttons, icon tiles, user bubbles |
| `fieldFocused` | `#2B2B2B` | `#DFDFE2` | A field being typed in, a sheet's handle, a key |
| `line` | `#242424` | `#E6E6E8` | The rare hairline between rows of one card |
| `text` | `#F2F2F2` | `#111111` | Titles and body |
| `muted` | `#A3A3A3` | `#5C5C5C` | Secondary lines, icons, section headings |
| `faint` | `#8C8C8C` | `#666666` | Meta: counts, times, hints (still 4.5:1 on a card) |
| `off` | `#5A5A5A` | `#B0B0B0` | Disabled (not used for text that must be read) |
| `accent` | `#7DD3B6` | `#1E6B58` | herdr's mint: links, selection, progress, cursor, focus |

Sheets and dialogs sit on `card` in dark and on `ground` (white) in light, so they read as raised
in both.

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

Roboto, six sizes, three weights. Nothing else.

| Style | Size / line | Weight | Use |
| --- | --- | --- | --- |
| `headlineMedium` | 28 / 34 | SemiBold | Top-level screen titles (Agents, Machines, Settings), a task's title |
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

The nav bar: Agents (robot), Machines (server), Settings (gear).

## Components

| Component | Anatomy |
| --- | --- |
| `HerdrTopBar` | On the ground. Top level: 28sp title + actions. Detail: back arrow, 20sp title, muted 13sp subtitle, actions. Never changes colour on scroll. |
| `BarIcon` / `BarAdd` | 48dp round targets; `BarAdd` is the screen's one "add", a 38dp inverse circle with + (Puggie's "New bot"). Replaces floating buttons, which covered list content. |
| `HerdrNavBar` | Ground, one hairline above, the selected icon on a `field` pill, 12sp labels, red count badge. |
| `ListRow` | Leading tile (40dp, `field`, 12dp corners, optional status dot cut out of the background), title (16 SemiBold), one muted line, optional extra line, trailing content or chevron. ≥56dp, the whole row is the target. |
| `QuietCard` | `card`, 20dp corners, no border. Holds a group of `ListRow`s (no padding) or content (`padded`, 16dp). |
| `ActionRow`, `MenuRow` | Sheet rows: 22dp muted icon, 16sp label, optional muted second line, chevron for sub-screens, ≥56dp. Destructive rows are red. Every row of a sheet has an icon so labels line up. |
| `SectionHeading`, `MenuSection` | 14sp SemiBold muted, over a group. |
| `PrimaryButton` | Inverse pill, 48dp, 14sp SemiBold. One per screen or sheet. |
| `SecondaryButton` | `field` pill. |
| `QuietButton` | Text only; in a dialog's confirm slot it becomes the primary pill (red if destructive). |
| `DangerButton` | Red pill, white text: only to confirm something that can't be undone. |
| `HerdrChip` | Filter or choice: `field` pill, inverse when selected, 36dp drawn in a 48dp target. |
| `SegmentedPills` | Mutually exclusive choices as one track; wraps into chips at large font sizes. |
| `HerdrField` / `HerdrTextField` | Filled, no outline: `field`, 12dp corners, the label inside, hint or error under it, accent cursor. |
| `SwitchRow` | Label, optional muted line, switch; the whole 56dp row toggles. Switches are monochrome. |
| `StatusBadge` / `StatusLabel` | Dot + label on the tint / dot + muted label. |
| `Pill` | Neutral (`field`, muted 12sp) or a status tone on its tint. 8dp corners. |
| `InfoLine` | Label left (muted), value right. |
| `MonoBlock`, `CodeBlock` | Monospace on `field`, 12dp corners; code scrolls sideways and has a copy icon. |
| `ChatComposer` | A `card` pill with the text and one round inverse button (send, or stop while the agent works); an optional full-width secondary action under it. |
| Chat | Your messages are `field` bubbles on the right (20dp corners, the last one tucked); replies are plain 16sp text on the ground; tool calls fold into a `card`; a waiting question is the one card with a red edge, its choices full-width rows (the current one inverse). |

### Sheets and dialogs

- **Sheet** (`HerdrSheet`, `HerdrMenu`, `ManageSheet`): 28dp top corners, a 32×4 handle,
  `SheetTitle` (20sp title, muted subtitle) on a 24dp margin, then rows inset 8dp so their content
  lines up with the title. Scrolls when long, drags away.
- **Dialog** (`HerdrDialog`): only for confirmations and short forms. 28dp corners, 24dp padding,
  title, muted body, buttons bottom-right: quiet Cancel, then the primary (or red) pill.
- **Board panels** (`BoardPanel`) follow the sheet anatomy, drawn inside the screen.

## States

- **Empty** (`EmptyState`, `StateBlock`): a 56dp round `field` tile with a muted icon, a 16sp
  title, one muted sentence, and the button that fixes it when there is one.
- **Loading** (`LoadingState`, `Spinner`): a 24dp accent spinner and a muted "Reading…" line;
  never a bare progress bar. A running action shows a 3dp accent bar with what it is.
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
