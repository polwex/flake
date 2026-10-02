# The sorchat chat server, packaged from the Gradle distribution tarball
# (`./gradlew :server:distTar` → server/build/distributions/server.tar).
{
  lib,
  stdenvNoCC,
  makeWrapper,
  jdk21_headless,
  src,
}:
stdenvNoCC.mkDerivation {
  pname = "sorchat-server";
  version = "0.1.0";
  inherit src;

  nativeBuildInputs = [makeWrapper];

  installPhase = ''
    runHook preInstall
    mkdir -p $out/share/sorchat $out/bin
    cp -r lib $out/share/sorchat/
    # A small heap is plenty: the server holds little in memory (SQLite and files hold the data).
    makeWrapper ${jdk21_headless}/bin/java $out/bin/sorchat-server \
      --add-flags "-Xmx256m -cp '$out/share/sorchat/lib/*' one.yago.sorchat.server.ApplicationKt"
    runHook postInstall
  '';

  meta = {
    description = "sorchat chat server";
    mainProgram = "sorchat-server";
    platforms = lib.platforms.linux;
  };
}
