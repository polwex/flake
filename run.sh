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

# coturn's static-auth-secret, encrypted with:
#   openssl enc -aes-256-cbc -pbkdf2 -in turn-secret.txt -out bulk/turn-secret.enc
if [[ -f bulk/turn-secret.enc ]]; then
    SORCHAT_TURN_SECRET="$(decrypt bulk/turn-secret.enc | tr -d '[:space:]')"
    SORCHAT_TURN_URLS="stun:turn.urbit.men:3478,turn:turn.urbit.men:3478?transport=udp,turn:turn.urbit.men:3478?transport=tcp,turns:turn.urbit.men:5349?transport=tcp"
    export SORCHAT_TURN_SECRET SORCHAT_TURN_URLS
else
    echo "bulk/turn-secret.enc not found: calls will use public STUN only, without a relay" >&2
fi
unset SECRETS_PASSWORD

exec ./gradlew :server:run --console=plain
