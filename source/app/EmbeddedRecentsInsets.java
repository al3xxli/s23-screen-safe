package ca.screensafe.core;

import android.content.ComponentName;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import java.util.ArrayList;
import java.util.List;

/** Samsung Recents-only compensation for its physical-origin child layout. */
final class EmbeddedRecentsInsets {
    final EmbeddedController controller;
    final IBinder owner=new Binder();
    // Retain every attempted owner until a successful removal, including when
    // Binder reports a failure after the server may have applied a transaction.
    final List<Object> owned=new ArrayList<>();
    Object service,appliedToken;
    Rect appliedFrame;
    long settleUntil,retryAfter;

    EmbeddedRecentsInsets(EmbeddedController controller){this.controller=controller;}

    void layoutChanged()throws Exception{
        try{clear();}catch(Exception error){
            appliedToken=null;
            android.util.Log.w("ScreenSafe","Recents inset removal will retry",error);
        }
        // Samsung rebuilds its style from native WindowMetrics during rotation.
        // Deliver our inset after that configuration pass, not before it gets
        // overwritten by the activity's subsequent onConfigurationChanged.
        settleUntil=android.os.SystemClock.uptimeMillis()+750;
    }

    /** A cosmetic/task-query failure must not stop the display or touch guard. */
    void refresh(SafeArea area,Rect localNav){
        if(android.os.SystemClock.uptimeMillis()<retryAfter)return;
        try{update(area,localNav);}
        catch(Exception error){
            appliedToken=null;
            retryAfter=android.os.SystemClock.uptimeMillis()+1000;
            android.util.Log.w("ScreenSafe","Recents spacing update will retry",error);
            try{clear();}catch(Exception cleanup){
                android.util.Log.w("ScreenSafe","Recents spacing cleanup pending",cleanup);
            }
        }
    }

    static Rect frame(SafeArea area,Rect localNav){
        if(localNav==null)return null;
        // Landscape and reverse portrait have no vertical-origin error. Native
        // insets suffice there. Do not invent space for a hidden or side bar.
        if(area.top==0||localNav.isEmpty()||localNav.bottom!=area.height()
                ||localNav.left!=0||localNav.right!=area.width())return new Rect();
        // On this supported Samsung build, one extra native navigation-height
        // gutter clears Close All. Cap it by the offset that causes the error.
        // This affects only Recents, not the display/app bounds or input mapping.
        int extra=Math.min(area.top,localNav.height());
        return new Rect(area.left,Math.max(area.top,area.top+localNav.top-extra),area.right,area.bottom);
    }

    Object findTask(SafeArea area)throws Exception{
        if(service==null)service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        Object task=EmbeddedController.call(service,"getRootTaskInfoOnDisplay",new Class<?>[]{int.class,int.class,int.class},0,3,0);
        if(task==null)return null;
        ComponentName top=(ComponentName)task.getClass().getField("topActivity").get(task);
        if(task.getClass().getField("displayId").getInt(task)!=0||top==null
                ||!"com.sec.android.app.launcher".equals(top.getPackageName())
                ||!"com.android.quickstep.RecentsActivity".equals(top.getClassName()))return null;
        Rect bounds=(Rect)task.getClass().getField("bounds").get(task);
        if(!new Rect(area.left,area.top,area.right,area.bottom).equals(bounds))return null;
        return task.getClass().getField("token").get(task);
    }

    void update(SafeArea area,Rect localNav)throws Exception{
        Rect next=frame(area,localNav);
        if(next==null)return; // incoherent rotation snapshot: retry next tick
        if(!next.isEmpty()&&android.os.SystemClock.uptimeMillis()<settleUntil)return;
        Object token=next.isEmpty()?null:findTask(area);
        if(token==null){clear();return;}
        if(token.equals(appliedToken)&&next.equals(appliedFrame))return;
        Object tx=controller.wctType.getConstructor().newInstance();
        for(Object previous:owned)remove(tx,previous);
        if(!owned.contains(token))owned.add(token);
        EmbeddedController.call(tx,"addInsetsSource",new Class<?>[]{controller.tokenType,IBinder.class,int.class,int.class,Rect.class,Rect[].class,int.class},
                token,owner,0,2,next,null,0);
        EmbeddedController.call(controller.organizer,"applyTransaction",new Class<?>[]{controller.wctType},tx);
        owned.clear();owned.add(token);appliedToken=token;appliedFrame=next;
    }

    void remove(Object tx,Object token)throws Exception{
        EmbeddedController.call(tx,"removeInsetsSource",new Class<?>[]{controller.tokenType,IBinder.class,int.class,int.class},token,owner,0,2);
    }
    void clear()throws Exception{
        if(!owned.isEmpty()){
            Object tx=controller.wctType.getConstructor().newInstance();
            for(Object token:owned)remove(tx,token);
            EmbeddedController.call(controller.organizer,"applyTransaction",new Class<?>[]{controller.wctType},tx);
        }
        owned.clear();appliedToken=null;appliedFrame=null;
    }
}
