"""Recents scope, rotation, idempotency and partial-Binder-failure cleanup."""
from pathlib import Path
import argparse, subprocess
p=argparse.ArgumentParser();p.add_argument('--jdk',required=True);args=p.parse_args()
root=Path(__file__).resolve().parents[1]
stubs={
'android/os/SystemClock.java':'package android.os; public class SystemClock {public static long now;public static long uptimeMillis(){return now;}}',
'android/util/Log.java':'package android.util;public class Log {public static int w(String tag,String msg,Throwable e){return 0;}}',
'android/os/IBinder.java':'package android.os; public interface IBinder {}',
'android/os/Binder.java':'package android.os; public class Binder implements IBinder {}',
'android/content/ComponentName.java':'''package android.content; public class ComponentName {
String p,c;public ComponentName(String p,String c){this.p=p;this.c=c;}public String getPackageName(){return p;}public String getClassName(){return c;}}''',
'android/graphics/Rect.java':'''package android.graphics; public class Rect {
public int left,top,right,bottom;public Rect(){}public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
public int width(){return right-left;}public int height(){return bottom-top;}
public boolean isEmpty(){return left>=right||top>=bottom;}
public boolean equals(Object o){if(!(o instanceof Rect))return false;Rect r=(Rect)o;return left==r.left&&top==r.top&&right==r.right&&bottom==r.bottom;}}''',
'ca/screensafe/core/EmbeddedController.java':'''package ca.screensafe.core; class EmbeddedController {
Class<?> wctType=RecentsChecks.Wct.class,tokenType=RecentsChecks.Token.class;Object organizer=new RecentsChecks.Organizer();
static Object call(Object t,String n,Class<?>[] c,Object...a)throws Exception{return t.getClass().getMethod(n,c).invoke(t,a);}}''',
'android/app/ActivityTaskManager.java':'''package android.app; public class ActivityTaskManager {
public static final ca.screensafe.core.RecentsChecks.Service SERVICE=new ca.screensafe.core.RecentsChecks.Service();
public static Object getService(){return SERVICE;}}''',
'ca/screensafe/core/RecentsChecks.java':'''package ca.screensafe.core;
import android.graphics.Rect;import android.content.ComponentName;import android.os.IBinder;import java.util.*;
public class RecentsChecks {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static class Token {}
 public static class Info {public int displayId=0;public ComponentName topActivity=new ComponentName("com.sec.android.app.launcher","com.android.quickstep.RecentsActivity");public Rect bounds=new Rect(0,618,1440,3088);public Token token=new Token();}
 public static class Service {public Info task=new Info();int queries;public Info getRootTaskInfoOnDisplay(int mode,int type,int display){check(mode==0&&type==3&&display==0,"Wrong task scope");queries++;return task;}}
 public static class Wct {List<Token> removed=new ArrayList<>();Token added;IBinder owner;Rect frame;
 public void addInsetsSource(Token t,IBinder o,int i,int type,Rect r,Rect[] bounds,int flags){check(i==0&&type==2&&bounds==null&&flags==0,"Wrong source policy");added=t;owner=o;frame=r;}
 public void removeInsetsSource(Token t,IBinder o,int i,int type){check(i==0&&type==2,"Wrong removal");removed.add(t);owner=o;}}
 public static class Organizer {List<Wct> transactions=new ArrayList<>();boolean fail;public void applyTransaction(Wct tx){transactions.add(tx);if(fail)throw new IllegalStateException("Binder failed after possible server apply");}}
 public static void main(String[] args)throws Exception {
 EmbeddedController c=new EmbeddedController();EmbeddedRecentsInsets insets=new EmbeddedRecentsInsets(c);
 Organizer org=(Organizer)c.organizer;Service service=android.app.ActivityTaskManager.SERVICE;
 SafeArea portrait=new SafeArea(0);Rect nav=new Rect(0,2302,1440,2470);
 insets.update(portrait,nav);check(org.transactions.size()==1,"Initial source missing");
 Wct first=org.transactions.get(0);Token original=service.task.token;
 check(first.added==original&&first.frame.equals(new Rect(0,2752,1440,3088)),"Wrong physical Recents frame");
 check(nav.equals(new Rect(0,2302,1440,2470)),"Mutated shared shade frame");
 insets.update(portrait,nav);check(org.transactions.size()==1,"Unchanged layout reapplied");
 insets.update(portrait,null);check(org.transactions.size()==1,"Incoherent snapshot changed layout");
 service.task=new Info();Token replacement=service.task.token;insets.update(portrait,nav);
 Wct changed=org.transactions.get(1);check(changed.removed.contains(original)&&changed.added==replacement&&changed.owner==first.owner,"Task replacement leaked source or changed owner");
 insets.update(new SafeArea(1),new Rect(2302,0,2470,1440));
 check(org.transactions.get(2).removed.contains(replacement)&&insets.owned.isEmpty(),"Landscape kept portrait compensation");
 for(int r=1;r<4;r++)check(EmbeddedRecentsInsets.frame(new SafeArea(r),new Rect(0,1272,2470,1440)).isEmpty(),"Nonportrait compensation");
 insets.update(portrait,nav);insets.update(portrait,new Rect());check(insets.owned.isEmpty(),"Hidden nav source retained");
 service.task.topActivity=new ComponentName("another.app","com.android.quickstep.RecentsActivity");insets.update(portrait,nav);check(insets.owned.isEmpty(),"Another app modified");
 service.task=new Info();service.task.displayId=1;insets.update(portrait,nav);check(insets.owned.isEmpty(),"Secondary display modified");
 service.task=new Info();service.task.bounds=new Rect(0,0,1440,3088);insets.update(portrait,nav);check(insets.owned.isEmpty(),"Stale task geometry modified");
 service.task=new Info();insets.update(portrait,nav);service.task=null;insets.update(portrait,nav);check(insets.owned.isEmpty(),"Missing task retained source");
 service.task=new Info();org.fail=true;
 try{insets.update(portrait,nav);throw new AssertionError("Apply failure swallowed");}catch(java.lang.reflect.InvocationTargetException expected){}
 check(insets.owned.contains(service.task.token)&&insets.appliedToken==null,"Unconfirmed source owner discarded");
 try{insets.clear();throw new AssertionError("Restore failure swallowed");}catch(java.lang.reflect.InvocationTargetException expected){}
 check(!insets.owned.isEmpty(),"Failed restore discarded cleanup token");
 org.fail=false;insets.clear();check(insets.owned.isEmpty(),"Restore retry failed");
 insets.update(portrait,nav);insets.clear();int count=org.transactions.size();insets.clear();check(org.transactions.size()==count,"Repeated restore mutated layout");
 insets.layoutChanged();int delayed=org.transactions.size();insets.update(portrait,nav);check(org.transactions.size()==delayed,"Applied during Samsung configuration pass");
 android.os.SystemClock.now+=749;insets.update(portrait,nav);check(org.transactions.size()==delayed,"Settle interval shortened");
 android.os.SystemClock.now++;insets.update(portrait,nav);check(org.transactions.size()==delayed+1,"Deferred source not applied");insets.clear();
 org.fail=true;insets.refresh(portrait,nav);check(!insets.owned.isEmpty(),"Cosmetic failure lost cleanup ownership");
 int pendingCount=org.transactions.size();insets.refresh(portrait,nav);check(org.transactions.size()==pendingCount,"Failure retry spun on every tick");
 org.fail=false;android.os.SystemClock.now+=1000;insets.refresh(portrait,nav);check(insets.appliedToken==service.task.token,"Cosmetic failure did not recover");insets.clear();
 System.out.println("PASS: Recents targeting, physical geometry, rotation/hidden-bar removal, task replacement, stable ownership, idempotency, apply/restore failure retention and restart.");
 }
}'''}
base=root/'work/recents-checks';base.mkdir(parents=True,exist_ok=True);sources=[]
for name,text in stubs.items():
 path=base/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8');sources.append(path)
sources += [root/'source/app/EmbeddedRecentsInsets.java',root/'source/shared/SafeArea.java']
classes=base/'classes';classes.mkdir(exist_ok=True)
subprocess.run([str(Path(args.jdk)/'bin/javac.exe'),'-encoding','UTF-8','-d',str(classes),*map(str,sources)],check=True)
subprocess.run([str(Path(args.jdk)/'bin/java.exe'),'-cp',str(classes),'ca.screensafe.core.RecentsChecks'],check=True)
