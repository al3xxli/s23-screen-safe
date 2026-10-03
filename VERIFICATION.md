# Screen Safe 0.9 preview verification — October 3, 2026

## Notification panel

- Reproduced the right-side crop on the connected SM-S918W. The header and quick settings occupied the full width, but notification cards stopped at physical x=822 (1440 minus the 618-pixel protected strip). Window and surface bounds still reported the full usable width.
- Giving only `OneHanded:17:17` (NotificationShade) local bounds eliminated that crop. Its surface remains positioned in the physical safe viewport. Other app areas and wallpaper retain the previously confirmed geometry.
- A first local-bounds trial let the footer overlap navigation. The retained correction translates the visible native navigation frame into shade-local coordinates, without shrinking app bounds or adding a global spacer.
- Final captures show complete notification cards and unobstructed footer controls in portrait and both landscape directions. The first rotation-1 capture caught a collapsed panel; a repeat after settling confirmed its expanded layout. These are settled-layout checks, not a claim of smooth animation.

## Touch responsiveness

- The user reports freezes during ordinary use, sometimes requiring multiple power-button lock/unlock cycles. A frozen moment was not captured during this investigation. The initial 0.8 service was alive with zero reported injection failures; that does not establish that all physical input was working.
- Source inspection found that the two-argument `UiAutomation.injectInputEvent(event, false)` still waits for animations. The retained implementation resolves the three-argument overload before capture starts and passes `false, false`, removing that wait for normal events and cancellation. The launchers permit this hidden API only in the instrumentation process.
- Events older than 500 ms are discarded. An accepted interrupted gesture is canceled using the current timestamp, orphan moves cannot synthesize a click, and a fresh DOWN can recover. Fresh samples from long-held gestures are accepted normally.
- Dumps now report queued/stale events and maximum queue/injection duration. The observed final run processed protected-strip samples with no forwarding failures and no queued backlog. No accepted physical touches had been observed at this checkpoint, so actual injection latency and elimination of the intermittent freeze are **not yet verified**.
- Android still synchronizes input-window metadata during injection. Removing the animation wait does not guarantee that every system-level input stall is impossible.

## Checks and restoration

- All three host suites passed: gesture/lifecycle/injection-flag/stale-event recovery; direct controller rotation updates and shade-only geometry/inset lifecycle; translated/clipped native navigation frames in all four rotations.
- The installed 0.9 APK passed on-device generated-event checks: filtering, pointer recovery, lifecycle cancellation, all four masks, stale-input rejection, and fresh-contact recovery. These use a test sink and do not inject actions into other apps.
- Java compilation against Android 36, DEX conversion, APK alignment, signing, and signature verification passed for `0.9-preview` / code 9.
- Restore cleared requested bounds from all eight OneHanded areas and the wallpaper child. The owned shade inset is explicitly removed during graceful restoration and has Binder-death cleanup; the host suite verifies explicit removal. Protection was then reactivated without a timer.
- Controlled notification-panel rotations restored the current user preferences, `free` / fixed-to-user-rotation `default`. USB remains `sec_charging,adb`; no USB setting was changed. The earlier user-confirmed disconnect workaround is retained.

## Preserved behavior and limits

The mask remains the natural top 618 pixels. Apps use physical bounds with inherited native insets, and the wallpaper child retains full display dimensions at its parent's physical origin. Only the notification-shade area uses local bounds and its corresponding local navigation source. The 0.8 correction that avoids a five-second hidden-status-bar redraw wait is retained.

**Harsh rotation blink remains unresolved.** Physical touch confirmation, long-term reliability/power use, all third-party app layouts, and reverse-portrait device navigation remain unverified. The filter cannot distinguish ghost contacts that hardware reports outside the protected strip.

Private phone screenshots, logs, notification contents, and signing material remain outside Git. No notification action, call control, Camera shutter, or Camera recording was triggered.
