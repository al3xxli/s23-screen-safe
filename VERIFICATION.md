# Screen Safe 0.10 preview verification — October 3, 2026

## Ghost-contact and cancellation findings

- The user observed blocked-region ghost contacts with the Samsung accessibility menu while Maps was unresponsive. The original Maps failure was not captured. The accessibility display alone does not prove the foreground app received or remapped those contacts.
- A host reproduction showed the previous filter forwarding 1,002 redundant MOVE events after one accepted DOWN when only blocked ghost contacts changed. The updated filter suppresses all 1,002, while retaining real accepted-pointer movement, pressure, other axes, and batched history.
- A controlled test on the connected SM-S918W exercised the actual Android input dispatcher, using untargeted `InputManagerGlobal` injection into the explicitly opened diagnostic touchpad. DOWN 0, POINTER_DOWN 1, and POINTER_UP 1 were accepted. Old-style CANCEL `{0,1}` was rejected, and fresh DOWN 2 was rejected. CANCEL `{0}` was accepted and recovered the stream. A separate corrected sequence accepted CANCEL `{3}` and then fresh DOWN 5.
- That test establishes an invalid-cancellation path that can leave subsequent gestures rejected. The production fix builds CANCEL from only the accepted pointers still down, rather than retaining a pointer that already lifted. Samsung automatically adds `FLAG_CANCELED` to injected cancellation; a missing cancellation flag was not the cause.
- A raw physical-input snapshot placed a ghost at y=205, within the protected 618-pixel region. During thousands of protected ghost samples, the app receiver saw no additional input beyond the ten controlled synthetic test events. Those events had device ID -1, no raw-coordinate SafeArea violations, and no stream anomalies. No ghost-coordinate remapping was observed in that receiver test.
- A later short physical test delivered single-finger and two-finger gestures to the final 0.10 touchpad. Filter telemetry recorded 645 raw events, 635 forwarded events, zero failures, zero stale events, no queued backlog, and a maximum injection time of 41 ms. The app received 349 events after batching, two DOWN/UP pairs, one POINTER_DOWN/POINTER_UP pair, a maximum of two pointers, device IDs `[-1]`, and no active pointers left afterward. It reported no SafeArea breaches or stream anomalies; maximum event age was 93 ms. `mixedSamples=0` and `suppressedStationary=0`, so simultaneous physical ghosts and good fingers were not exercised in that short test.
- These results verify specific mechanisms and checkpoint behavior. They do not establish that every reported ordinary-use freeze is eliminated, nor substitute for a capture during the original Maps failure.

## Diagnostics and retained touch behavior

- The filter dump now includes `rawEvents`, `mixedSamples`, `suppressedStationary`, active/rejected pointer counts, `maxPhysicalPointers`, `maxQueued`, and cancellation/recovery causes, alongside the existing stale-event and injection-latency telemetry.
- `TouchProbeActivity` is launched explicitly with ADB and has no home-screen launcher entry. It consumes app-delivered touches and displays received fingers plus the filter's blocked-sample count. Its in-memory dump includes event counts, current pointer IDs, devices, maximum event age, SafeArea breaches, stream anomalies, and 30 coordinate-free summaries. It does not inject input, persist touch data, or use the network.
- The 0.9 forwarding changes remain: three-argument `UiAutomation` injection with both waiting flags false, process-scoped hidden API access, and cancellation/discard of events older than 500 ms. Fresh samples from long-held gestures remain valid. Android still synchronizes input-window metadata, so removing animation waits does not exclude every system-level stall.

## Checks and deployment

- The host gesture/lifecycle suite passed for 0.10, including injection/stale-event cases, ghost-only traffic, history preservation, and active-pointer cancellation regressions. The unchanged controller and shade suites passed at the 0.9 checkpoint; they cover rotation, shade-only geometry/inset lifecycle, and translated/clipped native navigation frames in all four rotations.
- Production sources compile against Android 36 for `0.10-preview` / code 10. The final build passed on-device generated-event checks, including the new ghost-traffic and pointer-cancellation cases. Those checks use a test sink and are distinct from the actual native-dispatcher reproduction described above.
- Version 0.10 / code 10 is installed with protection active and no timeout. The short physical single-finger and two-finger checks above passed; extended ordinary-use and Maps reliability remain unverified.
- No backend/layout changes were made for 0.10. Its backend DEX is unchanged from the 0.9 build. The prior graceful-restore check cleared all eight OneHanded areas and the wallpaper child; shade-source removal remains covered by host checks.
- The shell regression source is retained at `tests/StreamInjectionProbe.java`, with a foreground-receiver guard and cleanup attempt. It is not included in the APK or normal activation. Private execution output remains outside Git.
- Current rotation preferences remain `lock 0` / fixed-to-user-rotation `default`, with USB `sec_charging,adb`. This investigation did not change rotation or USB settings.

## Notification panel and preserved layout

The 0.9 phone investigation reproduced cards ending at physical x=822 while the panel header occupied the full width. Giving only `OneHanded:17:17` local bounds corrected that crop, with its surface still at the physical protected viewport origin. A shade-only translated native navigation inset corrected footer overlap without shrinking app bounds or adding a global spacer. Settled captures then verified complete cards and unobstructed footer controls in portrait and both landscape directions. These are retained prior-version checks, not new 0.10 animation tests.

The mask remains the natural top 618 pixels. Other app areas keep physical bounds and native insets; the wallpaper child retains native display dimensions. The 0.8 correction avoiding a five-second hidden-status-bar redraw wait, untimed activation, and the user-confirmed charging USB disconnect workaround remain unchanged.

**Harsh rotation blink remains unresolved.** Long-term touch reliability/power use, all third-party app layouts, and reverse-portrait device navigation remain unverified. The filter cannot distinguish ghost contacts that hardware reports outside the protected strip.

Private phone captures, logs, notification contents, native-probe output, and signing material remain outside Git. The diagnostic tests did not perform notification actions, call controls, Camera shutter actions, or Camera recording.
