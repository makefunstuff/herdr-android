# Demo host for the screenshots

The README's screenshots come from the real app talking to real herdr servers, in three throwaway containers (`devbox`, `gpu-box`, `ci-runner`) with a user called `demo` and made-up projects. Nothing in the app changes for them.

- `up.sh [authorized_keys]` builds the image (it copies the `herdr` on your PATH into it), recreates the containers and lays out their workspaces. `devbox` listens on `127.0.0.1:2201`; the other two are reachable only from `devbox`, so the app adds them with **Connect through devbox**.
- `setup.sh` starts herdr in a container and opens the panes. The "agents" are stand-in scripts named `claude` and `codex` that draw a screen (`content.py` writes them), so herdr detects them, and their sessions point at transcripts `content.py` writes in the agents' own formats. The chat view follows those like any other.
- `herdr-projects` stands in for the work board: `remote list` / `remote dispatch` for the Coordinator chat, and `list`, `details` and the task commands (which only say "Done") for the Board screen, with made-up tasks.
- `demo-session` and `mkrepo.sh` fill the shell pane that the Terminal screenshot shows.

To take the shots on an emulator (Pixel 7 size, API 35, started with `-writable-system`):

```sh
adb root && adb remount            # once, then reboot the emulator
adb root && adb remount
adb shell "echo '10.0.2.2 devbox' >> /system/etc/hosts"
adb shell iptables -t nat -A OUTPUT -p tcp -d 10.0.2.2 --dport 22 -j DNAT --to-destination 10.0.2.2:2201
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941
```

A rebuilt image has new host keys, which the app refuses for machines it already pinned: add them again. Make a key in the app, save its public key to a file, run `./up.sh that-file`, and add `demo@devbox`, then `demo@gpu-box` and `demo@ci-runner` through devbox. Screens were captured with `adb exec-out screencap -p` (light and dark: `adb shell cmd uimode night yes|no`) and converted with `magick in.png -strip -resize 540x -quality 82 out.webp`.

`docker rm -f herdr-devbox herdr-gpu-box herdr-ci-runner && docker network rm herdr-demo-net` removes it all.
