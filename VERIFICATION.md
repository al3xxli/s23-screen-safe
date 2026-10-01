# Screen Safe 0.7 preview verification — October 1, 2026

## Implemented and checked

- Top 20% protection uses 618 of 3088 physical pixels, shared by the guard, filter, and display controller. The portrait viewport is `(0,618)-(1440,3088)`.
- App display areas use physical window bounds and inherit app bounds. Android reports the native status bar at `(0,618)-(1440,743)` and navigation bar at `(0,2920)-(1440,3088)` in portrait. No extra bottom spacer or local inset source is installed.
- Transitions were observed resetting the organized parent surface to an identity transform, while the configured viewport remained resized. The controller now reapplies its owned surface position/crop during its existing 250 ms check. Settled captures after app switching and in rotations 0, 1, and 3 no longer show the displaced/cropped app panel.
- The wallpaper child keeps native dimensions with the same origin as its parent. Its crop is supplied by the parent.
- Host checks passed 20% boundary rejection/acceptance, unchanged coordinates in the newly exposed band, mixed pointers, crossing cancellation, unsafe historical samples in a batched MOVE, lost UP, reused pointer IDs, interruption recovery, service reconnect, and rapid rotation reversal. Generated-event checks on the phone passed the updated filter suite; these checks do not inject input into other apps.
- Clean compilation, DEX conversion, APK alignment, signing, and signature verification passed. Installed version is `0.7-preview` / code 7.
- Restore was exercised from the new physical-bounds layout: all nine display areas returned to empty requested bounds, with no custom inset sources left behind. Protection was then reactivated without a timer.
- Both forced landscape directions were tested, then the user's existing rotation preferences were restored to `lock 0` / fixed-to-user-rotation `default`. Sleep/wake retained an active filter with zero forwarding failures.

## User validation and limits

The user confirmed the final candidate: "Yes, everything fits now" when asked about the bottom gap, navigation overlap, and lock-screen photograph. The first local-inset trial was rejected after the user reported a large bottom gap; that implementation is not retained.

Rotation blink remains unresolved. The position check restores settled geometry but is not synchronized to every animation frame. Long-term power use/reliability, complete physical-coordinate calibration, reverse-portrait system navigation, every third-party app, and ghost touches reported outside the protected strip are not established by these checks.

## USB survival workaround retained

The previous session failure was traced to the default USB tethering configuration switching to charging on lock and back on unlock, restarting adbd and killing the controller. The phone remains on charging with USB debugging enabled (`sec_charging,adb`). The user previously confirmed a successful unplug/lock/unlock/reconnect cycle with that configuration, independently verified by surviving controller/backend PIDs and zero forwarding failures. `nohup setsid` alone did not fix USB-mode restarts. Rebooting or changing USB mode can still require reactivation.

Private captures and logs remain outside Git. No camera shutter, purchase, or account-setting action was performed.
