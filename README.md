# Screen Safe 0.4 — S23 Ultra prototype

Screen Safe keeps the interface in the bottom 70% of your SM-S918W's screen at full width. Version 0.4 adds early touch filtering to the separate top-30% blocker. **Root is not required.** This portrait-only prototype is for the tested One UI 8.5 / Android 16 phone at 1440 × 3088 resolution.

## What changed

The earlier version's controller had stopped, leaving the screen resized but the touch blocker absent. A black strip alone did not mean protection was active. The updated recovery path retains the blocker and touch filter if restoring the layout fails, and lets you retry restoration.

The new accessibility filter consumes physical touchscreen events before normal window and gesture-monitor dispatch. Contacts that begin in the top 927 pixels remain blocked until lifted, even if their reported position jumps below the boundary. Accepted fingers are forwarded separately, so a blocked finger is removed from the usable area's multitouch stream. If an accepted finger enters the strip, its ongoing gesture is cancelled; lift your fingers and start again. Two accepted fingers can still perform multitouch gestures.

The September 19, 2026 live test discarded thousands of blocked contact samples with no forwarding errors. You confirmed scrolling and typing worked and black-strip touches were ignored. This is software filtering; it does not electrically disable the damaged sensor or guarantee removal of noise that first appears below the boundary.

## Use it now

This package was recovered and rebuilt on October 1, 2026. No phone was connected for this rebuild, so installation and current device behavior have not been verified. After activation, open **Screen Safe** to check for **Touch filter active**.

- **Protect top 30%** starts the resized layout, overlay blocker, and early touch filter after you have restored the full screen.
- **Restore full screen** returns to the original layout and rotation setting and stops filtering.
- **End activated session** restores the screen and ends the computer-authorized session.
- You can disconnect USB after activation. No Internet connection is needed.

The usable area is 1440 × 2161 pixels, beginning at pixel 927. Navigation stays at the bottom. The phone remains upright while protected.

## After a restart or ended session

1. Extract this entire folder on your Windows computer.
2. Connect the unlocked phone by USB, with USB debugging enabled.
3. Double-click **Start Screen Safe.cmd**. Accept a USB-debugging prompt if one appears.
4. The launcher enables protection automatically and checks that the touch filter is active.

The Windows launcher activates the display controller and Screen Safe's accessibility touch filter. It preserves other enabled accessibility services. The touch filter does not request access to window contents; it uses touch coordinates and forwards accepted contacts through the authorized debugging session. Installing the APK alone is insufficient. The filter is inactive after a phone restart until you reactivate protection.

Touch-exploration services such as TalkBack affect Android's touchscreen filtering API and are not supported concurrently by this prototype. Ordinary accessibility services are not suppressed.

## Recovery

If the controller loses contact, it attempts to restore the layout after about eight seconds of awake time. Deep sleep no longer counts toward that timeout. If restoration fails while the app remains alive, the blocker and filter stay active and the app offers a retry. This failure path was tested by terminating the controller while its recovery file was temporarily unavailable, then restoring the file and successfully retrying.

For manual recovery, reconnect USB and double-click **Restore Screen.cmd**. It disables only Screen Safe's accessibility service, stops Screen Safe, and restores the saved layout and rotation. Recovery understands the 10%, 20%, and 30% versions. The screen density and Samsung Tap duration settings are preserved.

If the app itself is stopped or killed, its filter and overlay cannot remain active. A black strip may outlast the process: check Screen Safe's status rather than relying on the strip. Reconnect and run the launcher if activation is needed. Lock-screen behavior, all third-party apps, and long-term reliability remain unverified.

## Contents and implementation

- **ScreenSafe.apk** and **screensafe-backend.dex**: phone app and display controller.
- **Start Screen Safe.cmd**, **Restore Screen.cmd**, **Activate.ps1**: Windows activation and recovery.
- **phone-tools/**: Google's Android Debug Bridge and notices.
- **source/**: Java sources, on-device touch-sequence checks, and build script. Requires JDK 17, Android SDK platform 36, Build Tools 36, and R8.
- **VERIFICATION.md**: measured results and limitations.

Filtering uses Android's [AccessibilityService.onMotionEvent API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#onMotionEvent(android.view.MotionEvent)). Touch events are processed on the phone and not saved or transmitted. This app has no network permission.

