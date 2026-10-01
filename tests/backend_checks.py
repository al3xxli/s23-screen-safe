"""Exercise production layout control with a status area that never redraws.

These are host regressions for controller behavior, not Android rendering tests.
"""
from pathlib import Path
import argparse
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--jdk', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
stubs = {
    'android/os/Looper.java': '''package android.os; public class Looper {
        public static void prepareMainLooper(){} public static Looper getMainLooper(){return null;}
        public static void loop(){} }''',
    'android/os/Handler.java': '''package android.os; public class Handler {
        public Handler(Looper l){} public boolean postDelayed(Runnable r,long delay){return true;} }''',
    'android/os/SystemClock.java': '''package android.os; public class SystemClock {
        public static long uptimeMillis(){return System.nanoTime()/1000000;} }''',
    'android/os/Build.java': '''package android.os; public class Build {public static String MODEL="SM-S918W";}''',
    'DisplayBridge.java': '''final class DisplayBridge {
        interface Rotation {void changed(int r);} interface Ready {void run(Object tx)throws Exception;}
        static void watch(Rotation r){} static void unwatch(){}
        // Reproduce a hidden status bar never producing a sync-ready callback.
        static int pendingDraws;
        static void sync(Object organizer,Object tx,Ready ready){pendingDraws++;}
    }''',
    'BackendChecks.java': r'''
import java.util.*;
import ca.screensafe.core.SafeArea;
public class BackendChecks {
    public static class Rect {
        public int left,top,right,bottom;
        public Rect(){} public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
    }
    public static class Token {}
    public static class Surface {float x,y;int width,height;}
    public static class SurfaceTx {
        static int applied,closed;
        public SurfaceTx setPosition(Surface s,float x,float y){s.x=x;s.y=y;return this;}
        public SurfaceTx setWindowCrop(Surface s,int w,int h){s.width=w;s.height=h;return this;}
        public void apply(){applied++;} public void close(){closed++;}
    }
    public static class Change {Rect bounds,appBounds;int widthDp,heightDp;}
    public static class Wct {
        Map<Token,Change> changes=new LinkedHashMap<>();
        Change change(Token t){return changes.computeIfAbsent(t,k->new Change());}
        public Wct setBounds(Token t,Rect r){change(t).bounds=r;return this;}
        public Wct setAppBounds(Token t,Rect r){change(t).appBounds=r;return this;}
        public Wct setScreenSizeDp(Token t,int w,int h){change(t).widthDp=w;change(t).heightDp=h;return this;}
        public Wct setSmallestScreenWidthDp(Token t,int w){return this;}
    }
    public static class Organizer {
        Wct latest;int count;boolean fail;
        public void applyTransaction(Wct tx){if(fail)throw new IllegalStateException("test failure");latest=tx;count++;}
    }
    public static class Info {public int rotation,logicalWidth,logicalHeight,logicalDensityDpi=560;}
    public static class Displays {
        Info info=new Info();
        public Info getDisplayInfo(int id){return info;}
        void rotate(int r){SafeArea a=new SafeArea(r);info.rotation=r;info.logicalWidth=a.displayWidth;info.logicalHeight=a.displayHeight;}
    }
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void bounds(Rect r,SafeArea a){check(r.left==a.left&&r.top==a.top&&r.right==a.right&&r.bottom==a.bottom,"Physical window bounds mismatch");}
    public static void main(String[] ignored)throws Exception {
        ScreenSafeBackend.wctType=Wct.class;ScreenSafeBackend.rectType=Rect.class;
        ScreenSafeBackend.tokenType=Token.class;ScreenSafeBackend.surfaceType=Surface.class;
        ScreenSafeBackend.surfaceTxType=SurfaceTx.class;
        Organizer organizer=new Organizer();Displays displays=new Displays();
        ScreenSafeBackend.organizer=organizer;ScreenSafeBackend.displayManager=displays;
        for(int i=0;i<9;i++){
            Token t=new Token();Surface s=new Surface();
            ScreenSafeBackend.tokens.add(t);ScreenSafeBackend.surfaces.add(s);
            if(i==8){ScreenSafeBackend.wallpaperToken=t;ScreenSafeBackend.wallpaperSurface=s;}
        }
        // No draw callback is ever delivered. Every turn must still update both
        // configuration and surface geometry, including immediate reversals.
        for(int rotation:new int[]{0,1,0,3,2,0,1,3,0}){
            displays.rotate(rotation);int before=organizer.count;ScreenSafeBackend.layout();
            SafeArea a=new SafeArea(rotation);
            check(organizer.count==before+1,"Rotation stalled waiting for another area to draw");
            check(DisplayBridge.pendingDraws==0,"Layout introduced a redraw dependency");
            for(int i=0;i<8;i++){
                Change c=organizer.latest.changes.get(ScreenSafeBackend.tokens.get(i));
                bounds(c.bounds,a);check(c.appBounds==null,"Native app insets no longer inherited");
                check(c.widthDp==a.width()*160/560&&c.heightDp==a.height()*160/560,"Viewport dimensions incorrect");
                Surface s=(Surface)ScreenSafeBackend.surfaces.get(i);
                check(s.x==a.left&&s.y==a.top&&s.width==a.width()&&s.height==a.height(),"Surface disagrees with physical viewport");
            }
            Change wallpaper=organizer.latest.changes.get(ScreenSafeBackend.wallpaperToken);
            check(wallpaper.bounds.left==a.left&&wallpaper.bounds.top==a.top,"Wallpaper origin changed");
            check(wallpaper.bounds.right-a.left==a.displayWidth&&wallpaper.bounds.bottom-a.top==a.displayHeight,"Wallpaper native dimensions lost");
            Surface ws=(Surface)ScreenSafeBackend.wallpaperSurface;
            check(ws.x==0&&ws.y==0&&ws.width==0&&ws.height==0,"Wallpaper must inherit parent crop");
        }
        System.out.println("PASS: non-drawing status area cannot stall rotations; rapid reversals retain physical bounds, native insets, and wallpaper geometry.");
        int before=organizer.count;ScreenSafeBackend.layout();check(organizer.count==before,"Unchanged configuration reapplied");
        displays.info.rotation=1; // dimensions still belong to portrait
        ScreenSafeBackend.layout();check(organizer.count==before,"Mixed display snapshots accepted");
        displays.rotate(1);organizer.fail=true;
        try{ScreenSafeBackend.layout();throw new AssertionError("Apply failure was swallowed");}catch(Exception expected){}
        check(ScreenSafeBackend.appliedRotation==0,"Failed layout marked as applied");
        organizer.fail=false;ScreenSafeBackend.layout();check(ScreenSafeBackend.appliedRotation==1,"Retry did not apply landscape");
        Surface s=(Surface)ScreenSafeBackend.surfaces.get(0);s.x=0;s.width=0;
        ScreenSafeBackend.maintainPosition();check(s.x==618&&s.width==2470,"Transition reset was not repaired");
        ScreenSafeBackend.restored=true;displays.rotate(0);before=organizer.count;int positioned=SurfaceTx.applied;
        ScreenSafeBackend.layout();ScreenSafeBackend.maintainPosition();
        check(organizer.count==before&&SurfaceTx.applied==positioned,"Late callback changed restored display");
        check(SurfaceTx.applied==SurfaceTx.closed,"Surface transactions leaked");
        System.out.println("PASS: coherent snapshots, apply-failure retry, surface repair, and restored-session isolation.");
    }
}'''
}
base = root / 'work' / 'backend-checks'
base.mkdir(parents=True, exist_ok=True)
sources = []
for name, source in stubs.items():
    target = base / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(source, encoding='utf-8')
    sources.append(target)
sources += [root/'source/backend/ScreenSafeBackend.java', root/'source/shared/SafeArea.java']
classes = base / 'classes'
classes.mkdir(exist_ok=True)
subprocess.run([str(Path(args.jdk)/'bin/javac.exe'), '-encoding', 'UTF-8', '-d', str(classes), *map(str,sources)], check=True)
subprocess.run([str(Path(args.jdk)/'bin/java.exe'), '-cp', str(classes), 'BackendChecks'], check=True)
