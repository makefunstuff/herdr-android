#!/bin/bash
set -e
cd ~/src/herdr-android; git init -q -b main; git config user.name Demo; git config user.email demo@example.com
msgs=("Initial Compose client" "Machines list with live status" "Terminal: fit the phone by default" "Native Coordinator chat" "Replace the app icon with the herdr icon" "Installable releases that keep the SSH key" "Replace agent terminals with native chat views")
i=0; for m in "${msgs[@]}"; do i=$((i+1)); echo $i > f$i; git add .; GIT_AUTHOR_DATE="2026-10-0${i}T10:00:00" GIT_COMMITTER_DATE="2026-10-0${i}T10:00:00" git commit -qm "$m"; done
git update-index --assume-unchanged demo-session
git checkout -qb pull-to-refresh
mkdir -p app core; echo x > app/MachinesScreen.kt; echo y > core/HostSession.kt; git add -N app core
