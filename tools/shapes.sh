#!/usr/bin/env bash
# The four shapes of the phone (12 Nov 2026), run first so they always report. The Fold's glass in
# dp, from its own screenshots: cover 411 x 960, main 859 x 896. The emulator can't go past 1920 px,
# so densities are lowered to keep the dp. Landscape is the same glass turned: the display really
# rotates (user_rotation), so the app sees the rotation the phone would report.
#
# For each shape: the settings, with the live stage and the main action in view at rest (checked
# from files/layout.json, which the screen writes once laid out), held on one frame of Fanale at dusk.
PKG=com.fergusonconsultancy.foldmotion

adb shell settings put system accelerometer_rotation 0

shape() {  # WxH (upright) dpi rotation
  adb shell wm density "$2"; adb shell wm size "$1"
  adb shell settings put system user_rotation "$3"
  sleep 3
}

check() {  # name: the stage and the main action end inside the view, clear of the bars
  json=$(adb shell run-as $PKG cat files/layout.json 2>/dev/null)
  python3 - "$1" "$json" <<'PY'
import sys, json
name = sys.argv[1]
try:
    d = json.loads(sys.argv[2])
except Exception:
    print(f"::error::{name}: no layout recorded"); sys.exit(0)
vw, vh = d["view"]; il, it, ir, ib = d["insets"]
ox, oy = d.get("origin", [0, 0])
bad = []
for k in ("live", "action"):
    # The boxes are in window coordinates; the view starts at origin within the window
    l, t, r, b = d[k]
    l -= ox; r -= ox; t -= oy; b -= oy
    if l < 0 or t < 0 or r > vw or b > vh - ib: bad.append(f"{k} at {d[k]} leaves the view {vw}x{vh} (bottom bar {ib})")
    if r - l < 40 or b - t < 20: bad.append(f"{k} is too small: {d[k]}")
lay = "two panes" if d["wide"] else "one column"
print(("::error::" if bad else "::notice::") + f"{name} ({lay}): " + ("; ".join(bad) if bad else f"stage {d['live']} and action {d['action']} in view {vw}x{vh}"))
PY
}

open_settings() {
  adb shell run-as $PKG rm -f files/layout.json
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/.MainActivity --es hold "0.5,0.45" >/dev/null; sleep 6
}

for s in "cover-portrait 823x1920 320 0" "cover-landscape 823x1920 320 1" "main-portrait 1503x1568 280 0" "main-landscape 1503x1568 280 1"; do
  set -- $s
  name=$1
  shape "$2" "$3" "$4"
  open_settings
  # The emulator sometimes ignores a rotation: ask again until the screen really is the right way round
  want=$([ "$4" = 0 ] && echo 0 || echo 1)
  for try in 1 2 3; do
    turned=$(adb shell run-as $PKG cat files/layout.json 2>/dev/null | python3 -c "import sys,json;v=json.load(sys.stdin)['view'];print(int(v[0]>v[1]))" 2>/dev/null)
    [ "$turned" = "$want" ] && break
    echo "rotation not applied (try $try), asking again"
    adb shell settings put system user_rotation 0; sleep 1; adb shell settings put system user_rotation "$4"; sleep 3
    open_settings
  done
  [ "$turned" = "$want" ] || echo "::error::$name: the screen never turned" | tee -a shapes.txt
  adb exec-out screencap -p > "shot-shape-$name.png"
  check "Settings, $name" | tee -a shapes.txt
done

adb shell am force-stop $PKG
adb shell settings put system user_rotation 0
adb shell wm size reset; adb shell wm density reset
sleep 3
