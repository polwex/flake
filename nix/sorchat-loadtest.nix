# The sorchat load tester (loadtest/), built from source. Like the server, its dependencies are
# pinned (in deps-loadtest.json, for the Gradle version the flake pins). Regenerate with:
#   nix build .#sorchat-loadtest.mitmCache.updateScript && ./result
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
    pname = "sorchat-loadtest";
    version = "0.2.0";

    src = fs.toSource {
      root = ../.;
      fileset = fs.unions [
        ../settings.gradle.kts
        ../build.gradle.kts
        ../gradle.properties
        ../gradle/libs.versions.toml
        (fs.difference ../shared (fs.maybeMissing ../shared/build))
        (fs.difference ../loadtest (fs.maybeMissing ../loadtest/build))
      ];
    };

    nativeBuildInputs = [gradle makeWrapper];

    mitmCache = gradle.fetchDeps {
      pkg = finalAttrs.finalPackage;
      data = ./deps-loadtest.json;
    };

    gradleFlags = [
      "-Psorchat.serverOnly=true"
      "-Dfile.encoding=utf-8"
      "-Dorg.gradle.java.home=${jdk21_headless}"
    ];
    gradleBuildTask = ":loadtest:installDist";
    gradleUpdateTask = ":loadtest:installDist";

    installPhase = ''
      runHook preInstall
      mkdir -p $out/share/sorchat-loadtest $out/bin
      cp -r loadtest/build/install/loadtest/lib $out/share/sorchat-loadtest/
      makeWrapper ${jdk21_headless}/bin/java $out/bin/sorchat-loadtest \
        --add-flags "-cp '$out/share/sorchat-loadtest/lib/*' one.yago.sorchat.loadtest.LoadTestKt"
      runHook postInstall
    '';

    meta = {
      description = "Load tester for a sorchat server";
      mainProgram = "sorchat-loadtest";
      platforms = lib.platforms.linux;
    };
  })
