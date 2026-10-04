"""Check shade-only navigation translation/ownership with Android API fakes."""
from pathlib import Path
import argparse
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--jdk', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
stubs = {
    'android/os/IBinder.java': 'package android.os; public interface IBinder {}',
    'android/os/Binder.java': 'package android.os; public class Binder implements IBinder {}',
    'android/graphics/Rect.java': '''package android.graphics; public class Rect {
        public int left,top,right,bottom;
        public Rect(){} public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
        public int width(){return right-left;} public int height(){return bottom-top;}
        public boolean isEmpty(){return left>=right||top>=bottom;}
        public void union(int l,int t,int r,int b){if(l>=r||t>=b)return;
            if(isEmpty()){left=l;top=t;right=r;bottom=b;return;}
            left=Math.min(left,l);top=Math.min(top,t);right=Math.max(right,r);bottom=Math.max(bottom,b);}
    }''',
    'android/view/InsetsSource.java': '''package android.view; import android.graphics.Rect;
        public class InsetsSource {private int type;private boolean visible;private Rect frame;
        public InsetsSource(int t,boolean v,Rect f){type=t;visible=v;frame=f;}
        public int getType(){return type;}public boolean isVisible(){return visible;}public Rect getFrame(){return frame;}}''',
    'android/view/InsetsState.java': '''package android.view; import android.graphics.Rect;
        public class InsetsState {public Rect display=new Rect();public InsetsSource[] sources={};
        public Rect getDisplayFrame(){return display;}public int sourceSize(){return sources.length;}
        public InsetsSource sourceAt(int i){return sources[i];}}''',
    'DisplayBridge.java': 'final class DisplayBridge {static Object wm;}',
    'ScreenSafeBackend.java': '''final class ScreenSafeBackend {
        static Class<?> tokenType=ShadeChecks.Token.class;
        static Object call(Object target,String name,Class<?>[] types,Object... args)throws Exception {
            return target.getClass().getMethod(name,types).invoke(target,args);}}''',
    'ShadeChecks.java': '''
import android.graphics.Rect;import android.os.IBinder;import android.view.*;
import ca.screensafe.core.SafeArea;
public class ShadeChecks {
    public static class Token {}
    public static class Wm {
        public Rect display;public InsetsSource[] sources={};int queries;
        public void getWindowInsets(int displayId,IBinder token,InsetsState result){
            check(displayId==0&&token==null,"Must query physical display's native insets");
            queries++;result.display=display;result.sources=sources;}
    }
    public static class Wct {
        int added,removed;Token token;IBinder owner;Rect frame;
        public void addInsetsSource(Token t,IBinder o,int i,int type,Rect r,Rect[] bounds,int flags){
            check(i==0&&type==2&&bounds==null&&flags==0,"Unexpected local inset policy");
            added++;token=t;owner=o;frame=r;}
        public void removeInsetsSource(Token t,IBinder o,int i,int type){
            check(i==0&&type==2,"Removal must identify our navigation source");
            removed++;token=t;owner=o;}
    }
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void frame(Rect r,int l,int t,int rr,int b){check(r!=null&&r.left==l&&r.top==t&&r.right==rr&&r.bottom==b,"Unexpected local navigation rectangle");}
    static InsetsSource source(int type,boolean visible,int l,int t,int r,int b){return new InsetsSource(type,visible,new Rect(l,t,r,b));}
    static Rect read(Wm wm,int rotation,InsetsSource... sources)throws Exception {
        SafeArea a=new SafeArea(rotation);wm.display=new Rect(0,0,a.displayWidth,a.displayHeight);wm.sources=sources;
        Rect result=ShadeInsets.read(a);
        if(result!=null&&!result.isEmpty())check(result.left>=0&&result.top>=0&&result.right<=a.width()&&result.bottom<=a.height(),"Inset escaped the local viewport");
        return result;
    }
    public static void main(String[] ignored)throws Exception {
        Wm wm=new Wm();DisplayBridge.wm=wm;
        InsetsSource bottom=source(2,true,0,2920,1440,3088);
        frame(read(wm,0,bottom),0,2302,1440,2470);
        frame(bottom.getFrame(),0,2920,1440,3088);
        frame(read(wm,1,source(2,true,2920,0,3088,1440)),2302,0,2470,1440);
        frame(read(wm,3,source(2,true,0,0,168,1440)),0,0,168,1440);
        frame(read(wm,2,source(2,true,0,0,1440,168)),0,0,1440,168);
        frame(read(wm,0,source(2,true,-30,2920,1700,3300)),0,2302,1440,2470);
        frame(read(wm,1,source(2,true,500,-30,786,1500)),0,0,168,1440);
        check(read(wm,0,source(2,true,0,0,1440,618)).isEmpty(),"Protected strip became a navigation inset");
        check(read(wm,1,source(2,true,0,0,168,1440)).isEmpty(),"Outside left bar was reflected into usable area");
        check(read(wm,3,source(2,true,2920,0,3088,1440)).isEmpty(),"Outside right bar was reflected into usable area");
        check(read(wm,0,source(2,false,0,2920,1440,3088),source(1,true,0,618,1440,743)).isEmpty(),"Hidden navigation/status bar created padding");
        frame(read(wm,0,source(2,true,0,2920,720,3088),source(2,true,720,2920,1440,3088)),0,2302,1440,2470);
        wm.display=new Rect(0,0,3088,1440);
        check(ShadeInsets.read(new SafeArea(0))==null,"Mixed rotation snapshot accepted");
        System.out.println("PASS: visible native navigation translates/clamps to shade bounds in all rotations; hidden/outside sources do not add space.");
        Token shade=new Token();Wct tx=new Wct();Rect nav=read(wm,0,bottom);
        ShadeInsets.apply(tx,shade,nav);
        check(tx.added==1&&tx.removed==0&&tx.token==shade&&tx.owner==ShadeInsets.owner,"Wrong inset receiver or owner");
        frame(tx.frame,0,2302,1440,2470);IBinder owner=tx.owner;
        ShadeInsets.apply(tx,shade,nav);check(tx.owner==owner,"Inset identity changed across updates");
        ShadeInsets.apply(tx,shade,null);check(tx.added==2&&tx.removed==0,"Incoherent snapshot changed inset");
        ShadeInsets.apply(tx,shade,read(wm,0));check(tx.removed==1&&tx.owner==owner&&tx.token==shade,"Hiding navigation did not remove owned source");
        ShadeInsets.remove(tx,shade);check(tx.removed==2&&tx.owner==owner,"Restore removal did not retain source identity");
        System.out.println("PASS: apply/remove uses one stable owner and supplied shade token only; no app bounds or global spacers exist in helper.");
    }
}'''
}
base = root/'work'/'shade-checks'
base.mkdir(parents=True, exist_ok=True)
sources = []
for name, source in stubs.items():
    target = base/name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(source, encoding='utf-8')
    sources.append(target)
sources += [root/'source/backend/ShadeInsets.java',root/'source/shared/SafeArea.java']
classes = base/'classes'
classes.mkdir(exist_ok=True)
subprocess.run([str(Path(args.jdk)/'bin/javac.exe'), '-encoding', 'UTF-8', '-d', str(classes), *map(str,sources)], check=True)
subprocess.run([str(Path(args.jdk)/'bin/java.exe'), '-cp', str(classes), 'ShadeChecks'], check=True)
