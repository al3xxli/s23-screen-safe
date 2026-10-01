# Screen Safe 0.8 preview verification — October 1, 2026

## Camera rotation correction

- Reproduced the 0.7 failure on SM-S918W: an all-display-area synchronized resize waited five seconds for `OneHanded:15:15`, which contains the hidden status bar. Android logged a BLAST sync timeout and dependent rotation groups. A landscape capture showed Camera at its old portrait position with most controls cropped.
- Removed that redraw dependency. Bounds now apply directly, then the controller positions/crops its owned surfaces. There is no outstanding resize callback or pending-layout flag to defer later turns.
- Controlled Camera tests covered rotations 0 → 1 → 0 → 3 → 0 and rapid reversals. Both landscape directions retained usable controls and the filter remained active with zero forwarding failures. The captured revised run did not contain the reproduced sync timeout.
- The user confirmed the shift correction: "Still present, but at least it's usable" (blink versus usability), then "You've fixed it for now."
- The additional app-only synchronized-redraw trial still showed transient cropping, so it was discarded. The user-confirmed direct-apply behavior is retained.

## Automated checks and build

- Gesture/lifecycle host regressions passed: protected-boundary filtering, unchanged forwarded coordinates, unsafe historical samples, mixed pointers, interrupted streams, service reconnect, and rapid rotation reversals.
- New controller host regressions passed with a status area that never sends a redraw callback. They cover all four viewport rotations, immediate reversals, physical bounds, inherited app insets, native wallpaper dimensions, inconsistent display snapshots, failure retry, surface repair, and no changes after restoration.
- The same controller suite was run against the 0.7 backend as a negative control and failed at the expected stalled-rotation assertion.
- Host tests use Android API fakes; they are not visual or physical-touch tests. The unchanged filter previously passed generated-event checks on the phone in 0.7.
- Clean Java compilation, DEX conversion, APK alignment, signing, and signature verification passed for `0.8-preview` / code 8.
- The final build was reactivated without a timer. The installed backend SHA-256 matched the saved DEX; the filter was active with zero forwarding failures, and original rotation/USB settings were verified.

## Preserved behavior and limitations

The protected strip remains 618 of 3088 physical pixels. The portrait viewport is `(0,618)-(1440,3088)`. App bounds inherit normally, with no extra navigation spacer or synthetic inset. The wallpaper child retains full display dimensions at the parent's physical origin. The 250 ms surface repair remains active. Those layout choices were user-confirmed in 0.7 and are unchanged here.

**Harsh rotation blink is still unresolved.** Frame inspection shows brief intermediate crop/position mismatches. This revision fixes the reproduced prolonged unusable layout; it does not guarantee every app or transition. Long-term reliability/power use, full physical-touch calibration, reverse-portrait device navigation, and ghost touches reported outside the strip are not established.

Controlled rotation tests restored the user's `lock 0` / fixed-to-user-rotation `default` preferences. The charging USB workaround (`sec_charging,adb`) remains in place; its unplug/lock/unlock survival was user-confirmed earlier. Normal activation remains untimed. Phone reboot, changing USB mode, or ending instrumentation can require reactivation.

Private phone captures/logs and the rejected experiment remain outside Git. No Camera photograph or Camera video was taken.
