# herdr-quick

The worker behind Herdr for Android's **AI quick commands**: one run of a `<name>.prompt.md` command as a durable AI worker, on [pi-durable](https://www.npmjs.com/package/@earendil-works/pi-durable). The app carries it (core's resources) and copies it to a machine each time it starts one, so there is nothing to install by hand: the first AI command on a machine installs pi-durable there with npm. It needs Node.js 22 or newer (pi-durable keeps its state in `node:sqlite`).

## Where it lives on a machine

| Path | What |
| --- | --- |
| `~/.local/share/herdr-android/quick/herdr-quick.mjs` | the worker (replaced when the app carries another) |
| `~/.local/share/herdr-android/quick/package.json`, `node_modules/` | pi-durable, pi-ai and chord, at the versions pinned here |
| `~/.local/state/herdr-android/quick/<run>/` | one run: what the app and the worker write (below) |

## A run's folder

The app writes `meta.json` (what the command is, where it ran, what was typed) and `request.json` (the prompt with its placeholders filled, the model, the tools, the folder, the limits), and starts `node herdr-quick.mjs run <folder>` detached from its SSH connection (`setsid`, else `nohup`). The worker writes:

- `status.json`: `{"state": "setup|running|done|failed|stopped|limit", "activity": "Reading", "tokens": 1234, "model": "…", "started": …, "ended": …, "error": "…"}` (times on the machine's clock, in seconds);
- `answer.md`: the answer as it is written, then the whole answer;
- `durable.sqlite`: the conversation. A worker that was killed, or a machine that restarted, carries on where it was when it is started again on the same folder (the app's **Carry on** does that); pi-durable resumes the generation or the tool call it was in;
- `worker.log`, `setup.log`: what node and npm printed.

The app reads it over SSH (`core/…/quick/QuickCommands.kt`); [herdr-push](../herdr-push/README.md) tells the phone when one ends while the app is closed. Scripts' runs share the folder (`out`, `exit`, `start`, `end`); the newest 80 runs are kept.

## Models, tools and limits

- `# model: claude/<model>`: Anthropic's API, with `ANTHROPIC_API_KEY` or pi's key for `anthropic` (`~/.pi/agent/auth.json`).
- `# model: pi/<provider>/<model>`: a provider of pi's `~/.pi/agent/models.json` (OpenAI-compatible or Anthropic messages), with its key from there or from pi's `auth.json`; or one of pi-ai's built-in providers.
- No model: pi's default (`defaultProvider` and `defaultModel` in `~/.pi/agent/settings.json`); with none there it fails and says so, rather than pick one.
- `# tools:` `read-only` (the default: read files, list folders, search, and Git's read-only commands, all inside the run's folder), `shell` (pi-durable's read, write, edit and bash: it can change things), `web` (a web search and fetching pages; what comes back is untrusted).
- Limits: 10 minutes and 200 000 tokens unless the header says (`# timeout: 30m`, `# tokens: 50k`). At one, it stops with `limit`. Tokens are counted as each response comes in.
- Stop: SIGTERM or a file `stop` in the folder; it ends the conversation's turn and says `stopped`.

## By hand

```sh
mkdir -p /tmp/run && cat > /tmp/run/request.json <<'EOF'
{"prompt": "List the three most recent commits here.", "model": "pi/deepseek/deepseek-flash", "cwd": "/path/to/repo"}
EOF
node ~/.local/share/herdr-android/quick/herdr-quick.mjs run /tmp/run && cat /tmp/run/status.json /tmp/run/answer.md
```
