#!/usr/bin/env bash
# Load-tests a throwaway sorchat server on a remote machine through an SSH tunnel.
#
#   ./bench.sh <ssh-host> [load tester options…]
#   ./bench.sh myvps --users 500 --rate 50 --duration 60 --upload-rate 1
#
# Start the throwaway server on that machine first (it must listen on localhost:8099):
#   nix shell <this repo's flake>#sorchat-server -c \
#     env HOST=127.0.0.1 PORT=8099 SORCHAT_DB=/tmp/lt/db SORCHAT_MEDIA_DIR=/tmp/lt/media sorchat-server
set -euo pipefail
cd "$(dirname "$0")"

if [[ $# -lt 1 || $1 == -* ]]; then
    echo "usage: $0 <ssh-host> [load tester options, see --help]" >&2
    exit 2
fi
host=$1
shift
port=8099

# Default to a moderate first run; anything passed on the command line overrides it.
args=(--users 200 --rate 20 --duration 60)
[[ $# -gt 0 ]] && args=("$@")

ssh -N -o ExitOnForwardFailure=yes -L "$port:localhost:$port" "$host" &
tunnel=$!
trap 'kill "$tunnel" 2>/dev/null || true' EXIT

# Wait for the tunnel (and the server behind it) to answer.
for _ in $(seq 30); do
    if curl -s -o /dev/null "http://localhost:$port/ice-servers"; then break; fi
    if ! kill -0 "$tunnel" 2>/dev/null; then
        echo "SSH tunnel to $host failed" >&2
        exit 1
    fi
    sleep 1
done
if ! curl -s -o /dev/null "http://localhost:$port/ice-servers"; then
    echo "No sorchat server answering on $host:localhost:$port, is the throwaway server running?" >&2
    exit 1
fi

./gradlew -q :loadtest:installDist
loadtest/build/install/loadtest/bin/loadtest --server "http://localhost:$port" "${args[@]}"
