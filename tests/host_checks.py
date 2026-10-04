"""Run the production filter against small Android API fakes; not a device test."""
from pathlib import Path
import argparse
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--jdk', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
stubs = {
'android/content/Context.java': '''package android.content; public class Context {public static final int RECEIVER_NOT_EXPORTED=4; public BroadcastReceiver receiver; public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,int flags){receiver=r;return null;} public void unregisterReceiver(BroadcastReceiver r){receiver=null;}}''',
'android/content/BroadcastReceiver.java': '''package android.content; public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}''',
'android/content/Intent.java': '''package android.content; public class Intent {public static final String ACTION_SCREEN_OFF="OFF",ACTION_SCREEN_ON="ON",ACTION_USER_PRESENT="UNLOCK";}''',
'android/content/IntentFilter.java': '''package android.content; public class IntentFilter {public void addAction(String s){}}''',
'android/os/Looper.java': '''package android.os; public class Looper {}''',
'android/os/SystemClock.java': '''package android.os; public class SystemClock {public static long now=10000; public static long uptimeMillis(){return now;}}''',
'android/os/Handler.java': '''package android.os; public class Handler {public static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> queue=new java.util.concurrent.ConcurrentLinkedQueue<>(); public static volatile java.util.concurrent.CountDownLatch postedSignal; public static boolean rejectPosts; public Handler(Looper l){} public boolean post(Runnable r){if(rejectPosts)return false;queue.add(r);java.util.concurrent.CountDownLatch signal=postedSignal;if(signal!=null)signal.countDown();return true;} public static void drain(){Runnable next;while((next=queue.poll())!=null)next.run();}}''',
'android/os/HandlerThread.java': '''package android.os; public class HandlerThread {public HandlerThread(String s){} public void start(){} public Looper getLooper(){return new Looper();} public void quitSafely(){}}''',
'android/accessibilityservice/AccessibilityServiceInfo.java': '''package android.accessibilityservice; public class AccessibilityServiceInfo {public int sources; public void setMotionEventSources(int s){sources=s;}}''',
'android/accessibilityservice/AccessibilityService.java': '''package android.accessibilityservice; public class AccessibilityService extends android.content.Context {private AccessibilityServiceInfo info=new AccessibilityServiceInfo(); public AccessibilityServiceInfo getServiceInfo(){return info;} public void setServiceInfo(AccessibilityServiceInfo i){info=i;} public android.os.Looper getMainLooper(){return new android.os.Looper();} public void onDestroy(){}}''',
'android/view/accessibility/AccessibilityEvent.java': '''package android.view.accessibility; public class AccessibilityEvent {}''',
'android/view/InputDevice.java': '''package android.view; public class InputDevice {public static final int SOURCE_TOUCHSCREEN=4098;}''',
'android/hardware/input/InputManagerGlobal.java': '''package android.hardware.input;
public class InputManagerGlobal {
 public static final InputManagerGlobal instance=new InputManagerGlobal();
 public static final java.util.ArrayList<String> trace=new java.util.ArrayList<>();
 public static int injected;public static boolean rejectNext,throwNext,failResolve;
 public static InputManagerGlobal getInstance(){if(failResolve)throw new SecurityException("Hidden input API unavailable");return instance;}
 public boolean injectInputEvent(android.view.InputEvent event,int mode){if(mode!=0)throw new AssertionError("Forwarding must use MODE_ASYNC");trace.add("inject:"+((android.view.MotionEvent)event).getActionMasked());if(throwNext){throwNext=false;throw new SecurityException("Input permission lost");}if(rejectNext){rejectNext=false;return false;}injected++;return true;}
 public static void reset(){trace.clear();injected=0;rejectNext=throwNext=failResolve=false;}
}''',
'android/view/IWindowManager.java': '''package android.view; public interface IWindowManager {void syncInputTransactions(boolean waitForAnimations);}''',
'android/view/WindowManagerGlobal.java': '''package android.view;
public class WindowManagerGlobal {
 public static boolean failNextSync;
 public static IWindowManager getWindowManagerService(){return new IWindowManager(){public void syncInputTransactions(boolean waitForAnimations){if(waitForAnimations)throw new AssertionError("Input sync must not wait for animations");android.hardware.input.InputManagerGlobal.trace.add("sync:"+waitForAnimations);if(failNextSync){failNextSync=false;throw new SecurityException("Window input sync unavailable");}}};}
}''',
'android/util/Log.java': '''package android.util; public class Log {public static int e(String t,String m,Throwable e){return 0;} public static int w(String t,String m,Throwable e){return 0;}}''',
'ca/screensafe/app/SessionRunner.java': '''package ca.screensafe.app; public class SessionRunner {public static SessionRunner current; public boolean autoStart,busy,ready; public String lastRequest; public String status; public void request(String s){lastRequest=s;}}''',
'android/view/InputEvent.java': '''package android.view; public class InputEvent {}''',
'android/view/MotionEvent.java': '''package android.view;
public class MotionEvent extends InputEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5,ACTION_POINTER_UP=6,TOOL_TYPE_FINGER=1,FLAG_CANCELED=32;
 public static class PointerProperties {public int id,toolType;}
 public static class PointerCoords {
  public float x,y,pressure,size,touchMajor,touchMinor,toolMajor,toolMinor,orientation;
  private float[] extra=new float[64];
  public float getAxisValue(int axis){switch(axis){case 0:return x;case 1:return y;case 2:return pressure;case 3:return size;case 4:return touchMajor;case 5:return touchMinor;case 6:return toolMajor;case 7:return toolMinor;case 8:return orientation;default:return extra[axis];}}
  public void setAxisValue(int axis,float v){switch(axis){case 0:x=v;break;case 1:y=v;break;case 2:pressure=v;break;case 3:size=v;break;case 4:touchMajor=v;break;case 5:touchMinor=v;break;case 6:toolMajor=v;break;case 7:toolMinor=v;break;case 8:orientation=v;break;default:extra[axis]=v;}}
  public void copyFrom(PointerCoords c){for(int axis=0;axis<64;axis++)setAxisValue(axis,c.getAxisValue(axis));}
 }
 private java.util.ArrayList<PointerCoords[]> history=new java.util.ArrayList<>();
 private java.util.ArrayList<Long> historyTimes=new java.util.ArrayList<>();
 private long down,time;private int action,flags,meta,buttons,edge;private float xp,yp;
 private PointerProperties[] props;private PointerCoords[] coords;
 private static PointerCoords[] copyCoords(PointerCoords[] c){PointerCoords[] out=new PointerCoords[c.length];for(int i=0;i<c.length;i++){out[i]=new PointerCoords();out[i].copyFrom(c[i]);}return out;}
 public static MotionEvent obtain(long d,long t,int a,int n,PointerProperties[] p,PointerCoords[] c,int meta,int buttons,float xp,float yp,int device,int edge,int source,int flags){
  MotionEvent e=new MotionEvent();e.down=d;e.time=t;e.action=a;e.flags=flags|((a&255)==ACTION_CANCEL?FLAG_CANCELED:0);e.meta=meta;e.buttons=buttons;e.xp=xp;e.yp=yp;e.edge=edge;
  e.props=new PointerProperties[n];for(int i=0;i<n;i++){e.props[i]=new PointerProperties();e.props[i].id=p[i].id;e.props[i].toolType=p[i].toolType;}
  e.coords=copyCoords(c);return e;
 }
 public static MotionEvent obtain(MotionEvent e){MotionEvent out=obtain(e.down,e.time,e.action,e.props.length,e.props,e.coords,e.meta,e.buttons,e.xp,e.yp,0,e.edge,4098,e.flags);for(int h=0;h<e.history.size();h++){out.history.add(copyCoords(e.history.get(h)));out.historyTimes.add(e.historyTimes.get(h));}return out;}
 public int getActionMasked(){return action&255;}public int getActionIndex(){return action>>8;}public void setAction(int a){action=a;if((a&255)==ACTION_CANCEL)flags|=FLAG_CANCELED;}
 public int getPointerCount(){return props.length;}public int getPointerId(int i){return props[i].id;}public float getY(int i){return coords[i].y;}
 public float getX(int i){return coords[i].x;}public float getHistoricalX(int i,int h){return history.get(h)[i].x;}
 public int getHistorySize(){return history.size();}public float getHistoricalY(int i,int h){return history.get(h)[i].y;}
 public void addBatch(long t,PointerCoords[] c,int meta){history.add(coords);historyTimes.add(time);coords=copyCoords(c);time=t;this.meta=meta;}
 public long getEventTime(){return time;}public long getDownTime(){return down;}public long getHistoricalEventTime(int h){return historyTimes.get(h);}
 public void getPointerProperties(int i,PointerProperties p){p.id=props[i].id;p.toolType=props[i].toolType;}
 public void getPointerCoords(int i,PointerCoords c){c.copyFrom(coords[i]);}public void getHistoricalPointerCoords(int i,int h,PointerCoords c){c.copyFrom(history.get(h)[i]);}
 public int getMetaState(){return meta;}public int getButtonState(){return buttons;}public float getXPrecision(){return xp;}public float getYPrecision(){return yp;}public int getEdgeFlags(){return edge;}public int getFlags(){return flags;}public void recycle(){}
}''',
'ca/screensafe/app/HostChecks.java': '''package ca.screensafe.app;
import android.os.Handler;import android.view.MotionEvent;import android.view.InputDevice;
public class HostChecks {
 static MotionEvent down(){MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=0;MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();c.y=1500;return MotionEvent.obtain(1,android.os.SystemClock.uptimeMillis(),0,1,new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);}
 public static void main(String[] args) throws Exception {
  if(args.length>0){System.out.println(TouchFilterChecks.ghostChecks());return;}
  System.out.println(TouchFilterChecks.run());
  SessionRunner.current=new SessionRunner();SessionRunner.current.busy=true;SessionRunner.current.ready=true;
  TouchFilterService f=TouchFilterChecks.fresh();f.onServiceConnected();Handler.drain();
  f.onMotionEvent(down());f.receiver.onReceive(f,new android.content.Intent());Handler.drain();
  TouchFilterChecks.actions();
  f.onMotionEvent(down());Handler.drain();TouchFilterChecks.actions(0);
  f.onServiceConnected();Handler.drain();TouchFilterChecks.actions(0,3);
  TouchFilterChecks.require(f.filtering&&f.getServiceInfo().sources==InputDevice.SOURCE_TOUCHSCREEN,"Reconnect did not restore capture");
  f.onMotionEvent(down());Handler.drain();TouchFilterChecks.actions(0,3,0);
  f.disable();f.enable();Handler.drain();
  f.onMotionEvent(down());Handler.drain();TouchFilterChecks.actions(0,3,0,3,0);
  System.out.println("PASS: queued pre-lock events discarded, service reconnect, disable/enable ordering.");
  f.setRotation(1);f.setRotation(0);Handler.drain();
  TouchFilterChecks.require(f.area.rotation==0,"Quick return to portrait lost while landscape was queued");
  f.setRotation(1);f.setRotation(3);Handler.drain();
  TouchFilterChecks.require(f.area.rotation==3,"Latest rotation did not win");
  f.setRotation(0);Handler.drain();
  System.out.println("PASS: rapid rotation reversals keep the touch filter aligned.");
  for(int r=0;r<4;r++){
   ca.screensafe.core.SafeArea a=new ca.screensafe.core.SafeArea(r);
   TouchFilterChecks.require(a.width()*a.height()==1440*2470,"Area changed with rotation");
   TouchFilterChecks.require(a.maskWidth()*a.maskHeight()==1440*618,"Mask area changed");
   TouchFilterChecks.require(a.contains(a.left,a.top),"Usable boundary rejected");
   TouchFilterChecks.require(!a.contains(a.maskLeft()+1,a.maskTop()+1),"Damaged edge accepted");
   TouchFilterChecks.require(!a.contains(a.right,a.bottom),"Outside display accepted");
  }
  System.out.println("PASS: all four rotations preserve usable area and physical mask.");
  System.out.println("PASS: 20% boundary, unmodified forwarded coordinates, and batched unsafe samples.");
  // Exercise direct system-service reflection and synchronization, without UiAutomation.
  f.disable();Handler.drain();android.hardware.input.InputManagerGlobal.reset();
  f=new TouchFilterService();f.onServiceConnected();Handler.drain();
  f.onMotionEvent(down());Handler.drain();
  MotionEvent moving=down();moving.setAction(MotionEvent.ACTION_MOVE);f.onMotionEvent(moving);Handler.drain();
  MotionEvent ending=down();ending.setAction(MotionEvent.ACTION_UP);f.onMotionEvent(ending);Handler.drain();
  f.onMotionEvent(down());Handler.drain();f.onInterrupt();Handler.drain();
  TouchFilterChecks.require(android.hardware.input.InputManagerGlobal.injected==5,"Direct injector did not forward DOWN/MOVE/UP/DOWN/CANCEL");
  TouchFilterChecks.require(android.hardware.input.InputManagerGlobal.trace.toString().equals("[sync:false, inject:0, inject:2, inject:1, sync:false, sync:false, inject:0, inject:3]"),"Input-window synchronization order changed: "+android.hardware.input.InputManagerGlobal.trace);
  System.out.println("PASS: direct asynchronous input injection syncs input windows before DOWN/after UP without animation waits or shell-owner calls.");
  f.disable();Handler.drain();
  // API preparation must finish before capture becomes active.
  android.hardware.input.InputManagerGlobal.failResolve=true;
  f=new TouchFilterService();f.onServiceConnected();Handler.drain();
  TouchFilterChecks.require(!f.filtering&&f.getServiceInfo().sources==0&&"STOP".equals(SessionRunner.current.lastRequest),"Failed direct injector preparation captured physical touches");
  android.hardware.input.InputManagerGlobal.reset();
  for(int failure=0;failure<3;failure++){
   f=new TouchFilterService();f.onServiceConnected();Handler.drain();
   if(failure==0)android.hardware.input.InputManagerGlobal.rejectNext=true;
   if(failure==1)android.hardware.input.InputManagerGlobal.throwNext=true;
   if(failure==2)android.view.WindowManagerGlobal.failNextSync=true;
   f.onMotionEvent(down());Handler.drain();
   TouchFilterChecks.require(!f.filtering&&f.getServiceInfo().sources==0&&f.failures==1&&f.activePointers==0,"Direct input failure did not stop capture and clear stream: "+failure);
   f.enable();Handler.drain();f.onMotionEvent(down());Handler.drain();
   TouchFilterChecks.require(f.filtering&&f.forwarded==1,"Direct input did not recover after explicit restart");
   f.disable();Handler.drain();
  }
  // A reconnect before bootstrap readiness must not enable input capture.
  SessionRunner.current.ready=false;
  f=new TouchFilterService();f.onServiceConnected();Handler.drain();
  TouchFilterChecks.require(!f.filtering&&f.getServiceInfo().sources==0,"Input captured before permission bootstrap ready");
  SessionRunner.current.ready=true;
  System.out.println("PASS: direct-injector preparation, rejection, exceptions, window-sync failures, and readiness gating fail safely and recover.");
  f=TouchFilterChecks.fresh();f.onServiceConnected();Handler.drain();
  f.onMotionEvent(down());Handler.drain();
  MotionEvent old=down();old.setAction(MotionEvent.ACTION_UP);f.onMotionEvent(old);
  android.os.SystemClock.now+=600;Handler.drain();TouchFilterChecks.actions(0,3);
  TouchFilterChecks.require(f.staleEvents==1,"Queued old UP replayed as a click");
  MotionEvent orphan=down();orphan.setAction(MotionEvent.ACTION_MOVE);f.onMotionEvent(orphan);Handler.drain();TouchFilterChecks.actions(0,3);
  f.onMotionEvent(down());Handler.drain();TouchFilterChecks.actions(0,3,0);
  System.out.println("PASS: queued stall cancels stale UP, ignores orphan MOVE, and accepts the next fresh DOWN.");
  f.disable();Handler.drain();
  TouchFilterChecks.require(new TouchFilterService().awaitDisabled(),"Unused filter unexpectedly needs a worker drain");
  f=new TouchFilterService();f.onServiceConnected();Handler.drain();
  TouchFilterChecks.require(!f.awaitDisabled(),"Active filtering incorrectly reported disabled/drained");
  f.onMotionEvent(down());Handler.drain();
  final TouchFilterService shuttingDown=f;
  int injectedBeforeDisable=android.hardware.input.InputManagerGlobal.injected;
  f.disable();
  TouchFilterChecks.require(android.hardware.input.InputManagerGlobal.injected==injectedBeforeDisable,"Disable should enqueue worker cancellation");
  final java.util.concurrent.atomic.AtomicBoolean drained=new java.util.concurrent.atomic.AtomicBoolean();
  Handler.postedSignal=new java.util.concurrent.CountDownLatch(1);
  Thread shutdownWait=new Thread(new Runnable(){public void run(){drained.set(shuttingDown.awaitDisabled());}});
  shutdownWait.start();
  TouchFilterChecks.require(Handler.postedSignal.await(1,java.util.concurrent.TimeUnit.SECONDS),"Shutdown barrier was not queued");
  Handler.postedSignal=null;Handler.drain();shutdownWait.join(1000);
  TouchFilterChecks.require(!shutdownWait.isAlive()&&drained.get(),"Disabled worker did not drain");
  TouchFilterChecks.require(android.hardware.input.InputManagerGlobal.injected==injectedBeforeDisable+1&&shuttingDown.activePointers==0,"Drain returned before final CANCEL/reset");
  final java.util.concurrent.atomic.AtomicBoolean interruptedResult=new java.util.concurrent.atomic.AtomicBoolean();
  Thread interruptedWait=new Thread(new Runnable(){public void run(){Thread.currentThread().interrupt();interruptedResult.set(!shuttingDown.awaitDisabled()&&Thread.currentThread().isInterrupted());}});
  interruptedWait.start();interruptedWait.join(1000);Handler.drain();
  TouchFilterChecks.require(!interruptedWait.isAlive()&&interruptedResult.get(),"Interrupted drain lost interruption or reported success");
  Handler.rejectPosts=true;TouchFilterChecks.require(!shuttingDown.awaitDisabled(),"Rejected barrier reported successful drain");Handler.rejectPosts=false;
  System.out.println("PASS: disable barrier waits for final cancellation, rejects active capture/failed posting, and preserves interruption.");
 }
}'''
}
scratch = root / 'work'
scratch.mkdir(exist_ok=True)
def run():
    base = scratch / 'host-checks'
    base.mkdir(exist_ok=True)
    for name, source in stubs.items():
        target = base / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source, encoding='utf-8')
    sources = [base / name for name in stubs]
    sources += [root / 'source/app/TouchFilterService.java', root / 'source/app/TouchFilterChecks.java']
    sources += list((root / 'source/shared').glob('*.java'))
    classes = base / 'classes'
    classes.mkdir(exist_ok=True)
    subprocess.run([str(Path(args.jdk) / 'bin/javac.exe'), '-encoding', 'UTF-8', '-d', str(classes), *map(str, sources)], check=True)
    subprocess.run([str(Path(args.jdk) / 'bin/java.exe'), '-cp', str(classes), 'ca.screensafe.app.HostChecks'], check=True)
run()
