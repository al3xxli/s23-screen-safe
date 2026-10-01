package ca.screensafe.app;
import android.view.MotionEvent;
import android.view.InputDevice;
import java.util.ArrayList;

/** Runs on-device without injecting any input into the phone. */
final class TouchFilterChecks {
    static final ArrayList<MotionEvent> sent=new ArrayList<>();
    static long time=1000;
    static TouchFilterService fresh(){
        for(MotionEvent e:sent)e.recycle();sent.clear();
        TouchFilterService f=new TouchFilterService();
        f.testSink=new TouchFilterService.EventSink(){public void send(MotionEvent e){sent.add(MotionEvent.obtain(e));}};
        return f;
    }
    static void event(TouchFilterService f,int action,int[] ids,float... y){
        MotionEvent.PointerProperties[] p=new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] c=new MotionEvent.PointerCoords[ids.length];
        for(int i=0;i<ids.length;i++){p[i]=new MotionEvent.PointerProperties();p[i].id=ids[i];p[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            c[i]=new MotionEvent.PointerCoords();c[i].x=500+i*100;c[i].y=y[i];c[i].pressure=1;c[i].size=1;}
        MotionEvent e=MotionEvent.obtain(1000,time+=10,action,ids.length,p,c,0,0,1,1,6,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try{f.filter(e);}finally{e.recycle();}
    }
    static void require(boolean okay,String message){if(!okay)throw new AssertionError(message);}
    static void point(TouchFilterService f,int action,float x,float y){
        MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=0;p.toolType=MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();c.x=x;c.y=y;c.pressure=1;
        MotionEvent e=MotionEvent.obtain(1000,time+=10,action,1,new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,0,1,1,6,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try{f.filter(e);}finally{e.recycle();}
    }
    static void actions(int... expected){require(sent.size()==expected.length,"Event count "+sent.size()+" expected "+expected.length);
        for(int i=0;i<expected.length;i++)require(sent.get(i).getActionMasked()==expected[i],"Unexpected action at "+i);}
    static String run(){
        TouchFilterService f=fresh();
        event(f,0,new int[]{0},100);event(f,2,new int[]{0},1800);event(f,1,new int[]{0},1800);actions();
        f=fresh();event(f,0,new int[]{0},100);event(f,5|(1<<8),new int[]{0,7},100,1700);
        event(f,2,new int[]{0,7},110,1750);event(f,6,new int[]{0,7},110,1750);event(f,1,new int[]{7},1750);
        actions(0,2,2,1);for(MotionEvent e:sent){require(e.getPointerCount()==1&&e.getPointerId(0)==7,"Ghost mixed into real gesture");require(e.getDownTime()==1020+30,"Filtered down time");}
        f=fresh();event(f,0,new int[]{0},1500);event(f,5|(1<<8),new int[]{0,3},1500,100);
        event(f,2,new int[]{0,3},1600,130);event(f,6|(1<<8),new int[]{0,3},1600,130);event(f,1,new int[]{0},1600);
        actions(0,2,2,2,1);for(MotionEvent e:sent)require(e.getPointerCount()==1,"Extra blocked pointer delivered");
        f=fresh();event(f,0,new int[]{0},1500);event(f,5|(1<<8),new int[]{0,2},1500,2000);
        event(f,2,new int[]{0,2},1400,2100);event(f,6,new int[]{0,2},1400,2100);event(f,1,new int[]{2},2100);actions(0,5,2,6,1);
        require(sent.get(1).getPointerCount()==2&&sent.get(3).getActionIndex()==0,"Multitouch changed");
        f=fresh();event(f,0,new int[]{0},1000);event(f,2,new int[]{0},600);event(f,2,new int[]{0},1200);event(f,1,new int[]{0},1200);actions(0,3);
        f=fresh();event(f,0,new int[]{0},617.99f);event(f,1,new int[]{0},617.99f);actions();
        event(f,0,new int[]{0},618);event(f,1,new int[]{0},618);actions(0,1);
        // The newly usable 20-30% band forwards unchanged physical coordinates.
        f=fresh();point(f,0,700,750);point(f,1,700,750);actions(0,1);
        for(MotionEvent e:sent)require(e.getX(0)==700&&e.getY(0)==750,"Accepted coordinates were remapped");
        // A protected sample hidden in a batched MOVE must cancel even if the latest is safe.
        f=fresh();point(f,0,700,750);
        MotionEvent.PointerProperties bp=new MotionEvent.PointerProperties();bp.id=0;bp.toolType=MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords bc=new MotionEvent.PointerCoords();bc.x=700;bc.y=500;bc.pressure=1;
        MotionEvent batch=MotionEvent.obtain(1000,time+=10,2,1,new MotionEvent.PointerProperties[]{bp},new MotionEvent.PointerCoords[]{bc},0,0,1,1,6,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        bc.y=750;batch.addBatch(time+=10,new MotionEvent.PointerCoords[]{bc},0);
        try{f.filter(batch);}finally{batch.recycle();}
        point(f,1,700,750);actions(0,3);
        // Lost ghost UP followed by reuse of its pointer ID must not poison new fingers.
        f=fresh();event(f,0,new int[]{0},100);event(f,5|(1<<8),new int[]{0,7},100,1700);
        event(f,2,new int[]{7},1750);event(f,5|(1<<8),new int[]{7,0},1750,1800);
        event(f,6|(1<<8),new int[]{7,0},1750,1800);event(f,1,new int[]{7},1750);
        actions(0,2,5,6,1);
        // Lost accepted UP cancels the old stream. A subsequent fresh DOWN recovers.
        f=fresh();event(f,0,new int[]{0},1500);event(f,5|(1<<8),new int[]{0,2},1500,1800);
        event(f,2,new int[]{2},1850);event(f,1,new int[]{2},1850);
        event(f,0,new int[]{2},1850);event(f,1,new int[]{2},1850);actions(0,5,3,0,1);
        // Lock/interruption cleanup must not permit an orphan MOVE to create a click.
        f=fresh();event(f,0,new int[]{0},1500);f.clearStream();
        event(f,2,new int[]{0},1600);event(f,1,new int[]{0},1600);
        event(f,0,new int[]{0},1700);event(f,1,new int[]{0},1700);actions(0,3,0,1);
        // Failure to inject CANCEL must still clear blocked and accepted state.
        f=fresh();event(f,0,new int[]{0},1500);
        f.testSink=new TouchFilterService.EventSink(){public void send(MotionEvent e){throw new IllegalStateException("Locked dispatcher");}};
        f.clearStream();require(f.failures==1,"Cancellation failure was not recorded");
        f.testSink=new TouchFilterService.EventSink(){public void send(MotionEvent e){sent.add(MotionEvent.obtain(e));}};
        event(f,0,new int[]{0},1600);event(f,1,new int[]{0},1600);actions(0,0,1);
        for(int rotation=0;rotation<4;rotation++){
            f=fresh();f.area=new ca.screensafe.core.SafeArea(rotation);
            float goodX=(f.area.left+f.area.right)/2f,goodY=(f.area.top+f.area.bottom)/2f;
            float badX=f.area.maskLeft()+1,badY=f.area.maskTop()+1;
            point(f,0,badX,badY);point(f,2,goodX,goodY);point(f,1,goodX,goodY);actions();
            point(f,0,goodX,goodY);point(f,1,goodX,goodY);actions(0,1);
            point(f,0,goodX,goodY);point(f,2,badX,badY);point(f,1,goodX,goodY);actions(0,1,0,3);
        }
        fresh();return "PASS: filtering, lost pointer UP, reused IDs, interruption recovery, failed cancellation, and mask filtering in all four rotations.";
    }
}
