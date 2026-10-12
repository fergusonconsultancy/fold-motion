# Fold Motion

A right-to-left fold transition for the Galaxy Z Fold8 Ultra. Closing plays Roll: a glossy cylinder rolls across the inner screen laying a dark sheet behind it. Opening plays Slide: a tinted sheet glides across the cover. Both finish as One UI switches panels, and the screen you arrive on is never touched.

## Get the APK (no Android Studio needed)

1. Push this folder to a GitHub repository.
2. Open the repository's **Actions** tab. The **Build APK** workflow runs on every push.
3. When it goes green, open the run and download **FoldMotion-apk**.
4. Unzip it, copy the `.apk` to the phone and open it. Allow installs from that source when asked.

## First run on the phone

1. Open **Fold Motion**. The hinge readout should move as you open and close the phone.
2. Tap **Allow display over other apps** and switch it on.
3. Tap **Start animation**. A silent notification confirms it is running.

Timing follows Developer options > Animator duration scale.

## Tuning

All values live in `MotionConfig.kt`. Change a number, push, and Actions builds a new APK.

## Limits

- This is an overlay, not a system animation. One UI's own display switch still happens underneath.
- The overlay cannot draw over the lock screen.
- Untested on hardware at the time of writing.
