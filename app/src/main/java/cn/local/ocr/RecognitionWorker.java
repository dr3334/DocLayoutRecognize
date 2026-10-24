package cn.local.ocr;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Process;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 一个进程代次。停止后绝不重新绑定；下一作业只能取得新的代次。 */
final class RecognitionWorker implements RecognitionController.NativeResources {
    private final Context context;
    private final CompletableFuture<IRemoteOcr> connected = new CompletableFuture<>();
    private final CompletableFuture<Void> exited = new CompletableFuture<>();
    private boolean bound, requested;
    private volatile boolean stopped;
    private IRemoteOcr remote;
    private int pid;
    RecognitionWorker(Context context) { this.context = context; }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            try {
                IRemoteOcr value = IRemoteOcr.Stub.asInterface(binder);
                int workerPid = value.workerPid();
                if (workerPid <= 0 || workerPid == Process.myPid()) throw new IOException("识别进程未隔离");
                binder.linkToDeath(RecognitionWorker.this::died, 0);
                synchronized (RecognitionWorker.this) {
                    remote = value; pid = workerPid;
                    if (stopped) terminateBound();
                    else connected.complete(value);
                }
            } catch (Exception e) { failedConnection(e); }
        }
        @Override public void onServiceDisconnected(ComponentName name) { died(); }
        @Override public void onNullBinding(ComponentName name) { failedConnection(new IOException("识别进程连接失败")); }
        @Override public void onBindingDied(ComponentName name) { died(); }
    };
    private synchronized void unbind() {
        if (bound) { bound = false; try { context.unbindService(connection); } catch (IllegalArgumentException ignored) {} }
    }
    private synchronized void died() {
        stopped = true; remote = null; unbind();
        connected.completeExceptionally(new IOException("识别进程已停止"));
        exited.complete(null);
    }
    private synchronized void failedConnection(Exception e) {
        stopped = true; unbind(); connected.completeExceptionally(e); exited.complete(null);
    }
    private IRemoteOcr connection() throws IOException {
        synchronized (this) {
            if (stopped) throw new IOException("识别进程已停止");
            if (!requested) {
                requested = true;
                try {
                    bound = context.bindService(new Intent(context, RecognitionService.class), connection, Context.BIND_AUTO_CREATE);
                    if (!bound) failedConnection(new IOException("无法启动识别进程"));
                } catch (Exception e) { failedConnection(e); }
            }
        }
        try {
            IRemoteOcr value = connected.get(15, TimeUnit.SECONDS);
            if (stopped) throw new IOException("识别进程已停止");
            return value;
        } catch (Exception e) { throw new IOException("识别进程连接失败", e); }
    }
    private JSONObject invoke(String method, JSONObject args) throws IOException {
        try {
            JSONObject result = new JSONObject(connection().invoke(method, args.toString()));
            if (stopped) throw new IOException("识别进程已停止");
            if (result.has("error")) throw new IOException(result.getString("error"));
            return result;
        } catch (android.os.DeadObjectException e) { died(); throw new IOException("识别进程已退出", e); }
        catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException(e.getMessage(), e); }
    }
    private synchronized void terminateBound() {
        unbind();
        if (pid > 0) Process.killProcess(pid);
        // 等待 Binder death 后才能建立下一代，避免旧连接迟到时杀掉新进程。
    }
    @Override public synchronized boolean stopProcess() {
        if (stopped) return true;
        stopped = true;
        connected.completeExceptionally(new IOException("识别已取消"));
        if (remote != null) terminateBound();
        else if (!bound) exited.complete(null);
        // 绑定仍在途中时，onServiceConnected 会立即终止该代进程。
        return true;
    }
    @Override public boolean invalidated() { return stopped; }
    @Override public RecognitionController.NativeResources next() throws IOException {
        if (!stopped) return this;
        try { exited.get(15, TimeUnit.SECONDS); }
        catch (Exception e) { throw new IOException("旧识别进程未能退出，请重试", e); }
        return new RecognitionWorker(context);
    }
    @Override public long create(String config, String cache) throws IOException { return invoke("create", FilesUtil.obj("config", config)).optLong("value"); }
    @Override public long jobCreate(long engine) throws IOException { return invoke("jobCreate", FilesUtil.obj("engine", engine)).optLong("value"); }
    @Override public int run(long job, String image) throws IOException { return invoke("run", FilesUtil.obj("job", job, "image", image)).optInt("value"); }
    @Override public int runRegion(long job, String image, String type) throws IOException { return invoke("runRegion", FilesUtil.obj("job", job, "image", image, "type", type)).optInt("value"); }
    @Override public String status(long job) throws IOException {
        JSONObject result=invoke("status",FilesUtil.obj("job",job));
        if(!result.has("statusFile"))return result.optString("value");
        try{return FilesUtil.read(FilesUtil.child(context.getCacheDir(),result.getString("statusFile")));}
        catch(Exception e){throw new IOException("读取识别进度失败",e);}
    }
    @Override public void cancel(long job) throws IOException { invoke("cancel", FilesUtil.obj("job", job)); }
    @Override public void export(long job, String directory) throws IOException { invoke("export", FilesUtil.obj("job", job, "directory", directory)); }
    @Override public void jobDestroy(long job) throws IOException { if (!stopped) invoke("jobDestroy", FilesUtil.obj("job", job)); }
    @Override public void destroy(long engine) throws IOException { if (!stopped) invoke("destroy", FilesUtil.obj("engine", engine)); }
}
