#!/bin/bash
# Runs as demo inside a demo container: starts herdr and lays out workspaces with scripted agents.
set -e
role=$1
cd ~
mkdir -p ~/.local/bin
# Stand-ins named like the agents, so herdr detects them; they just draw a screen.
for a in claude codex; do printf '#!/bin/bash\nclear; printf "\\033[?25l"; cat ~/.demo/$DEMO_SCREEN\nwhile :; do sleep 3600; done\n' > ~/.local/bin/$a; chmod +x ~/.local/bin/$a; done
python3 ~/.demo/content.py $role
cat > ~/.bashrc <<'RC'
PS1='\[\e[1;32m\]demo@\h\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]$ '
alias ls='ls --color=auto'
export PATH=$HOME/.local/bin:$PATH
RC
echo '[ -f ~/.bashrc ] && . ~/.bashrc' > ~/.bash_profile
pgrep -u demo -f "herdr server" >/dev/null || { SHELL=/bin/bash nohup herdr server > /tmp/herdr.log 2>&1 & sleep 2; }
H=herdr
ws() { $H workspace create --no-focus --cwd "$1" --label "$2" | python3 -c 'import json,sys; r=json.load(sys.stdin)["result"]; print(r["root_pane"]["pane_id"])'; }
split() { $H pane split "$1" --direction "${2:-right}" | python3 -c 'import json,sys; r=json.load(sys.stdin)["result"]; print(r["pane"]["pane_id"] if "pane" in r else r)'; }
now() { date +%s%N; }
agent() { # pane agent state session-id session-path title screen
  local pane=$1 a=$2 st=$3 sid=$4 sp=$5 title=$6 screen=$7
  $H pane run "$pane" "DEMO_SCREEN=$screen $a" >/dev/null
  sleep 0.5
  $H pane report-agent-session --source herdr:$a --agent $a --seq $(now) --agent-session-id "$sid" ${sp:+--agent-session-path "$sp"} "$pane" >/dev/null
  $H pane report-agent --source herdr:$a --agent $a --state $st --seq $(now) "$pane" >/dev/null
  [ -n "$title" ] && $H pane report-metadata "$pane" --title "$title" --source herdr:$a >/dev/null || true
}
case $role in
devbox)
  mkdir -p src/herdr-android src/api-server src/website
  p=$(ws ~/src/herdr-android herdr-android)
  agent $p claude working 3f6c2a1e-8b7d-4c2a-9e51-0a1b2c3d4e5f "" "Show when each machine last answered" screen-claude-app
  q=$(split $p right)
  $H pane rename "$q" shell >/dev/null
  $H pane run "$q" "cd ~/src/herdr-android && clear && ./demo-session" >/dev/null
  p=$(ws ~/src/api-server api-server)
  agent $p claude blocked 9d2e4b7a-1c3f-4e6a-8b90-5f4e3d2c1b0a "" "Speed up /jobs on large tables" screen-claude-api
  p=$(ws ~/src/website website)
  agent $p codex working 0199a3b2-7c41-7e10-9a2b-3c4d5e6f7a8b "" "Rewrite the install page" screen-codex
  sleep 1
  $H pane report-agent --source herdr:codex --agent codex --state idle --seq $(now) "$p" >/dev/null
  ;;
gpu-box)
  mkdir -p llm-eval notebooks
  p=$(ws ~/llm-eval llm-eval)
  agent $p codex working 0199a3c4-1d2e-7f30-8a4b-5c6d7e8f9a0b "" "Long-context eval on ckpt-0612" screen-codex-eval
  ws ~/notebooks notebooks >/dev/null
  ;;
ci-runner)
  mkdir -p runners deploy
  ws ~/runners runners >/dev/null
  ws ~/deploy deploy >/dev/null
  ;;
esac
$H agent list
