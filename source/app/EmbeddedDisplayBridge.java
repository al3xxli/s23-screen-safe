package ca.screensafe.core;
import android.os.*;
import java.lang.reflect.*;

/** Narrow adapters for Samsung's hidden window APIs, resolved by name on the phone. */
final class EmbeddedDisplayBridge {
    interface Rotation {void changed(int rotation);}
    static Object proxy(final Class<?> type,final Binder binder){
        return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},new InvocationHandler(){
            public Object invoke(Object p,Method m,Object[] args){
                if(m.getName().equals("asBinder"))return binder;
                if(m.getName().equals("toString"))return "ScreenSafe "+type.getName();
                if(m.getName().equals("hashCode"))return System.identityHashCode(p);
                if(m.getName().equals("equals"))return p==args[0];
                throw new UnsupportedOperationException(m.getName());
            }
        });
    }
    static int transactionCode(String type,String method)throws Exception{
        Field f=Class.forName(type+"$Stub").getDeclaredField("TRANSACTION_"+method);f.setAccessible(true);return f.getInt(null);
    }
    Object wm,watcher;
    final Handler handler;
    EmbeddedDisplayBridge(Handler handler){this.handler=handler;}
    void connect()throws Exception{
        if(wm!=null)return;
        Object service=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"window");
        wm=Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,service);
    }
    void watch(final Rotation listener)throws Exception{
        final String name="android.view.IRotationWatcher";
        final int code=transactionCode(name,"onRotationChanged");
        Binder binder=new Binder(){protected boolean onTransact(int id,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(id==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(name);return true;}
            if(id!=code)return super.onTransact(id,data,reply,flags);
            data.enforceInterface(name);final int rotation=data.readInt();
            handler.post(new Runnable(){public void run(){listener.changed(rotation);}});return true;
        }};
        Object service=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"window");
        wm=Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,service);
        Class<?> type=Class.forName(name);watcher=proxy(type,binder);
        EmbeddedController.call(wm,"watchRotation",new Class<?>[]{type,int.class},watcher,0);
    }
    void unwatch()throws Exception{
        if(watcher!=null){EmbeddedController.call(wm,"removeRotationWatcher",new Class<?>[]{Class.forName("android.view.IRotationWatcher")},watcher);watcher=null;}
    }
}
