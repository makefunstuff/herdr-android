# herdr-logins

Which agent CLIs are signed in on a machine, and signing them in or out there. Herdr for Android copies it there over SSH (as it first opens the machine's **Agent logins**) and runs it. Every login stays on the machine: what comes back is whether each CLI is signed in, how and until when, **never a token or a key**.

It is one Python 3 file (3.6 or newer), standard library only.

```sh
P=~/.local/share/herdr-logins/herdr-logins
python3 $P status                  # every CLI's login and the keys in shell profiles, as one JSON line
python3 $P run codex login         # sign in again: the link and the code come out as JSON lines
echo "$KEY" | python3 $P run codex key        # a key, on stdin only
python3 $P run pi key groq < keyfile           # a key for one pi provider
python3 $P run claude logout
```

## How each CLI keeps and takes its login

Each action is the CLI's own command. A key or a code goes in as that command's input: never as an argument (it would show in `ps` and shell history), never logged, never kept by the script.

| CLI | Status | Sign in | Key | Sign out |
| --- | --- | --- | --- | --- |
| **Claude Code** | `claude auth status --json`; when the login runs out is `claudeAiOauth.refreshTokenExpiresAt` in `~/.claude/.credentials.json` (only that number is read out) | `claude auth login` on a terminal of its own: the link goes to the phone, the code the browser shows comes back and is typed into it | — (`ANTHROPIC_API_KEY` is a variable) | `claude auth logout` |
| **Codex** | `codex login status`; last renewed from `last_refresh` in `~/.codex/auth.json` | `codex login --device-auth`: the link and the code to type there go to the phone | `codex login --with-api-key`, the key on its stdin | `codex logout` |
| **pi** | `~/.pi/agent/auth.json`: each provider's kind (a typed key, `!command`, `$VARIABLE`, a login), never the key | — (`/login` inside pi) | pi has no command that takes a key, so only that provider's entry is written, holding pi's own lock (`auth.json.lock`, as proper-lockfile makes it), as a new file of mode 600 swapped in; then `pi auth check --provider P --json --no-refresh`. A key pi takes from a command (a keyring) or a variable is **never** replaced. | Only that provider's entry is taken out, the same way (never a `!command` or `$VARIABLE` one) |
| **opencode** | `opencode auth list` | `opencode auth login [provider]`: its questions are relayed to the phone | through its sign-in | `opencode auth logout <provider>` |
| **Gemini CLI** | `GEMINI_API_KEY`, or `~/.gemini/oauth_creds.json` there | — (its first run asks) | — | — |

Commands that print a secret (`claude setup-token`, `opencode auth export`, `pi auth check --credentials`, `pi auth print-api-key`, `pi auth print-bearer-token`) are never run.

## Keys in shell profiles

`status` also lists keys exported in plain text from `~/.bashrc`, `~/.bash_profile`, `~/.profile`, `~/.zshrc`, `~/.zprofile`, `~/.zshenv` and fish's `config.fish`: the file, the line and the name, and whether the value is too short to be a real key. Never the value. The app shows how to move each into its CLI's own store and which line to delete; it edits no profile.

## Tests

`core`'s `LoginsTest` runs the script against fake CLIs in a home of their own: no real CLI is run and no real login is touched.
