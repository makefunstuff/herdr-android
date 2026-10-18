**7 TODOs and 2 FIXMEs**, most in the board screen.

## Board (`ui/board`)
- `BoardScreen.kt:212` TODO: keep the scroll when a filter changes
- `BoardScreen.kt:388` TODO: show the attempt's model next to its letter
- `BoardSheets.kt:96` FIXME: the plan section collapses on every refresh

## Terminal (`core/terminal`)
- `TerminalEmulator.kt:144` TODO: DECSCUSR cursor shapes
- `TerminalEmulator.kt:402` TODO: wide characters at the last column

## Machines
- `MachineCard.kt:37` TODO: say when the machine last answered
- `MachineScreen.kt:118` FIXME: the health card flickers while it reconnects

## Elsewhere
- `Brief.kt:61` TODO: a brief for one machine only
- `HostSession.kt:233` TODO: back off longer after an auth failure

The board has the most: three of them are in code AND-9 touches.
