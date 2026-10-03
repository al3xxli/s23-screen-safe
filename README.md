# Screen Safe 0.9 preview — S23 Ultra

This preview blocks the damaged top **20%** and places apps in the remaining physical screen area. Version 0.9 corrects the reproduced notification-card crop and adds touch recovery changes for the reported intermittent unresponsiveness. Settled portrait and both landscape captures now show complete notification cards and unobstructed footer controls. **The intermittent touch freeze has not been reproduced during this investigation, so its elimination is not yet verified. Rotation blink remains unresolved.**

Tested device: Samsung SM-S918W, Android 16 / One UI 8.5, physical resolution 1440 × 3088. No root is required. The natural top 618 pixels (20%, rounded up) are masked; the interface uses the remaining area. The damaged edge follows rotation. The 0.8 Camera rotation-stall correction and the previously confirmed wallpaper correction are retained.

## Start or restore

Connect the phone by USB with debugging authorized, then run **Start Screen Safe.cmd**. It enables the touch filter and starts the preview without a five-minute timeout. The APK alone cannot activate protection. Reconnect and run the launcher after a phone restart or ended session.

Run **Restore Screen.cmd** to stop protection and restore full-screen geometry. The phone app also provides Protect, Restore, and End activated session buttons. If forwarding or restoration fails, reconnect USB and use the computer launcher. A black strip alone does not prove the filter is active.

`Trial.ps1` starts an optional five-minute developer test that attempts automatic restoration. Normal activation uses `Activate.ps1` and has no trial timer.

The updated launchers enable hidden API access only for Screen Safe's instrumentation process, using `am instrument --no-hidden-api-checks`. This permits the injection overload that skips animation waits; no global hidden API policy setting is changed.

## USB disconnection and screen locking

Keep **Default USB configuration** set to **No data transfer / Charging** while using this preview, with USB debugging enabled. This phone previously defaulted to USB tethering: locking switched it to charging, then unlocking switched it back. Those changes restarted Android's debugging service and killed Screen Safe's controller, leaving the black guard visible while the content shifted upward.

Changing the default to charging fixed the tested unplug, lock/unlock, and reconnect cycle. The same controller and backend processes survived, and the touch filter stayed active with zero forwarding failures. Shell detachment (`nohup setsid`) alone did not fix the USB-mode restart.

The charging default is already applied to the test phone. This disables automatic USB tethering. If you re-enable tethering, change USB mode, disable debugging, or reboot, protection may end; reconnect and run **Start Screen Safe.cmd** after selecting charging again. The wallpaper fix is retained, and rotation blink remains unresolved.

Developer commands for the tested setting are `adb shell svc usb setScreenUnlockedFunctions` and `adb shell svc usb setFunctions`, both without a function argument. Apply them before activation because changing mode may end a running session. The previous default can be restored with `adb shell svc usb setScreenUnlockedFunctions rndis`; this reintroduces the mode-switch risk.

## Changes and verification

- Give only Samsung's notification-shade display area local window bounds, while keeping its surface at the protected viewport's physical position. This corrects the reproduced right-side notification-card crop.
- Mirror the visible native navigation inset into that shade's local coordinates. Remove it when hidden or restoring, and refresh when its frame changes. Other app areas retain physical bounds and native insets; no global spacer or app-bounds shrink is added.
- Forward touches without waiting for window animations. The prior two-argument `UiAutomation` call waited for animations even with asynchronous injection; Android's input-window synchronization still remains. See the [Android 16 UiAutomation implementation](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/core/java/android/app/UiAutomation.java).
- Cancel an interrupted stream and discard events older than 500 ms, preventing delayed taps from replaying after a stall. Only a subsequent fresh DOWN or POINTER_DOWN can admit a contact. Normal long-held gestures remain valid when their samples are current.
- Report queued events, discarded stale events, maximum queue delay, and maximum injection time in the service dump to distinguish future input stalls from a dead service.
- Retain immediate rotation layout updates, native wallpaper dimensions, the 250 ms surface-position/crop maintenance, shared 20% geometry, and interrupted-gesture/service-reconnection recovery.

All three host suites, Android 36 compilation, and APK signature verification passed. Version 0.9 is installed and active on the test phone. Generated-event checks on the phone also passed. Captures verify notification cards and navigation spacing in portrait and both landscape directions; physical user input with this version and long-term touch reliability still need confirmation. The earlier 0.8 Camera tests and user feedback established a usability improvement, not seamless rendering. See [EXPERIMENT.md](EXPERIMENT.md) and [VERIFICATION.md](VERIFICATION.md) for evidence and limits.

The filter rejects a contact reported inside the protected strip for its entire gesture, including batched samples, and forwards accepted coordinates unchanged. It cannot identify a hardware-generated ghost touch reported outside the strip.

The app has no network permission and does not save or transmit touch events. Other enabled accessibility services are preserved; touch-exploration services such as TalkBack are not supported concurrently.

## Development

Build `source/build.ps1` with JDK 17, Android platform 36, Build Tools 36, and R8. Run all three host suites:

```text
python tests/host_checks.py --jdk <JDK-folder>
python tests/backend_checks.py --jdk <JDK-folder>
python tests/shade_checks.py --jdk <JDK-folder>
```

The gesture suite covers protected/mixed contacts, batched unsafe samples, lifecycle recovery, injection flags, and stale-input cancellation. Controller checks cover a non-drawing status area, rapid reversals, shade-only geometry/insets, visibility updates, failed-apply retry, and restoration. Shade checks cover translated/clipped navigation frames in all four rotations and stable source ownership. Host checks do not establish Samsung rendering or physical touch responsiveness.

For on-device generated-event checks, end protection first and run `phone-tools\adb.exe shell am instrument --no-hidden-api-checks -w -r -e test checks ca.screensafe.app/.SessionRunner`, then reactivate. Instrumentation checks replace the active session.

Work is on `wip/adaptive-rotation`; `main` retains the 0.5 baseline. `SHA256SUMS.txt` covers the APK, backend DEX, and bundled ADB binaries.
