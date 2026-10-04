package ca.screensafe.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicInteger;
import ca.screensafe.core.SafeArea;

/** Consumes physical touchscreen events before window and gesture-monitor dispatch. */
public final class TouchFilterService extends AccessibilityService {
    public static volatile TouchFilterService current;
    private HandlerThread thread;
    private Handler worker;
    private volatile UiAutomation injector;
    private Method injectWithoutAnimationWait;
    public volatile boolean filtering;
    public volatile long blocked, forwarded, failures;
    private final HashSet<Integer> allowed=new HashSet<>();
    private final HashSet<Integer> rejected=new HashSet<>();
    private long downTime;
    private MotionEvent last;
    volatile SafeArea area=new SafeArea(0);
    private int requestedRotation;
    private volatile int generation;
    public volatile long recoveries;
    // Old input must never replay as a delayed click after a system/worker stall.
    static final long MAX_EVENT_AGE_MS=500;
    private final AtomicInteger queued=new AtomicInteger();
    public volatile long staleEvents,maxQueueDelayMs,maxInjectionMs;
    public volatile long rawEvents,mixedSamples,suppressedStationary,cancelledStreams;
    public volatile long unsafeCrossings,missingPointers,reusedPointers,hardwareCancels;
    public volatile int activePointers,rejectedPointers,maxPhysicalPointers,maxQueued;
    private boolean receiving;
    private final BroadcastReceiver screenState=new BroadcastReceiver(){
        public void onReceive(Context context,Intent intent){
            // Android can end input dispatch without delivering the final UP/CANCEL.
            if(filtering)restartStream();
        }
    };
    interface EventSink {void send(MotionEvent e);}
    EventSink testSink;

    protected void onServiceConnected(){
        if(thread==null){thread=new HandlerThread("Screen Safe touch filter");thread.start();worker=new Handler(thread.getLooper());}
        // The framework can reconnect the same service object. Reinitialize its capture
        // state instead of leaving filtering=true while motion sources are disabled.
        filtering=false;generation++;
        worker.post(new Runnable(){public void run(){clearStream();injector=null;}});
        current=this;
        IntentFilter screen=new IntentFilter();
        screen.addAction(Intent.ACTION_SCREEN_OFF);screen.addAction(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_USER_PRESENT);
        if(!receiving){registerReceiver(screenState,screen,Context.RECEIVER_NOT_EXPORTED);receiving=true;}
        configure(false);
        SessionRunner session=SessionRunner.current;
        if(session!=null&&session.busy&&session.automation!=null){
            try{enable(session.automation);}catch(RuntimeException error){
                session.status="Touch filter could not start. Restoring the screen.";
                android.util.Log.e("ScreenSafeFilter","Could not prepare touch injection",error);
                session.request("STOP");
            }
        }else if(session!=null&&session.autoStart)session.request("START");
    }
    private void configure(boolean enabled){
        AccessibilityServiceInfo info=getServiceInfo();
        info.setMotionEventSources(enabled?InputDevice.SOURCE_TOUCHSCREEN:0);
        setServiceInfo(info);
    }
    // Called on the application's main thread, with injection already available.
    public void enable(UiAutomation automation){
        if(filtering)return;
        // The public two-argument overload waits for all window animations even
        // with sync=false. Resolve the non-waiting overload before capturing input.
        // The ADB launcher enables hidden API access for this instrumentation only.
        final Method nonWaiting;
        try{nonWaiting=UiAutomation.class.getMethod("injectInputEvent",InputEvent.class,boolean.class,boolean.class);}
        catch(ReflectiveOperationException error){throw new IllegalStateException("Restart Screen Safe using its updated computer launcher",error);}
        generation++;
        worker.post(new Runnable(){public void run(){reset();injector=automation;injectWithoutAnimationWait=nonWaiting;}});
        blocked=forwarded=failures=recoveries=0;
        staleEvents=maxQueueDelayMs=maxInjectionMs=0;
        rawEvents=mixedSamples=suppressedStationary=cancelledStreams=0;
        unsafeCrossings=missingPointers=reusedPointers=hardwareCancels=0;
        activePointers=rejectedPointers=maxPhysicalPointers=maxQueued=0;
        filtering=true;configure(true);
    }
    public void disable(){
        if(!filtering)return;
        filtering=false;generation++;configure(false);
        worker.post(new Runnable(){public void run(){try{clearStream();}finally{injector=null;}}});
    }
    private void restartStream(){
        generation++;
        worker.post(new Runnable(){public void run(){clearStream();}});
    }
    public void setRotation(int rotation){
        rotation&=3;
        // Compare the requested rotation, not the worker's last completed update.
        // A quick turn back must not be lost while the first update is queued.
        if(requestedRotation==rotation)return;
        requestedRotation=rotation;
        generation++;
        final SafeArea next=new SafeArea(rotation);
        worker.post(new Runnable(){public void run(){clearStream();area=next;}});
    }
    // Cleanup must succeed even when injection is unavailable (for example while locked).
    void clearStream(){
        try{cancel();}catch(RuntimeException error){
            failures++;android.util.Log.w("ScreenSafeFilter","Could not cancel interrupted gesture",error);
        }finally{reset();recoveries++;}
    }
    public void onMotionEvent(MotionEvent event){
        if(!filtering)return;
        rawEvents++;
        maxPhysicalPointers=Math.max(maxPhysicalPointers,event.getPointerCount());
        final MotionEvent copy=MotionEvent.obtain(event);
        final int epoch=generation;
        maxQueued=Math.max(maxQueued,queued.incrementAndGet());
        boolean posted=worker.post(new Runnable(){public void run(){
            try{if(filtering&&epoch==generation)filterCurrent(copy);}catch(Throwable error){
                failures++;android.util.Log.e("ScreenSafeFilter","Touch forwarding failed",error);
                clearStream();
                new Handler(getMainLooper()).post(new Runnable(){public void run(){if(epoch!=generation)return;disable();
                    if(SessionRunner.current!=null)SessionRunner.current.status="Touch filter stopped. Top overlay still protects the strip.";
                }});
            }finally{copy.recycle();queued.decrementAndGet();}
        }});
        if(!posted){copy.recycle();queued.decrementAndGet();}
    }
    void filterCurrent(MotionEvent e){
        long age=Math.max(0,SystemClock.uptimeMillis()-e.getEventTime());
        maxQueueDelayMs=Math.max(maxQueueDelayMs,age);
        if(age>MAX_EVENT_AGE_MS){
            staleEvents++;
            clearStream();
            // Only a subsequent fresh DOWN/POINTER_DOWN can admit a contact.
            return;
        }
        filter(e);
    }
    private boolean unsafe(MotionEvent e,int i){
        if(!area.contains(e.getX(i),e.getY(i)))return true;
        for(int h=0;h<e.getHistorySize();h++)if(!area.contains(e.getHistoricalX(i,h),e.getHistoricalY(i,h)))return true;
        return false;
    }
    void filter(MotionEvent e){
        try{filterStream(e);}finally{activePointers=allowed.size();rejectedPointers=rejected.size();}
    }
    private void filterStream(MotionEvent e){
        int action=e.getActionMasked(), index=e.getActionIndex();
        if(action==MotionEvent.ACTION_POINTER_UP&&(e.getFlags()&MotionEvent.FLAG_CANCELED)!=0)hardwareCancels++;
        if(action==MotionEvent.ACTION_DOWN){try{cancel();}finally{reset();}}
        if(action==MotionEvent.ACTION_CANCEL){hardwareCancels++;try{cancel();}finally{reset();}return;}
        if(action!=MotionEvent.ACTION_DOWN && action!=MotionEvent.ACTION_POINTER_DOWN &&
           action!=MotionEvent.ACTION_MOVE && action!=MotionEvent.ACTION_UP && action!=MotionEvent.ACTION_POINTER_UP)return;
        HashSet<Integer> present=new HashSet<>();
        for(int i=0;i<e.getPointerCount();i++)present.add(e.getPointerId(i));
        // Recover a missing POINTER_UP without emitting an invalid multitouch stream.
        if(!present.containsAll(allowed)){
            missingPointers++;clearStream();
            // Existing contacts need a fresh DOWN; do not synthesize a click from MOVE.
            rejected.addAll(present);
        }
        rejected.retainAll(present);
        if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){
            int id=e.getPointerId(index);
            // A DOWN is authoritative: this ID may belong to a new contact after a lost UP.
            if(allowed.contains(id)){
                reusedPointers++;clearStream();rejected.addAll(present);
            }
            rejected.remove(id);
        }
        // Never allow a contact that began in the strip to become a usable-area touch.
        // If an accepted contact enters the strip, cancel its current gesture, never click it.
        boolean crossing=false;
        for(int i=0;i<e.getPointerCount();i++)if(allowed.contains(e.getPointerId(i))&&unsafe(e,i))crossing=true;
        if(crossing){unsafeCrossings++;try{cancel();}finally{rejected.addAll(allowed);allowed.clear();if(last!=null){last.recycle();last=null;}}}
        if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){
            int id=e.getPointerId(index);
            if(unsafe(e,index)){rejected.add(id);blocked++;}
            else if(!rejected.contains(id)){if(allowed.isEmpty())downTime=e.getEventTime();allowed.add(id);}
        }
        ArrayList<Integer> indices=new ArrayList<>();
        for(int i=0;i<e.getPointerCount();i++){
            if(allowed.contains(e.getPointerId(i)))indices.add(i);
            else blocked++;
        }
        if(!indices.isEmpty()){
            boolean mixed=indices.size()<e.getPointerCount();
            if(mixed)mixedSamples++;
            int newAction=MotionEvent.ACTION_MOVE;
            int newIndex=indices.indexOf(index);
            if((action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN)&&newIndex>=0)
                newAction=indices.size()==1?MotionEvent.ACTION_DOWN:MotionEvent.ACTION_POINTER_DOWN|(newIndex<<8);
            else if((action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP)&&newIndex>=0)
                newAction=indices.size()==1?
                        ((e.getFlags()&MotionEvent.FLAG_CANCELED)!=0?MotionEvent.ACTION_CANCEL:MotionEvent.ACTION_UP):
                        MotionEvent.ACTION_POINTER_UP|(newIndex<<8);
            MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[indices.size()];
            MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[indices.size()];
            for(int j=0;j<indices.size();j++){
                props[j]=new MotionEvent.PointerProperties();e.getPointerProperties(indices.get(j),props[j]);
                coords[j]=new MotionEvent.PointerCoords();e.getPointerCoords(indices.get(j),coords[j]);
            }
            // A damaged contact can report thousands of samples while an accepted
            // finger is stationary. Do not turn that noise into an app MOVE flood.
            // Only mixed streams qualify; keep real movement, every axis and history.
            boolean stationary=newAction==MotionEvent.ACTION_MOVE&&mixed&&unchanged(e,indices);
            if(stationary)suppressedStationary++;
            else{
                int history=newAction==MotionEvent.ACTION_MOVE?e.getHistorySize():0;
                if(history>0)for(int j=0;j<indices.size();j++)e.getHistoricalPointerCoords(indices.get(j),0,coords[j]);
                MotionEvent out=MotionEvent.obtain(downTime,history>0?e.getHistoricalEventTime(0):e.getEventTime(),newAction,indices.size(),props,coords,
                        e.getMetaState(),e.getButtonState(),e.getXPrecision(),e.getYPrecision(),0,e.getEdgeFlags(),InputDevice.SOURCE_TOUCHSCREEN,e.getFlags());
                try{
                    for(int h=1;h<history;h++){
                        for(int j=0;j<indices.size();j++)e.getHistoricalPointerCoords(indices.get(j),h,coords[j]);
                        out.addBatch(e.getHistoricalEventTime(h),coords,e.getMetaState());
                    }
                    if(history>0){
                        for(int j=0;j<indices.size();j++)e.getPointerCoords(indices.get(j),coords[j]);
                        out.addBatch(e.getEventTime(),coords,e.getMetaState());
                    }
                    inject(out);if(last!=null)last.recycle();last=MotionEvent.obtain(out);
                }finally{out.recycle();}
            }
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP){
            int id=e.getPointerId(index);allowed.remove(id);rejected.remove(id);
            if(allowed.isEmpty()&&last!=null){last.recycle();last=null;}
        }
        if(action==MotionEvent.ACTION_UP)reset();
    }
    private boolean unchanged(MotionEvent e,ArrayList<Integer> indices){
        if(last==null||e.getMetaState()!=last.getMetaState()||e.getButtonState()!=last.getButtonState())return false;
        // FLAG_CANCELED can describe a rejected POINTER_UP; it does not cancel
        // the surviving accepted pointer. Other flag changes are preserved.
        if(((e.getFlags()^last.getFlags())&~MotionEvent.FLAG_CANCELED)!=0)return false;
        MotionEvent.PointerCoords before=new MotionEvent.PointerCoords(),now=new MotionEvent.PointerCoords();
        MotionEvent.PointerProperties beforeProp=new MotionEvent.PointerProperties(),nowProp=new MotionEvent.PointerProperties();
        for(int index:indices){
            int previous=-1;
            for(int p=0;p<last.getPointerCount();p++)if(last.getPointerId(p)==e.getPointerId(index)){previous=p;break;}
            if(previous<0)return false;
            last.getPointerProperties(previous,beforeProp);e.getPointerProperties(index,nowProp);
            if(beforeProp.toolType!=nowProp.toolType)return false;
            last.getPointerCoords(previous,before);e.getPointerCoords(index,now);
            if(!sameAxes(before,now))return false;
            for(int h=0;h<e.getHistorySize();h++){
                e.getHistoricalPointerCoords(index,h,now);
                if(!sameAxes(before,now))return false;
            }
        }
        return true;
    }
    private static boolean sameAxes(MotionEvent.PointerCoords a,MotionEvent.PointerCoords b){
        for(int axis=0;axis<64;axis++)if(a.getAxisValue(axis)!=b.getAxisValue(axis))return false;
        return true;
    }
    private void inject(MotionEvent e){
        if(testSink!=null){testSink.send(e);return;}
        UiAutomation target=injector;
        if(target==null||injectWithoutAnimationWait==null)throw new IllegalStateException("Touch injection unavailable");
        long started=SystemClock.uptimeMillis();
        try{
            if(!Boolean.TRUE.equals(injectWithoutAnimationWait.invoke(target,e,false,false)))throw new IllegalStateException("Touch injection unavailable");
        }catch(InvocationTargetException error){throw new IllegalStateException("Touch injection failed",error.getCause());}
        catch(ReflectiveOperationException error){throw new IllegalStateException("Touch injection unavailable",error);}
        finally{maxInjectionMs=Math.max(maxInjectionMs,SystemClock.uptimeMillis()-started);}
        forwarded++;
    }
    private void cancel(){
        if(last!=null && !allowed.isEmpty() && last.getActionMasked()!=MotionEvent.ACTION_UP && last.getActionMasked()!=MotionEvent.ACTION_CANCEL){
            // A held/stalled stream can have an old last sample. Timestamp cleanup
            // now rather than replaying the interrupted contact's old event time.
            ArrayList<Integer> live=new ArrayList<>();
            for(int i=0;i<last.getPointerCount();i++)if(allowed.contains(last.getPointerId(i)))live.add(i);
            int count=live.size();
            if(count==0)return;
            MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[count];
            MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[count];
            for(int i=0;i<count;i++){
                props[i]=new MotionEvent.PointerProperties();last.getPointerProperties(live.get(i),props[i]);
                coords[i]=new MotionEvent.PointerCoords();last.getPointerCoords(live.get(i),coords[i]);
            }
            MotionEvent cancel=MotionEvent.obtain(last.getDownTime(),SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,
                    count,props,coords,last.getMetaState(),last.getButtonState(),last.getXPrecision(),last.getYPrecision(),
                    0,last.getEdgeFlags(),InputDevice.SOURCE_TOUCHSCREEN,last.getFlags()|MotionEvent.FLAG_CANCELED);
            try{inject(cancel);cancelledStreams++;}finally{cancel.recycle();}
        }
    }
    private void reset(){allowed.clear();rejected.clear();activePointers=rejectedPointers=0;if(last!=null){last.recycle();last=null;}downTime=0;}
    public void onAccessibilityEvent(AccessibilityEvent event){}
    public void onInterrupt(){if(filtering)restartStream();}
    protected void dump(java.io.FileDescriptor fd,java.io.PrintWriter out,String[] args){
        out.println("ScreenSafe touch filter: active="+filtering+" blockedSamples="+blocked+" forwardedEvents="+forwarded+" failures="+failures+" recoveries="+recoveries+" generation="+generation
                +" queued="+queued.get()+" staleEvents="+staleEvents+" maxQueueDelayMs="+maxQueueDelayMs+" maxInjectionMs="+maxInjectionMs
                +" rawEvents="+rawEvents+" mixedSamples="+mixedSamples+" suppressedStationary="+suppressedStationary
                +" activePointers="+activePointers+" rejectedPointers="+rejectedPointers+" maxPhysicalPointers="+maxPhysicalPointers+" maxQueued="+maxQueued
                +" cancelledStreams="+cancelledStreams+" unsafeCrossings="+unsafeCrossings+" missingPointers="+missingPointers+" reusedPointers="+reusedPointers+" hardwareCancels="+hardwareCancels);
    }
    public void onDestroy(){if(receiving){unregisterReceiver(screenState);receiving=false;}disable();current=null;if(thread!=null)thread.quitSafely();super.onDestroy();}
}
