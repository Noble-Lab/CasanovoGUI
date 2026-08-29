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
    # A .deb installs under /opt, which is on neither the desktop menu nor PATH, so a user can
    # be left unable to find what they just installed. Two things fix that:
    #
    #   --linux-shortcut  ships a .desktop file and has jpackage's postinst register it with
    #                     xdg-desktop-menu, putting the app in the applications menu. It also
    #                     makes jpackage add Depends: xdg-utils, so the .deb must be installed
    #                     with `apt install ./<file>.deb` rather than a bare `dpkg -i`.
    #   --resource-dir    overrides two of jpackage's Debian maintainer scripts to register the
    #                     launcher under /usr/bin, so it is also on PATH.
    #
    # The PATH entry goes through update-alternatives rather than a bare `ln -sf`. Debian
    # reserves /usr/bin for dpkg-tracked files: a symlink created behind dpkg's back is
    # invisible to `dpkg -S` and to file-conflict detection, and `ln -sf` would silently
    # clobber anything already there. update-alternatives is the mechanism Debian provides for
    # exactly this, and it removes its own link on uninstall.
    #
    # postinst and prerm are overridden because jpackage runs xdg-desktop-menu bare under
    # set -e in both: on a machine with no writable system menu directory (a container, a
    # headless server) that makes the package impossible to install AND impossible to remove.
    # The override file names are jpackage's own (it prints them under --verbose as
    # "add <name> to the resource-dir to customize"); a misspelt name is ignored without
    # warning, so they must match exactly.
    #
    # The scripts are generated here rather than committed so the package name, launcher path
    # and desktop-file name cannot drift away from what jpackage actually produces.
    #
    # jpackage derives the Debian package name from the app name by more than lowercasing, so
    # this only stays correct while the app name needs nothing else doing to it.
    case "$APP" in
      *[!A-Za-z0-9]*) echo "package.sh: APP='$APP' needs more than lowercasing to become a" \
                           "Debian package name; teach PKG jpackage's rule before renaming." >&2
                      exit 1 ;;
    esac
    PKG=$(printf %s "$APP" | tr "[:upper:]" "[:lower:]")
    RES=$(mktemp -d)
    trap 'rm -rf "$RES"' EXIT
    cat > "$RES/postinst" <<EOF
#!/bin/sh
# postinst script for $PKG (overrides the jpackage template; see packaging/package.sh)
set -e

case "\$1" in
    configure)
        # Never let menu registration fail the install: xdg-desktop-menu exits non-zero on a
        # machine with no writable system menu directory (a headless server, a container),
        # and under set -e that leaves the package unconfigurable over a cosmetic shortcut.
        xdg-desktop-menu install /opt/$PKG/lib/$PKG-$APP.desktop || echo "note: no desktop menu entry (no writable menu directory); $PKG is still installed" >&2
        # Put the launcher on PATH; /opt is not searched by default. Priority 100 is arbitrary:
        # nothing else provides this name, so there is no contest to win.
        update-alternatives --install /usr/bin/$PKG $PKG /opt/$PKG/bin/$APP 100
    ;;

    abort-upgrade|abort-remove|abort-deconfigure)
    ;;

    *)
        echo "postinst called with unknown argument \\\`\$1'" >&2
        exit 1
    ;;
esac

exit 0
EOF
    cat > "$RES/prerm" <<EOF
#!/bin/sh
# prerm script for $PKG (overrides the jpackage template; see packaging/package.sh)
#
# jpackage's own prerm is a short script whose one effective statement unregisters the desktop
# entry (its MIME-handler helpers are emitted only when the app declares file associations,
# which this one does not). It runs xdg-desktop-menu bare under set -e, so where there is no
# writable system menu directory it makes the package impossible to REMOVE. A cosmetic menu
# entry must not be able to trap a package on the system, hence this override.
#
# It also drops the PATH entry here rather than in postrm: by the time postrm runs, dpkg has
# already deleted /opt/$PKG, and update-alternatives wants its target to still exist.
set -e

case "\$1" in
    remove|deconfigure)
        xdg-desktop-menu uninstall /opt/$PKG/lib/$PKG-$APP.desktop || echo "note: could not unregister the desktop menu entry" >&2
        update-alternatives --remove $PKG /opt/$PKG/bin/$APP
    ;;

    upgrade|failed-upgrade)
        # Left in place: the new version's postinst re-registers both.
    ;;

    *)
        echo "prerm called with unknown argument: \$1" >&2
        exit 1
    ;;
esac

exit 0
EOF
    # chmod is belt-and-braces: jpackage sets rwxr-xr-x on maintainer scripts as it copies
    # them, but a resource file that arrives non-executable would break the package silently.
    chmod +x "$RES/postinst" "$RES/prerm"
    "$JPACKAGE" --type deb --app-image "dist/$APP" --name "$APP" --app-version "$VERSION" \
      --linux-shortcut --linux-menu-group "Science" --resource-dir "$RES" \
      --dest dist "${ICON_ARG[@]}"
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
