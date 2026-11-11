#!/usr/bin/env bash
# Run by the emulator job: the Fanale opening held at three moments in each of the three lights
# (daylight, dusk, dark), so the lamps' look can be compared. Fails if the app crashed.
set -x
PKG=com.fergusonconsultancy.foldmotion
adb install -r FoldMotion.apk
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell wm dismiss-keyguard; adb shell input keyevent 82
adb shell settings put system screen_off_timeout 1800000
adb logcat -c
# Fanale chosen before the app opens
printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map><string name="style">FANALE</string><string name="opening_effect">FANALE</string><string name="closing_effect">FANALE</string></map>\n' > fm.xml
adb shell am start -n $PKG/.MainActivity; sleep 4; adb shell am force-stop $PKG
adb push fm.xml /data/local/tmp/fm.xml; adb shell chmod 644 /data/local/tmp/fm.xml
adb shell run-as $PKG cp /data/local/tmp/fm.xml shared_prefs/fold_motion.xml
for light in 0.05 0.5 0.95; do
  for p in 0.75 0.45 0.2; do
    adb shell am force-stop $PKG
    adb shell am start -n $PKG/.MainActivity --es hold "$light,$p"
    sleep 4
    adb shell wm dismiss-keyguard
    sleep 1
    adb exec-out screencap -p > "shot-light$light-p$p.png"
  done
done
adb logcat -d > app-log.txt
if adb logcat -d | grep -A2 "FATAL EXCEPTION" | grep -q "Process: $PKG"; then echo "::error::Fold Motion crashed"; grep -A20 "FATAL EXCEPTION" app-log.txt | head -40; exit 1; fi
echo "No crash"
