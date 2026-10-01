import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Executor;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Shell-side controller for the experimentally verified SM-S918W display areas. */
public final class ScreenSafeBackend {
    static final int WIDTH=1440, HEIGHT=3088, PROTECTED_TOP=927;
    static Class<?> organizerType, wctType, surfaceType, surfaceTxType, rectType, tokenType;
    static Object organizer;
    static final List<Object> surfaces = new ArrayList<>(), tokens = new ArrayList<>();
    static boolean registered, restored, rotationChanged;
    static String originalRotation;
    static volatile boolean stop;
    static volatile long heartbeat;
    static final File stateFile=new File("/data/local/tmp/screensafe-rotation-state");
    static boolean ownsState;

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
        try {
            if(!tokens.isEmpty()) {
                Object tx=wctType.getConstructor().newInstance();
                for(Object token:tokens) call(tx,"setBounds",new Class<?>[]{tokenType,rectType},token,rectType.getConstructor().newInstance());
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
            String containers=command("dumpsys","activity","containers");
            int count=0;
            for(String line:containers.split("\n")) if(line.contains("OneHanded:")) {
                count++;
                boolean empty=line.contains("requested-bounds=[0,0][0,0]");
                // Permit recovery of the 10%, 20%, and current 30% versions.
                boolean ours=line.contains("requested-bounds=[0,"+PROTECTED_TOP+"][1440,3088]")
                        || line.contains("requested-bounds=[0,618][1440,3088]")
                        || line.contains("requested-bounds=[0,309][1440,3088]");
                if(!empty && !(recovering && stateFile.exists() && ours)) throw new IllegalStateException("Unexpected existing display-area bounds.");
            }
            if(count!=8) throw new IllegalStateException("Unrecognized Samsung display layout; expected 8 areas.");
            originalRotation=command("wm","user-rotation");
            if(recovering && stateFile.exists()) {
                try(BufferedReader r=new BufferedReader(new FileReader(stateFile))){originalRotation=r.readLine();}
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
                try(FileWriter writer=new FileWriter(stateFile)){writer.write(originalRotation);}
                ownsState=true;
                rotationChanged=true; command("wm","user-rotation","lock","0");
            } else rotationChanged=ownsState;
            List<?> areas=(List<?>)call(organizer,"registerOrganizer",new Class<?>[]{int.class},3);
            registered=true;
            for(Object area:areas) {
                Object info=call(area,"getDisplayAreaInfo",new Class<?>[]{});
                if(info.getClass().getField("displayId").getInt(info)!=0) continue;
                tokens.add(info.getClass().getField("token").get(info));
                surfaces.add(call(area,"getLeash",new Class<?>[]{}));
            }
            if(tokens.size()!=8) throw new IllegalStateException("Unexpected number of display areas.");
            if(recovering){restore();System.exit(0);return;}
            Object tx=wctType.getConstructor().newInstance();
            Object bounds=rectType.getConstructor(int.class,int.class,int.class,int.class).newInstance(0,PROTECTED_TOP,WIDTH,HEIGHT);
            for(Object token:tokens) call(tx,"setBounds",new Class<?>[]{tokenType,rectType},token,bounds);
            call(organizer,"applyTransaction",new Class<?>[]{wctType},tx);
            Object st=surfaceTxType.getConstructor().newInstance();
            for(Object s:surfaces) {
                call(st,"setPosition",new Class<?>[]{surfaceType,float.class,float.class},s,0f,(float)PROTECTED_TOP);
                call(st,"setWindowCrop",new Class<?>[]{surfaceType,int.class,int.class},s,WIDTH,HEIGHT-PROTECTED_TOP);
            }
            call(st,"apply",new Class<?>[]{}); call(st,"close",new Class<?>[]{});
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
                if(stop || SystemClock.uptimeMillis()-heartbeat>8000) {restore(); System.exit(0);}
                else handler.postDelayed(this,250);
            }},250);
            System.out.println("APPLIED"); System.out.flush();
            Looper.loop();
        } catch(Throwable e) {exitCode=1; System.out.println("ERROR: "+e.getMessage()); e.printStackTrace(System.out);}
        finally {restore();}
        System.exit(exitCode);
    }
}
