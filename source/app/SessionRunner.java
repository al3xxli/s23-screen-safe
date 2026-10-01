package ca.screensafe.app;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.*;
import android.view.*;
import java.io.*;
import java.util.concurrent.*;
import ca.screensafe.core.SafeArea;

/** ADB-activated prototype session. No accessibility services are disabled. */
public class SessionRunner extends Instrumentation {
    public static volatile SessionRunner current;
    public volatile String status="Ready to protect your screen";
    public volatile boolean busy;
    final BlockingQueue<String> commands=new LinkedBlockingQueue<>();
    UiAutomation automation; WindowManager manager; View guard;
    volatile OutputStream control;
    ScheduledExecutorService pulses;
    CountDownLatch backendFinished;
    volatile boolean restored; volatile String backendError;
    String testMode; boolean autoStart;
    DisplayManager displayManager;
    final DisplayManager.DisplayListener displayListener=new DisplayManager.DisplayListener(){
        public void onDisplayAdded(int id){}
        public void onDisplayRemoved(int id){}
        public void onDisplayChanged(int id){if(id==0&&guard!=null)updateGuard();}
    };
    void updateGuard(){
        SafeArea area=new SafeArea(displayManager.getDisplay(0).getRotation());
        WindowManager.LayoutParams p=(WindowManager.LayoutParams)guard.getLayoutParams();
        p.width=area.maskWidth();p.height=area.maskHeight();p.x=area.maskLeft();p.y=area.maskTop();
        if(guardRotation!=area.rotation){manager.updateViewLayout(guard,p);guardRotation=area.rotation;}
        if(TouchFilterService.current!=null)TouchFilterService.current.setRotation(area.rotation);
    }
    int guardRotation=-1;
    public void onCreate(Bundle args){testMode=args==null?null:args.getString("test");autoStart=args!=null&&"true".equals(args.getString("protect"));start();}
    public void request(String command){commands.offer(command);}
    void mainAction(final Runnable action) throws Exception {
        final Throwable[] error=new Throwable[1];
        runOnMainSync(new Runnable(){public void run(){try{action.run();}catch(Throwable t){error[0]=t;}}});
        if(error[0]!=null)throw new Exception(error[0]);
    }
    public void onStart(){
        Bundle result=new Bundle();
        if("checks".equals(testMode)){
            try{result.putString("stream",TouchFilterChecks.run());finish(0,result);}
            catch(Throwable error){result.putString("error",android.util.Log.getStackTraceString(error));finish(1,result);}
            return;
        }
        try {
            automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            automation.adoptShellPermissionIdentity("android.permission.INTERNAL_SYSTEM_WINDOW","android.permission.STATUS_BAR_SERVICE");
            current=this;
            getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if(testMode!=null){
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable(){public void run(){request("START");}},2000);
                long stopAt="adaptive".equals(testMode)?300000:("filter".equals(testMode)?92000:12000);
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable(){public void run(){request("disconnect".equals(testMode)?"DISCONNECT":"STOP");}},stopAt);
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable(){public void run(){request("EXIT");}},stopAt+8000);
            }
            boolean running=true;
            while(running){
                String command=commands.take();
                try {
                    if(command.equals("START")&&!busy)begin();
                    else if(command.equals("STOP")){stopBackend();if(control==null)cleanSession();}
                    else if(command.equals("DISCONNECT")){if(control!=null)control.close();}
                    else if(command.equals("DONE"))cleanSession();
                    else if(command.equals("EXIT")){stopBackend();if(backendFinished!=null)backendFinished.await(10,TimeUnit.SECONDS);cleanSession();if(!busy)running=false;}
                } catch(Exception e){status="Could not start: "+e.getMessage();stopBackend();if(backendFinished!=null)backendFinished.await(10,TimeUnit.SECONDS);cleanSession();status="Stopped: "+e.getMessage();}
            }
        }catch(Throwable e){result.putString("error",android.util.Log.getStackTraceString(e));}
        finally {
            try{stopBackend();if(backendFinished!=null)backendFinished.await(10,TimeUnit.SECONDS);cleanSession();}catch(Exception ignored){}
            current=null;
            if(automation!=null)automation.dropShellPermissionIdentity();
        }
        result.putString("stream","Screen Safe session ended.\n");finish(0,result);
    }
    void begin() throws Exception {
        if(TouchFilterService.current==null)throw new IllegalStateException("Activate Screen Safe touch filter from your computer first.");
        busy=true;restored=false;backendError=null;status="Preparing the protected area…";
        mainAction(new Runnable(){public void run(){addGuard();}});
        ParcelFileDescriptor[] pipes=automation.executeShellCommandRw("env CLASSPATH=/data/local/tmp/screensafe-backend.dex app_process /system/bin ScreenSafeBackend");
        control=new ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]);
        final InputStream output=new ParcelFileDescriptor.AutoCloseInputStream(pipes[0]);
        backendFinished=new CountDownLatch(1);
        new Thread(new Runnable(){public void run(){
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(output))){
                String line;
                while((line=reader.readLine())!=null){
                    if(line.equals("APPLIED")){
                        try{mainAction(new Runnable(){public void run(){TouchFilterService.current.enable(automation);updateGuard();}});
                            status=testMode==null?"Protected\nAdaptive layout preview":"Experimental layout active\nFive-minute rotation trial";
                        }catch(Exception e){backendError="Touch filter could not start";stopBackend();}
                    }
                    else if(line.equals("RESTORED"))restored=true;
                    else if(line.startsWith("ERROR:")||line.equals("RESTORE_ERROR"))backendError=line;
                    android.util.Log.i("ScreenSafe",line);
                }
            }catch(IOException e){backendError="Controller connection closed";}
            finally {backendFinished.countDown();commands.offer("DONE");}
        }},"ScreenSafe display").start();
        pulses=Executors.newSingleThreadScheduledExecutor();
        pulses.scheduleAtFixedRate(new Runnable(){public void run(){try{send("H");}catch(IOException ignored){}}},0,2,TimeUnit.SECONDS);
    }
    synchronized void send(String text) throws IOException {
        if(control!=null){control.write((text+"\n").getBytes("UTF-8"));control.flush();}
    }
    void stopBackend(){
        if(!busy)return;
        status="Restoring your full screen…";
        try{send("STOP");}catch(IOException ignored){}
        try{if(control!=null)control.close();}catch(IOException ignored){}
    }
    void cleanSession() throws Exception {
        if(pulses!=null){pulses.shutdownNow();pulses=null;}
        try{if(control!=null)control.close();}catch(IOException ignored){}control=null;
        if(busy && !restored && backendFinished!=null && backendFinished.getCount()==0) {
            status="Recovering the full screen…";
            ParcelFileDescriptor recovery=automation.executeShellCommand("env CLASSPATH=/data/local/tmp/screensafe-backend.dex app_process /system/bin ScreenSafeBackend recover");
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(recovery)))) {
                String line; boolean failed=false;
                while((line=reader.readLine())!=null){if(line.startsWith("ERROR:")||line.equals("RESTORE_ERROR"))failed=true;if(line.equals("RESTORED")&&!failed)restored=true;}
            }
        }
        if(busy&&!restored){
            // A failed recovery can leave the layout shifted. Keep its protection in place.
            status="Layout not restored\nTouch filtering may be unavailable. Reconnect your computer and run Restore Screen.";
            android.util.Log.e("ScreenSafe","Recovery unconfirmed; retaining guard and filter");
            return;
        }
        mainAction(new Runnable(){public void run(){
            if(TouchFilterService.current!=null)TouchFilterService.current.disable();
            if(displayManager!=null)displayManager.unregisterDisplayListener(displayListener);
            if(guard!=null&&guard.isAttachedToWindow())manager.removeViewImmediate(guard);guard=null;
        }});
        if(busy)status=backendError!=null?"Stopped\n"+backendError:(restored?"Full screen restored\nReady when you are":"Stopped. Check the screen is restored.");
        busy=false;backendFinished=null;
    }
    void addGuard(){
        Context context=getTargetContext();
        displayManager=context.getSystemService(DisplayManager.class);
        context=context.createDisplayContext(context.getSystemService(DisplayManager.class).getDisplay(0)).createWindowContext(2024,null);
        manager=context.getSystemService(WindowManager.class);
        guardRotation=-1;
        guard=new View(context){public boolean onTouchEvent(MotionEvent event){return true;}};
        guard.setBackgroundColor(Color.BLACK);
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(SafeArea.WIDTH,SafeArea.STRIP,2024,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS|WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,PixelFormat.OPAQUE);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.setTitle("Screen Safe touch guard");p.packageName=getTargetContext().getPackageName();
        p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;p.setFitInsetsTypes(0);manager.addView(guard,p);
        displayManager.registerDisplayListener(displayListener,new Handler(Looper.getMainLooper()));updateGuard();
    }
}
