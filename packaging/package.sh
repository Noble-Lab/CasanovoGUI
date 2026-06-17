#!/usr/bin/env bash
# ============================================================
#  Casanovo GUI - build a self-contained native app with jpackage.
#  Run this ON the target OS - jpackage cannot cross-build.
#
#    ./packaging/package.sh              app-image only
#       Linux -> dist/CasanovoGUI/         (run dist/CasanovoGUI/bin/CasanovoGUI)
#       macOS -> dist/CasanovoGUI.app
#    ./packaging/package.sh --installer  Linux: .deb + portable .tar.gz (no root, no Java); macOS: .dmg
#
#  Mirrors build-exe.bat, including copying a real `java` launcher back into the
#  bundled runtime (jpackage strips it) so "Open in PDV" can spawn PDV.
#  Requires a JDK 23+ (jpackage; CI builds on 25) and Maven on PATH, or JAVA_HOME set.
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."   # this script lives in packaging/; operate from the repo root

APP="CasanovoGUI"
MAIN_CLASS="org.casanovo.gui.CasanovoGuiApp"
OS=$(uname -s)
JPACKAGE="${JAVA_HOME:+$JAVA_HOME/bin/}jpackage"
SRC_JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

INSTALLER=0
[ "${1:-}" = "--installer" ] && INSTALLER=1

echo "[1/4] Building the fat JAR with Maven..."
mvn -q -DskipTests clean package
JAR=$(ls target/casanovo-gui-*.jar | grep -vE 'original|shaded' | head -n1)
JAR_NAME=$(basename "$JAR")
# Real app version so jpackage stamps it on the package instead of its 1.0 default.
# Taken from the jar name (= pom version); override with APP_VERSION (e.g. a release tag).
VERSION="${APP_VERSION:-$(basename "$JAR_NAME" .jar | sed -E 's/^casanovo-gui-//')}"
echo "      $JAR_NAME  (version $VERSION)"

echo "[2/4] Staging..."
rm -rf staging dist
mkdir -p staging
cp "$JAR" staging/

# Icon (optional): jpackage wants PNG on Linux and ICNS on macOS.
ICON_ARG=()
if [ "$OS" = "Linux" ] && [ -f src/main/resources/org/casanovo/gui/icon.png ]; then
  ICON_ARG=(--icon src/main/resources/org/casanovo/gui/icon.png)
elif [ "$OS" = "Darwin" ] && [ -f packaging/icon.icns ]; then
  ICON_ARG=(--icon packaging/icon.icns)
fi

echo "[3/4] Running jpackage (app-image)..."
"$JPACKAGE" --type app-image --name "$APP" --app-version "$VERSION" \
  --input staging --main-jar "$JAR_NAME" --main-class "$MAIN_CLASS" \
  --java-options "--enable-native-access=ALL-UNNAMED" \
  --dest dist "${ICON_ARG[@]}"

echo "[4/4] Adding a Java launcher to the bundled runtime (needed to open PDV)..."
# jpackage's jlink strips java from the runtime; the native launcher boots the JVM
# directly via libjli/jvm, so it never needs it. But "Open in PDV" launches PDV as a
# separate `java -jar PDV.jar` process, which DOES need a launcher. The trimmed runtime
# still ships the core libs and full module set, so the matching `java` works.
if [ "$OS" = "Darwin" ]; then
  RT_BIN="dist/$APP.app/Contents/runtime/Contents/Home/bin"
else
  RT_BIN="dist/$APP/lib/runtime/bin"
fi
mkdir -p "$RT_BIN"
cp "$(command -v "$SRC_JAVA")" "$RT_BIN/java"
chmod +x "$RT_BIN/java"
echo "      copied java -> $RT_BIN/java"

if [ "$INSTALLER" = "1" ]; then
  echo "[+] Building installer from the app-image..."
  if [ "$OS" = "Darwin" ]; then
    "$JPACKAGE" --type dmg --app-image "dist/$APP.app" --name "$APP" --app-version "$VERSION" --dest dist
    rm -rf "dist/$APP.app"   # installer-only artifact: keep just the .dmg
  else
    # Portable tarball alongside the .deb: needs no root and no system Java (the runtime is
    # bundled), and tar preserves the executable bits a GitHub-artifact .zip would strip.
    ARCH=$(uname -m)
    tar -C dist -czf "dist/$APP-$VERSION-linux-$ARCH.tar.gz" "$APP"
    echo "      portable tarball -> dist/$APP-$VERSION-linux-$ARCH.tar.gz"
    "$JPACKAGE" --type deb --app-image "dist/$APP" --name "$APP" --app-version "$VERSION" --dest dist
    rm -rf "dist/$APP"       # raw folder removed; the portable .tar.gz + the .deb remain
  fi
fi

echo
echo "======================================================"
echo " Done. Artifacts are under: dist/"
if [ "$INSTALLER" = "1" ]; then
  for f in dist/*.deb dist/*.dmg dist/*.tar.gz; do [ -e "$f" ] && echo "   $f"; done
elif [ "$OS" = "Darwin" ]; then
  echo "   App: dist/$APP.app"
else
  echo "   App: dist/$APP/bin/$APP  (portable app-image)"
fi
echo "======================================================"
