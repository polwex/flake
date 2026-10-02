export LD_LIBRARY_PATH="$ANDROID_HOME/emulator/lib64''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$ANDROID_HOME/emulator/emulator" "$@"
