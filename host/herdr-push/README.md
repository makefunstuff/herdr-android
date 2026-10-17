# herdr-push

A small watcher that runs on a machine with herdr and tells your phone when something there needs you, also while Herdr for Android is closed:

- an agent becomes **blocked** (it asks something: a permission, a question) or **finishes**;
- a work board task starts **waiting for you**: ready to approve, review failed, plan ready, pick one, asks you, needs a login, stopped at a limit, went wrong.

When that is over (answered on the desktop, the agent back at work, the task moved on), it says so too, and the phone takes the notification down.

It also looks at the machine itself every 30 seconds (`HERDR_PUSH_HEALTH_EVERY`, 0 for never): when RAM runs low (under 10% available) or a disk does (less than a tenth free, at most 20 GB), it tells the phone once, naming the three processes using the most memory, and again when there is room (15%, one and a half times the disk's mark). The readings of the last hour go to `health.json` for the app's chart.

It also tells the phone when an **AI quick command** the app started there ends (done, failed, or at its time or token limit; not one stopped by hand), with the start of its answer: it looks at the runs in `~/.local/state/herdr-android/quick/` (`HERDR_PUSH_QUICK` for another folder; see [herdr-quick](../herdr-quick/README.md)), once each, and only at runs that ended in the last hour. A tap opens the run in the app.

It also keeps a **journal** of the last week for the app's brief: each time a board task is done, starts waiting for you, goes wrong, starts work or is added, and each time an agent finishes or asks something, with the time and the task or agent as it was then (`journal.json`, at most 600 entries per herdr session; `HERDR_PUSH_JOURNAL_DAYS` changes the 8 days). The first look only learns how things stand, as for the pushes. The app reads it with `herdr-push journal --session <name> --since <seconds>`, which answers `{"ok": true, "now", "from", "upto", "entries": [...]}`: `from` is when the journal began (null before any look), `upto` its last look, so the app can tell a journal that stopped keeping track.

For a phone with **Live Updates** on, it also sends what runs: the working agents and the running board tasks (stage, progress, minutes left, the review round and the most reviews there are), which the phone shows as Live Updates with a Stop button. That push goes out at once when something starts, stops or moves on a stage, with progress and the "doing now" line at most every 90 seconds (`HERDR_PUSH_LIVE_MINOR`), and again every 30 minutes while anything runs (`HERDR_PUSH_LIVE_KEEP`), so the phone knows the machine still watches. A phone without Live Updates gets none of these.

The phone shows each one as a notification with buttons: **Approve** and **Deny** for an agent's yes-or-no question, **Reply** (to the agent, to the task's agent, or to the Coordinator about the task), **Approve and merge**, **Approve plan**, **Continue** or **Retry**. A tap opens the agent's chat or the task on the board. The buttons work over the app's own SSH connection, never through the push.

It is one Python 3 file (`herdr-push`), standard library only, and needs nothing installed besides `python3` (3.6 or newer).

## Setting it up

The app does it for you:

1. On the phone, install a [UnifiedPush](https://unifiedpush.org) push app. [ntfy](https://ntfy.sh) is free and needs no account ([F-Droid](https://f-droid.org/packages/io.heckel.ntfy/), [Play](https://play.google.com/store/apps/details?id=io.heckel.ntfy)).
2. In Herdr, open **Settings → Notifications** and turn on **While the app is closed**. Allow notifications when Android asks. The app asks the push app for an address.
3. Every machine the app knows is set up as it connects: the app copies `herdr-push` there over SSH, subscribes the phone (its push address and a key made for that machine), and starts it so it also runs after a reboot. Settings shows each machine's state; **Test** sends a test notification from it. The switch next to a machine leaves it out (the app unsubscribes the phone there; with no phone left, herdr-push stops and removes its start-up entry).

A machine that is offline is set up the next time the app connects to it. Machines reached through a jump host work the same way.

The app also **looks at its machines itself** while it is closed (every 15 minutes by default; Settings → Notifications → The app also looks), with an exact alarm. That covers a machine without python3, or a push that got lost. Pushes arrive within seconds; the app's own looks only as often as you set.

## What it puts on the machine

| Path | What |
| --- | --- |
| `~/.local/share/herdr-push/herdr-push` | the watcher (the app replaces it when the app carries a newer version) |
| `~/.config/herdr-push/subscribers.json` | the subscriptions, one per phone and machine in the app (two machines in the app on this host, say two herdr sessions, are two): each one's push address, key and herdr session (mode 600) |
| `~/.local/state/herdr-push/` | `state.json` (what it saw last, so a restart doesn't repeat itself), `journal.json` (the last week of what tasks and agents did, for the brief), `health.json` (the last hour of RAM, swap and CPU readings), `herdr-push.log`, `run.lock` |
| `~/.config/systemd/user/herdr-push.service` | Linux with systemd: a user service, `Restart=always` |
| `~/Library/LaunchAgents/dev.herdr.push.plist` | macOS: a launchd agent |
| an `@reboot` line in your crontab | elsewhere, when there is `crontab` |

On Linux, a user service stops when your last login ends unless lingering is on. herdr-push turns it on (`loginctl enable-linger`) where the system lets you; where it doesn't, the app's Settings say "it stops when you log out", and an admin can run `loginctl enable-linger <you>`. Without any of these, the app starts herdr-push again whenever it connects.

## By hand

```sh
P=~/.local/share/herdr-push/herdr-push
python3 $P status          # running?, start-up entry, phones, last error
python3 $P once --dry-run  # one look: what it would send now (the first look only learns how things stand)
python3 $P test            # a test notification to every phone
python3 $P journal         # what it kept for the brief (--since <unix seconds>)
python3 $P ensure          # start it if it isn't running
python3 $P install         # start it now and with the machine
python3 $P uninstall       # stop it and remove the start-up entry (--purge: and the phones and its state)
journalctl --user -u herdr-push -f   # its log, under systemd (else ~/.local/state/herdr-push/herdr-push.log)
```

To install it without the app's help, copy `herdr-push` from this folder to `~/.local/share/herdr-push/` and run `python3 herdr-push install`. A phone still has to subscribe from the app (Settings → Notifications), because only the app has the phone's push address and makes the key.

What it watches comes from each phone's subscription: the herdr session (`default` unless the machine is set to another in the app), and the `herdr` and `herdr-projects` the app found there. It reads them with `herdr api snapshot`, `herdr pane read <pane> --source visible` (for a blocked agent's question), `herdr-projects list --json` and, for a task in review on a phone with Live Updates, `herdr-projects show <id>` once an hour (its `review.rounds`), every 4 seconds and every 12 seconds for the board. `HERDR_PUSH_INTERVAL` (seconds) and `HERDR_PUSH_BOARD_EVERY` (looks between board reads) change that; under systemd, set them with `systemctl --user edit herdr-push` (`[Service]` / `Environment=HERDR_PUSH_INTERVAL=10`).

Removing a machine from the app doesn't reach the machine. Turn it off in Settings → Notifications first, or run `herdr-push uninstall --purge` there.

## Your own push server

ntfy uses ntfy.sh unless you point it elsewhere (ntfy → Settings → Default server). The push address the phone hands out is then on your server, and each machine needs to reach it over HTTP(S). Any UnifiedPush push app works.

## What is sent, and who can read it

Each push is a JSON message, compressed and sealed with the key the app made for that machine and gave it over SSH:

```
hp1.<base64url(nonce ‖ ciphertext ‖ tag)>
```

Two keys come from the shared one, `enc = HMAC-SHA256(key, "herdr-push enc")` and `mac = HMAC-SHA256(key, "herdr-push mac")`. The keystream is `HMAC-SHA256(enc, nonce ‖ counter)` for a 32-bit big-endian counter from 0, XORed with the zlib-compressed JSON, and `tag = HMAC-SHA256(mac, "hp1" ‖ nonce ‖ ciphertext)`: HMAC-SHA256 as a pseudorandom function in counter mode, then encrypt-then-MAC, because that is what Python's standard library has. The app (`core/…/push/PushSeal.kt`) checks the tag before anything else and ignores a push no machine's key opens, or one older than two days.

So the push server sees only that a machine sent the phone a few hundred bytes, and when. Someone who learns the phone's push address can't make it show anything, and a push sent again later is dropped (the app keeps the newest push time per machine). The notification buttons don't go back through the push: they connect to the machine over SSH like the app does. **Approve** and **Deny** read the agent's screen again first and press nothing if the question changed.

Inside, the message is:

```json
{"v": 1, "host": "devbox", "at": 1760000000, "events": [
  {"type": "agent", "agent": {"pane_id": "w1:p2", "agent_status": "blocked", "…": "…as session.snapshot gives it"},
   "where": "api › agents", "screen": "…the bottom of its screen…"},
  {"type": "task", "key": "AND-7", "task": {"id": "herdr-android/7", "state": "blocked", "stage": "approval", "…": "…as list --json gives it"}},
  {"type": "test"},
  {"type": "health", "low": ["ram", "disk:/"], "memory": {"total": 65536000, "available": 3276800, "swap_total": 0, "swap_free": 0},
   "disks": [{"mount": "/", "device": "/dev/nvme0n1p2", "total": 500000000, "used": 495000000, "avail": 5000000}],
   "top": [["Android emulator · Pixel_8", 5872000], ["Gradle daemon", 3250000]]},
  {"type": "quick", "id": "q1792222540000-0a1f", "name": "Find TODOs", "state": "done", "text": "…the start of its answer…", "error": "", "cwd": "/home/demo/src/app"},
  {"type": "live", "agents": [{"agent": {"pane_id": "w1:p3", "agent_status": "working", "…": "…"}, "where": "api › agents", "workspace": "api", "since": 1759999000}],
   "tasks": [{"key": "AND-7", "task": {"id": "herdr-android/7", "stage": "reviewing", "reviewRound": 2, "…": "…"}, "reviews": 4, "since": 1759999900}]}
]}
```

A message stays under 4 KB sealed (what ntfy and most push servers take): several events go in separate pushes, a long screen is shortened, and a live event leaves out what doesn't fit (at most 10 agents and 10 tasks). `since` is when the agent started working, or the task's current part of its run began (working, checking, review, merging), on the machine's clock; the phone counts from the push's `at`. A live event without `tasks` (no board there) leaves the tasks as they were.
