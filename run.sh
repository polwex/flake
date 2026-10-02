#!/usr/bin/env bash
# Starts the dev server with FCM enabled. Run it inside the devenv shell;
# it asks for the password of the encrypted service-account key.
set -euo pipefail
cd "$(dirname "$0")"

SORCHAT_FCM_CREDENTIALS_JSON="$(openssl enc -d -aes-256-cbc -pbkdf2 -in bulk/sorchat-80c1b-firebase-adminsdk-fbsvc-ed864cd33f.json.enc)"
export SORCHAT_FCM_CREDENTIALS_JSON

exec ./gradlew :server:run --console=plain
