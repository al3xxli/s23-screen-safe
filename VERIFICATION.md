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
- USB disconnection ended the control session at 10:48:04, leaving the installed 0.6-preview APK with a resized layout and inactive filter (one forwarding failure). Logs show a dead UiAutomation owner as adbd restarted. Reactivation restored the wallpaper geometry and active filtering. The launchers now use `nohup setsid` so instrumentation has its own session and process group; the new PID and PGID matched, with parent PID 1 and no terminal. The physical unplug/lock test killed that detached session too, so `setsid` alone does not resolve this failure. USB logs then identified `rndis,adb` changing to `sec_charging,adb` on lock and back on unlock, restarting adbd each time. The phone default was changed from USB tethering to charging (`svc usb setScreenUnlockedFunctions`, then `svc usb setFunctions`, both with no function argument), followed by reactivation. Debugging remains enabled. The user then confirmed that unplugging, locking, unlocking, and reconnecting preserved both layout and touch. ADB independently verified the same controller PID 13962, backend PID 14100, and app PID 13995; filtering remained active, with 230 forwarded events and zero failures. This verifies the tested cycle, not indefinite survival or survival after changing USB mode again. Long-term reliability is not established.

The final preview retains the confirmed wallpaper backend from `c2dcd39`. Normal activation is untimed; `Trial.ps1` is the optional five-minute developer trial. The 0.5 baseline remains on `main`. Details are in [EXPERIMENT.md](EXPERIMENT.md).
