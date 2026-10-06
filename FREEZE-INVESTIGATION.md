# Ordinary-use freeze: false Samsung palm classification

## Failure and cause

On 2026-10-06 the connected phone remained unlocked and ignored the user's physical taps and scrolls. A coordinated recording showed genuine usable-area touchscreen events reaching Screen Safe and being forwarded successfully. Android's input dispatcher delivered them to the foreground app, but Samsung ViewRootImpl converted them into CANCEL events because their palm axis was 1, 2 or 3. The same behavior recurred in the email app. This captured failure was a classification problem after forwarding, not a stopped controller or evidence of coordinate remapping.

The first idle capture did not include user swipes and cannot establish hardware suppression. The coordinated capture contained 2,040 usable-area Y updates. Inspection and captures were read-only until those observations were preserved.

## Native/Java mismatch

This firmware exposes AXIS_PALM=55 and a Java PointerCoords.palm field, but its native bridge carries the axis in mPackedAxisBits/mPackedAxisValues. The native event getter reported 1/2/3 while PointerCoords.getAxisValue(55) returned 0. Setting the Java field left the native value intact. This was reproduced using in-memory Android events without injecting into the phone UI.

The filter resolves the vendor axis by name and normalizes both representations in its own copied coordinates. Only accepted finger contacts are changed. Ghosts starting in the protected area stay rejected even if their reported position moves into the usable area. Native packed-array edits change only the palm element, retaining every other axis and resampling metadata. Batched historical samples receive the same treatment. Real hardware cancellation remains cancellation; stylus metadata and other palm-axis values are preserved.

## Verification and limits

- Regression failed on the old filter and on the Java-field-only attempt.
- Final host gesture/lifecycle suite and four layout/controller suites passed.
- Actual Android MotionEvent tests passed on the phone without interrupting the active controller.
- During the corrected live test, 240 palm samples were normalized, forwarding had zero failures/backlog, and the capture contained no repeated Samsung palm-cancellation messages.
- The user confirmed that scrolling in the email app stayed responsive.

The final stationary-event comparison also reads the packed palm value, preserving changes to non-palm sentinels. This is a firmware-specific workaround for a damaged touchscreen, not proof of permanent reliability. Ghost coordinates outside the protected strip and the harsh rotation blink remain outside this fix. Geometry, Recents spacing and USB-session ownership are unchanged.

Full logs, framework extracts and private screen content are retained only in the local investigation workspace, not in the repository.
