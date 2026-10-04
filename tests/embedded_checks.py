"""Host layout/lifecycle regressions; Android Binder permissions require phone validation."""
from pathlib import Path
import argparse
import ast
import subprocess

p=argparse.ArgumentParser();p.add_argument('--jdk',required=True);p.add_argument('--repo',type=Path);args=p.parse_args()
here=Path(__file__).resolve().parent
repo=args.repo.resolve() if args.repo else here.parent
controller=repo/'source/app/EmbeddedController.java'
if not controller.is_file():
    # The development scratch directory can be checked against an explicit repo.
    controller=here/'EmbeddedController.java'
tree=ast.parse((repo/'tests/backend_checks.py').read_text(encoding='utf-8-sig'))
stubs=next(ast.literal_eval(node.value) for node in tree.body if isinstance(node,ast.Assign)
           and any(isinstance(t,ast.Name) and t.id=='stubs' for t in node.targets))
geometry=stubs.pop('BackendChecks.java').replace('BackendChecks','EmbeddedGeometryChecks')
geometry='package ca.screensafe.core;\n'+geometry
geometry=geometry.replace('ScreenSafeBackend.', 'controller.').replace('DisplayBridge.', 'EmbeddedDisplayBridge.').replace('ShadeInsets.', 'EmbeddedShadeInsets.')
geometry=geometry.replace('appliedEmbeddedShadeInsets','appliedShadeInsets')
geometry=geometry.replace('public static void main(String[] ignored)throws Exception {', '''public static void main(String[] ignored)throws Exception {
        EmbeddedController controller=new EmbeddedController(new android.content.Context(),null);
        controller.restored=false;''')
geometry=geometry.replace('controller.restore();','Object restoredShade=controller.shadeToken;controller.stop();')
geometry=geometry.replace('organizer.latest.insetToken==controller.shadeToken,"Restore', 'organizer.latest.insetToken==restoredShade,"Restore')
geometry=geometry.replace('public static class Surface {float x,y;int width,height;}',
    'public static class Surface {float x,y;int width,height;public void release(){}}')
geometry=geometry.replace('Wct latest;int count;boolean fail;', '''Wct latest;int count,unregisters;boolean fail;
        public Organizer(){}public Organizer(java.util.concurrent.Executor ignored){}
        public void unregisterOrganizer(){unregisters++;}''')
stubs['ca/screensafe/core/EmbeddedGeometryChecks.java']=geometry
stubs.pop('DisplayBridge.java')
shade=stubs.pop('ShadeInsets.java').replace('ShadeInsets','EmbeddedShadeInsets').replace('BackendChecks','EmbeddedGeometryChecks')
shade='package ca.screensafe.core;\n'+shade
shade=shade.replace('static boolean incoherent;', 'EmbeddedShadeInsets(EmbeddedController ignored){} static boolean incoherent;')
stubs['ca/screensafe/core/EmbeddedShadeInsets.java']=shade
stubs.update({
 'android/content/Context.java':'package android.content; public class Context {}',
 'android/graphics/Point.java':'package android.graphics; public class Point {public int x,y;}',
 'android/util/Log.java':'''package android.util; public class Log {
    public static int w(String t,String s,Throwable e){return 0;}public static int e(String t,String s,Throwable e){return 0;}}''',
 'android/os/Looper.java':'''package android.os; public class Looper {
    static final Looper MAIN=new Looper(); static final ThreadLocal<Boolean> main=ThreadLocal.withInitial(()->false);
    public static Looper getMainLooper(){return MAIN;}public static Looper myLooper(){return main.get()?MAIN:null;}
 }''',
 'android/os/Handler.java':'''package android.os;public class Handler {
    public Handler(Looper l){} public Looper getLooper(){return Looper.MAIN;}
    public boolean post(Runnable r){synchronized(Looper.MAIN){boolean old=Looper.main.get();Looper.main.set(true);
        try{r.run();}finally{Looper.main.set(old);}return true;}}
    public boolean postDelayed(Runnable r,long delay){return true;}public void removeCallbacks(Runnable r){}
 }''',
 'android/os/IBinder.java':'''package android.os; public interface IBinder {
    void dump(java.io.FileDescriptor fd,String[] args)throws Exception; }''',
 'android/os/ParcelFileDescriptor.java':'''package android.os;public class ParcelFileDescriptor implements java.io.Closeable {
    public static ParcelFileDescriptor[] createPipe(){return new ParcelFileDescriptor[]{new ParcelFileDescriptor(),new ParcelFileDescriptor()};}
    public java.io.FileDescriptor getFileDescriptor(){return null;}public void close()throws java.io.IOException{}
    public static class AutoCloseInputStream extends java.io.InputStream {
        public AutoCloseInputStream(ParcelFileDescriptor p){}public int read(){return -1;}}
 }''',
 'ca/screensafe/core/EmbeddedDisplayBridge.java':'''package ca.screensafe.core; final class EmbeddedDisplayBridge {
    interface Rotation{void changed(int r);}Object wm;static int pendingDraws;Rotation watcher;
    EmbeddedDisplayBridge(android.os.Handler h){}void connect(){}void watch(Rotation r){watcher=r;}void unwatch(){watcher=null;}
 }''',
 'android/hardware/display/DisplayManagerGlobal.java':'''package android.hardware.display; public class DisplayManagerGlobal {
    public static final DisplayManagerGlobal INSTANCE=new DisplayManagerGlobal();
    public static class Info {public int rotation,logicalWidth=1440,logicalHeight=3088,logicalDensityDpi=560;}
    public final Info info=new Info(); public static DisplayManagerGlobal getInstance(){return INSTANCE;}
    public Info getDisplayInfo(int id){return info;}
 }''',
 'ca/screensafe/core/EmbeddedLifecycleChecks.java':r'''package ca.screensafe.core;
import android.graphics.Rect;import android.os.Looper;import java.util.concurrent.atomic.AtomicInteger;
public class EmbeddedLifecycleChecks {
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
    static String nativeContainers(){StringBuilder b=new StringBuilder();
        for(int i=0;i<8;i++)b.append("OneHanded:"+i+" requested-bounds=[0,0][0,0]\n");
        return b+"RemoteWallpaperAnim:1:1 requested-bounds=[0,0][0,0]\n";}
    public static class Organizer extends EmbeddedGeometryChecks.Organizer {
        static Organizer latest;static int registrations;boolean wrongThread;
        public Organizer(java.util.concurrent.Executor ignored){latest=this;}
        public void applyTransaction(EmbeddedGeometryChecks.Wct tx){
            wrongThread|=Looper.myLooper()!=Looper.getMainLooper();super.applyTransaction(tx);}
    }
    static class Controller extends EmbeddedController {
        String display="",containers=nativeContainers();boolean badRegistration;
        Controller(Listener listener){super(new android.content.Context(),listener);}
        void preflight(){validatePreflightDumps(display,containers);}
        void initializeTypes(){organizerType=Organizer.class;wctType=EmbeddedGeometryChecks.Wct.class;
            surfaceType=EmbeddedGeometryChecks.Surface.class;surfaceTxType=EmbeddedGeometryChecks.SurfaceTx.class;
            rectType=Rect.class;tokenType=EmbeddedGeometryChecks.Token.class;}
        void registerAreas(){registered=true;Organizer.registrations++;
            for(int i=0;i<9;i++){Object t=new EmbeddedGeometryChecks.Token(),s=new EmbeddedGeometryChecks.Surface();
                tokens.add(t);surfaces.add(s);if(i==7)shadeToken=t;if(i==8){wallpaperToken=t;wallpaperSurface=s;}}
            if(badRegistration)throw new IllegalStateException("Unexpected organizer count");
        }
    }
    public static void main(String[] args)throws Exception {
        EmbeddedShadeInsets.override=null;EmbeddedShadeInsets.incoherent=false;
        Controller c=new Controller(null);c.start();check(c.isActive(),"start did not apply");
        Organizer first=Organizer.latest;check(!first.wrongThread,"Mutation outside main serialization");
        check(c.stop()&&!c.isActive()&&first.unregisters==1,"stop did not restore/unregister");
        c.start();check(c.isActive()&&Organizer.latest!=first,"start/stop/start reused dead organizer");
        Organizer second=Organizer.latest;second.fail=true;
        check(!c.stop()&&c.isActive()&&second.unregisters==0,"Failed restoration discarded owner or reported success");
        int registrations=Organizer.registrations;
        try{c.start();throw new AssertionError("Restart over failed restoration permitted");}catch(IllegalStateException expected){}
        check(Organizer.registrations==registrations,"Restore failure stole another organizer");
        second.fail=false;check(c.stop()&&second.unregisters==1,"Restore retry did not complete");
        c.display="OneHanded:17:17 (organized)";
        try{c.start();throw new AssertionError("Other organizer was accepted");}catch(IllegalStateException expected){}
        check(Organizer.registrations==registrations,"Ownership failure registered an organizer");
        c.display="";c.containers=nativeContainers().replace("requested-bounds=[0,0][0,0]","requested-bounds=[0,618][1440,3088]");
        try{c.start();throw new AssertionError("Unknown bounds were accepted");}catch(IllegalStateException expected){}
        check(Organizer.registrations==registrations,"Bounds failure registered an organizer");
        c.containers=nativeContainers();c.badRegistration=true;
        try{c.start();throw new AssertionError("Invalid registration accepted");}catch(IllegalStateException expected){}
        check(!c.isActive()&&Organizer.latest.count==0&&Organizer.latest.unregisters==1,"Failed registration changed native bounds");
        AtomicInteger failures=new AtomicInteger();Controller failing=new Controller((message,restored)->{
            check(!restored,"Maintenance restoration falsely succeeded");failures.incrementAndGet();});
        failing.start();Organizer.latest.fail=true;
        failing.onMain(()->{failing.appliedRotation=-1;failing.tick.run();return true;});
        check(failures.get()==1&&failing.isActive()&&failing.paused,"Failure callback/retained recovery state incorrect");
        Organizer.latest.fail=false;check(failing.stop(),"Retry after maintenance failure failed");
        System.out.println("PASS: start/stop/start, serialized mutation, ownership/bounds refusal, failed registration cleanup, restoration failure retention/retry, maintenance failure callback.");
    }
}'''
})
base=repo/'work/embedded-checks';base.mkdir(parents=True,exist_ok=True);sources=[]
for name,text in stubs.items():
    target=base/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text,encoding='utf-8');sources.append(target)
sources.extend([controller,repo/'source/shared/SafeArea.java'])
classes=base/'classes';classes.mkdir(exist_ok=True)
subprocess.run([str(Path(args.jdk)/'bin/javac.exe'),'-encoding','UTF-8','-d',str(classes),*map(str,sources)],check=True)
for name in ['EmbeddedGeometryChecks','EmbeddedLifecycleChecks']:
    subprocess.run([str(Path(args.jdk)/'bin/java.exe'),'-cp',str(classes),'ca.screensafe.core.'+name],check=True)
