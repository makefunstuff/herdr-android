#!/bin/bash
# Recreates the three demo hosts. $1: optional authorized_keys file.
D=$(cd "$(dirname "$0")" && pwd)
# The image takes this machine's herdr binary (static, x86_64).
ctx=$(mktemp -d); cp "$D/Dockerfile" "$ctx/"; cp "$(command -v herdr)" "$ctx/herdr"
docker build -q -t herdr-demo "$ctx" >/dev/null && rm -rf "$ctx"
docker network inspect herdr-demo-net >/dev/null 2>&1 || docker network create herdr-demo-net >/dev/null
for h in devbox gpu-box ci-runner; do
 docker rm -f herdr-$h >/dev/null 2>&1
 extra=""; [ $h = devbox ] && extra="-p 127.0.0.1:2201:22"
 docker run -d --name herdr-$h --hostname $h --network herdr-demo-net --network-alias $h $extra herdr-demo >/dev/null
 docker exec herdr-$h mkdir -p /home/demo/.demo /home/demo/.ssh
 docker cp $D/content.py herdr-$h:/home/demo/.demo/content.py; docker cp $D/setup.sh herdr-$h:/home/demo/.demo/setup.sh
 [ -n "$1" ] && docker cp "$1" herdr-$h:/home/demo/.ssh/authorized_keys
done
docker exec herdr-devbox mkdir -p /home/demo/.herdr-projects/data/bin /home/demo/src/herdr-android
docker cp $D/herdr-projects herdr-devbox:/home/demo/.herdr-projects/data/bin/herdr-projects
docker cp $D/mkrepo.sh herdr-devbox:/home/demo/.demo/; docker cp $D/demo-session herdr-devbox:/home/demo/src/herdr-android/demo-session
for h in devbox gpu-box ci-runner; do
 docker exec herdr-$h sh -c 'chown -R demo:demo /home/demo; chmod 700 /home/demo/.ssh; chmod 600 /home/demo/.ssh/authorized_keys 2>/dev/null; true'
done
docker exec -u demo -w /home/demo herdr-devbox bash /home/demo/.demo/mkrepo.sh
for h in devbox gpu-box ci-runner; do docker exec -u demo -w /home/demo -e HOME=/home/demo herdr-$h bash /home/demo/.demo/setup.sh $h 2>&1 | grep -v '^{"id":"cli:agent:list"'; done
