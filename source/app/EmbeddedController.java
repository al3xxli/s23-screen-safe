package ca.screensafe.core;

import android.content.Context;
import android.graphics.Point;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** App-owned display organizer: no subprocess, shell pipe, or instrumentation Binder. */
public class EmbeddedController {
    public interface Listener { void onFailure(String message, boolean restored); }
    final Handler handler;
    final Listener listener;
    final EmbeddedDisplayBridge bridge;
    final EmbeddedShadeInsets shadeInsets;
    final EmbeddedRecentsInsets recentsInsets;
    Class<?> organizerType,wctType,surfaceType,surfaceTxType,rectType,tokenType;
    Object organizer,displayManager,wallpaperToken,wallpaperSurface,shadeToken;
    final List<Object> surfaces=new ArrayList<>(),tokens=new ArrayList<>();
    volatile boolean registered,restored=true,paused=true;
    boolean mutationAttempted;
    volatile String lastError;
    int appliedRotation=-1,appliedDensity=-1;
    android.graphics.Rect appliedShadeInsets;
    final Runnable tick=new Runnable(){public void run(){
        if(paused||restored)return;
        try{layout();maintainPosition();handler.postDelayed(this,250);}
        catch(Exception error){
            lastError="Adaptive layout failed: "+error;
            boolean okay=restoreOnMain();
            if(listener!=null)listener.onFailure(lastError,okay);
        }
    }};

    public EmbeddedController(Context context,Listener listener){
        if(context==null)throw new NullPointerException("context");
        this.listener=listener;handler=new Handler(Looper.getMainLooper());
        bridge=new EmbeddedDisplayBridge(handler);shadeInsets=new EmbeddedShadeInsets(this);recentsInsets=new EmbeddedRecentsInsets(this);
    }

    /** Call on SessionRunner's command thread. Returns only after layout is applied. */
    public synchronized void start()throws Exception{
        if(registered||!restored)throw new IllegalStateException("Restore the previous layout before starting again");
        lastError=null;
        bridge.connect();
        preflight();
        onMain(new Work<Boolean>(){public Boolean run()throws Exception{
            restored=false;paused=false;mutationAttempted=false;
            appliedRotation=appliedDensity=-1;appliedShadeInsets=null;
            try{
                initializeTypes();
                organizer=organizerType.getConstructor(Executor.class).newInstance(new Executor(){
                    public void execute(Runnable task){handler.post(task);}
                });
                registerAreas();
                displayManager=Class.forName("android.hardware.display.DisplayManagerGlobal")
                        .getMethod("getInstance").invoke(null);
                bridge.watch(new EmbeddedDisplayBridge.Rotation(){public void changed(int rotation){
                    if(paused||restored)return;
                    try{layout();}catch(Exception error){
                        lastError="Rotation update failed: "+error;
                        boolean okay=restoreOnMain();
                        if(listener!=null)listener.onFailure(lastError,okay);
                    }
                }});
                layout();
                if(appliedRotation<0)throw new IllegalStateException("Display rotation is changing; try again");
                handler.postDelayed(tick,250);
                return true;
            }catch(Exception error){
                lastError="Could not start display protection: "+error;
                restoreOnMain();
                throw error;
            }
        }});
    }

    /** False means configuration/surfaces are not confirmed restored: retain guard/filter. */
    public synchronized boolean stop(){
        try{return onMain(new Work<Boolean>(){public Boolean run(){return restoreOnMain();}});}
        catch(Exception error){lastError="Restore failed: "+error;return false;}
    }
    public boolean isActive(){return registered&&!restored;}
    public String getLastError(){return lastError;}

    interface Work<T>{T run()throws Exception;}
    <T>T onMain(final Work<T> work)throws Exception{
        if(Looper.myLooper()==handler.getLooper())return work.run();
        FutureTask<T> task=new FutureTask<>(new java.util.concurrent.Callable<T>(){
            public T call()throws Exception{return work.run();}
        });
        if(!handler.post(task))throw new IllegalStateException("Main looper is shutting down");
        // Do not time out and let queued configuration mutate after the caller has
        // removed its guard. This operation is serialized with layout and rotation.
        try{return task.get();}catch(java.util.concurrent.ExecutionException error){
            Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;
            throw new Exception(cause);
        }
    }
    void initializeTypes()throws Exception{
        organizerType=Class.forName("android.window.DisplayAreaOrganizer");
        wctType=Class.forName("android.window.WindowContainerTransaction");
        surfaceType=Class.forName("android.view.SurfaceControl");
        surfaceTxType=Class.forName("android.view.SurfaceControl$Transaction");
        rectType=android.graphics.Rect.class;tokenType=Class.forName("android.window.WindowContainerToken");
    }
    void preflight()throws Exception{
        if(!android.os.Build.MODEL.equals("SM-S918W"))throw new IllegalStateException("This prototype supports SM-S918W only");
        Point initial=new Point(),base=new Point();
        call(bridge.wm,"getInitialDisplaySize",new Class<?>[]{int.class,Point.class},0,initial);
        call(bridge.wm,"getBaseDisplaySize",new Class<?>[]{int.class,Point.class},0,base);
        if(initial.x!=SafeArea.WIDTH||initial.y!=SafeArea.HEIGHT||base.x!=initial.x||base.y!=initial.y)
            throw new IllegalStateException("Restore the normal 1440x3088 resolution before enabling");
        validatePreflightDumps(dumpBinder("window",new String[]{"displays"}),
                dumpBinder("activity",new String[]{"containers"}));
    }
    static void validatePreflightDumps(String displays,String containers){
        if(displays.contains("Permission Denial:")||containers.contains("Permission Denial:"))
            throw new IllegalStateException("Display ownership inspection denied; DUMP and PACKAGE_USAGE_STATS delegation are required");
        if(java.util.regex.Pattern.compile("OneHanded:.*\\(organized\\)").matcher(displays).find()
                ||java.util.regex.Pattern.compile("RemoteWallpaperAnim:.*\\(organized\\)").matcher(displays).find())
            throw new IllegalStateException("Another feature already controls a protected display area");
        int areas=0,wallpapers=0;
        for(String line:containers.split("\n")){
            if(line.contains("OneHanded:")){
                areas++;
                if(!line.contains("requested-bounds=[0,0][0,0]"))
                    throw new IllegalStateException("Unexpected existing display-area bounds; run Restore Screen first");
            }
            if(line.contains("RemoteWallpaperAnim:")){
                wallpapers++;
                if(!line.contains("requested-bounds=[0,0][0,0]"))
                    throw new IllegalStateException("Unexpected wallpaper bounds; run Restore Screen first");
            }
        }
        if(areas!=8||wallpapers!=1)throw new IllegalStateException("Unrecognized Samsung layout; expected eight areas and one wallpaper, found "+areas+" and "+wallpapers);
    }
    void registerAreas()throws Exception{
        List<?> areas=(List<?>)call(organizer,"registerOrganizer",new Class<?>[]{int.class},3);
        registered=true;
        for(Object area:areas){
            Object info=call(area,"getDisplayAreaInfo",new Class<?>[]{});
            if(info.getClass().getField("displayId").getInt(info)!=0)
                throw new IllegalStateException("Unexpected secondary display area");
            Object token=info.getClass().getField("token").get(info),leash=call(area,"getLeash",new Class<?>[]{});
            tokens.add(token);surfaces.add(leash);
            if(leash.toString().contains("OneHanded:17:17")){
                if(shadeToken!=null)throw new IllegalStateException("Duplicate notification area");
                shadeToken=token;
            }
        }
        if(tokens.size()!=8||shadeToken==null)throw new IllegalStateException("Unrecognized Samsung display areas");
        List<?> wallpapers=(List<?>)call(organizer,"registerOrganizer",new Class<?>[]{int.class},10002);
        for(Object area:wallpapers){
            Object info=call(area,"getDisplayAreaInfo",new Class<?>[]{});
            if(info.getClass().getField("displayId").getInt(info)!=0)
                throw new IllegalStateException("Unexpected secondary wallpaper area");
            Object token=info.getClass().getField("token").get(info),leash=call(area,"getLeash",new Class<?>[]{});
            tokens.add(token);surfaces.add(leash);
            if(wallpaperToken!=null||!leash.toString().contains("RemoteWallpaperAnim:1:1"))
                throw new IllegalStateException("Unrecognized Samsung wallpaper area");
            wallpaperToken=token;wallpaperSurface=leash;
        }
        if(tokens.size()!=9||wallpaperToken==null)throw new IllegalStateException("Missing Samsung wallpaper area");
    }

    boolean restoreOnMain(){
        paused=true;handler.removeCallbacks(tick);
        if(restored)return true;
        try{bridge.unwatch();}catch(Exception error){Log.w("ScreenSafe","Rotation watcher cleanup failed",error);}
        try{
            recentsInsets.clear();
            if(mutationAttempted){
                Object tx=wctType.getConstructor().newInstance();
                if(shadeToken!=null)shadeInsets.remove(tx,shadeToken);
                for(Object token:tokens){
                    call(tx,"setBounds",new Class<?>[]{tokenType,rectType},token,rectType.getConstructor().newInstance());
                    call(tx,"setAppBounds",new Class<?>[]{tokenType,rectType},token,null);
                    call(tx,"setScreenSizeDp",new Class<?>[]{tokenType,int.class,int.class},token,0,0);
                    call(tx,"setSmallestScreenWidthDp",new Class<?>[]{tokenType,int.class},token,0);
                }
                call(organizer,"applyTransaction",new Class<?>[]{wctType},tx);
                Object surfacesTx=surfaceTxType.getConstructor().newInstance();
                try{
                    for(Object surface:surfaces){
                        call(surfacesTx,"setPosition",new Class<?>[]{surfaceType,float.class,float.class},surface,0f,0f);
                        call(surfacesTx,"setWindowCrop",new Class<?>[]{surfaceType,int.class,int.class},surface,0,0);
                    }
                    call(surfacesTx,"apply",new Class<?>[]{});
                }finally{call(surfacesTx,"close",new Class<?>[]{});}
            }
            if(registered)call(organizer,"unregisterOrganizer",new Class<?>[]{});
        }catch(Exception error){
            lastError="Layout restoration is unconfirmed: "+error;
            Log.e("ScreenSafe",lastError,error);
            // Keep organizer/tokens available for stop() retry and keep the caller's
            // guard/filter. Do not unregister after a failed bounds transaction.
            return false;
        }
        for(Object surface:surfaces)try{call(surface,"release",new Class<?>[]{});}
            catch(Exception error){Log.w("ScreenSafe","Leash cleanup failed after restore",error);}
        tokens.clear();surfaces.clear();wallpaperToken=wallpaperSurface=shadeToken=organizer=null;
        registered=false;restored=true;mutationAttempted=false;appliedRotation=appliedDensity=-1;appliedShadeInsets=null;
        return true;
    }

    static Object call(Object target,String name,Class<?>[] types,Object...args)throws Exception{
        try{return target.getClass().getMethod(name,types).invoke(target,args);}
        catch(InvocationTargetException error){throw new Exception(name,error.getCause());}
    }
    /** Read-only Binder dump: bounded memory/time, no subprocess and no persisted private data. */
    static String dumpBinder(String serviceName,final String[] args)throws Exception{
        final IBinder service=(IBinder)Class.forName("android.os.ServiceManager")
                .getMethod("getService",String.class).invoke(null,serviceName);
        if(service==null)throw new IOException("Missing system service: "+serviceName);
        final ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
        final CountDownLatch done=new CountDownLatch(2);
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        final Throwable[] failure=new Throwable[2];
        Thread reader=new Thread(new Runnable(){public void run(){
            try(ParcelFileDescriptor.AutoCloseInputStream in=new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])){
                byte[] bytes=new byte[4096];int n;
                while((n=in.read(bytes))!=-1){
                    if(output.size()+n>4*1024*1024)throw new IOException("System dump exceeded diagnostic limit");
                    output.write(bytes,0,n);
                }
            }catch(Throwable error){failure[0]=error;}finally{done.countDown();}
        }},"ScreenSafe preflight read");
        Thread writer=new Thread(new Runnable(){public void run(){
            try{service.dump(pipe[1].getFileDescriptor(),args);}
            catch(Throwable error){failure[1]=error;}
            finally{try{pipe[1].close();}catch(IOException ignored){}done.countDown();}
        }},"ScreenSafe preflight Binder");
        reader.setDaemon(true);writer.setDaemon(true);reader.start();writer.start();
        try{
            if(!done.await(4,TimeUnit.SECONDS))throw new IOException("System preflight timed out");
            if(failure[0]!=null||failure[1]!=null)throw new IOException("Cannot inspect display ownership",failure[0]!=null?failure[0]:failure[1]);
            return output.toString("UTF-8");
        }finally{
            try{pipe[0].close();}catch(IOException ignored){}
            try{pipe[1].close();}catch(IOException ignored){}
        }
    }

    // Geometry methods below are copied verbatim from the verified 0.10 shell
    // controller, apart from instance helper names and mutation bookkeeping.
    void position(Object st,SafeArea area)throws Exception{
        for(Object s:surfaces){
            if(s==wallpaperSurface){
                call(st,"setPosition",new Class<?>[]{surfaceType,float.class,float.class},s,0f,0f);
                call(st,"setWindowCrop",new Class<?>[]{surfaceType,int.class,int.class},s,0,0);
                continue;
            }
            call(st,"setPosition",new Class<?>[]{surfaceType,float.class,float.class},s,(float)area.left,(float)area.top);
            call(st,"setWindowCrop",new Class<?>[]{surfaceType,int.class,int.class},s,area.width(),area.height());
        }
    }
    void maintainPosition()throws Exception{
        if(restored||appliedRotation<0)return;
        // System transitions can reset the organized surfaces without a bounds
        // change. Keep their transforms consistent with the physical WM bounds.
        Object tx=surfaceTxType.getConstructor().newInstance();
        try{position(tx,new SafeArea(appliedRotation));call(tx,"apply",new Class<?>[]{});}
        finally{call(tx,"close",new Class<?>[]{});}
    }

    void layout() throws Exception {
        if(restored)return;
        Object info=call(displayManager,"getDisplayInfo",new Class<?>[]{int.class},0);
        // Rotation callbacks can arrive before DisplayInfo has the new dimensions.
        // Use one coherent display snapshot instead of mixing those two epochs.
        int rotation=info.getClass().getField("rotation").getInt(info);
        int density=info.getClass().getField("logicalDensityDpi").getInt(info);
        SafeArea snapshot=new SafeArea(rotation);
        if(info.getClass().getField("logicalWidth").getInt(info)!=snapshot.displayWidth
                ||info.getClass().getField("logicalHeight").getInt(info)!=snapshot.displayHeight)return;
        android.graphics.Rect shadeInsets=this.shadeInsets.read(snapshot);
        if(shadeInsets==null)return;
        if(rotation==appliedRotation&&density==appliedDensity&&shadeInsets.equals(appliedShadeInsets)){
            recentsInsets.refresh(snapshot,shadeInsets);return;
        }
        final SafeArea area=new SafeArea(rotation);
        // Android and SurfaceFlinger must agree on the viewport origin. Local (0,0)
        // configuration plus an independent surface offset loses native bar insets
        // and snaps back to zero when Android finishes a rotation/app transition.
        Object bounds=rectType.getConstructor(int.class,int.class,int.class,int.class).newInstance(area.left,area.top,area.right,area.bottom);
        Object tx=wctType.getConstructor().newInstance();
        for(int i=0;i<tokens.size();i++){
            Object token=tokens.get(i);
            // The wallpaper-only child retains native dimensions. Its shared
            // OneHanded parent still resizes apps and clips everything to the mask.
            // Give this child the same origin as its parent so its local position is zero.
            if(token==wallpaperToken){
                Object nativeBounds=rectType.getConstructor(int.class,int.class,int.class,int.class).newInstance(area.left,area.top,area.left+area.displayWidth,area.top+area.displayHeight);
                call(tx,"setBounds",new Class<?>[]{tokenType,rectType},token,nativeBounds);
                call(tx,"setAppBounds",new Class<?>[]{tokenType,rectType},token,nativeBounds);
                call(tx,"setScreenSizeDp",new Class<?>[]{tokenType,int.class,int.class},token,area.displayWidth*160/density,area.displayHeight*160/density);
                continue;
            }
            // Samsung's notification stack clips its cards when its configuration
            // has a nonzero origin. Only this area uses local coordinates; mirror
            // its native navigation frame locally so the footer still fits.
            Object windowBounds=token==shadeToken
                    ?rectType.getConstructor(int.class,int.class,int.class,int.class).newInstance(0,0,area.width(),area.height()):bounds;
            call(tx,"setBounds",new Class<?>[]{tokenType,rectType},token,windowBounds);
            call(tx,"setAppBounds",new Class<?>[]{tokenType,rectType},token,null);
            call(tx,"setScreenSizeDp",new Class<?>[]{tokenType,int.class,int.class},token,area.width()*160/density,area.height()*160/density);
            call(tx,"setSmallestScreenWidthDp",new Class<?>[]{tokenType,int.class},token,SafeArea.WIDTH*160/density);
        }
        this.shadeInsets.apply(tx,shadeToken,shadeInsets);
        // A sync covering all display areas can wait for a hidden status bar to
        // redraw, blocking subsequent rotations until BLAST's five-second timeout.
        mutationAttempted=true;
        call(organizer,"applyTransaction",new Class<?>[]{wctType},tx);
        appliedRotation=rotation;appliedDensity=density;appliedShadeInsets=shadeInsets;
        recentsInsets.layoutChanged();
        recentsInsets.refresh(snapshot,shadeInsets);
        maintainPosition();

    }


}
