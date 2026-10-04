package ca.screensafe.app;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import ca.screensafe.core.SafeArea;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Explicitly launched diagnostic receiver. Keeps its observations in memory only. */
public final class TouchProbeActivity extends Activity {
    public static volatile TouchProbeActivity current;
    private final Object lock = new Object();
    private final Handler handler = new Handler();
    private final Set<Integer> active = new LinkedHashSet<>();
    private final Set<Integer> deviceIds = new TreeSet<>();
    private final Map<Integer, float[]> circles = new LinkedHashMap<>();
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private long events, downs, ups, pointerDowns, pointerUps, cancels;
    private long maxEventAge, outsideEvents, outsideSamples;
    private long newDownDuringActive, idMismatch, eventsWithoutDown;
    private int maxPointers;
    private ProbeView pad;
    private final Runnable refresh = new Runnable() {
        public void run() {
            if (pad != null) pad.invalidate();
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        pad = new ProbeView();
        pad.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            }
        });
        setContentView(pad);
        pad.requestApplyInsets();
        current = this;
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        // Preserve the stream for diagnosis: a missing CANCEL remains observable.
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(refresh);
        if (current == this) current = null;
        super.onDestroy();
    }

    /** Test harness entry point; resets this receiver without altering the filter. */
    void reset() {
        synchronized (lock) {
            events = downs = ups = pointerDowns = pointerUps = cancels = 0;
            maxEventAge = outsideEvents = outsideSamples = 0;
            newDownDuringActive = idMismatch = eventsWithoutDown = 0;
            maxPointers = 0;
            active.clear(); deviceIds.clear(); circles.clear(); recent.clear();
        }
        if (pad != null) pad.postInvalidate();
    }

    private void receive(MotionEvent event) {
        synchronized (lock) {
            events++;
            int action = event.getActionMasked();
            int index = event.getActionIndex();
            int count = event.getPointerCount();
            int changed = index < count ? event.getPointerId(index) : -1;
            Set<Integer> present = new LinkedHashSet<>();
            int rotation = getDisplay() == null ? 0 : getDisplay().getRotation();
            SafeArea area = new SafeArea(rotation);
            int outside = 0;
            for (int i = 0; i < count; i++) {
                present.add(event.getPointerId(i));
                if (!area.contains(event.getRawX(i), event.getRawY(i))) outside++;
            }
            if (outside > 0) outsideEvents++;
            outsideSamples += outside;
            maxPointers = Math.max(maxPointers, count);
            maxEventAge = Math.max(maxEventAge,
                    Math.max(0, SystemClock.uptimeMillis() - event.getEventTime()));
            deviceIds.add(event.getDeviceId());

            boolean touchAction = action == MotionEvent.ACTION_DOWN
                    || action == MotionEvent.ACTION_POINTER_DOWN
                    || action == MotionEvent.ACTION_MOVE
                    || action == MotionEvent.ACTION_POINTER_UP
                    || action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL;
            if (touchAction && action != MotionEvent.ACTION_DOWN && active.isEmpty())
                eventsWithoutDown++;
            if (present.size() != count) idMismatch++;
            if (action == MotionEvent.ACTION_DOWN) {
                downs++;
                if (!active.isEmpty()) newDownDuringActive++;
                if (count != 1 || index != 0) idMismatch++;
                active.clear(); active.addAll(present);
            } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
                pointerDowns++;
                Set<Integer> expected = new LinkedHashSet<>(active);
                boolean added = expected.add(changed);
                if (!added || !expected.equals(present)) idMismatch++;
                active.clear(); active.addAll(present);
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (!active.equals(present)) idMismatch++;
                // Retain the last known active IDs rather than inventing a DOWN.
            } else if (action == MotionEvent.ACTION_POINTER_UP) {
                pointerUps++;
                boolean sameIds = active.equals(present);
                boolean removed = active.remove(changed);
                if (!sameIds || !removed) idMismatch++;
            } else if (action == MotionEvent.ACTION_UP) {
                ups++;
                if (count != 1 || !active.equals(present)) idMismatch++;
                active.clear();
            } else if (action == MotionEvent.ACTION_CANCEL) {
                cancels++;
                if (!active.isEmpty() && !active.equals(present)) idMismatch++;
                active.clear();
            }
            if (touchAction) {
                circles.clear();
                for (int i = 0; i < count; i++) {
                    int id = event.getPointerId(i);
                    if (active.contains(id)) circles.put(id,
                            new float[] {event.getX(i), event.getY(i)});
                }
            }
            if (recent.size() == 30) recent.removeFirst();
            recent.addLast("#" + events + " " + MotionEvent.actionToString(event.getAction())
                    + " ids=" + present + " active=" + active
                    + " source=0x" + Integer.toHexString(event.getSource())
                    + " device=" + event.getDeviceId()
                    + " flags=0x" + Integer.toHexString(event.getFlags())
                    + " safe=" + (count - outside) + " outside=" + outside
                    + " rotation=" + rotation);
        }
        pad.invalidate();
    }

    /** Read through dumpsys activity ca.screensafe.app/.TouchProbeActivity. */
    @Override public void dump(String prefix, FileDescriptor fd, PrintWriter writer,
            String[] args) {
        synchronized (lock) {
            writer.println(prefix + "ScreenSafeTouchProbe (app receiver; memory only)");
            writer.println(prefix + "events=" + events + " downs=" + downs + " ups=" + ups
                    + " pointerDowns=" + pointerDowns + " pointerUps=" + pointerUps
                    + " cancels=" + cancels + " maxPointers=" + maxPointers
                    + " maxEventAgeMs=" + maxEventAge);
            writer.println(prefix + "deviceIds=" + deviceIds + " activePointerIds=" + active
                    + " outsideSafeEvents=" + outsideEvents
                    + " outsideSafePointerSamples=" + outsideSamples);
            writer.println(prefix + "newDownDuringActive=" + newDownDuringActive
                    + " idMismatch=" + idMismatch + " eventsWithoutDown=" + eventsWithoutDown);
            TouchFilterService filter = TouchFilterService.current;
            writer.println(prefix + "filterActive=" + (filter != null && filter.filtering)
                    + " filterBlockedSamples=" + (filter == null ? 0 : filter.blocked));
            writer.println(prefix + "Recent events (no coordinates):");
            for (String entry : recent) writer.println(prefix + "  " + entry);
        }
    }

    private final class ProbeView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ProbeView() { super(TouchProbeActivity.this); setBackgroundColor(Color.rgb(16,30,40)); }
        private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
        @Override public boolean onTouchEvent(MotionEvent event) { receive(event); return true; }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = getPaddingLeft() + dp(24);
            float top = getPaddingTop() + dp(40);
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE); paint.setTextSize(dp(25));
            canvas.drawText("Drag or pinch here", left, top, paint);
            paint.setTextSize(dp(17));
            synchronized (lock) {
                canvas.drawText("Fingers received: " + active.size(), left, top + dp(34), paint);
                TouchFilterService filter = TouchFilterService.current;
                canvas.drawText("Touches blocked: " + (filter == null ? 0 : filter.blocked),
                        left, top + dp(61), paint);
                canvas.save();
                canvas.clipRect(getPaddingLeft(), getPaddingTop(),
                        getWidth() - getPaddingRight(), getHeight() - getPaddingBottom());
                for (float[] point : circles.values()) {
                    paint.setStyle(Paint.Style.FILL); paint.setColor(0x554ED4C6);
                    canvas.drawCircle(point[0], point[1], dp(28), paint);
                    paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(3));
                    paint.setColor(0xFF4ED4C6);
                    canvas.drawCircle(point[0], point[1], dp(28), paint);
                }
                canvas.restore();
            }
        }
    }
}
