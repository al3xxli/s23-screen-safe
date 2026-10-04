# Screen Safe 0.11 preview — S23 Ultra

This preview blocks the damaged top **20%** and places apps in the remaining physical screen area. Version 0.11 runs the layout controller and touch forwarding inside the activated app process, removing their ongoing dependency on the USB debugging launcher. Version 0.10 corrects a touch-cancellation defect reproduced in Android's actual input dispatcher and suppresses redundant forwarded moves caused solely by blocked ghost contacts. It retains the notification-panel and navigation-spacing corrections. **The original Maps freeze was not captured, so these findings do not establish that every reported freeze is fixed or that ghost coordinates were being remapped. Rotation blink remains unresolved.**

Tested device: Samsung SM-S918W, Android 16 / One UI 8.5, physical resolution 1440 × 3088. No root is required. The natural top 618 pixels (20%, rounded up) are masked; the interface uses the remaining area. The damaged edge follows rotation. The 0.8 Camera rotation-stall correction and the previously confirmed wallpaper correction are retained.

## Start or restore

Connect the phone by USB with debugging authorized, then run **Start Screen Safe.cmd**. It enables the touch filter and starts the preview without a five-minute timeout. The APK alone cannot activate protection. Reconnect and run the launcher after a phone restart or ended session.

Run **Restore Screen.cmd** to stop protection and restore full-screen geometry. The phone app also provides Protect, Restore, and End activated session buttons. If forwarding or restoration fails, reconnect USB and use the computer launcher. A black strip alone does not prove the filter is active.

`Trial.ps1` starts an optional five-minute developer test that attempts automatic restoration. Normal activation uses `Activate.ps1` and has no trial timer.

The updated launchers enable hidden API access only for Screen Safe's instrumentation process, using `am instrument --no-hidden-api-checks`. This permits direct system input and window APIs; no global hidden API policy setting is changed. The bootstrap delegates only input injection, task/window control, internal guard-window, status-bar, and read-only diagnostic permissions (DUMP and PACKAGE_USAGE_STATS, both required for Android's ownership dump). It then destroys the shell automation connection before protection starts. Android removes delegation when instrumentation ends.

## USB tethering and session lifetime

The activated app owns display-area organization and forwards touches directly to Android. Changing USB modes may stop the old shell launcher, but ongoing protection does not call it or run a shell backend. There is no network listener, wireless-debugging requirement, added app, root requirement, or recurring computer task.

The charging default remains a convenience; version 0.11 passed actual USB tethering-mode switches. Android restarted adbd and removed the original launcher, while the same app process retained all nine organized areas and active filtering. The user confirmed responsive taps/swipes, correctly positioned notifications, and lock/unlock behavior afterward. A computer is still needed after reboot, force-stop, an ended instrumentation session, or app-process termination. This is not an unconditional always-on system service. The app already receives foreground priority while its activated instrumentation runs.

Use **Restore full screen** in the app to stop protection, and **Protect top 20%** to restart it during the same activated session, including after a USB mode change. **End activated session** restores first, waits for touch cancellation to finish, then ends the delegated permissions. The computer's **Restore Screen.cmd** remains the recovery path after app termination.

At activation, the shell writes a recovery marker before any resize. The app's own start/stop cycle uses direct Binder calls and keeps this marker for emergency computer recovery; the next computer restore removes it. Read-only ownership checks remain in place to avoid taking over another active display feature.

## Changes and verification

- Cancel only the accepted pointer IDs that are still down. Previously, cancellation after a finger lifted could include that departed pointer. A controlled test on this phone showed Android rejecting that CANCEL and the next DOWN, leaving navigation stuck until a valid cancellation arrived.
- Suppress redundant MOVE events when only rejected ghost contacts change and all accepted fingers remain unchanged. A host reproduction generated 1,002 redundant moves after one legitimate DOWN; the updated filter suppresses all 1,002. Real motion, pressure, other axes, metadata changes, and batched history are retained.
- Report raw events, mixed-contact samples, suppressed stationary moves, active/rejected pointers, maximum physical pointer count, and recovery causes. These distinguish ghost traffic and exhausted or interrupted pointer streams from app-delivered input.
- Give only Samsung's notification-shade display area local window bounds, while keeping its surface at the protected viewport's physical position. This corrects the reproduced right-side notification-card crop.
- Mirror the visible native navigation inset into that shade's local coordinates. Remove it when hidden or restoring, and refresh when its frame changes. Other app areas retain physical bounds and native insets; no global spacer or app-bounds shrink is added.
- Forward touches directly with `InputManagerGlobal`, retaining input-window synchronization before DOWN and after UP without waiting for animations. Gesture filtering and the 0.10 pointer/ghost corrections are retained.
- Cancel an interrupted stream and discard events older than 500 ms, preventing delayed taps from replaying after a stall. Only a subsequent fresh DOWN or POINTER_DOWN can admit a contact. Normal long-held gestures remain valid when their samples are current.
- Report queued events, discarded stale events, maximum queue delay, and maximum injection time in the service dump to distinguish future input stalls from a dead service.
- Retain immediate rotation layout updates, native wallpaper dimensions, the 250 ms surface-position/crop maintenance, shared 20% geometry, and interrupted-gesture/service-reconnection recovery.

Version 0.11 passed all four host suites, Android 36 compilation, and on-device generated-event checks. After the USB launcher died, the app's Restore, Protect, and End buttons were also checked: restoration cleared all nine area overrides, restart resumed filtering, and End restored geometry before its app process exited. Protection is reactivated without a timeout. USB networking throughput was not tested; extended ordinary-use reliability and rotation blink remain open.

Prior 0.10 validation: host gesture/lifecycle checks, Android 36 compilation, and on-device generated-event checks passed. The controlled device reproduction verified Android rejecting the old cancellation and accepting cancellation containing only the remaining pointer. An app receiver observed no extra events during thousands of protected-strip ghost samples; it received only the ten controlled synthetic test events. A later short physical test delivered both single-finger and two-finger gestures with no injection failures, protected-area breaches, or stream anomalies. Simultaneous physical ghost contacts and good fingers were not present in that test; the host stress tests cover that combination. These observations do not establish long-term reliability. The 0.9 captures verified notification cards and navigation spacing in portrait and both landscape directions; its backend and layout are unchanged in 0.10. See [EXPERIMENT.md](EXPERIMENT.md) and [VERIFICATION.md](VERIFICATION.md) for evidence and limits.

The filter rejects a contact reported inside the protected strip for its entire gesture, including batched samples, and forwards accepted coordinates unchanged. It cannot identify a hardware-generated ghost touch reported outside the strip.

The Samsung accessibility touch display can show contacts before the foreground app receives them. Seeing a ghost contact there does not establish that Maps received or remapped it. The developer receiver below measures delivery to an app directly.

The app has no network permission and does not save or transmit touch events. The optional touch probe keeps current finger circles and up to 30 coordinate-free event summaries in memory; they disappear when its process ends. Other enabled accessibility services are preserved; touch-exploration services such as TalkBack are not supported concurrently.

## Development

Build `source/build.ps1` with JDK 17, Android platform 36, Build Tools 36, and R8. Run all four host suites:

```text
python tests/host_checks.py --jdk <JDK-folder>
python tests/backend_checks.py --jdk <JDK-folder>
python tests/shade_checks.py --jdk <JDK-folder>
python tests/embedded_checks.py --jdk <JDK-folder>
```

The gesture suite covers protected/mixed contacts, ghost-only changes while a good finger remains down, pressure and other axes, batched history, cancellation after POINTER_UP, lifecycle recovery, injection flags, and stale-input cancellation. Controller checks cover a non-drawing status area, rapid reversals, shade-only geometry/insets, visibility updates, failed-apply retry, and restoration. Shade checks cover translated/clipped navigation frames in all four rotations and stable source ownership. Host checks do not establish Samsung rendering or physical touch responsiveness.

For on-device generated-event checks, end protection first and run `phone-tools\adb.exe shell am instrument --no-hidden-api-checks -w -r -e test checks ca.screensafe.app/.SessionRunner`, then reactivate. Instrumentation checks replace the active session.

To inspect what reaches an ordinary app window, explicitly launch the diagnostic touchpad and drag or pinch on it:

```text
phone-tools\adb.exe shell am start -n ca.screensafe.app/.TouchProbeActivity
phone-tools\adb.exe shell dumpsys activity ca.screensafe.app/.TouchProbeActivity
```

This activity has no launcher icon and does not inject input or change protection. Its display shows received fingers and the filter's blocked-sample count. The dump reports pointer IDs, devices, event age, protected-area breaches, stream anomalies, and recent coordinate-free summaries. The separate native-dispatcher regression source is retained in `tests/StreamInjectionProbe.java` for advanced investigation; it checks for the diagnostic receiver and attempts cleanup. It is not included in the APK or normal activation.

Work is on `wip/adaptive-rotation`; `main` retains the 0.5 baseline. `SHA256SUMS.txt` covers the APK, backend DEX, and bundled ADB binaries.
