# Herdr for Android

A native Android client for [herdr](https://herdr.dev) hosts: see your agents across machines,
answer the blocked ones, and open any pane as a live terminal.

Kotlin and Jetpack Compose. Light and dark, as the phone is.

## Building

```sh
./gradlew :app:assembleDebug
./gradlew -p core test
```

## Layout

- `app/`: the screens, in Compose
- `core/`: SSH, herdr's API and the work board, without Android
- `docs/`: the design
