# The sorchat chat server, built from source. Dependencies are pinned in deps.json, which is
# only valid for the Gradle version it was recorded with (the flake pins it via nixpkgs-gradle).
# After changing Gradle dependencies or that pin, regenerate it with:
#   nix build .#sorchat-server.mitmCache.updateScript && ./result
{
  lib,
  stdenv,
  gradle_9,
  makeWrapper,
  jdk21_headless,
}: let
  gradle = gradle_9;
  fs = lib.fileset;
in
  stdenv.mkDerivation (finalAttrs: {
    pname = "sorchat-server";
    version = "0.2.2";

    # Only what the server build reads, so app changes don't trigger rebuilds.
    src = fs.toSource {
      root = ../.;
      fileset = fs.unions [
        ../settings.gradle.kts
        ../build.gradle.kts
        ../gradle.properties
        ../gradle/libs.versions.toml
        (fs.difference ../shared (fs.maybeMissing ../shared/build))
        (fs.difference ../server (fs.unions [(fs.maybeMissing ../server/build) (fs.maybeMissing ../server/data)]))
      ];
    };

    nativeBuildInputs = [gradle makeWrapper];

    mitmCache = gradle.fetchDeps {
      pkg = finalAttrs.finalPackage;
      data = ./deps.json;
    };

    gradleFlags = [
      "-Psorchat.serverOnly=true"
      "-Dfile.encoding=utf-8"
      "-Dorg.gradle.java.home=${jdk21_headless}"
    ];
    gradleBuildTask = ":server:installDist";
    gradleUpdateTask = ":server:installDist :server:test";

    doCheck = true;
    gradleCheckTask = ":server:test";

    installPhase = ''
      runHook preInstall
      mkdir -p $out/share/sorchat $out/bin
      cp -r server/build/install/server/lib $out/share/sorchat/
      # A small heap is plenty: the server holds little in memory (SQLite and files hold the data).
      makeWrapper ${jdk21_headless}/bin/java $out/bin/sorchat-server \
        --add-flags "-Xmx256m -cp '$out/share/sorchat/lib/*' one.yago.sorchat.server.ApplicationKt"
      runHook postInstall
    '';

    meta = {
      description = "sorchat chat server";
      mainProgram = "sorchat-server";
      platforms = lib.platforms.linux;
      sourceProvenance = with lib.sourceTypes; [fromSource binaryBytecode];
    };
  })
