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
import ca.screensafe.core.EmbeddedController;

/** ADB bootstraps permissions once; protection then belongs to this app process. */
public class SessionRunner extends Instrumentation {
    public static volatile SessionRunner current;
    public volatile String status="Ready to protect your screen";
    public volatile boolean busy,ready;
    final BlockingQueue<String> commands=new LinkedBlockingQueue<>();
    WindowManager manager; View guard;
    EmbeddedController controller;
    volatile String backendError;
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
        UiAutomation bootstrap=null;
        try {
            bootstrap=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            bootstrap.adoptShellPermissionIdentity("android.permission.INTERNAL_SYSTEM_WINDOW",
                    "android.permission.STATUS_BAR_SERVICE","android.permission.INJECT_EVENTS",
                    "android.permission.MANAGE_ACTIVITY_TASKS","android.permission.DUMP",
                    "android.permission.PACKAGE_USAGE_STATS");
            // Leave an explicit recovery marker for the computer's restore command if
            // this app ever dies. The app never depends on this shell process afterward.
            ParcelFileDescriptor file=bootstrap.executeShellCommand("env CLASSPATH=/data/local/tmp/screensafe-backend.dex app_process /system/bin ScreenSafeBackend prepare");
            boolean prepared=false;
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(file)))){
                String line;while((line=reader.readLine())!=null){
                    if(line.equals("PREPARED"))prepared=true;
                    if(line.startsWith("ERROR:")||line.equals("RESTORE_ERROR"))throw new IOException(line);
                }
            }
            if(!prepared)throw new IOException("Computer recovery was not prepared. Run Start Screen Safe again.");
            // Disconnecting automation does not finish instrumentation or revoke its
            // delegated permissions. It also avoids a dead Binder at session shutdown.
            UiAutomation.class.getMethod("destroy").invoke(bootstrap);
            bootstrap=null;
            ready=true;current=this;
            getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if(autoStart&&TouchFilterService.current!=null)request("START");
            if(testMode!=null){
                Handler timers=new Handler(Looper.getMainLooper());
                timers.postDelayed(new Runnable(){public void run(){request("START");}},2000);
                long stopAt="adaptive".equals(testMode)?300000:("filter".equals(testMode)?92000:12000);
                timers.postDelayed(new Runnable(){public void run(){request("STOP");}},stopAt);
                timers.postDelayed(new Runnable(){public void run(){request("EXIT");}},stopAt+8000);
            }
            boolean running=true;
            while(running){
                String command=commands.take();
                try {
                    if(command.equals("START")&&!busy)begin();
                    else if(command.equals("STOP")||command.equals("FAILED"))endProtection();
                    else if(command.equals("EXIT")){if(endProtection())running=false;}
                } catch(Exception error){
                    backendError=error.getMessage();
                    android.util.Log.e("ScreenSafe","Protection operation failed",error);
                    endProtection();
                }
            }
        }catch(Throwable error){
            result.putString("error",android.util.Log.getStackTraceString(error));
            backendError=error.toString();
            android.util.Log.e("ScreenSafe","Session failed; restoring before exit",error);
            // Unexpected command-thread failure must not abandon resized bounds.
            // Keep the activated session available when restoration needs a retry.
            while(busy&&!endProtection()){
                try{commands.take();}catch(InterruptedException interrupted){Thread.interrupted();}
            }
        }
        finally {
            ready=false;current=null;
            // Only bootstrap failure reaches this with a live automation connection.
            // Normal completion is revoked by AMS when finish() ends instrumentation.
            if(bootstrap!=null){
                try{bootstrap.dropShellPermissionIdentity();}catch(Throwable ignored){}
                try{UiAutomation.class.getMethod("destroy").invoke(bootstrap);}catch(Throwable ignored){}
            }
        }
        result.putString("stream","Screen Safe session ended.\n");
        // A failed initial disconnect already marks automation disconnected. Retrying
        // finish lets AMS perform its permission cleanup if the launcher disappeared.
        try{finish(0,result);}catch(RuntimeException disconnected){finish(0,result);}
    }
    void begin() throws Exception {
        if(!ready||TouchFilterService.current==null)throw new IllegalStateException("Activate Screen Safe touch filter from your computer first.");
        busy=true;backendError=null;status="Preparing the protected area…";
        mainAction(new Runnable(){public void run(){addGuard();}});
        controller=new EmbeddedController(getTargetContext(),new EmbeddedController.Listener(){
            public void onFailure(String error,boolean restored){backendError=error;commands.offer("FAILED");}
        });
        controller.start();
        mainAction(new Runnable(){public void run(){TouchFilterService.current.enable();updateGuard();}});
        status=testMode==null?"Protected\nController running on this phone":"Experimental layout active\nFive-minute rotation trial";
        android.util.Log.i("ScreenSafe","APPLIED in-app controller pid="+android.os.Process.myPid());
    }
    boolean endProtection() {
        if(!busy)return true;
        status="Restoring your full screen…";
        boolean restored=controller==null||controller.stop();
        if(!restored){
            status="Layout not restored\nReconnect your computer and run Restore Screen.";
            android.util.Log.e("ScreenSafe","Recovery unconfirmed; retaining guard and filter");
            return false;
        }
        try {
            final TouchFilterService filter=TouchFilterService.current;
            mainAction(new Runnable(){public void run(){if(filter!=null)filter.disable();}});
            if(filter!=null&&!filter.awaitDisabled()){
                status="Waiting for touch cleanup\nTry Restore again before ending this session.";
                return false;
            }
            mainAction(new Runnable(){public void run(){
                if(displayManager!=null)displayManager.unregisterDisplayListener(displayListener);
                if(guard!=null&&guard.isAttachedToWindow())manager.removeViewImmediate(guard);guard=null;
            }});
        } catch(Exception error){status="Could not finish restoration: "+error.getMessage();return false;}
        controller=null;busy=false;
        status=backendError!=null?"Stopped\n"+backendError:"Full screen restored\nReady when you are";
        android.util.Log.i("ScreenSafe","RESTORED in-app controller");
        return true;
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
