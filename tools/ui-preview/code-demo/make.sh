#!/bin/sh
# Writes the previews' demo code (src/main/resources/demo/code): a small made-up herdr-android
# repository, built here with real Git (a main branch, and task AND-9's worktree with two commits,
# a change not committed yet and a new file), and what the app's code commands print on it. The
# fake machine (preview/FakeCode.kt) answers with these. Run it again after changing the files below:
#   sh tools/ui-preview/code-demo/make.sh
set -eu
here=$(cd "$(dirname "$0")" && pwd)
out="$here/../src/main/resources/demo/code"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export GIT_AUTHOR_NAME=demo GIT_AUTHOR_EMAIL=demo@devbox GIT_COMMITTER_NAME=demo GIT_COMMITTER_EMAIL=demo@devbox
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 TZ=UTC
# commit <when> <git commit's arguments>: at a fixed time, so the same files give the same commits.
commit() { when=$1; shift; GIT_AUTHOR_DATE=$when GIT_COMMITTER_DATE=$when git commit -q "$@"; }
put() { mkdir -p "$(dirname "$1")"; cat > "$1"; }

main="$work/herdr-android"
mkdir -p "$main" && cd "$main"
git init -q -b main
cp "$here"/files/README.md README.md
put .gitignore <<'EOF'
build/
.gradle/
local.properties
EOF
put settings.gradle.kts <<'EOF'
rootProject.name = "herdr-android"
include(":app", ":core")
EOF
mkdir -p app core docs
cp -r "$here"/files/main/. .
git add -A && commit 2026-10-02T10:12:00Z -m "Start the app: machines and their agents"
cp "$here"/files/DESIGN.md docs/DESIGN.md
git add -A && commit 2026-10-09T15:40:00Z -m "Adopt the herdr web UI look"
sed -i 's/Status unknown/Not connected/' app/src/main/kotlin/demo/herdr/ui/machines/MachineCard.kt
git add -A && commit 2026-10-14T11:05:00Z -m "Say when a machine is not connected"
sed -i 's/^Kotlin and Jetpack Compose\.$/Kotlin and Jetpack Compose. Light and dark, as the phone is./' README.md
git add -A && commit 2026-10-16T17:20:00Z -m "Better chat composer"

# Task AND-9's worktree: two commits, a change not committed yet, a new file.
wt="$work/herdr-android-9"
git worktree add -q -b work/9-last-answered "$wt"
cd "$wt"
cp -r "$here"/files/task1/. .
git add -A && commit 2026-10-16T23:48:00Z -m "Keep when each machine last answered" -m "HostSession notes the time of every answer; reconnecting keeps it."
cp -r "$here"/files/task2/. .
git add -A && commit 2026-10-17T06:31:00Z -m "Show it on the machine cards"
cp -r "$here"/files/task3/. .

rm -rf "$out" && mkdir -p "$out"
g() { git -c core.quotepath=off -c color.ui=false "$@"; }
rec() { # rec <checkout> <name>: what the commands print in it
  cd "$1"
  g ls-files -co --exclude-standard -z > "$out/$2.files"
  g status --porcelain=v1 -b --untracked-files=all > "$out/$2.status"
  { from=$(g merge-base HEAD main); printf '@@from %s\n' "$from"; g diff --no-ext-diff --no-textconv -M "$from" --
    g ls-files -o --exclude-standard | while IFS= read -r f; do g diff --no-ext-diff --no-index -- /dev/null "$f" || true; done; } > "$out/$2.diff-base"
  { printf '@@from %s\n' "$(g rev-parse HEAD)"; g diff --no-ext-diff --no-textconv -M HEAD --
    g ls-files -o --exclude-standard | while IFS= read -r f; do g diff --no-ext-diff --no-index -- /dev/null "$f" || true; done; } > "$out/$2.diff-head"
  { g rev-list main..HEAD | sed 's/^/@@ahead /'; echo '@@herdr-code log'; g log -n 80 --format='%H%x1f%h%x1f%an%x1f%at%x1f%D%x1f%s%x1e' HEAD; } > "$out/$2.log"
  printf '%s\t%s\n' "$(g rev-parse --abbrev-ref HEAD)" "$(g rev-parse --short HEAD)" > "$out/$2.head"
  mkdir -p "$out/$2"
  g ls-files -co --exclude-standard | while IFS= read -r f; do mkdir -p "$out/$2/$(dirname "$f")"; cp "$f" "$out/$2/$f"; done
}
rec "$main" main
rec "$wt" task
cd "$wt"
mkdir -p "$out/show"
g log --format=%H HEAD | while read -r sha; do
  g show --no-ext-diff --no-textconv -M -m --first-parent --format='%H%x1f%h%x1f%an%x1f%at%x1f%P%x1f%B%x1e' "$sha" -- > "$out/show/$sha"
done
echo "Wrote $out"
