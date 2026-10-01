# Preview verification — October 1, 2026

## Confirmed

- The user confirmed the lock-screen photograph is fixed.
- Captures show the photo filling the usable area and Camera's portrait controls fitting.
- The user's rotation test produced rotations 0 and 1 and 32 forwarded events with zero injection failures.
- Observed sleep/wake checks retained active filtering.
- The final APK's on-device generated-event checks passed: filtering, missing pointer-up, reused IDs, interruption recovery, failed cancellation, and masks in all four rotations.
- Host checks also passed queued-event discard, service reconnect, enable/disable ordering, and rapid rotation reversals.
- Clean compilation, DEX conversion, resource build, alignment, signing, and signature verification passed.
- Recovery cleared the nine display areas. Rotation preferences were verified as `free` and fixed-to-user-rotation `default`.
- Final normal activation was untimed, with the filter active and zero forwarding errors at verification.

## Still unresolved

- The user reports a harsh rotation blink. Recording reproduces a bad intermediate frame. Three timing/synchronization changes made rendering worse and were reverted.
- Landscape physical touch alignment and all app layouts are not comprehensively verified.
- An earlier trial lost its UiAutomation connection, leaving a resized layout and inactive filter. The cause is unresolved; subsequent short trials stayed connected. Long-term reliability is not established.

The final preview retains the confirmed wallpaper backend from `c2dcd39`. Normal activation is untimed; `Trial.ps1` is the optional five-minute developer trial. The 0.5 baseline remains on `main`. Details are in [EXPERIMENT.md](EXPERIMENT.md).
