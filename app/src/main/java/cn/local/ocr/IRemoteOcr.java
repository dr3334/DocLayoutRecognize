package cn.local.ocr;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/** 仅供同一应用的主进程和 :ocr 使用的两个同步 Binder 调用。 */
interface IRemoteOcr extends IInterface {
    int workerPid() throws RemoteException;
    String invoke(String method,String arguments) throws RemoteException;
    abstract class Stub extends Binder implements IRemoteOcr {
        private static final String DESCRIPTOR="cn.local.ocr.IRemoteOcr";
        private static final int PID=IBinder.FIRST_CALL_TRANSACTION,INVOKE=PID+1;
        Stub(){attachInterface(this,DESCRIPTOR);}
        @Override public IBinder asBinder(){return this;}
        static IRemoteOcr asInterface(IBinder binder){
            IInterface local=binder.queryLocalInterface(DESCRIPTOR);
            return local instanceof IRemoteOcr?(IRemoteOcr)local:new Proxy(binder);
        }
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
            if(code==INTERFACE_TRANSACTION){reply.writeString(DESCRIPTOR);return true;}
            if(code==PID){data.enforceInterface(DESCRIPTOR);int pid=workerPid();reply.writeNoException();reply.writeInt(pid);return true;}
            if(code==INVOKE){data.enforceInterface(DESCRIPTOR);String result=invoke(data.readString(),data.readString());reply.writeNoException();reply.writeString(result);return true;}
            return super.onTransact(code,data,reply,flags);
        }
        private static final class Proxy implements IRemoteOcr {
            private final IBinder remote;
            Proxy(IBinder remote){this.remote=remote;}
            @Override public IBinder asBinder(){return remote;}
            @Override public int workerPid()throws RemoteException {
                Parcel data=Parcel.obtain(),reply=Parcel.obtain();
                try{data.writeInterfaceToken(DESCRIPTOR);if(!remote.transact(PID,data,reply,0))throw new RemoteException("识别进程协议不兼容");reply.readException();return reply.readInt();}
                finally{reply.recycle();data.recycle();}
            }
            @Override public String invoke(String method,String arguments)throws RemoteException {
                Parcel data=Parcel.obtain(),reply=Parcel.obtain();
                try{data.writeInterfaceToken(DESCRIPTOR);data.writeString(method);data.writeString(arguments);if(!remote.transact(INVOKE,data,reply,0))throw new RemoteException("识别进程协议不兼容");reply.readException();return reply.readString();}
                finally{reply.recycle();data.recycle();}
            }
        }
    }
}
