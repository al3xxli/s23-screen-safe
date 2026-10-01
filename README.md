# Screen Safe 0.6 preview — S23 Ultra

The lock-screen photograph now fills the usable screen area. **Rotation still produces a harsh blink.** This preview retains the confirmed wallpaper fix while rotation work remains unfinished.

Tested device: Samsung SM-S918W, Android 16 / One UI 8.5, physical resolution 1440 × 3088. No root is required. The natural top 927 pixels are masked; the interface uses the remaining area. The damaged edge follows rotation.

## Start or restore

Connect the phone by USB with debugging authorized, then run **Start Screen Safe.cmd**. It enables the touch filter and starts the preview without a five-minute timeout. The APK alone cannot activate protection. Reconnect and run the launcher after a phone restart or ended session.

Run **Restore Screen.cmd** to stop protection and restore full-screen geometry. The phone app also provides Protect, Restore, and End activated session buttons. If forwarding or restoration fails, reconnect USB and use the computer launcher. A black strip alone does not prove the filter is active.

`Trial.ps1` starts an optional five-minute developer test that attempts automatic restoration. Normal activation uses `Activate.ps1` and has no trial timer.

## USB disconnection and screen locking

Keep **Default USB configuration** set to **No data transfer / Charging** while using this preview, with USB debugging enabled. This phone previously defaulted to USB tethering: locking switched it to charging, then unlocking switched it back. Those changes restarted Android's debugging service and killed Screen Safe's controller, leaving the black guard visible while the content shifted upward.

Changing the default to charging fixed the tested unplug, lock/unlock, and reconnect cycle. The same controller and backend processes survived, and the touch filter stayed active with zero forwarding failures. Shell detachment (`nohup setsid`) alone did not fix the USB-mode restart.

The charging default is already applied to the test phone. This disables automatic USB tethering. If you re-enable tethering, change USB mode, disable debugging, or reboot, protection may end; reconnect and run **Start Screen Safe.cmd** after selecting charging again. The wallpaper fix is retained, and rotation blink remains unresolved.

Developer commands for the tested setting are `adb shell svc usb setScreenUnlockedFunctions` and `adb shell svc usb setFunctions`, both without a function argument. Apply them before activation because changing mode may end a running session. The previous default can be restored with `adb shell svc usb setScreenUnlockedFunctions rndis`; this reintroduces the mode-switch risk.

## Changes and verification

- Keep Samsung's wallpaper-only child at native dimensions while clipping it inside the smaller app viewport.
- Update app bounds, guard, and touch mask for each orientation.
- Avoid redundant guard relayouts and handle rapid queued rotation reversals.
- Retain the interrupted-gesture and service-reconnection recovery from 0.5.

The user confirmed the lock-screen fix. Camera controls fit in portrait. Host checks, on-device generated-event checks, APK compilation, and signature verification passed. Smooth rotation, full landscape touch calibration, all third-party apps, and long-term reliability remain unverified or unresolved. See [EXPERIMENT.md](EXPERIMENT.md) and [VERIFICATION.md](VERIFICATION.md).

The app has no network permission and does not save or transmit touch events. Other enabled accessibility services are preserved; touch-exploration services such as TalkBack are not supported concurrently.

## Development

Build `source/build.ps1` with JDK 17, Android platform 36, Build Tools 36, and R8. Host checks: `python tests/host_checks.py --jdk <JDK-folder>`.

For on-device generated-event checks, end protection first and run `phone-tools\adb.exe shell am instrument -w -r -e test checks ca.screensafe.app/.SessionRunner`, then reactivate. Instrumentation checks replace the active session.

Work is on `wip/adaptive-rotation`; `main` retains the 0.5 baseline. `SHA256SUMS.txt` covers the APK, backend DEX, and bundled ADB binaries.
