#!/usr/bin/env bash
# Starts the dev server with FCM and TURN enabled. Run it inside the devenv shell;
# it asks once for the password of the encrypted secrets in bulk/.
set -euo pipefail
cd "$(dirname "$0")"

read -rsp "Password for bulk/ secrets: " SECRETS_PASSWORD
echo
export SECRETS_PASSWORD
decrypt() { openssl enc -d -aes-256-cbc -pbkdf2 -pass env:SECRETS_PASSWORD -in "$1"; }

SORCHAT_FCM_CREDENTIALS_JSON="$(decrypt bulk/sorchat-80c1b-firebase-adminsdk-fbsvc-ed864cd33f.json.enc)"
export SORCHAT_FCM_CREDENTIALS_JSON
SORCHAT_TURN_SECRET="$(secret-tool lookup Title 'coturn-key')"
export SORCHAT_TURN_SECRET
SORCHAT_TURN_URLS="stun:turn.urbit.men:3478,turn:turn.urbit.men:3478?transport=udp,turn:turn.urbit.men:3478?transport=tcp,turns:turn.urbit.men:5349?transport=tcp"
export SORCHAT_TURN_URLS

unset SECRETS_PASSWORD

exec ./gradlew :server:run --console=plain
