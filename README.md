# Herdr for Android

A native Android client for [herdr](https://herdr.dev) hosts: see your agents across machines, answer the blocked ones, and open any pane as a live terminal. Kotlin, Jetpack Compose and Material 3.

## Getting the app

Every push builds a debug APK on GitHub Actions. Download it from the branch's pre-release under **Releases** (or the `herdr-android-apk` artifact on the run page) and open it on your phone to install. Android will ask you to allow installs from your browser or file manager once.

## Setting up a machine

1. Open **Settings** and copy the app's public key.
2. Add it to `~/.ssh/authorized_keys` on the machine running herdr.
3. In **Machines**, tap **Add machine** and enter `user@host[:port]`. The app pins the host key on first connect.

Machines reachable only through another one can use it as a jump host ("Connect through"). From a machine's menu, **Import herdr machines** adds the machines its herdr already knows (`herdr machine list`).

## How it talks to herdr

The app opens an SSH connection and reaches herdr's socket API through `direct-streamlocal` (falling back to `herdr remote-api-bridge`). herdr answers one request per socket connection, so each call opens a fresh channel; `events.subscribe` keeps one channel open for live updates. Terminals use `herdr terminal session control`.

## Layout

- `core/`: pure Kotlin/JVM: SSH, herdr API, host sessions, terminal emulator. `./gradlew -p core test`.
- `app/`: the Android app.
