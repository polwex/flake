{
  pkgs,
  lib,
  config,
  inputs,
  ...
}: {
  packages = with pkgs; [
    git
    util-linux # setsid, for start-emulator
  ];

  android = {
    enable = true;
    buildTools.version = ["36.0.0"];
    emulator.enable = true;
    # Current AndroidX/Compose/OkHttp releases require compileSdk 37.
    platforms.version = ["34" "36" "37"];
    systemImages.enable = true;
    abis = ["x86_64"];
    systemImageTypes = ["google_apis_playstore"];
  };

  languages.java = {
    enable = true;
    jdk.package = pkgs.jdk17;
  };

  languages.kotlin = {
    enable = true;
  };

  scripts = {
    # The android module puts build-tools/*/lib64 on LD_LIBRARY_PATH; its older libc++
    # shadows the emulator's bundled one ("undefined symbol ... libabseil_dll.so").
    emulator.exec = ''
      LD_LIBRARY_PATH="$ANDROID_HOME/emulator/lib64''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
        exec "$ANDROID_HOME/emulator/emulator" "$@"
    '';

    create-avd.exec = ''
      name="''${1:-pixel6}"
      if [ -d "$ANDROID_AVD_HOME/$name.avd" ]; then
        echo "AVD '$name' already exists"
        exit 0
      fi
      echo no | avdmanager create avd --force --name "$name" \
        --package "system-images;android-36;google_apis_playstore;x86_64" --device pixel_6
    '';

    # Boots an AVD fully detached. Run it once per AVD to have several emulators up.
    start-emulator.exec = ''
      name="''${1:-pixel6}"
      shift || true
      create-avd "$name"
      for s in $(adb devices | grep -o '^emulator-[0-9]*'); do
        if [ "$(adb -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r')" = "$name" ]; then
          echo "'$name' is already running as $s"
          exit 0
        fi
      done
      log="$DEVENV_STATE/emulator-$name.log"
      setsid emulator -avd "$name" -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect "$@" \
        </dev/null >"$log" 2>&1 &
      echo "Booting '$name' (log: $log). Wait with: adb wait-for-device"
    '';
  };

  enterShell = ''
    git --version
  '';

  enterTest = ''
    git --version | grep --color=auto "${pkgs.git.version}"
  '';
}
