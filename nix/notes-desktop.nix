{
  lib,
  stdenv,
  gradle_9,
  jdk21,
  makeWrapper,
  makeDesktopItem,
  copyDesktopItems,
  libsecret,
  imagemagick,
  src,
  version,
  runtimeLibs,
}:

let
  gradle = gradle_9;
  jdk = jdk21;
in
stdenv.mkDerivation (finalAttrs: {
  pname = "notes-desktop";
  inherit version;

  # Only the client sources (plus the server's icon) – changes elsewhere don't rebuild the package.
  src = lib.fileset.toSource {
    root = src;
    fileset = lib.fileset.unions [
      (lib.fileset.difference (src + "/clients") (lib.fileset.unions [
        (lib.fileset.maybeMissing (src + "/clients/.gradle"))
        (lib.fileset.maybeMissing (src + "/clients/.kotlin"))
        (lib.fileset.maybeMissing (src + "/clients/build"))
      ]))
      (src + "/server/Notes.Server/wwwroot/favicon.svg")
    ];
  };
  sourceRoot = "${finalAttrs.src.name}/clients";

  nativeBuildInputs = [ gradle makeWrapper copyDesktopItems imagemagick ];

  mitmCache = gradle.fetchDeps {
    pkg = finalAttrs.finalPackage;
    data = ./notes-desktop-deps.json;
  };

  # Build outputs of other modules (build/ dirs of a dirty checkout) must not leak in.
  preBuild = ''
    find . -name build -type d -prune -exec rm -rf {} +
  '';

  gradleFlags = [
    "-Pnotes.desktopOnly=true"
    "-Pnotes.versionName=${version}"
    "-Dorg.gradle.java.home=${jdk}"
    "-Dorg.gradle.java.installations.auto-download=false"
    "--no-configuration-cache"
    "--no-parallel"
    "-Dorg.gradle.workers.max=2"
  ];
  gradleBuildTask = ":desktopApp:nixDistribution";
  # Record the real build: KSP resolves its processor at execution time (detached configuration).
  gradleUpdateTask = finalAttrs.gradleBuildTask;
  doCheck = false;

  installPhase = ''
    runHook preInstall
    mkdir -p $out/share/notes $out/share/icons/hicolor/256x256/apps
    cp -r desktopApp/build/nix/lib $out/share/notes/lib
    magick -background none -density 384 ../server/Notes.Server/wwwroot/favicon.svg -resize 256x256 \
      $out/share/icons/hicolor/256x256/apps/notes.png

    # Runs via XWayland under Wayland (AWT/X11 backend); secret-tool for the Secret Service.
    makeWrapper ${jdk}/bin/java $out/bin/notes \
      --add-flags "--add-opens java.desktop/sun.awt.X11=ALL-UNNAMED" \
      --add-flags "-Dnotes.version=${version}" \
      --add-flags "-cp '$out/share/notes/lib/*' dev.welles.notes.desktop.MainKt" \
      --prefix LD_LIBRARY_PATH : ${lib.makeLibraryPath runtimeLibs} \
      --prefix PATH : ${lib.makeBinPath [ libsecret ]} \
      --set-default GDK_BACKEND x11 \
      --unset WAYLAND_DISPLAY
    runHook postInstall
  '';

  desktopItems = [
    (makeDesktopItem {
      name = "notes";
      desktopName = "Notes";
      comment = "Post-its und Notizen";
      exec = "notes";
      icon = "notes";
      startupWMClass = "notes";
      categories = [ "Office" "Utility" ];
      actions.capture = {
        name = "Schnell notieren";
        exec = "notes --capture";
      };
    })
  ];

  meta = {
    description = "Notes – personal notes (desktop client)";
    mainProgram = "notes";
    platforms = [ "x86_64-linux" ];
  };
})
