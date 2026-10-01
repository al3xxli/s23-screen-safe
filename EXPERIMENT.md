# Adaptive rotation investigation — October 1, 2026

Status: **unfinished; not a release**. Local branch `wip/adaptive-rotation` is a reviewable checkpoint. `main` retains version 0.5. No GitHub remote has been created or pushed.

## Latest retry — supersedes the candidate description below

The user made the phone available again. The first retry confirmed a photo without a bottom gap, but Camera's controls were cropped: `OneHanded:0:14` contains BOTH apps and wallpaper, so excluding that parent from app resizing was incorrect.

The revised backend now registers Samsung's separate wallpaper-only `RemoteWallpaperAnim:1:1` display area (feature 10002), keeps that child at native display dimensions, and resizes all eight shared OneHanded areas. The child's surface stays local to the parent. Recovery clears all nine areas. Activation rejects an already-organized wallpaper area or unexpected existing wallpaper bounds.

This revision was compiled to DEX, installed, and activated on the connected phone. Captures confirm:

- The lock-screen photograph fills the usable area with no bottom black gap; the clock and shortcuts remain visible.
- Camera's preview, shutter, zoom, and mode controls fit inside the reduced portrait area.
- The filter remained active without forwarding errors through the observed sleep/wake checks; no real user touches had been forwarded at the last inspection, so this does not validate physical touch alignment.

The physical Camera rotation/blink check remains pending user feedback. A requested rotation via `wm user-rotation` while keyguard/secure Camera was active did not change the reported display rotation, so it is not counted as a landscape test. The command was returned to `free` afterward. The five-minute trial remains experimental; the earlier Binder disconnection cause is still unresolved.

## Observed on the connected SM-S918W

- The first adaptive trial made Camera's controls usable. The user confirmed Camera worked.
- The lock-screen clock fit, but the photograph shifted upward, leaving a large bottom gap.
- The user reported a harsh flash/blink on rotation in both tested revisions. Neither visual issue is confirmed fixed.
- Later landscape captures showed incorrect offsets/cropping. Some captures occurred after the controller had died; those cannot establish the active controller's rendering behavior.
- The latest installed trial lost its `UiAutomation` Binder connection after a screen-off transition. Recovery and injection returned `DeadObjectException`; the filter became inactive while the altered layout remained. The trigger for the connection death is not established.
- The experimental recovery command successfully cleared all eight display-area overrides and returned user rotation to `free`. The phone was left at its normal full-screen layout, with Screen Safe protection stopped, while awaiting availability for further testing.

## Prepared after that failed trial — not installed or visually verified

- Keep the wallpaper display area at the native logical bounds; crop and translate its surface separately. The earlier wallpaper dump had an additional vertical offset of -927 pixels when its logical bounds were reduced. This is a candidate correction, not evidence that the photo is fixed.
- Update the black guard only when rotation changes, rather than on every display notification.
- Read rotation and dimensions from one DisplayInfo snapshot. Do not combine an early rotation callback with stale dimensions.
- Remove the experiment that reapplied SurfaceControl transactions every 250 ms. It did not establish a fix and would add continuous work.
- Keep requested touch-filter rotation separately from the worker's applied rotation so a fast turn back to portrait is not lost.
- Make recovery failure text admit that forwarding may be unavailable instead of promising active protection.
- Clear stale Java class files before building so removed anonymous classes cannot enter the APK/DEX.

## Implementation and limits

The mask is the natural top 927 pixels of the 1440 × 3088 display. It maps to the left edge at rotation 1, bottom at 2, and right at 3. The remaining viewport is 1440 × 2161 in portrait and 2161 × 1440 in landscape.

The backend changes Samsung's eight OneHanded display areas through hidden WindowContainerTransaction APIs and combines bounds changes with a synchronized surface transaction. This does not change the root display's logical dimensions and has not established foldable-equivalent behavior for all apps. `DisplayBridge.java` contains the hidden API adapters. `SafeArea.java` defines physical rotation geometry shared by the controller, guard, and filter.

The wallpaper-only surface name `RemoteWallpaperAnim:1:1` and feature 10002 are specific to the observed Samsung display tree. Activation fails if the child cannot be identified. Recovery clears bounds, app bounds, and density-independent size overrides from all registered areas, including the wallpaper child.

The existing instrumentation connection remains a reliability limitation. Guard retention cannot make a dead injection connection work. Do not treat these changes as a fix for the observed Binder failure.

## Checks completed

- Host gesture and lifecycle regressions passed against the production filter and small Android API fakes.
- All four physical mask geometries and rotated touch acceptance/rejection checks passed.
- Rapid queued rotation reversals passed.
- Java compilation, DEX conversion, APK alignment, signing, and signature verification passed for the prepared candidate.
- These checks do not verify Samsung rendering, physical input coordinates, Camera animation, wallpaper placement, or reconnect reliability.

## Next phone test

Wait until the user has finished using the phone. Run `Start Screen Safe.cmd` on this branch to install and start a five-minute trial. Keep USB connected. It attempts automatic restoration after five minutes; `Restore Screen.cmd` is the recovery path if restoration fails.

1. Confirm the controller and filter remain active through the test, with no forwarding failures. Record controller and launcher process lifetimes to diagnose the lost Binder connection before interpreting screenshots.
2. Check the lock-screen photograph with the clock/unlock area still visible; verify the photograph has no bottom gap.
3. In Camera, observe portrait-to-landscape and landscape-to-portrait transitions. Check preview, shutter, modes, and physical touch alignment. Do not take photographs for the test.
4. Check quick rotation reversals, then screen off/on and unlock. End early and restore if touch forwarding fails.
5. Run on-device instrumentation checks only after ending the trial; those checks replace the active instrumentation session.

The failed trial's screenshots, personal phone content, raw device dumps, exports, and signing key are kept out of Git.

## Source reference

The [Android DisplayArea implementation](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/services/core/java/com/android/server/wm/DisplayArea.java) distinguishes container configuration from its surfaces. The [UiAutomationConnection implementation](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/app/UiAutomationConnection.java) owns the shell-command pipes used by this prototype. These references inform the investigation; they do not prove Samsung-specific behavior.
