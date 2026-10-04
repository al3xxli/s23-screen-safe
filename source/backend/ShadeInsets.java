import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import ca.screensafe.core.SafeArea;

/** Native navigation space expressed in the notification shade's local bounds. */
final class ShadeInsets {
    static final int NAVIGATION_BARS=2;
    // Keep the same source identity across polls. Android removes sources owned
    // by this Binder if the controller dies; normal restoration removes it too.
    static final IBinder owner=new Binder();
    static Object state;
    static Class<?> stateType;

    /** null means a rotation snapshot is not coherent yet; empty means no visible bar. */
    static Rect read(SafeArea area)throws Exception {
        if(state==null){stateType=Class.forName("android.view.InsetsState");state=stateType.getConstructor().newInstance();}
        ScreenSafeBackend.call(DisplayBridge.wm,"getWindowInsets",new Class<?>[]{int.class,IBinder.class,stateType},0,null,state);
        Rect display=(Rect)ScreenSafeBackend.call(state,"getDisplayFrame",new Class<?>[]{});
        if(display.width()!=area.displayWidth||display.height()!=area.displayHeight)return null;
        Rect nav=new Rect();
        int count=(Integer)ScreenSafeBackend.call(state,"sourceSize",new Class<?>[]{});
        for(int i=0;i<count;i++){
            Object source=ScreenSafeBackend.call(state,"sourceAt",new Class<?>[]{int.class},i);
            if((Integer)ScreenSafeBackend.call(source,"getType",new Class<?>[]{})!=NAVIGATION_BARS)continue;
            if(!(Boolean)ScreenSafeBackend.call(source,"isVisible",new Class<?>[]{}))continue;
            Rect physical=(Rect)ScreenSafeBackend.call(source,"getFrame",new Class<?>[]{});
            int left=Math.max(area.left,physical.left),top=Math.max(area.top,physical.top);
            int right=Math.min(area.right,physical.right),bottom=Math.min(area.bottom,physical.bottom);
            if(left<right&&top<bottom)nav.union(left-area.left,top-area.top,right-area.left,bottom-area.top);
        }
        return nav;
    }

    /** Call only for the shade container; all other windows retain native insets. */
    static void apply(Object transaction,Object shadeToken,Rect nav)throws Exception {
        if(nav==null)return;
        if(nav.isEmpty()){remove(transaction,shadeToken);return;}
        ScreenSafeBackend.call(transaction,"addInsetsSource",new Class<?>[]{ScreenSafeBackend.tokenType,IBinder.class,int.class,int.class,Rect.class,Rect[].class,int.class},
                shadeToken,owner,0,NAVIGATION_BARS,nav,null,0);
    }
    static void remove(Object transaction,Object shadeToken)throws Exception {
        ScreenSafeBackend.call(transaction,"removeInsetsSource",new Class<?>[]{ScreenSafeBackend.tokenType,IBinder.class,int.class,int.class},
                shadeToken,owner,0,NAVIGATION_BARS);
    }
}
