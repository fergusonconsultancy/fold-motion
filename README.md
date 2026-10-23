# Fold Motion

A fold transition for the Galaxy Z Fold8 Ultra. Three styles, chosen in the app:

- **Visconti**: a lacquer arch closes in on the crease (closing); dark glass with Visconti light inside it opens into a lit bezel that seats and slides away (opening).
- **Giulietta**: the same glass opening, woken by the Giulietta's rear lamps. The right ring sweeps round where your fingers left the cover, its beaded bar runs in to the crease, the light crosses, and the left lamp completes as the halves' hold gives.
- **Fanale**: Alfa Romeo as the only influence. Opening, both lamps light in sequence, bloom, and the dark halves part carrying them off the edges. Closing locks the car: the halves slide in lit, meet with a soft close, the lamps go out like an indicator, and the last point of light dips into the hinge.

The effect learns your closing speed and fits itself inside it, measures its own frame timing, and restarts itself if the phone kills it.

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
