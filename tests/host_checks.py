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
'android/os/Handler.java': '''package android.os; import java.util.ArrayDeque; public class Handler {public static final ArrayDeque<Runnable> queue=new ArrayDeque<>(); public Handler(Looper l){} public boolean post(Runnable r){queue.add(r);return true;} public static void drain(){while(!queue.isEmpty())queue.remove().run();}}''',
'android/os/HandlerThread.java': '''package android.os; public class HandlerThread {public HandlerThread(String s){} public void start(){} public Looper getLooper(){return new Looper();} public void quitSafely(){}}''',
'android/accessibilityservice/AccessibilityServiceInfo.java': '''package android.accessibilityservice; public class AccessibilityServiceInfo {public int sources; public void setMotionEventSources(int s){sources=s;}}''',
'android/accessibilityservice/AccessibilityService.java': '''package android.accessibilityservice; public class AccessibilityService extends android.content.Context {private AccessibilityServiceInfo info=new AccessibilityServiceInfo(); public AccessibilityServiceInfo getServiceInfo(){return info;} public void setServiceInfo(AccessibilityServiceInfo i){info=i;} public android.os.Looper getMainLooper(){return new android.os.Looper();} public void onDestroy(){}}''',
'android/view/accessibility/AccessibilityEvent.java': '''package android.view.accessibility; public class AccessibilityEvent {}''',
'android/view/InputDevice.java': '''package android.view; public class InputDevice {public static final int SOURCE_TOUCHSCREEN=4098;}''',
'android/app/UiAutomation.java': '''package android.app; public class UiAutomation {public int injected; public boolean injectInputEvent(android.view.InputEvent e,boolean sync){throw new AssertionError("Two-argument injection waits for animation");} public boolean injectInputEvent(android.view.InputEvent e,boolean sync,boolean waitForAnimations){if(sync||waitForAnimations)throw new AssertionError("Live forwarding must not wait for animation/dispatch");injected++;return true;}}''',
'android/util/Log.java': '''package android.util; public class Log {public static int e(String t,String m,Throwable e){return 0;} public static int w(String t,String m,Throwable e){return 0;}}''',
'ca/screensafe/app/SessionRunner.java': '''package ca.screensafe.app; public class SessionRunner {public static SessionRunner current; public boolean autoStart,busy; public android.app.UiAutomation automation; public String status; public void request(String s){}}''',
'android/view/InputEvent.java': '''package android.view; public class InputEvent {}''',
'android/view/MotionEvent.java': '''package android.view;
public class MotionEvent extends InputEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5,ACTION_POINTER_UP=6,TOOL_TYPE_FINGER=1;
 public static class PointerProperties {public int id,toolType;}
 public static class PointerCoords {public float x,y,pressure,size;}
 private java.util.ArrayList<PointerCoords[]> history=new java.util.ArrayList<>(); private long down,time; private int action; private PointerProperties[] props;private PointerCoords[] coords;
 public static MotionEvent obtain(long d,long t,int a,int n,PointerProperties[] p,PointerCoords[] c,int meta,int buttons,float xp,float yp,int device,int edge,int source,int flags){
  MotionEvent e=new MotionEvent();e.down=d;e.time=t;e.action=a;e.props=new PointerProperties[n];e.coords=new PointerCoords[n];
  for(int i=0;i<n;i++){e.props[i]=new PointerProperties();e.props[i].id=p[i].id;e.props[i].toolType=p[i].toolType;e.coords[i]=new PointerCoords();e.coords[i].x=c[i].x;e.coords[i].y=c[i].y;e.coords[i].pressure=c[i].pressure;e.coords[i].size=c[i].size;}return e;
 }
 public static MotionEvent obtain(MotionEvent e){return obtain(e.down,e.time,e.action,e.props.length,e.props,e.coords,0,0,1,1,0,0,4098,0);}
 public int getActionMasked(){return action&255;}public int getActionIndex(){return action>>8;}public void setAction(int a){action=a;}
 public int getPointerCount(){return props.length;}public int getPointerId(int i){return props[i].id;}public float getY(int i){return coords[i].y;}
 public float getX(int i){return coords[i].x;}public float getHistoricalX(int i,int h){return history.get(h)[i].x;}
 public int getHistorySize(){return history.size();}public float getHistoricalY(int i,int h){return history.get(h)[i].y;}
 public void addBatch(long t,PointerCoords[] c,int meta){history.add(coords);coords=new PointerCoords[c.length];for(int i=0;i<c.length;i++){coords[i]=new PointerCoords();coords[i].x=c[i].x;coords[i].y=c[i].y;coords[i].pressure=c[i].pressure;}time=t;}
 public long getEventTime(){return time;}public long getDownTime(){return down;}
 public void getPointerProperties(int i,PointerProperties p){p.id=props[i].id;p.toolType=props[i].toolType;}
 public void getPointerCoords(int i,PointerCoords c){c.x=coords[i].x;c.y=coords[i].y;c.pressure=coords[i].pressure;c.size=coords[i].size;}
 public int getMetaState(){return 0;}public int getButtonState(){return 0;}public float getXPrecision(){return 1;}public float getYPrecision(){return 1;}public int getEdgeFlags(){return 0;}public int getFlags(){return 0;}public void recycle(){}
}''',
'ca/screensafe/app/HostChecks.java': '''package ca.screensafe.app;
import android.os.Handler;import android.view.MotionEvent;import android.view.InputDevice;
public class HostChecks {
 static MotionEvent down(){MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=0;MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();c.y=1500;return MotionEvent.obtain(1,android.os.SystemClock.uptimeMillis(),0,1,new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);}
 public static void main(String[] args){
  System.out.println(TouchFilterChecks.run());
  SessionRunner.current=new SessionRunner();SessionRunner.current.busy=true;SessionRunner.current.automation=new android.app.UiAutomation();
  TouchFilterService f=TouchFilterChecks.fresh();f.onServiceConnected();Handler.drain();
  f.onMotionEvent(down());f.receiver.onReceive(f,new android.content.Intent());Handler.drain();
  TouchFilterChecks.actions();
  f.onMotionEvent(down());Handler.drain();TouchFilterChecks.actions(0);
  f.onServiceConnected();Handler.drain();TouchFilterChecks.actions(0,3);
  TouchFilterChecks.require(f.filtering&&f.getServiceInfo().sources==InputDevice.SOURCE_TOUCHSCREEN,"Reconnect did not restore capture");
  f.onMotionEvent(down());Handler.drain();TouchFilterChecks.actions(0,3,0);
  f.disable();f.enable(SessionRunner.current.automation);Handler.drain();
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
  // Exercise production reflection/flags, not the test sink, including cancellation.
  f.disable();Handler.drain();
  f=new TouchFilterService();f.onServiceConnected();Handler.drain();
  f.onMotionEvent(down());Handler.drain();f.onInterrupt();Handler.drain();
  TouchFilterChecks.require(SessionRunner.current.automation.injected==2,"Non-waiting injection did not forward DOWN and CANCEL");
  System.out.println("PASS: production injector bypasses animation waits for both normal events and cancellation.");
  f.disable();Handler.drain();
  f=TouchFilterChecks.fresh();f.onServiceConnected();Handler.drain();
  f.onMotionEvent(down());Handler.drain();
  MotionEvent old=down();old.setAction(MotionEvent.ACTION_UP);f.onMotionEvent(old);
  android.os.SystemClock.now+=600;Handler.drain();TouchFilterChecks.actions(0,3);
  TouchFilterChecks.require(f.staleEvents==1,"Queued old UP replayed as a click");
  MotionEvent orphan=down();orphan.setAction(MotionEvent.ACTION_MOVE);f.onMotionEvent(orphan);Handler.drain();TouchFilterChecks.actions(0,3);
  f.onMotionEvent(down());Handler.drain();TouchFilterChecks.actions(0,3,0);
  System.out.println("PASS: queued stall cancels stale UP, ignores orphan MOVE, and accepts the next fresh DOWN.");
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
    sources = list(base.rglob('*.java'))
    sources += [root / 'source/app/TouchFilterService.java', root / 'source/app/TouchFilterChecks.java']
    sources += list((root / 'source/shared').glob('*.java'))
    classes = base / 'classes'
    classes.mkdir(exist_ok=True)
    subprocess.run([str(Path(args.jdk) / 'bin/javac.exe'), '-encoding', 'UTF-8', '-d', str(classes), *map(str, sources)], check=True)
    subprocess.run([str(Path(args.jdk) / 'bin/java.exe'), '-cp', str(classes), 'ca.screensafe.app.HostChecks'], check=True)
run()
