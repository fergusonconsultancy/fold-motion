# Fold Motion

A Louvre Signature fold transition for the Galaxy Z Fold8 Ultra, in Visconti green. As you close the phone, metallic blades turn across the inner screen from left to right, each flashing as it catches the light and casting a shadow ahead of it. A pulse of warm light runs back along the seams with a haptic click, then a green-to-black dusk rolls in from the left so the screen meets One UI's panel switch without a jolt. Opening gets a low haptic settle as the inner screen arrives. The effect learns your closing speed and fits itself inside it, measures its own frame timing, and restarts itself if the phone kills it.

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
