# Experimental candidate status — October 1, 2026

NOT RELEASE-VERIFIED. Read [EXPERIMENT.md](EXPERIMENT.md) for the latest evidence. Host checks and APK build/signature verification passed. The latest retry has been activated: captures show the photo filling the usable area and Camera controls fitting in portrait. Physical rotation and touch alignment remain unverified; previous trials failed the rotation visual check. The instrumentation connection also died during a trial. Full-screen recovery succeeded afterward.

The verification notes below are historical results from version 0.5 and earlier; they do not validate this experimental candidate.

---
# Version 0.5 verification — October 1, 2026

- Production app and backend compiled against Android API 36; APK signature verified.
- Desktop regression checks passed using the actual TouchFilterService.java and TouchFilterChecks.java with Android API fakes. Covered original filtering behavior, lost pointer-up events, pointer-ID reuse, interrupted-stream recovery, failed cancellation, queued pre-lock input, service reconnect, and disable/enable ordering.
- No phone connected over ADB. Device instrumentation checks, freeze reproduction, keyboard behavior, real lock/unlock transitions, S Pen, and prolonged operation have not been tested for version 0.5.
- Changes address confirmed code-state defects; the cause of the user's intermittent freeze remains unconfirmed.

## Historical version 0.4 evidence

# Current rebuild — October 1, 2026

Recovered source compiled to APK and backend DEX; APK signature verification passed. ADB reported no connected devices. All device results below are historical and were not repeated in this workspace.

# Screen Safe 0.4 verification — 2026-09-19

Device: Samsung SM-S918W, Android 16 / One UI 8.5; physical display 1440 × 3088; density override 560. Protected top: 927 pixels. Active height: 2161 pixels.

## Findings and changes

At the start, the previous app process was alive and the layout remained shifted, but its controller and touch guard were absent. The app reported "Stopped. Check the screen is restored." All eight display areas were unorganized while their old bounds remained. The cause of the original controller termination is not established. Removing the guard despite failed restoration was a confirmed failure path.

Version 0.4 adds an accessibility service that consumes SOURCE_TOUCHSCREEN events before normal dispatch. It removes blocked pointer IDs, forwards only accepted fingers with a consistent down/move/up sequence, rejects blocked-origin contacts until release, and cancels accepted gestures that enter the strip. Forwarding uses UiAutomation input injection; it does not re-enter the physical-input filter on this device. No screen-content access is requested. The overlay remains as a second layer.

Failed restoration now retains both protections. The controller timeout uses uptime rather than elapsed time so deep sleep does not count as a lost heartbeat. No global battery or security features were disabled. Only Screen Safe's own accessibility service was added; no other accessibility services were enabled at the start.

## Results

| Check | Result |
|---|---|
| APK | Version 0.4 / code 4 compiled, signed, signature verified, and installed using the original signing key. |
| Touch-sequence checks | Passed on Android: blocked-origin jump into usable area; ghost-first and real-first simultaneous touches; two accepted fingers; cancellation on boundary crossing; exact 927-pixel boundary. Synthetic events go to a test sink and do not inject into other apps. |
| Live input filter | Android reported InputFilterEnabled=true. During the user trial, a sample showed 3,801 blocked contact samples, 709 forwarded events, zero forwarding failures. Counts are event samples, not unique taps. |
| User trial | User confirmed: "Everything works; black-strip touches are ignored" after being asked to scroll/type while touching the black strip with another finger. |
| Guard | Trusted overlay frame/touchable region [0,0][1440,927], identity transform. |
| Timed restoration | Trial ended normally; filter disabled, guard removed, all eight areas unorganized, rotation free, saved recovery marker absent. |
| Failed recovery | Terminated only the Screen Safe controller, temporarily withholding its DEX recovery file. Layout remained shifted, but filter and guard stayed active. A sample showed 2,440 blocked samples and zero forwarding failures during this state. |
| Retry after failure | Restored the original DEX file and pressed Restore. All requested display-area bounds became empty, rotation returned to free, filter disabled, guard absent, recovery marker removed. |
| Windows launcher | Activation enables its service and starts protection automatically, then checks active filtering. Other enabled service IDs are retained. |
| Settings | Density remains 560 and Tap duration flag remains 0, as found. |

The filter's system service must be rebound after an instrumentation process restart. The launcher explicitly removes/re-adds only its own service around activation and checks that it bound and started filtering successfully.

## Limits

This is software event filtering, not sensor/driver region shutdown. Touchscreen events still exist in the hardware and early OS input stages; they are discarded before window/gesture-monitor dispatch. A faulty contact whose first reported coordinate is below pixel 927 cannot be identified as originating in the damaged strip. Input injection into protected coordinates bypasses the early physical-input filter and is instead caught by the overlay.

Cancellation when an accepted finger enters the strip cancels the current accepted gesture, including other accepted fingers in that gesture; it avoids accidental clicks. Lift and restart. Touch exploration, additional displays, landscape, long-term reliability, lock screens, and every app's treatment of injected events remain unverified or unsupported. Process death removes the filter and overlay; the app cannot promise permanent protection after it is killed. Root and bootloader changes were not used.
