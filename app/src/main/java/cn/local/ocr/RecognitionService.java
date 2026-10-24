package cn.local.ocr;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import org.json.JSONObject;

/** 只拥有 native 引擎和作业，不提交文档状态。整个进程可被取消/替换。 */
public final class RecognitionService extends Service {
    private final IRemoteOcr.Stub remote = new IRemoteOcr.Stub() {
        @Override public int workerPid() { return Process.myPid(); }
        @Override public String invoke(String method, String arguments) {
            try {
                JSONObject a = new JSONObject(arguments);
                Object value;
                switch (method) {
                    case "create": value = NativeOcr.create(a.getString("config"), getCacheDir().getPath()); break;
                    case "jobCreate": value = NativeOcr.jobCreate(a.getLong("engine")); break;
                    case "run": value = NativeOcr.run(a.getLong("job"), a.getString("image")); break;
                    case "runRegion": value = NativeOcr.runRegion(a.getLong("job"), a.getString("image"), a.getString("type")); break;
                    case "status":
                        String status=NativeOcr.status(a.getLong("job"));
                        // 多区域长文本会超过 Binder 的事务上限；大快照走同 UID 的私有缓存。
                        if(status.length()>64000){
                            java.io.File snapshot=new java.io.File(getCacheDir(),"ocr-status-"+Process.myPid()+".json");
                            FilesUtil.write(snapshot,status);
                            return FilesUtil.obj("statusFile",snapshot.getName()).toString();
                        }
                        value=status;break;
                    case "export": NativeOcr.export(a.getLong("job"), a.getString("directory")); value = true; break;
                    case "cancel": NativeOcr.cancel(a.getLong("job")); value = true; break;
                    case "jobDestroy": NativeOcr.jobDestroy(a.getLong("job")); value = true; break;
                    case "destroy": NativeOcr.destroy(a.getLong("engine")); value = true; break;
                    default: throw new IllegalArgumentException("未知识别进程操作");
                }
                return FilesUtil.obj("value", value).toString();
            } catch (Exception | LinkageError e) {
                return FilesUtil.obj("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).toString();
            }
        }
    };
    @Override public IBinder onBind(Intent intent) { return remote; }
}
