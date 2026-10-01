import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Executor;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import ca.screensafe.core.SafeArea;

/** Shell-side controller for the experimentally verified SM-S918W display areas. */
public final class ScreenSafeBackend {
    static final int WIDTH=SafeArea.WIDTH, HEIGHT=SafeArea.HEIGHT;
    static Class<?> organizerType, wctType, surfaceType, surfaceTxType, rectType, tokenType;
    static Object organizer;
    static final List<Object> surfaces = new ArrayList<>(), tokens = new ArrayList<>();
    static Object wallpaperToken,wallpaperSurface;
    static boolean registered, restored, rotationChanged;
    static String originalRotation;
    static volatile boolean stop;
    static volatile long heartbeat;
    static final File stateFile=new File("/data/local/tmp/screensafe-rotation-state");
    static boolean ownsState;
    static int appliedRotation=-1, appliedDensity=-1;
    static Object displayManager;
    static boolean layoutPending;
    static long layoutStarted;
    static void position(Object st,SafeArea area)throws Exception{
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
    static void maintainPosition()throws Exception{
        if(restored||layoutPending||appliedRotation<0)return;
        // System transitions can reset the organized surfaces without a bounds
        // change. Keep their transforms consistent with the physical WM bounds.
        Object tx=surfaceTxType.getConstructor().newInstance();
        try{position(tx,new SafeArea(appliedRotation));call(tx,"apply",new Class<?>[]{});}
        finally{call(tx,"close",new Class<?>[]{});}
    }

    static void layout() throws Exception {
        Object info=call(displayManager,"getDisplayInfo",new Class<?>[]{int.class},0);
        // Rotation callbacks can arrive before DisplayInfo has the new dimensions.
        // Use one coherent display snapshot instead of mixing those two epochs.
        int rotation=info.getClass().getField("rotation").getInt(info);
        int density=info.getClass().getField("logicalDensityDpi").getInt(info);
        SafeArea snapshot=new SafeArea(rotation);
        if(info.getClass().getField("logicalWidth").getInt(info)!=snapshot.displayWidth
                ||info.getClass().getField("logicalHeight").getInt(info)!=snapshot.displayHeight)return;
        if(layoutPending)return;
        if(rotation==appliedRotation&&density==appliedDensity)return;
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
            call(tx,"setBounds",new Class<?>[]{tokenType,rectType},token,bounds);
            call(tx,"setAppBounds",new Class<?>[]{tokenType,rectType},token,null);
            call(tx,"setScreenSizeDp",new Class<?>[]{tokenType,int.class,int.class},token,area.width()*160/density,area.height()*160/density);
            call(tx,"setSmallestScreenWidthDp",new Class<?>[]{tokenType,int.class},token,WIDTH*160/density);
        }
        layoutPending=true;layoutStarted=SystemClock.uptimeMillis();
        appliedRotation=rotation;appliedDensity=density;
        DisplayBridge.sync(organizer,tx,new DisplayBridge.Ready(){public void run(Object st)throws Exception{
          try{
            if(!restored){
            position(st,area);
            }
            call(st,"apply",new Class<?>[]{});
          }finally{call(st,"close",new Class<?>[]{});layoutPending=false;}
          if(!restored){System.out.println("LAYOUT "+area.rotation+" "+area.left+" "+area.top+" "+area.width()+" "+area.height());System.out.flush();layout();}
        }});
    }

    static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        try { return target.getClass().getMethod(name, types).invoke(target, args); }
        catch (InvocationTargetException e) { throw new Exception(name, e.getCause()); }
    }
    static String command(String... args) throws Exception {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        StringBuilder result = new StringBuilder();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line; while((line=r.readLine())!=null) result.append(line).append('\n');
        }
        if(p.waitFor()!=0) throw new IOException("Command failed: "+Arrays.toString(args)+" "+result);
        return result.toString().trim();
    }
    static synchronized void restore() {
        if(restored) return;
        restored=true;
        boolean okay=true;
        try{DisplayBridge.unwatch();}catch(Exception e){okay=false;e.printStackTrace(System.out);}
        try {
            if(!tokens.isEmpty()) {
                Object tx=wctType.getConstructor().newInstance();
                for(Object token:tokens){
                    call(tx,"setBounds",new Class<?>[]{tokenType,rectType},token,rectType.getConstructor().newInstance());
                    call(tx,"setAppBounds",new Class<?>[]{tokenType,rectType},token,null);
                    call(tx,"setScreenSizeDp",new Class<?>[]{tokenType,int.class,int.class},token,0,0);
                    call(tx,"setSmallestScreenWidthDp",new Class<?>[]{tokenType,int.class},token,0);
                }
                call(organizer,"applyTransaction",new Class<?>[]{wctType},tx);
            }
        } catch(Exception e) { okay=false; e.printStackTrace(System.out); }
        try {
            if(!surfaces.isEmpty()) {
                Object tx=surfaceTxType.getConstructor().newInstance();
                for(Object surface:surfaces) {
                    call(tx,"setPosition",new Class<?>[]{surfaceType,float.class,float.class},surface,0f,0f);
                    call(tx,"setWindowCrop",new Class<?>[]{surfaceType,int.class,int.class},surface,0,0);
                }
                call(tx,"apply",new Class<?>[]{}); call(tx,"close",new Class<?>[]{});
            }
        } catch(Exception e) { okay=false; e.printStackTrace(System.out); }
        try { if(registered) call(organizer,"unregisterOrganizer",new Class<?>[]{}); }
        catch(Exception e) { okay=false; e.printStackTrace(System.out); }
        try {
            if(rotationChanged) {
                if(originalRotation.equals("free")) command("wm","user-rotation","free");
                else command("wm","user-rotation","lock",originalRotation.substring(5));
            }
        } catch(Exception e) { okay=false; e.printStackTrace(System.out); }
        if(okay && ownsState && stateFile.exists() && !stateFile.delete()) okay=false;
        System.out.println(okay?"RESTORED":"RESTORE_ERROR"); System.out.flush();
    }
    public static void main(String[] args) {
        int exitCode=0;
        try {
            boolean recovering=args.length==1 && args[0].equals("recover");
            if(args.length>0&&!recovering) throw new IllegalArgumentException("Unknown mode");
            if(!android.os.Build.MODEL.equals("SM-S918W")) throw new IllegalStateException("This prototype supports SM-S918W only.");
            if(!command("wm","size").equals("Physical size: 1440x3088")) throw new IllegalStateException("Restore the phone's normal 1440x3088 resolution before enabling.");
            String display=command("dumpsys","window","displays");
            if(java.util.regex.Pattern.compile("OneHanded:.*\\(organized\\)").matcher(display).find()) throw new IllegalStateException("Another feature already controls this display area.");
            if(java.util.regex.Pattern.compile("RemoteWallpaperAnim:.*\\(organized\\)").matcher(display).find())throw new IllegalStateException("Another feature controls the wallpaper area.");
            String containers=command("dumpsys","activity","containers");
            for(String line:containers.split("\n"))if(line.contains("RemoteWallpaperAnim:")){
                boolean empty=line.contains("requested-bounds=[0,0][0,0]");
                boolean ours=line.contains("requested-bounds=[0,0][1440,3088]")||line.contains("requested-bounds=[0,0][3088,1440]")
                        ||line.contains("requested-bounds=[0,618][1440,3706]")||line.contains("requested-bounds=[618,0][3706,1440]");
                if(!empty&&!(recovering&&stateFile.exists()&&ours))throw new IllegalStateException("Unexpected wallpaper display-area bounds.");
            }
            int count=0;
            for(String line:containers.split("\n")) if(line.contains("OneHanded:")) {
                count++;
                boolean empty=line.contains("requested-bounds=[0,0][0,0]");
                // Permit recovery of prior 10/20/30% layouts and both adaptive viewport sizes.
                boolean ours=line.contains("requested-bounds=[0,927][1440,3088]")
                        || line.contains("requested-bounds=[927,0][3088,1440]")
                        || line.contains("requested-bounds=[0,0][1440,2161]")
                        || line.contains("requested-bounds=[0,0][2161,1440]")
                        || line.contains("requested-bounds=[0,0][1440,2470]")
                        || line.contains("requested-bounds=[0,0][2470,1440]")
                        || line.contains("requested-bounds=[0,618][1440,3088]")
                        || line.contains("requested-bounds=[618,0][3088,1440]")
                        || line.contains("requested-bounds=[0,309][1440,3088]");
                if(!empty && !(recovering && stateFile.exists() && ours)) throw new IllegalStateException("Unexpected existing display-area bounds.");
            }
            if(count!=8) throw new IllegalStateException("Unrecognized Samsung display layout; expected 8 areas.");
            originalRotation=command("wm","user-rotation");
            if(recovering && stateFile.exists()) {
                try(BufferedReader r=new BufferedReader(new FileReader(stateFile))){originalRotation=r.readLine();rotationChanged=!"adaptive-v1".equals(r.readLine());}
                ownsState=true;
            } else if(!recovering && stateFile.exists()) throw new IllegalStateException("Run Restore Screen first to finish an interrupted session.");
            if(!originalRotation.matches("free|lock [0-3]")) throw new IllegalStateException("Unrecognized rotation state.");
            Looper.prepareMainLooper();
            organizerType=Class.forName("android.window.DisplayAreaOrganizer");
            wctType=Class.forName("android.window.WindowContainerTransaction");
            surfaceType=Class.forName("android.view.SurfaceControl");
            surfaceTxType=Class.forName("android.view.SurfaceControl$Transaction");
            rectType=Class.forName("android.graphics.Rect"); tokenType=Class.forName("android.window.WindowContainerToken");
            organizer=organizerType.getConstructor(Executor.class).newInstance(new Executor(){public void execute(Runnable r){r.run();}});
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable(){public void run(){restore();}}));
            if(!recovering) {
                try(FileWriter writer=new FileWriter(stateFile)){writer.write(originalRotation+"\nadaptive-v1\n");}
                ownsState=true;
            }
            List<?> areas=(List<?>)call(organizer,"registerOrganizer",new Class<?>[]{int.class},3);
            registered=true;
            for(Object area:areas) {
                Object info=call(area,"getDisplayAreaInfo",new Class<?>[]{});
                if(info.getClass().getField("displayId").getInt(info)!=0) continue;
                Object token=info.getClass().getField("token").get(info);
                Object leash=call(area,"getLeash",new Class<?>[]{});
                tokens.add(token);surfaces.add(leash);
            }
            if(tokens.size()!=8) throw new IllegalStateException("Unexpected number of display areas.");
            List<?> wallpaperAreas=(List<?>)call(organizer,"registerOrganizer",new Class<?>[]{int.class},10002);
            for(Object area:wallpaperAreas){
                Object info=call(area,"getDisplayAreaInfo",new Class<?>[]{});
                if(info.getClass().getField("displayId").getInt(info)!=0)continue;
                Object token=info.getClass().getField("token").get(info);
                Object leash=call(area,"getLeash",new Class<?>[]{});
                tokens.add(token);surfaces.add(leash);
                if(wallpaperToken!=null||!leash.toString().contains("RemoteWallpaperAnim:1:1"))throw new IllegalStateException("Unrecognized Samsung wallpaper area.");
                wallpaperToken=token;wallpaperSurface=leash;
            }
            if(recovering){restore();System.exit(0);return;}
            if(wallpaperToken==null)throw new IllegalStateException("Unrecognized Samsung wallpaper area.");
            displayManager=Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
            DisplayBridge.watch(new DisplayBridge.Rotation(){public void changed(int rotation){
                if(!restored)try{layout();}catch(Exception error){error.printStackTrace(System.out);stop=true;}
            }});
            layout();
            // Deep sleep must not look like a broken connection when the phone wakes.
            heartbeat=SystemClock.uptimeMillis();
            Thread control=new Thread(new Runnable(){ public void run(){
                try(BufferedReader r=new BufferedReader(new InputStreamReader(System.in))) {
                    String line;
                    while((line=r.readLine())!=null) {
                        if(line.equals("STOP")) break;
                        if(line.equals("H")) heartbeat=SystemClock.uptimeMillis();
                    }
                } catch(IOException ignored) {} finally {stop=true;}
            }},"ScreenSafe control");
            control.setDaemon(true); control.start();
            final Handler handler=new Handler(Looper.getMainLooper());
            handler.postDelayed(new Runnable(){ public void run(){
                if(stop || SystemClock.uptimeMillis()-heartbeat>8000 || (layoutPending&&SystemClock.uptimeMillis()-layoutStarted>6000)) {restore(); System.exit(0);}
                else {
                    try{layout();maintainPosition();}catch(Exception error){System.out.println("ERROR: Adaptive layout failed "+error);restore();System.exit(1);}
                    handler.postDelayed(this,250);
                }
            }},250);
            System.out.println("APPLIED"); System.out.flush();
            Looper.loop();
        } catch(Throwable e) {exitCode=1; System.out.println("ERROR: "+e.getMessage()); e.printStackTrace(System.out);}
        finally {restore();}
        System.exit(exitCode);
    }
}
