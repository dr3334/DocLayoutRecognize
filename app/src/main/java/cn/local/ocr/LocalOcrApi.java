package cn.local.ocr;

import android.content.Context;
import android.content.Intent;
import org.json.*;
import java.io.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 宿主桥：cn.local.ocr 包对外的唯一 public 入口。
 *
 * 引擎簇（RecognitionController/DocumentStore/RecognitionModels/RecognitionWorker/IRemoteOcr/
 * ImageInput/ModelTransfer/ImageResolution/DocumentCrop）保持包私有，UI 层（Kotlin）只经由本类
 * 访问端侧识别引擎、模型管理与文档记录，依赖方向 ui → data(LocalOcrEngine.kt 门面) → cn.local.ocr。
 *
 * 线程契约（与源项目一致）：除 addStateListener/removeStateListener 外，所有方法都可能做磁盘 IO
 * 甚至位图解码，必须在宿主工作线程调用，不得在主线程调用；状态回调 changed() 来自引擎的推理线程。
 *
 * 移植说明：本类是本次移植唯一新增的 Java 逻辑文件，方法体基本是对包私有引擎的一行转发；
 * 与源 MainActivity（源宿主）的区别只有两点——
 *   1) 源 MainActivity 通过 WebView JS 桥分发这些调用，这里改为普通 public 方法；
 *   2) startDownload/pauseDownload 收拢了源 MainActivity:86 的「reserveDownload → 启动前台服务 →
 *      失败复位 DownloadService.reserved」协议（reserved 是包私有字段，Kotlin 宿主无法直接复位）。
 */
public final class LocalOcrApi {
    /** RecognitionController.Listener 的对外形态；state 为识别状态的 JSON 快照。 */
    public interface StateListener { void changed(JSONObject state); }

    private final Context context;
    private final RecognitionController controller;
    private final List<StateListener> listeners=new CopyOnWriteArrayList<>();
    private final RecognitionController.Listener forwarder=this::forward;

    private static volatile LocalOcrApi singleton;

    /** 进程级单例；引擎调度器（RecognitionController）本身也是单例。 */
    public static synchronized LocalOcrApi get(Context c) {
        if(singleton==null) singleton=new LocalOcrApi(c.getApplicationContext());
        return singleton;
    }

    private LocalOcrApi(Context c) {
        context=c;
        controller=RecognitionController.get(c);
        controller.listen(forwarder);
    }

    private void forward(JSONObject state) {
        JSONObject copy;
        try { copy=new JSONObject(state.toString()); } catch(Exception e) { copy=state; }
        for(StateListener l:listeners) try { l.changed(copy); } catch(Exception ignored) {}
    }
    public void addStateListener(StateListener l) { listeners.add(l); }
    public void removeStateListener(StateListener l) { listeners.remove(l); }

    /**
     * 新建一条待识别记录并尝试自动开始识别（对齐源「导入即识别」流程）。
     * 记录形态仿源 DocumentImporter.imported() 的图片分支：mode=pending-ocr、rawInput=原图文件名、
     * input=source.png，blocks/pages/assets 为空；随后按所选分辨率策略生成规范化 source.png，
     * 最后 autoStart（模型未就绪/下载中时只落盘不启动，稍后可显式 start(id,false)）。
     * 工作线程调用。
     */
    public JSONObject createInput(String title,File image)throws Exception {
        if(image==null||!image.isFile())throw new IOException("输入图片不存在");
        DocumentStore store=controller.store();
        String id=store.create();File dir=store.dir(id);
        String safe=image.getName().replaceAll("[^\\p{L}\\p{N}._-]","_");
        File raw=new File(dir,"input-"+safe);
        try(InputStream in=new FileInputStream(image);OutputStream out=new FileOutputStream(raw)) {
            FilesUtil.copy(in,out,FilesUtil.IMPORT_LIMIT);
        } catch(Exception e) { FilesUtil.remove(dir);throw e; }
        JSONObject doc=FilesUtil.obj("id",id,"title",title,"mode","pending-ocr","status","ready","progress",0,
            "blocks",new JSONArray(),"assets",new JSONObject(),"pages",new JSONArray(),
            "rawInput",raw.getName(),"input","source.png",
            "recognition",FilesUtil.obj("state","waiting","message","输入已保存，可开始识别"));
        store.save(doc);
        try {
            doc=ImageInput.prepare(store,doc,ImageInput.policy(context));
        } catch(Exception e) {
            doc.put("recognition",FilesUtil.obj("state","failed","message",e.getMessage()+"；原始输入已保留"));
            store.save(doc);
        }
        return controller.autoStart(doc);
    }

    /** 开始识别 id 记录；again=true 时对已识别记录另存新记录（重新识别）。 */
    public JSONObject start(String id,boolean again)throws Exception { return controller.start(id,again); }
    /** 查询识别状态快照（含流式输出 stream，供增量渲染）。 */
    public JSONObject status(String id)throws Exception { return controller.status(id); }
    /** 取消作业；id/jobId 必须与启动时一致。 */
    public JSONObject cancel(String id,String jobId)throws Exception { return controller.cancel(id,jobId); }
    /** 预加载识别引擎（不含作业）。 */
    public JSONObject activate()throws Exception { return controller.activate(); }
    /** 引擎就绪状态（含模型/线程选择）。 */
    public JSONObject readiness()throws Exception { return controller.readiness(); }
    /** 模型下载总览：tasks/repos/running/activity/freeBytes。 */
    public JSONObject modelSummary()throws Exception { return ModelHub.summary(context); }
    /** 切换识别模型（model-profiles.json 的 key）；运行中禁止。 */
    public JSONObject setModel(String choice)throws Exception { return controller.setModel(choice); }
    /** 可选识别模型清单（model-profiles.json：[{id,name,repo}]），供模型页「识别模型」切换用。 */
    public JSONArray modelChoices()throws Exception { return controller.modelChoices(); }
    /** 调整推理线程（0=自动，或 1/2/4）；运行中禁止。 */
    public JSONObject setThreads(int threads)throws Exception { return controller.configureThreads(threads); }
    /** 声明即将下载：占用期间识别拒绝启动。配套 startDownload 使用。 */
    public void reserveDownload(String repo)throws Exception { controller.reserveDownload(repo); }
    /** 删除某仓库的全部已下载文件并从任务表移除。 */
    public JSONObject removeModel(String repo)throws Exception { return controller.removeModel(repo); }
    /** 删除一条文档记录（运行中/待启动的记录拒绝删除）。 */
    public void delete(String id)throws Exception { controller.delete(id); }
    /** 全部文档记录列表（按 updatedAt 降序）。 */
    public JSONArray list()throws Exception { return controller.store().list(); }

    /**
     * 拉取仓库文件清单（ModelScope，**写本地 catalog.json 缓存**）。工作线程调用。
     * 必须用带 Context 的重载（ModelHub.catalog(Context,repo)，内部 FilesUtil.write 缓存）：
     * 无参重载 catalog(repo) 只返回清单不落盘，全新安装上 startDownload 里
     * cachedCatalog 读不到缓存会抛「请先查看仓库文件清单」，下载永远走不通。
     */
    public JSONArray refreshCatalog(String repo)throws Exception { return ModelHub.catalog(context,repo); }

    /**
     * 磁盘对账版就绪检查：把 state.json + 已下载文件的元数据重新核对一遍并**回写内存 readiness**，
     * 不加载引擎（对照 prepare() 的 250-253 行，只是去掉后续加载）。
     * 为什么必须有它：readiness 是纯内存状态、进程冷启动恒为 unchecked，而下载完成前没有任何
     * UI 可达路径能把它推进到 files-ready —— 门槛读内存 → 永远拒绝 → prepare 永远执行不到，死锁。
     * 忙碌（识别/加载进行中）时直接返回当前内存值，不与运行中的状态写竞争。
     */
    public JSONObject refreshReadiness()throws Exception { return controller.refreshReadiness(); }

    /**
     * 启动模型下载前台服务（源 MainActivity:86 的协议）：
     * 校验清单仍匹配 → reserveDownload 占用 → startForegroundService；
     * 任何一步失败都复位 reserved，否则识别会被「请先完成或暂停模型下载」永久挡住。
     * Android 13+ 的 POST_NOTIFICATIONS 运行时权限由宿主 UI 在调用前请求（通知被拒不影响下载本身）。
     * 工作线程调用。
     */
    public JSONObject startDownload(String repo,JSONArray paths)throws Exception {
        if(DownloadService.active)throw new IOException("已有下载任务，请先暂停");
        String payload=paths.toString();
        if(payload.length()>200000)throw new IOException("所选文件过多，请分批下载");
        ModelHub.select(ModelHub.cachedCatalog(context,repo),paths);
        try {
            controller.reserveDownload(repo);
            context.startForegroundService(new Intent(context,DownloadService.class)
                .putExtra("repo",repo).putExtra("paths",payload));
            return FilesUtil.obj("started",true,"repo",repo);
        } catch(Exception e) {
            DownloadService.reserved=false;throw e;
        }
    }

    /** 请求暂停当前下载（服务未运行时该调用无副作用）。须在前台调用。 */
    public void pauseDownload() {
        context.startService(new Intent(context,DownloadService.class).setAction("pause"));
    }

    /** 宿主 Activity.onTrimMemory 达到 TRIM_MEMORY 级别时转发，压力下引擎会卸载已缓存模型。 */
    public void onMemoryPressure() { controller.onMemoryPressure(); }
}
