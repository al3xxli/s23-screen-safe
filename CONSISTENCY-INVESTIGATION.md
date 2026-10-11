# Screen Safe consistency investigation - October 11, 2026

## What was reproduced

Close All reverted to local y=2285..2439 while the Recents task still had the corrective navigation source at physical y=2752..3088. Adding an equal-frame source under another identity restored local y=2136..2290. Samsung's child layout and Android's installed inset had diverged. Screen Safe's former token/frame cache detected neither the overwritten child layout nor all configuration/resume events, so it never resent unchanged geometry.

Version 0.14 resends that geometry through alternating source identities on task configuration/resume and every two seconds while visible. Hidden tasks receive no periodic mutations. Removing both identities handles partial transactions and restoration. New host tests fail on the old implementation and pass on the repair. Device captures verify the button above navigation after the configuration cycle and repeated entry; source identity changes without geometry changes or source accumulation.

This is a bounded repair for Samsung's internal layout behavior, not a replacement for its layout engine. An asynchronous overwrite can remain until the next visible refresh, so seamless transitions and every launcher variant remain unverified.

## Maps: unintended contact outside the protected strip

The user reported an apparent third touch during two-finger zoom, sometimes reversing intended zoom. The initial coordinated traces did not reproduce it. After installing the bounded recorder, one admission captured three accepted contacts from touchscreen device 6 (rotation 0): two contacts began at (402,1607) and (254,1398), then pointer 2 began at (142,2633). All palm values were zero. The user confirmed using only two fingers with no lower contact intended.

This establishes an unintended extra contact reported inside the usable area before Screen Safe reconstructs/injects the event. Screen Safe did not remap this contact from the top strip. The record does not determine whether the underlying cause is digitizer/firmware behavior or another physical-contact mechanism, and it has no app identity. It explains why the present admission rule can let an unwanted third contact interfere with a pinch; it does not prove a new rejection heuristic would be reliable.

The production filter rejects contacts originating in the top strip, and keeps them rejected if they move out. A fresh contact originating in the usable area is accepted. That is a concrete limitation: software cannot identify a contact as a ghost solely because it is the third one. A two-contact cap could select an earlier ghost and reject a real finger. The previous false-palm fix addresses cancellation, not every possible extra contact.

The newly recorded outside-strip admission is distinct from the previously captured Samsung false-palm cancellation. Historical stale/crossing counters cannot explain individual episodes. No new input-rejection heuristic is shipped in 0.14: more samples are needed before blocking a new region or class of contacts without sacrificing legitimate input.

## Evidence for the next incident

The active service now keeps the last 24 third-or-later accepted pointer admissions in memory. Each records event uptime, display rotation, input device ID, incoming pointer ID, accepted/physical pointer counts, and accepted pointers' start/current positions, palm classification and contact size. It does not record app content, use the network or limit gesture pointer count. Records survive lock/unlock and stream cancellation; new protection activation clears them.

After a real incident, collect:

```text
phone-tools\adb.exe shell dumpsys activity service ca.screensafe.app/.TouchFilterService
```

`extra-contact` lines show the candidate admissions; `extraPointerAdmissions` and `maxAcceptedPointers` summarize the session. A third admission is evidence to inspect, not proof of a ghost. Retain the phone's current protection session until evidence is read.

Private full logs and screenshots are stored in the local work/consistency14 investigation folder, not the Git repository.

The user also confirmed repeated Recents entry stayed correctly positioned without jumping after the v0.14 repair.
