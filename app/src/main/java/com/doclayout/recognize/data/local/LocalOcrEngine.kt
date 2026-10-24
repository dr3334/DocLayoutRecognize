package com.doclayout.recognize.data.local

import android.content.Context
import android.os.Handler
import android.os.Looper
import cn.local.ocr.LocalOcrApi
import com.doclayout.recognize.data.model.OcrStreamPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地端侧识别引擎的 Kotlin 门面（本文件讲解目录）：
 *
 * 1. 属于什么文件
 *    - `app/src/main/java/com/doclayout/recognize/data/local/LocalOcrEngine.kt`，
 *      data 层文件，`MainActivity → ui → data` 链路的最底端。
 * 2. 使用了什么组件
 *    - [LocalOcrApi]（`cn/local/ocr/LocalOcrApi.java`）：引擎 Java 簇唯一的 public 宿主桥，
 *      内部包着 `RecognitionController`（识别调度）与 `DocumentStore`（记录落盘）；
 *    - `kotlinx.coroutines`：所有引擎调用都是磁盘 IO / 位图解码 / JNI 阻塞，统一丢进
 *      `Dispatchers.IO`，UI 层在协程里 `suspend` 调用即可，不会卡主线程；
 *    - `org.json`：只在本文件内部把 Java 侧的 JSON 快照解析成下面的 Kotlin 数据类，
 *      UI 层看不到任何 `org.json` / `cn.local.ocr` 类型；
 *    - `Handler(Looper.getMainLooper())`：引擎回调来自推理线程，切回主线程再分发给 UI。
 * 3. 数据类一览（全部是纯 Kotlin，无 Compose 类型）
 *    - [LocalReadiness]：引擎就绪状态（readiness/activate/setModel/setThreads 的返回）；
 *    - [LocalJobStatus]：一次识别作业的状态快照（start/status/cancel 的返回）；
 *    - [LocalStream] / [LocalStreamRegion]：作业里的流式输出（区域逐字生成）；
 *    - [LocalDocSummary]：一条识别记录的概要（list/createInput 的返回）；
 *    - [LocalModelSummary] / [LocalModelTask] / [LocalDownloadActivity]：模型下载总览；
 *    - [LocalCatalogFile]：ModelScope 仓库文件清单的一行。
 * 4. 状态回调
 *    - [addStateListener]：注册后在主线程收到每次作业状态变化（引擎推理线程推送），
 *      返回一个 () -> Unit 的注销函数，Compose 里用 DisposableEffect 配对注销。
 */
class LocalOcrEngine private constructor(context: Context) {

    private val api: LocalOcrApi = LocalOcrApi.get(context.applicationContext)
    // 读识别产物目录用：DocumentStore 把记录落在 filesDir/documents/<id>/（布局见 [readExport]）
    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(LocalJobStatus) -> Unit>()

    /** 引擎回调（推理线程）→ 解析 → 主线程分发。 */
    private val forwarder = object : LocalOcrApi.StateListener {
        override fun changed(state: JSONObject) {
            val parsed = try {
                state.toJobStatus()
            } catch (e: Exception) {
                return
            }
            mainHandler.post {
                listeners.toList().forEach { it(parsed) }
            }
        }
    }

    init {
        api.addStateListener(forwarder)
    }

    /** 注册作业状态监听（主线程回调）；返回注销函数。 */
    fun addStateListener(listener: (LocalJobStatus) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    // ---------------------------------------------------------------- 记录

    /**
     * 新建一条待识别记录并尝试自动开始识别（对齐源项目「导入即识别」）。
     * 记录形态：mode=pending-ocr、rawInput=原图、input=source.png；模型未就绪时只落盘不启动，
     * 稍后调 [start]。`image` 是已落地的原图文件（如相机输出或扫描缓存）。
     */
    suspend fun createInput(title: String, image: File): LocalDocSummary =
        withIo { api.createInput(title, image).toDocSummary() }

    /** 记录列表，新改动的在前。 */
    suspend fun list(): List<LocalDocSummary> = withIo {
        val array = api.list()
        (0 until array.length()).map { array.getJSONObject(it).toDocSummary() }
    }

    /** 删除一条记录；运行中/待启动的记录会被引擎拒绝（抛异常）。 */
    suspend fun delete(id: String): Unit = withIo { api.delete(id) }

    // ---------------------------------------------------------------- 识别

    /** 开始识别；again=true 时对已有结果另存新记录（重新识别）。 */
    suspend fun start(id: String, again: Boolean = false): LocalJobStatus =
        withIo { api.start(id, again).toJobStatus() }

    /** 查询作业状态快照（含流式输出）。识别页轮询此接口驱动界面。 */
    suspend fun status(id: String): LocalJobStatus = withIo { api.status(id).toJobStatus() }

    /** 取消作业；id/jobId 必须来自对应的 start/status 返回值。 */
    suspend fun cancel(id: String, jobId: String): LocalJobStatus =
        withIo { api.cancel(id, jobId).toJobStatus() }

    // ---------------------------------------------------------------- 单图全流程（WP5 导入流程复用）

    /**
     * 端侧**单图全流程**：门槛对账 → 建记录+autoStart → start 兜底 → 轮询到终态 → 读导出产物。
     *
     * 这是 WP5「导入文件识别」runScan 分流的复用入口 —— WP4 流水识别测试页用的是分步 API
     * （createInput/start/status 各自挂 UI 轮询），本方法把那条链收拢成一次 suspend 调用，
     * 调用方（OcrApp.runScan）只关心输入文件与进度回调，不再重复轮询/去重/取消拼装。
     *
     * 1. 门槛与流水页同源：先 [refreshReadiness] 磁盘对账（回写引擎内存 readiness），
     *    模型没就绪直接抛，不让调用方等到晦涩的 missing-models；
     * 2. [onStatus] 每次状态快照（含流式输出）都会在**主线程**回调一次，驱动 UI 的区域进度；
     *    不需要进度时传 null；
     * 3. 终态语义：succeeded / partial 视为成功并返回 [LocalScanResult]；
     *    blank / cancelled / failed / missing-models 抛 [IllegalStateException]，文案可直接展示；
     * 4. 调用方协程被取消（用户离开页面）时轮询随协程终止，引擎记录留在 documents/ 目录，
     *    与流水页行为一致，不做额外清理。
     *
     * @param image 已落地的单页图片文件（PNG/JPG 均可；PDF 先经 [PdfPageSource.rasterize] 变成页 PNG）
     */
    suspend fun recognizeFile(
        image: File,
        title: String,
        pollIntervalMs: Long = 400L,
        onStatus: ((LocalJobStatus) -> Unit)? = null,
    ): LocalScanResult {
        val ready = refreshReadiness()
        if (!ready.filesReady) {
            throw IllegalStateException(
                "端侧模型还没就绪（${ready.state}）：${ready.message.ifBlank { "去 设置 → 管理端侧模型 处理" }}",
            )
        }
        val startedAt = System.currentTimeMillis()
        val summary = createInput(title, image)
        // createInput 已 autoStart；再显式 start 一次兜住"autoStart 只落盘没启动"的分支，
        // start 对运行中的同记录作业是幂等返回（与 OcrPipelineScreen.runOne 同一约定）
        runCatching { start(summary.id, again = false) }

        var last: LocalJobStatus? = null
        var misses = 0
        while (true) {
            val s = runCatching { status(summary.id) }.getOrNull()
            if (s == null) {
                // 连续读不到快照才算异常：偶发解析失败不中断识别
                if (++misses >= 5) {
                    throw IllegalStateException("读不到识别状态（记录 ${summary.id}），引擎可能已异常退出")
                }
            } else {
                misses = 0
                last = s
                onStatus?.let { cb -> withContext(Dispatchers.Main) { cb(s) } }
                if (s.terminal) break
            }
            delay(pollIntervalMs)
        }

        val done = last ?: throw IllegalStateException("识别没有返回任何状态")
        if (done.state != "succeeded" && done.state != "partial") {
            throw IllegalStateException(
                when (done.state) {
                    "blank" -> "识别结果没有文字（可能是空白页或拍摄太糊）"
                    "cancelled" -> "识别已取消"
                    "missing-models" -> "端侧模型文件缺失：去 设置 → 管理端侧模型 处理"
                    else -> done.message.ifBlank { "识别失败（${done.state}）" }
                },
            )
        }
        val export = readExport(summary.id)
            ?: throw IllegalStateException("识别完成但读不到导出产物（记录 ${summary.id}）")
        if (export.markdown.isNullOrBlank()) {
            throw IllegalStateException("识别完成但没有文字产出（${done.state}）")
        }
        // 引擎裁剪图等资源：copyTree 后在记录根的 assets/（见 readExport 的目录布局说明）
        val assetsDir = File(File(File(appContext.filesDir, "documents"), summary.id), "assets")
            .takeIf { it.isDirectory }
        return LocalScanResult(
            docId = summary.id,
            state = done.state,
            modelChoice = done.modelChoice,
            elapsedMs = System.currentTimeMillis() - startedAt,
            markdown = export.markdown!!,
            assetsDir = assetsDir,
        )
    }

    // ---------------------------------------------------------------- 识别产物（WP4 流水识别测试页）

    /**
     * 读取一条记录识别完成后的导出产物（document.json / document.md / run-manifest.json）。
     *
     * 目录布局来自引擎 DocumentStore（`cn/local/ocr/DocumentStore.java:11`）：
     * 记录根是 `filesDir/documents/<id>/`；识别成功时 `RecognitionController.run`
     * 把引擎 export 的 `recognized/<jobId>/` 整棵 copyTree 进记录根，
     * 所以正常情况下三份产物就在根目录；找不到时回退 `recognized/` 下最近修改的作业目录
     * （jobId 是 UUID 无时间序，按 lastModified 挑）。
     *
     * 引擎未产出（还没识别 / 识别失败）时返回 null，调用方按"暂无产物"处理。
     * JSON 解析与模型映射在 [DocumentIrMapper]，本方法只负责定位与读文本。
     */
    suspend fun readExport(id: String): LocalIrExport? = withIo {
        // 与 DocumentStore.dir 同一条校验规则，杜绝拼路径时的越权（id 全部来自引擎返回，双保险）
        if (!id.matches(Regex("[a-zA-Z0-9_-]+"))) return@withIo null
        val docDir = File(File(appContext.filesDir, "documents"), id)
        if (!docDir.isDirectory) return@withIo null
        val jsonFile = exportFile(docDir, "document.json") ?: return@withIo null
        val documentJson = readTextOrNull(jsonFile) ?: return@withIo null
        val markdown = exportFile(docDir, "document.md")?.let { readTextOrNull(it) }
        val manifest = exportFile(docDir, "run-manifest.json")?.let { readTextOrNull(it) }
        // 规范化输入图：state.json 的 "input" 字段（如 source.png），区域框的 bbox 坐标系就是它
        val inputPath = readTextOrNull(File(docDir, "state.json"))
            ?.let { runCatching { JSONObject(it).optString("input") }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?.let { File(docDir, it) }
            ?.takeIf { it.isFile }
            ?.absolutePath
        DocumentIrMapper.build(documentJson, markdown, manifest, inputPath, docDir)
    }

    /** 产物文件定位：记录根优先，缺失时回退 recognized/<最新作业>/。 */
    private fun exportFile(docDir: File, name: String): File? {
        File(docDir, name).takeIf { it.isFile }?.let { return it }
        return File(docDir, "recognized").listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.lastModified() }
            ?.let { File(it, name) }
            ?.takeIf { it.isFile }
    }

    private fun readTextOrNull(file: File): String? = runCatching {
        file.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    // ---------------------------------------------------------------- 引擎

    /** 预加载引擎（不含作业），设置页/首页可用来提前热身。 */
    suspend fun activate(): LocalReadiness = withIo { api.activate().toReadiness() }

    /** 引擎就绪状态：state ∈ unchecked/checking/loading/files-ready/ready/load-failed/missing-models。 */
    suspend fun readiness(): LocalReadiness = withIo { api.readiness().toReadiness() }

    /**
     * 磁盘对账版就绪检查：把 state.json + 已下载文件重新核对一遍并回写引擎内存 readiness，
     * 不加载引擎。纯内存版 [readiness] 读到的值进程冷启动恒为 unchecked，除非有路径把
     * 磁盘对账结果写回去 —— 轮询处（设置页徽标 / 模型页）对端态一律用本方法，否则
     * 「模型已就绪」永远不可达（真机验证确认的死锁，见 RecognitionController.refreshReadiness）。
     */
    suspend fun refreshReadiness(): LocalReadiness = withIo { api.refreshReadiness().toReadiness() }

    /**
     * 同步版就绪快照（无磁盘 IO —— RecognitionController.readiness() 只序列化内存 JSON）。
     * 给 [OcrSettingsStore.configured] 这类**同步属性**用：suspend 的 [readiness] 需要协程，
     * 属性 getter 里调不了。引擎尚未初始化/解析失败时返回 unchecked 态，按"未就绪"理解。
     */
    fun readinessSnapshot(): LocalReadiness = try {
        api.readiness().toReadiness()
    } catch (e: Exception) {
        LocalReadiness(
            state = "unchecked",
            message = e.message ?: "引擎尚未初始化",
            inUse = false,
            cpuThreads = 0,
            threadChoice = 0,
            modelChoice = "",
            problems = emptyList(),
        )
    }

    /** 切换识别模型（model-profiles.json 的 key：ovis / glm），运行中会被拒绝。 */
    suspend fun setModel(choice: String): LocalReadiness = withIo { api.setModel(choice).toReadiness() }

    /** 可选识别模型清单（model-profiles.json 的 id + 展示名），供模型页「识别模型」切换弹窗用。 */
    suspend fun modelChoices(): List<LocalModelChoice> = withIo {
        val array = api.modelChoices()
        (0 until array.length()).map {
            val o = array.getJSONObject(it)
            LocalModelChoice(
                id = o.getString("id"),
                name = o.optString("name").ifBlank { o.getString("id") },
                repo = o.optString("repo"),
            )
        }
    }

    /** 调整推理线程：0=自动，或 1/2/4；运行中会被拒绝。 */
    suspend fun setThreads(threads: Int): LocalReadiness = withIo { api.setThreads(threads).toReadiness() }

    /** 宿主 onTrimMemory 触发时转发；内存压力下引擎自动卸载已缓存的模型权重。 */
    fun onMemoryPressure() = api.onMemoryPressure()

    // ---------------------------------------------------------------- 模型下载

    /** 模型下载总览：任务表 + 仓库列表 + 进行中的下载活动 + 剩余空间。 */
    suspend fun modelSummary(): LocalModelSummary = withIo { api.modelSummary().toModelSummary() }

    /** 拉取仓库文件清单（ModelScope 网络 + 写缓存），下载页浏览/勾选用。 */
    suspend fun refreshCatalog(repo: String): List<LocalCatalogFile> = withIo {
        val array = api.refreshCatalog(repo)
        (0 until array.length()).map { array.getJSONObject(it).toCatalogFile() }
    }

    /** 启动下载前台服务（内部完成 reserve → startForegroundService → 失败复位）。 */
    suspend fun startDownload(repo: String, paths: List<String>): Boolean = withIo {
        val array = JSONArray()
        paths.forEach { array.put(it) }
        api.startDownload(repo, array).optBoolean("started")
    }

    /** 请求暂停当前下载（进度保留，可继续）。 */
    suspend fun pauseDownload(): Unit = withIo { api.pauseDownload() }

    /** 声明即将下载：占用期间识别拒绝启动。一般直接用 [startDownload]，不必单独调。 */
    suspend fun reserveDownload(repo: String): Unit = withIo { api.reserveDownload(repo) }

    /** 删除某仓库全部已下载文件并从任务表移除。 */
    suspend fun removeModel(repo: String): LocalModelSummary = withIo { api.removeModel(repo).toModelSummary() }

    // ---------------------------------------------------------------- 解析

    private fun JSONObject.toReadiness() = LocalReadiness(
        state = optString("state", "unchecked"),
        message = optString("message"),
        inUse = optBoolean("inUse"),
        cpuThreads = optInt("cpuThreads"),
        threadChoice = optInt("threadChoice"),
        modelChoice = optString("modelChoice"),
        problems = optJSONArray("problems").mapObjects { it.optString("path") to it.optString("reason") },
    )

    private fun JSONObject.toJobStatus() = LocalJobStatus(
        state = optString("state", "waiting"),
        message = optString("message"),
        docId = optString("docId"),
        jobId = optString("jobId"),
        modelChoice = optString("modelChoice"),
        stage = optString("stage"),
        regionCompleted = optInt("regionCompleted"),
        regionTotal = optInt("regionTotal"),
        liveOutput = optBoolean("liveOutput"),
        sequence = optLong("sequence"),
        resultSaved = optBoolean("resultSaved"),
        cancellationReason = optString("cancellationReason"),
        stream = optJSONObject("stream")?.toStream(),
    )

    private fun JSONObject.toStream() = LocalStream(
        revision = optLong("revision"),
        regions = optJSONArray("regions").mapObjects { it.toStreamRegion() },
    )

    private fun JSONObject.toStreamRegion() = LocalStreamRegion(
        requestId = optString("requestId"),
        type = optString("type"),
        raw = optString("raw"),
        done = optBoolean("done"),
        attempt = optInt("attempt"),
        finishReason = optString("finishReason"),
        stopReason = optString("stopReason"),
    )

    private fun JSONObject.toDocSummary() = LocalDocSummary(
        id = getString("id"),
        title = optString("title"),
        mode = optString("mode"),
        status = optString("status"),
        progress = optInt("progress"),
        blockCount = optJSONArray("blocks")?.length() ?: optInt("count"),
        updatedAt = optLong("updatedAt"),
    )

    private fun JSONObject.toModelSummary() = LocalModelSummary(
        tasks = optJSONArray("tasks").mapObjects { it.toModelTask() },
        repos = optJSONArray("repos").mapArrays { optString(it) },
        running = optBoolean("running"),
        freeBytes = optLong("freeBytes"),
        activity = optJSONObject("activity")?.let {
            LocalDownloadActivity(
                repo = it.optString("repo"),
                stage = it.optString("stage"),
                path = it.optString("path"),
                message = it.optString("message"),
                bytes = it.optLong("bytes"),
                total = it.optLong("total"),
            )
        },
    )

    private fun JSONObject.toModelTask() = LocalModelTask(
        repo = optString("repo"),
        path = optString("path"),
        size = optLong("size"),
        sha256 = optString("sha256"),
        status = optString("status"),
        downloaded = optLong("downloaded"),
        bytesPerSecond = optLong("bytesPerSecond"),
        error = optString("error"),
    )

    private fun JSONObject.toCatalogFile() = LocalCatalogFile(
        repo = optString("repo"),
        path = optString("path"),
        size = optLong("size"),
        sha256 = optString("sha256"),
        revision = optString("revision"),
    )

    // 可空接收者：optJSONArray 可能返回 null，扩展内部统一兜底成空列表。
    private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (i in 0 until length()) out.add(transform(getJSONObject(i)))
        return out
    }

    private fun JSONArray?.mapArrays(transform: JSONArray.(Int) -> String): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) out.add(transform(i))
        return out
    }

    private suspend fun <T> withIo(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        @Volatile
        private var instance: LocalOcrEngine? = null

        /** 进程级单例；底层引擎调度器同为单例，多处调用共享同一状态。 */
        fun get(context: Context): LocalOcrEngine =
            instance ?: synchronized(this) {
                instance ?: LocalOcrEngine(context.applicationContext).also { instance = it }
            }

        /**
         * 端侧记录的 engine 标签：profile key（model-profiles.json 的 key）→ 展示名，
         * 如 "端侧 PP-DocLayoutV3 + GLM-OCR"。写进 OcrStore 记录并展示在结果页。
         * profile 名与 assets/ocr/model-profiles.json 的 name 字段一一对应
         * （ovis→OvisOCR2、glm→GLM-OCR）；未知 key 只做首字母大写，不编造名字。
         */
        fun engineLabel(modelChoice: String): String {
            val name = when (modelChoice) {
                "ovis" -> "OvisOCR2"
                "glm" -> "GLM-OCR"
                "" -> "GLM-OCR" // 引擎尚未给出选择时按默认 profile 展示（与 RecognitionModels 一致）
                else -> modelChoice.replaceFirstChar { it.uppercase() }
            }
            return "端侧 PP-DocLayoutV3 + $name"
        }
    }
}

/** 引擎就绪状态。problems 非空时表示所选模型缺少文件，每项是 (文件路径, 原因)。 */
data class LocalReadiness(
    val state: String,
    val message: String,
    val inUse: Boolean,
    val cpuThreads: Int,
    val threadChoice: Int,
    val modelChoice: String,
    val problems: List<Pair<String, String>>,
) {
    val filesReady: Boolean get() = state == "files-ready"
    val engineReady: Boolean get() = state == "ready"
}

/** 一次识别作业的状态快照。终态：succeeded/partial/blank/cancelled/failed/missing-models。 */
data class LocalJobStatus(
    val state: String,
    val message: String,
    val docId: String,
    val jobId: String,
    val modelChoice: String,
    val stage: String,
    val regionCompleted: Int,
    val regionTotal: Int,
    val liveOutput: Boolean,
    val sequence: Long,
    val resultSaved: Boolean,
    val cancellationReason: String,
    val stream: LocalStream?,
) {
    val terminal: Boolean
        get() = state in setOf("succeeded", "partial", "blank", "cancelled", "failed", "missing-models")
    val running: Boolean
        get() = state in setOf("preparing", "recognizing", "saving", "cancelling")
}

/** 一次作业的流式输出整体；revision 单调递增，UI 可据此跳过旧快照。 */
data class LocalStream(
    val revision: Long,
    val regions: List<LocalStreamRegion>,
)

/**
 * 一条记录的识别产物（[LocalOcrEngine.readExport] 返回）。
 *
 * - [page]：document.json pages[0] 映射出的区域框模型（单页；多页等 WP5 再扩），
 *   region bbox 的坐标系是引擎规范化后的输入图（记录目录里的 source.png），不是原始拍照；
 * - [inputPath]：规范化输入图（state.json 的 "input"，即 source.png）绝对路径，
 *   区域框视图的底图应该用它，保证与 bbox 坐标系一致；null = 没找到；
 * - [markdown]：document.md 全文（引擎把 DocumentIR 转好的整页 Markdown，可能为 null）；
 * - [manifestSummary]：run-manifest.json 顶层键值摘要（schema_version / job_status / 耗时统计等）。
 */
data class LocalIrExport(
    val markdown: String?,
    val page: OcrStreamPage?,
    val pageCount: Int,
    val schemaVersion: String,
    val inputPath: String?,
    val manifestSummary: List<Pair<String, String>>,
)

/** 单个区域的流式生成：raw 是已生成的原始文本（可能仍在增长）。 */
data class LocalStreamRegion(
    val requestId: String,
    val type: String,
    val raw: String,
    val done: Boolean,
    val attempt: Int,
    val finishReason: String,
    val stopReason: String,
)

/** 识别记录概要（对应 filesDir/documents/<id>/state.json 的头部字段）。 */
data class LocalDocSummary(
    val id: String,
    val title: String,
    val mode: String,
    val status: String,
    val progress: Int,
    val blockCount: Int,
    val updatedAt: Long,
)

/** 模型下载总览。 */
data class LocalModelSummary(
    val tasks: List<LocalModelTask>,
    val repos: List<String>,
    val running: Boolean,
    val freeBytes: Long,
    val activity: LocalDownloadActivity?,
)

/** 下载任务表的一行；status ∈ queued/downloading/verifying/verified/downloaded/paused/error。 */
data class LocalModelTask(
    val repo: String,
    val path: String,
    val size: Long,
    val sha256: String,
    val status: String,
    val downloaded: Long,
    val bytesPerSecond: Long,
    val error: String,
)

/** 正在进行的下载活动（通知栏同源数据）。 */
data class LocalDownloadActivity(
    val repo: String,
    val stage: String,
    val path: String,
    val message: String,
    val bytes: Long,
    val total: Long,
)

/** ModelScope 仓库文件清单的一行。 */
data class LocalCatalogFile(
    val repo: String,
    val path: String,
    val size: Long,
    val sha256: String,
    val revision: String,
)

/** 可选识别模型清单的一行（model-profiles.json：key、展示名、所在 ModelScope 仓库）。 */
data class LocalModelChoice(
    val id: String,
    val name: String,
    val repo: String,
)

/**
 * 端侧单图全流程（[LocalOcrEngine.recognizeFile]）的返回。
 *
 * - [markdown]：document.md 全文，图片还是引擎导出的相对引用（`assets/xxx.png`），
 *   落盘时由 [OcrStore.saveOnDevice] 改写成记录目录的 file:// 绝对路径；
 * - [assetsDir]：引擎记录里的裁剪图目录（`filesDir/documents/<docId>/assets/`），
 *   没有裁剪图（纯文字页）时为 null；落盘时整棵拷进 filesDir/ocr/<id>/assets/；
 * - [modelChoice]：本次识别实际用的 profile key（ovis/glm），engine 标签按它生成。
 */
data class LocalScanResult(
    val docId: String,
    val state: String,
    val modelChoice: String,
    val elapsedMs: Long,
    val markdown: String,
    val assetsDir: File?,
)
