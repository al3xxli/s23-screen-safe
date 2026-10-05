# Recents overlap investigation - October 5, 2026

Initial checkpoint: cause traced and a portrait-only mitigation demonstrated; no production fix installed at that checkpoint. User requested stopping within two minutes before leaving. Version 0.11 remains active. Intermittent touch freezes were explicitly deferred.

## Findings

- RecentsActivity window is correctly at physical (0,618)-(1440,3088), height 2470. Native navigation occupies y=2920..3088 (168 pixels).
- Close All initially occupies local y=2285..2439, physical y=2903..3057, overlapping navigation.
- Initial missing-navigation-inset hypothesis was disproved. Samsung's own RecentStyler log reports standard/layout/scene insets bottom=168 and top=125.
- Inspection of the installed launcher and its logs shows RecentStyler retaining the physical-origin rectangle (0,618)-(1440,3088) when calculating child scene/task coordinates. The resulting task-card bottom is local y=2422. CloseAllPositionHelper mixes those coordinates with bounds.height(), placing the button at y=2285. This is an origin mismatch, not an absent native navigation inset.
- A local-coordinate navigation source on only the Recents root task did not help. Task screen-size-DP changes also did not help. Fullscreen task bounds/appBounds trials did not produce an immediate correction. A temporary parent task-display-area height reduction affected its children; do not ship that broad workaround.
- A final 20-second trial added a Binder-owned navigation source ONLY to the Recents root task, physical frame (0,2580)-(1440,3088). This temporarily increased its bottom inset from 168 to 508. Close All moved to local y=1983..2137 (physical y=2601..2755), visibly clear of navigation. This is a compensation, not a principled origin correction; the fixed number must not be shipped without orientation/layout analysis.

## Cleanup and next implementation requirements

- Every temporary test removed its changes in finally. Final Recents dump contains only the native navigation source. DefaultTaskDisplayArea requested bounds are empty; it inherits the established protected viewport. Existing app/filter remained active; no APK or backend was replaced.
- Production work should scope any compensation to the exact Samsung Recents activity on display 0, retain a stable Binder owner, handle task replacement and rotation, remove the source on Restore/End and failure, and preserve ordinary app/shade/wallpaper geometry. Prefer a verified coordinate-origin correction if one is available without changing ordinary apps.
- Validate repeated Recents entry, app switching, both landscape directions, native bar visibility, Restore/Protect and USB launcher independence. Physical touch and ordinary-use freeze reliability remain separate and unverified.
- Private captures, logs, extracted launcher code/APK and the bounded trial are in the task workspace under work/recents12, outside Git. No Close All action or app dismissal was performed.

## Resumed implementation

Version 0.12 implements a narrower, measured compensation: one extra native navigation-bar height (168 pixels on the tested phone), capped by the viewport vertical offset. It targets only Samsung Recents on the primary display, keeps a stable Binder owner, handles task replacement, and removes the source outside portrait or during restoration. A 750 ms post-configuration settling period prevents Samsung from overwriting the correction on rotation. Failed cosmetic updates back off and retry without disabling core screen/touch protection. See VERIFICATION.md for measurements and limits; the original 508-pixel test source was not shipped.
