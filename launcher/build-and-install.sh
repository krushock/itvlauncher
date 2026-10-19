#!/usr/bin/env bash
# Build the launcher APK and install it on the TV as the default home screen.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.tvlauncher}"
# Google TV's setup app has a "recovery" home screen that outranks any installed launcher once the stock one is
# off (Home then shows a black screen). Android won't let adb switch off just that screen, so the whole setup app
# goes; the TV is already set up, and restore.sh or a factory reset turns it back on. Absent elsewhere: no-op.
SETUP_RECOVERY=com.google.android.tungsten.setupwraith
# JDK 17 or newer: Android Studio's bundled one first, then any the system knows about.
if [ -z "${JAVA_HOME:-}" ]; then
  for jbr in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
             "/Applications/Android Studio Preview.app/Contents/jbr/Contents/Home"; do
    [ -x "$jbr/bin/java" ] && { JAVA_HOME=$jbr; break; }
  done
  JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17+ 2>/dev/null || echo /opt/homebrew/opt/openjdk)}"
fi
export JAVA_HOME
export ANDROID_HOME="${ANDROID_HOME:-$([ -d ~/Library/Android/sdk ] && echo ~/Library/Android/sdk || echo /opt/homebrew/share/android-commandlinetools)}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
# A signed release build when the signing key is set up (see README), else a debug build. BUILD_VARIANT=debug or
# release overrides. A TV only takes updates signed with the key it already has.
SIGNING="${ITVLAUNCHER_SIGNING:-$HOME/.config/krushock/itvlauncher-signing.properties}"
if [ -f "$SIGNING" ]; then VARIANT=release; else VARIANT=debug; fi
VARIANT="${BUILD_VARIANT:-$VARIANT}"
case "$VARIANT" in
  release) ./gradlew assembleRelease -q --console=plain ;;
  *) VARIANT=debug; ./gradlew assembleDebug -q --console=plain ;;
esac
echo "Built the $VARIANT version"
PY=../tools/venv/bin/python
[ -x "$PY" ] || PY=../tools/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
APK=app/build/outputs/apk/$VARIANT/app-$VARIANT.apk
"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/tvhome.apk
# Check each step before the next: disabling the stock launcher without a working replacement
# leaves Home on a factory test app.
out=$("$PY" -I ../tools/tv.py "pm install -r /data/local/tmp/tvhome.apk")
echo "$out"
"$PY" -I ../tools/tv.py "rm -f /data/local/tmp/tvhome.apk"
case "$out" in
  *Success*) ;;
  *UPDATE_INCOMPATIBLE*) echo "The TV has this launcher signed with another key (debug vs release). Back up the layout, uninstall it (pm uninstall com.krushock.itvlauncher) and run this again; the stock launcher was left untouched" >&2; exit 1 ;;
  *) echo "pm install failed; the stock launcher was left untouched" >&2; exit 1 ;;
esac
out=$("$PY" -I ../tools/tv.py "cmd package set-home-activity --user 0 com.krushock.itvlauncher/.MainActivity")
echo "$out"
case "$out" in *[Ee]rror*) echo "could not set the new launcher as home; the stock launcher was left untouched" >&2; exit 1 ;; esac
printf '%s\n' \
  "pm grant com.krushock.itvlauncher android.permission.ACCESS_FINE_LOCATION 2>/dev/null; pm grant com.krushock.itvlauncher android.permission.ACCESS_COARSE_LOCATION 2>/dev/null; echo location-granted" \
  "pm grant com.krushock.itvlauncher android.permission.READ_EXTERNAL_STORAGE 2>/dev/null; pm grant com.krushock.itvlauncher android.permission.READ_MEDIA_IMAGES 2>/dev/null; echo photos-granted" \
  "pm grant com.krushock.itvlauncher android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null; echo backup-granted" \
  "cmd notification allow_dnd com.krushock.itvlauncher 2>/dev/null; echo dnd-granted" \
  "pm grant com.krushock.itvlauncher android.permission.READ_TV_LISTINGS 2>/dev/null; echo top-shelf-granted" \
  "appops set com.krushock.itvlauncher GET_USAGE_STATS allow 2>/dev/null; echo recent-apps-granted" \
  "sleep 2; cmd notification allow_listener com.krushock.itvlauncher/com.krushock.itvlauncher.MediaWatcher 2>/dev/null; echo now-playing-granted" \
  "pm disable-user --user 0 $STOCK_LAUNCHER" \
  "pm disable-user --user 0 $SETUP_RECOVERY 2>/dev/null; echo setup-recovery-off" \
  "cmd package set-home-activity --user 0 com.krushock.itvlauncher/.MainActivity" \
  | "$PY" -I ../tools/tv.py --batch
home=$("$PY" -I ../tools/tv.py "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1")
case "$home" in
  *com.krushock.itvlauncher*) echo "Installed. Press Home on the remote to see it." ;;
  *) echo "Installed, but Home still opens $home (see README: Working on the TV)." >&2; exit 1 ;;
esac
