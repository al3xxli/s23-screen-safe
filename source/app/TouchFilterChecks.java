package ca.screensafe.app;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.os.SystemClock;
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
    static void currentPoint(TouchFilterService f,int action,long when,float x,float y){
        MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=0;p.toolType=MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();c.x=x;c.y=y;c.pressure=1;
        MotionEvent e=MotionEvent.obtain(1000,when,action,1,new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,0,1,1,6,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try{f.filterCurrent(e);}finally{e.recycle();}
    }
    static MotionEvent sample(int action,int flags,int[] ids,MotionEvent.PointerCoords[] coords){
        MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[ids.length];
        for(int i=0;i<ids.length;i++){props[i]=new MotionEvent.PointerProperties();props[i].id=ids[i];props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;}
        return MotionEvent.obtain(1000,time+=10,action,ids.length,props,coords,0,0,1,1,6,0,InputDevice.SOURCE_TOUCHSCREEN,flags);
    }
    static MotionEvent.PointerCoords coord(float x,float y){
        MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();c.x=x;c.y=y;c.pressure=1;c.size=1;return c;
    }
    static void pass(TouchFilterService f,MotionEvent e){try{f.filter(e);}finally{e.recycle();}}
    static void palm(MotionEvent.PointerCoords c,float value){
        // Reproduce this phone's native packed axis, which its Java getter hides.
        try{
            java.lang.reflect.Field bits=MotionEvent.PointerCoords.class.getDeclaredField("mPackedAxisBits");bits.setAccessible(true);
            java.lang.reflect.Field values=MotionEvent.PointerCoords.class.getDeclaredField("mPackedAxisValues");values.setAccessible(true);
            long old=bits.getLong(c),bit=Long.MIN_VALUE>>>55;
            int index=Long.bitCount(old&~(-1L>>>55)),count=Long.bitCount(old);
            float[] data=(float[])values.get(c),next=new float[count+((old&bit)==0?1:0)];
            if(data!=null){System.arraycopy(data,0,next,0,index);System.arraycopy(data,index+((old&bit)==0?0:1),next,index+1,count-index-((old&bit)==0?0:1));}
            next[index]=value;values.set(c,next);bits.setLong(c,old|bit);
        }catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    static String ghostChecks(){
        TouchFilterService f=fresh();event(f,0,new int[]{7},1500);
        event(f,5|(1<<8),new int[]{7,0},1500,100);
        for(int i=0;i<1000;i++)event(f,2,new int[]{7,0},1500,100+(i%100));
        event(f,6|(1<<8),new int[]{7,0},1500,199);
        actions(0);require(f.suppressedStationary==1002,"Ghost-only changes flooded accepted stationary pointer");
        require(f.mixedSamples==1002&&f.activePointers==1&&f.rejectedPointers==0,"Mixed/contact diagnostics incorrect");
        event(f,2,new int[]{7},1510);event(f,1,new int[]{7},1510);actions(0,2,1);
        // Pressure and other axes can change without X/Y movement.
        f=fresh();MotionEvent.PointerCoords real=coord(700,1500),ghost=coord(600,100);
        pass(f,sample(0,0,new int[]{7},new MotionEvent.PointerCoords[]{real}));
        pass(f,sample(5|(1<<8),0,new int[]{7,0},new MotionEvent.PointerCoords[]{real,ghost}));actions(0);
        real.pressure=.5f;pass(f,sample(2,0,new int[]{7,0},new MotionEvent.PointerCoords[]{real,ghost}));actions(0,2);
        real.setAxisValue(32,12);pass(f,sample(2,0,new int[]{7,0},new MotionEvent.PointerCoords[]{real,ghost}));actions(0,2,2);
        MotionEvent.PointerCoords delivered=new MotionEvent.PointerCoords();sent.get(2).getPointerCoords(0,delivered);
        require(delivered.pressure==.5f&&delivered.getAxisValue(32)==12,"Accepted axes changed or were lost");
        // Preserve a real excursion in history even when the final point is unchanged.
        real.y=1600;MotionEvent history=sample(2,0,new int[]{7,0},new MotionEvent.PointerCoords[]{real,ghost});
        long firstTime=history.getEventTime();real.y=1500;history.addBatch(time+=10,new MotionEvent.PointerCoords[]{real,ghost},0);
        pass(f,history);actions(0,2,2,2);
        MotionEvent deliveredHistory=sent.get(3);
        require(deliveredHistory.getHistorySize()==1&&deliveredHistory.getHistoricalY(0,0)==1600&&deliveredHistory.getY(0)==1500,"Accepted MOVE history lost");
        require(deliveredHistory.getHistoricalEventTime(0)==firstTime&&deliveredHistory.getPointerCount()==1,"Filtered history timing/ghost pointer changed");
        // CANCEL contains only contacts still logically active after a POINTER_UP.
        f=fresh();event(f,0,new int[]{0},1500);event(f,5|(1<<8),new int[]{0,1},1500,1800);
        event(f,6|(1<<8),new int[]{0,1},1500,1800);require(f.activePointers==1,"Released pointer stayed active");
        f.clearStream();actions(0,5,6,3);
        MotionEvent cancel=sent.get(3);require(cancel.getPointerCount()==1&&cancel.getPointerId(0)==0,"CANCEL resurrected a released pointer");
        require((cancel.getFlags()&MotionEvent.FLAG_CANCELED)!=0,"CANCEL flag missing");
        // Hardware/palm cancellation of the last accepted finger is not a click.
        f=fresh();ghost=coord(500,100);real=coord(600,1500);
        pass(f,sample(0,0,new int[]{0},new MotionEvent.PointerCoords[]{ghost}));
        pass(f,sample(5|(1<<8),0,new int[]{0,7},new MotionEvent.PointerCoords[]{ghost,real}));
        pass(f,sample(6|(1<<8),MotionEvent.FLAG_CANCELED,new int[]{0,7},new MotionEvent.PointerCoords[]{ghost,real}));actions(0,3);
        require(f.activePointers==0,"Canceled finger stayed active");
        event(f,2,new int[]{0},100);event(f,5|(1<<8),new int[]{0,7},100,1700);actions(0,3,0);
        // Samsung tags otherwise-valid fingers as palms during the captured freeze.
        // Classification must not survive rebuilding the accepted stream or its history.
        f=fresh();ghost=coord(500,100);real=coord(700,1500);
        palm(ghost,1);palm(real,1);real.setAxisValue(32,12);real.pressure=.7f;
        pass(f,sample(0,0,new int[]{0},new MotionEvent.PointerCoords[]{ghost}));actions();
        pass(f,sample(5|(1<<8),0,new int[]{0,7},new MotionEvent.PointerCoords[]{ghost,real}));actions(0);
        sent.get(0).getPointerCoords(0,delivered);
        require(sent.get(0).getAxisValue(55,0)==0,"Samsung palm marker cancels accepted DOWN");
        require(delivered.x==700&&delivered.y==1500&&delivered.pressure==.7f&&delivered.getAxisValue(32)==12,"Palm correction changed real touch data");
        palm(real,2);pass(f,sample(2,0,new int[]{0,7},new MotionEvent.PointerCoords[]{ghost,real}));actions(0);
        real.y=1600;palm(real,2);history=sample(2,0,new int[]{0,7},new MotionEvent.PointerCoords[]{ghost,real});
        real.y=1700;palm(real,3);history.addBatch(time+=10,new MotionEvent.PointerCoords[]{ghost,real},0);
        pass(f,history);actions(0,2);
        sent.get(1).getHistoricalPointerCoords(0,0,delivered);require(sent.get(1).getHistoricalAxisValue(55,0,0)==0&&delivered.y==1600,"Historical palm classification leaked");
        sent.get(1).getPointerCoords(0,delivered);require(sent.get(1).getAxisValue(55,0)==0&&delivered.y==1700,"Current palm classification leaked");
        // A real hardware cancellation still cancels, and a rejected contact cannot
        // become accepted by moving out of the strip with the palm marker set.
        pass(f,sample(6|(1<<8),MotionEvent.FLAG_CANCELED,new int[]{0,7},new MotionEvent.PointerCoords[]{ghost,real}));actions(0,2,3);
        ghost.y=1800;pass(f,sample(2,0,new int[]{0},new MotionEvent.PointerCoords[]{ghost}));actions(0,2,3);
        f=fresh();ghost=coord(500,100);real=coord(700,1500);palm(real,1);
        pass(f,sample(0,0,new int[]{7},new MotionEvent.PointerCoords[]{real}));
        pass(f,sample(5|(1<<8),0,new int[]{7,0},new MotionEvent.PointerCoords[]{real,ghost}));actions(0);
        palm(real,-2);pass(f,sample(2,0,new int[]{7,0},new MotionEvent.PointerCoords[]{real,ghost}));actions(0,2);
        require(sent.get(1).getAxisValue(55,0)==-2,"Changed non-palm classification was suppressed");
        f=fresh();real=coord(700,1500);palm(real,-2);
        pass(f,sample(0,0,new int[]{7},new MotionEvent.PointerCoords[]{real}));sent.get(0).getPointerCoords(0,delivered);
        require(sent.get(0).getAxisValue(55,0)==-2,"Unrelated Samsung classification changed");
        f=fresh();palm(real,1);
        MotionEvent.PointerProperties[] sp={new MotionEvent.PointerProperties()};sp[0].id=7;sp[0].toolType=2;
        MotionEvent stylus=MotionEvent.obtain(1000,time+=10,0,1,sp,new MotionEvent.PointerCoords[]{real},0,0,1,1,6,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        pass(f,stylus);sent.get(0).getPointerCoords(0,delivered);require(sent.get(0).getAxisValue(55,0)==1,"Non-finger classification changed");
        fresh();return "PASS: ghost flood suppression, real axes/history, active-only cancellation, and hardware-canceled pointer handling.";
    }
    static String run(){
        TouchFilterService f=fresh();
        event(f,0,new int[]{0},100);event(f,2,new int[]{0},1800);event(f,1,new int[]{0},1800);actions();
        f=fresh();event(f,0,new int[]{0},100);event(f,5|(1<<8),new int[]{0,7},100,1700);
        event(f,2,new int[]{0,7},110,1750);event(f,6,new int[]{0,7},110,1750);event(f,1,new int[]{7},1750);
        actions(0,2,1);for(MotionEvent e:sent){require(e.getPointerCount()==1&&e.getPointerId(0)==7,"Ghost mixed into real gesture");require(e.getDownTime()==1020+30,"Filtered down time");}
        f=fresh();event(f,0,new int[]{0},1500);event(f,5|(1<<8),new int[]{0,3},1500,100);
        event(f,2,new int[]{0,3},1600,130);event(f,6|(1<<8),new int[]{0,3},1600,130);event(f,1,new int[]{0},1600);
        actions(0,2,1);for(MotionEvent e:sent)require(e.getPointerCount()==1,"Extra blocked pointer delivered");
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
        // A delayed tap must never replay after a worker/system stall.
        f=fresh();long stale=SystemClock.uptimeMillis()-TouchFilterService.MAX_EVENT_AGE_MS-100;
        currentPoint(f,0,stale,700,1500);currentPoint(f,1,stale,700,1500);actions();
        require(f.staleEvents==2,"Old input was not discarded");
        // An old UP must cancel an already accepted contact, not finish a click.
        f=fresh();point(f,0,700,1500);long cancelledAt=SystemClock.uptimeMillis();
        currentPoint(f,1,stale,700,1500);actions(0,3);
        require(sent.get(1).getEventTime()>=cancelledAt,"Cancellation retained a stale timestamp");
        currentPoint(f,2,SystemClock.uptimeMillis(),700,1600);currentPoint(f,1,SystemClock.uptimeMillis(),700,1600);actions(0,3);
        currentPoint(f,0,SystemClock.uptimeMillis(),700,1700);currentPoint(f,1,SystemClock.uptimeMillis(),700,1700);actions(0,3,0,1);
        // A long-held gesture is healthy when its individual samples are current.
        f=fresh();currentPoint(f,0,SystemClock.uptimeMillis(),700,1500);
        currentPoint(f,2,SystemClock.uptimeMillis(),700,1800);currentPoint(f,1,SystemClock.uptimeMillis(),700,1800);actions(0,2,1);
        require(f.staleEvents==0,"Gesture downTime incorrectly treated as event age");
        ghostChecks();fresh();return "PASS: filtering, pointer recovery, lifecycle cancellation, all four rotations, stale-input rejection, fresh touch recovery, ghost-flood suppression, active-only cancellation, canceled-palm handling, and native Samsung palm normalization.";
    }
}
