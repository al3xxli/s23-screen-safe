package ca.screensafe.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import java.util.ArrayList;
import java.util.HashSet;

/** Consumes physical touchscreen events before window and gesture-monitor dispatch. */
public final class TouchFilterService extends AccessibilityService {
    public static volatile TouchFilterService current;
    private HandlerThread thread;
    private Handler worker;
    private volatile UiAutomation injector;
    public volatile boolean filtering;
    public volatile long blocked, forwarded, failures;
    private final HashSet<Integer> allowed=new HashSet<>();
    private final HashSet<Integer> rejected=new HashSet<>();
    private long downTime;
    private MotionEvent last;
    private static final int TOP=927;
    interface EventSink {void send(MotionEvent e);}
    EventSink testSink;

    protected void onServiceConnected(){
        thread=new HandlerThread("Screen Safe touch filter");thread.start();worker=new Handler(thread.getLooper());
        current=this;
        configure(false);
        if(SessionRunner.current!=null&&SessionRunner.current.autoStart)SessionRunner.current.request("START");
    }
    private void configure(boolean enabled){
        AccessibilityServiceInfo info=getServiceInfo();
        info.setMotionEventSources(enabled?InputDevice.SOURCE_TOUCHSCREEN:0);
        setServiceInfo(info);
    }
    // Called on the application's main thread, with injection already available.
    public void enable(UiAutomation automation){
        if(filtering)return;
        injector=automation;blocked=forwarded=failures=0;
        worker.post(new Runnable(){public void run(){reset();}});
        filtering=true;configure(true);
    }
    public void disable(){
        if(!filtering)return;
        filtering=false;configure(false);
        worker.post(new Runnable(){public void run(){cancel();reset();injector=null;}});
    }
    public void onMotionEvent(MotionEvent event){
        if(!filtering)return;
        final MotionEvent copy=MotionEvent.obtain(event);
        worker.post(new Runnable(){public void run(){
            try{if(filtering)filter(copy);}catch(Throwable error){
                failures++;android.util.Log.e("ScreenSafeFilter","Touch forwarding failed",error);
                new Handler(getMainLooper()).post(new Runnable(){public void run(){disable();
                    if(SessionRunner.current!=null)SessionRunner.current.status="Touch filter stopped. Top overlay still protects the strip.";
                }});
            }finally{copy.recycle();}
        }});
    }
    private boolean unsafe(MotionEvent e,int i){
        if(e.getY(i)<TOP)return true;
        for(int h=0;h<e.getHistorySize();h++)if(e.getHistoricalY(i,h)<TOP)return true;
        return false;
    }
    void filter(MotionEvent e){
        int action=e.getActionMasked(), index=e.getActionIndex();
        if(action==MotionEvent.ACTION_DOWN){cancel();reset();}
        if(action==MotionEvent.ACTION_CANCEL){cancel();reset();return;}
        if(action!=MotionEvent.ACTION_DOWN && action!=MotionEvent.ACTION_POINTER_DOWN &&
           action!=MotionEvent.ACTION_MOVE && action!=MotionEvent.ACTION_UP && action!=MotionEvent.ACTION_POINTER_UP)return;
        // Never allow a contact that began in the strip to become a usable-area touch.
        // If an accepted contact enters the strip, cancel its current gesture, never click it.
        boolean crossing=false;
        for(int i=0;i<e.getPointerCount();i++)if(allowed.contains(e.getPointerId(i))&&unsafe(e,i))crossing=true;
        if(crossing){cancel();rejected.addAll(allowed);allowed.clear();}
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
            int newAction=MotionEvent.ACTION_MOVE;
            int newIndex=indices.indexOf(index);
            if((action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN)&&newIndex>=0)
                newAction=indices.size()==1?MotionEvent.ACTION_DOWN:MotionEvent.ACTION_POINTER_DOWN|(newIndex<<8);
            else if((action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP)&&newIndex>=0)
                newAction=indices.size()==1?MotionEvent.ACTION_UP:MotionEvent.ACTION_POINTER_UP|(newIndex<<8);
            MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[indices.size()];
            MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[indices.size()];
            for(int j=0;j<indices.size();j++){
                props[j]=new MotionEvent.PointerProperties();e.getPointerProperties(indices.get(j),props[j]);
                coords[j]=new MotionEvent.PointerCoords();e.getPointerCoords(indices.get(j),coords[j]);
            }
            MotionEvent out=MotionEvent.obtain(downTime,e.getEventTime(),newAction,indices.size(),props,coords,
                    e.getMetaState(),e.getButtonState(),e.getXPrecision(),e.getYPrecision(),0,e.getEdgeFlags(),InputDevice.SOURCE_TOUCHSCREEN,e.getFlags());
            try{inject(out);if(last!=null)last.recycle();last=MotionEvent.obtain(out);}finally{out.recycle();}
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP){
            int id=e.getPointerId(index);allowed.remove(id);rejected.remove(id);
            if(allowed.isEmpty()&&last!=null){last.recycle();last=null;}
        }
        if(action==MotionEvent.ACTION_UP)reset();
    }
    private void inject(MotionEvent e){
        if(testSink!=null){testSink.send(e);return;}
        UiAutomation target=injector;
        if(target==null||!target.injectInputEvent(e,false))throw new IllegalStateException("Touch injection unavailable");
        forwarded++;
    }
    private void cancel(){
        if(last!=null && last.getActionMasked()!=MotionEvent.ACTION_UP){
            MotionEvent cancel=MotionEvent.obtain(last);cancel.setAction(MotionEvent.ACTION_CANCEL);
            try{inject(cancel);}finally{cancel.recycle();}
        }
    }
    private void reset(){allowed.clear();rejected.clear();if(last!=null){last.recycle();last=null;}downTime=0;}
    public void onAccessibilityEvent(AccessibilityEvent event){}
    public void onInterrupt(){}
    protected void dump(java.io.FileDescriptor fd,java.io.PrintWriter out,String[] args){
        out.println("ScreenSafe touch filter: active="+filtering+" blockedSamples="+blocked+" forwardedEvents="+forwarded+" failures="+failures);
    }
    public void onDestroy(){disable();current=null;if(thread!=null)thread.quitSafely();super.onDestroy();}
}
