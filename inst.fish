#!/usr/bin/env fish
# Builds the debug app, installs it on a device and opens it.
#   ./inst.fish                    # the phone, against chat.urbit.men
#   ./inst.fish emulator-5554      # another device
#   ./inst.fish emulator-5554 http://10.0.2.2:8080   # against a local dev server
set -l serial (test (count $argv) -ge 1; and echo $argv[1]; or echo A7UHVB4611025932)
set -l gradle_args :app:installDebug
if test (count $argv) -ge 2
    set -a gradle_args -Psorchat.serverUrl=$argv[2]
    # A USB device reaches a dev server on this machine through adb reverse.
    string match -q 'http://localhost:*' $argv[2]; and adb -s $serial reverse tcp:8080 tcp:8080
end

ANDROID_SERIAL=$serial ./gradlew $gradle_args
and adb -s $serial shell am start -n one.yago.sorchat/one.yago.sorchat.app.MainActivity
