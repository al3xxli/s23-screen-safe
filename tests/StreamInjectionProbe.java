import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;

/** Shell regression diagnostic. Requires the blank TouchProbeActivity; never included in the APK. */
public final class StreamInjectionProbe {
    private static final String PROBE = "ca.screensafe.app/ca.screensafe.app.TouchProbeActivity";
    private static final int WAIT_FOR_RESULT = 1;
    private static Object inputManager;
    private static Method inject;
    private static long downTime;
    private static final ArrayList<int[]> cleanupCandidates = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        // Refuse to run on another app or with coordinates for another rotation.
        requireProbeFocus();
        requirePortrait();
        Class<?> global = Class.forName("android.hardware.input.InputManagerGlobal");
        inputManager = global.getMethod("getInstance").invoke(null);
        inject = global.getMethod("injectInputEvent", InputEvent.class, int.class);
        System.out.println("STREAM_PROBE_BEGIN focus=TouchProbeActivity rotation=0 mode=WAIT_FOR_RESULT");
        try {
            runScenario("stale_cancel", 0, 1, 2, true);
            runScenario("active_only_cancel", 3, 4, 5, false);
        } finally {
            // Cleanup must run even if focus changes, an assertion fails, or Binder fails.
            // CANCEL ends an existing stream; it cannot start a tap in another app.
            cleanup("final");
        }
        System.out.println("STREAM_PROBE_END");
    }

    private static void runScenario(String label, int first, int second, int fresh,
                                    boolean staleCancel) throws Exception {
        System.out.println("SCENARIO " + label);
        try {
            downTime = SystemClock.uptimeMillis();
            remember(first);
            requireAccepted(send("down", MotionEvent.ACTION_DOWN, new int[]{first}, true));
            remember(first, second);
            requireAccepted(send("pointer_down", MotionEvent.ACTION_POINTER_DOWN | (1 << 8),
                    new int[]{first, second}, true));
            requireAccepted(send("pointer_up", MotionEvent.ACTION_POINTER_UP | (1 << 8),
                    new int[]{first, second}, true));
            int[] cancelIds = staleCancel ? new int[]{first, second} : new int[]{first};
            boolean cancelAccepted = send("cancel_under_test", MotionEvent.ACTION_CANCEL,
                    cancelIds, true);
            downTime = SystemClock.uptimeMillis();
            remember(fresh);
            boolean freshAccepted = send("fresh_down_after_cancel", MotionEvent.ACTION_DOWN,
                    new int[]{fresh}, true);
            System.out.println("RESULT " + label + " cancelAccepted=" + cancelAccepted
                    + " freshDownAccepted=" + freshAccepted);
        } finally {
            cleanup(label);
        }
    }

    private static void remember(int... ids) {
        cleanupCandidates.add(ids);
    }

    private static void cleanup(String label) {
        if (inject == null) return;
        // A false WAIT_FOR_RESULT result may still have advanced verifier state before
        // delivery failed. Try every possible active set, newest first, until one works.
        // Sets contain only this probe's IDs. An unmatched CANCEL is harmlessly rejected.
        for (int i = cleanupCandidates.size() - 1; i >= 0; i--) {
            try {
                if (send("cleanup_" + label, MotionEvent.ACTION_CANCEL,
                        cleanupCandidates.get(i), false)) break;
            } catch (Exception error) {
                System.out.println("CLEANUP_ERROR " + error.getClass().getSimpleName());
            }
        }
        cleanupCandidates.clear();
    }

    private static boolean send(String step, int action, int[] ids, boolean guard) throws Exception {
        if (guard) {
            requireProbeFocus();
            requirePortrait();
        }
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = ids[i];
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = 450 + i * 450;
            coords[i].y = 1600;
            coords[i].pressure = 1;
            coords[i].size = 0.1f;
        }
        int flags = (action & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_CANCEL
                ? MotionEvent.FLAG_CANCELED : 0;
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                ids.length, props, coords, 0, 0, 1, 1, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, flags);
        try {
            boolean result;
            long started = SystemClock.uptimeMillis();
            try {
                result = Boolean.TRUE.equals(inject.invoke(inputManager, event, WAIT_FOR_RESULT));
            } catch (InvocationTargetException error) {
                throw new IllegalStateException("Injection failed at " + step, error.getCause());
            }
            System.out.println("STEP " + step + " action=" + MotionEvent.actionToString(action)
                    + " ids=" + Arrays.toString(ids) + " flags=" + event.getFlags()
                    + " accepted=" + result + " elapsedMs=" + (SystemClock.uptimeMillis()-started));
            return result;
        } finally {
            event.recycle();
        }
    }

    private static void requireAccepted(boolean accepted) {
        if (!accepted) throw new IllegalStateException("Control stream failed before cancellation test");
    }

    private static void requireProbeFocus() throws Exception {
        Process process = new ProcessBuilder("dumpsys", "window", "displays")
                .redirectErrorStream(true).start();
        boolean found = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("mCurrentFocus=") && line.contains(PROBE)) found = true;
            }
        }
        int code = process.waitFor();
        if (code != 0 || !found) throw new IllegalStateException("TouchProbeActivity must have focus");
    }

    private static void requirePortrait() throws Exception {
        Class<?> global = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object instance = global.getMethod("getInstance").invoke(null);
        Object info = global.getMethod("getDisplayInfo", int.class).invoke(instance, 0);
        Class<?> infoType = info.getClass();
        int rotation = infoType.getField("rotation").getInt(info);
        int width = infoType.getField("logicalWidth").getInt(info);
        int height = infoType.getField("logicalHeight").getInt(info);
        if (rotation != 0 || width != 1440 || height != 3088)
            throw new IllegalStateException("Probe requires native portrait 1440x3088 rotation 0");
    }
}
