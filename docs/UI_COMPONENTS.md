# UI 组件讲解目录

> 面向开发新手：每登记一个组件，回答三个问题——
> **①属于哪个文件；②用了什么组件（Compose API）；③一句话职责与改哪里。**
>
> 维护方式：WP3（模型管理页 + 三模式设置）首发，WP4（流水识别测试页）、WP5（PDF 导入）随包递增。
> 分层背景：`MainActivity → ui → data` 单向依赖，`ui/` 只画界面不做 IO，所有引擎调用
> 都经 `data/local/LocalOcrEngine.kt` 门面（suspend + Dispatchers.IO）。

---

## WP3 · 端侧模型管理 + 三模式设置

### 1. OcrModelScreen —— 端侧模型管理页（整页）

| | |
|---|---|
| 属于哪个文件 | `ui/screen/OcrModelScreen.kt` |
| 用了什么组件 | `Column`/`Box` 布局、`OcrTopBar`、`SectionLabel`、`SettingCard`、`SettingRow`、`LaunchedEffect` 轮询、`rememberLauncherForActivityResult(RequestPermission)`、`AlertDialog`（删除确认）、`AnimatedVisibility`（提示浮条） |
| 一句话职责 | 模型仓库的查看清单 → 勾选 → 下载 / 暂停 / 删除，以及引擎就绪状态展示。 |

- 入口：设置浮层「管理端侧模型」行、深链 `--ei ocr 5`（`OcrApp.kt` 的 `OcrRoute.Models`）。
- **轮询**：`LaunchedEffect(Unit)` 里 `while (isActive)` 每 600ms 调一次 `engine.modelSummary()` 与
  `engine.readiness()`（前者服务侧有 700ms 节流，轮太快只会白读）。页面离开组合时协程自动取消。
- **下载时序（协议写死，顺序错了会抛错）**：先「查看文件清单」= `refreshCatalog`（网络 + 写
  `catalog.json` 缓存）→ 勾选 → `startDownload`（内部校验清单缓存，没有缓存直接抛
  "请先查看仓库文件清单"，见源 `ModelHub.java:43`）。
- **权限**：Android 13+ 下载前先在前台请求 `POST_NOTIFICATIONS`（`rememberLauncherForActivityResult`），
  拿到结果（给没给都算）才继续 `startDownload`——通知被拒只是没进度通知，下载服务照常跑。
  权限弹窗期间的请求挂在 `pendingDownload` 状态里。
- 改哪里：想加仓库按钮/改轮询频率 → `OcrModelScreen` 顶层；改单个仓库卡片的布局 → `RepoCard`。

### 2. RepoCard —— 单个模型仓库卡片

| | |
|---|---|
| 属于哪个文件 | `ui/screen/OcrModelScreen.kt`（私有 Composable） |
| 用了什么组件 | `SettingCard` 容器、`Pill` 状态徽标、`LinearProgressIndicator(progress = {..})` 下载进度条、`Checkbox`（`onCheckedChange = null` 只做状态显示，整行 `clickable` 切换勾选）、私有 `ChipButton` |
| 一句话职责 | 展示一个 ModelScope 仓库的下载状态、进行中的活动条、动作按钮和可展开的文件清单。 |

- 头部（仓库名 + 状态徽标）可点：没清单时拉清单，有清单时展开/收起。
- 「暂停下载」只在有活跃任务（queued/downloading/verifying）时出现；「删除已下载」只要有任务记录就出现，
  点了先弹 `AlertDialog` 二次确认（删除会连校验记录一起清）。
- 汇总行、状态徽标文案来自纯函数 `repoStatus()` / `taskStatusText()`（同文件底部），改文案去那里。

### 3. ChipButton —— 小号动作按钮

| | |
|---|---|
| 属于哪个文件 | `ui/screen/OcrModelScreen.kt`（私有 Composable） |
| 用了什么组件 | `Box` + `clip(RoundedCornerShape)` + `background` + `clickable(enabled=)` |
| 一句话职责 | 34dp 高的胶囊小按钮，塞在卡片动作行里用。 |

- 公共 `PrimaryButton`（`ui/component/OcrComponents.kt`）是 44dp 的页面级主按钮，放这里太大。
- `accent = true` 珊瑚强调（下载所选）、`danger = true` 危险色（删除）。

### 4. ModeChoiceDialog —— 接口模式三选一弹窗

| | |
|---|---|
| 属于哪个文件 | `ui/screen/OcrSettingsScreen.kt`（私有 Composable） |
| 用了什么组件 | `AlertDialog` + 可点击行 + `Box` 画选中圆点 + `RowDivider` 分行 |
| 一句话职责 | 「接口模式」从二值翻转升级为三选（云端 / 本地 / 端侧），每行带一句说明。 |

- 之前是点一下 Cloud↔Local 循环翻转；三态后循环点会绕晕，列表式一眼可选。
- 打开方式：设置页「接口模式」行 `onClick = { editing = "mode" }`，走 `OcrSettingsContent`
  已有的 `editing` 状态机（`null / "mode" / "url" / "token"`）。

### 5. 设置浮层「识别服务」卡的三模式分支

| | |
|---|---|
| 属于哪个文件 | `ui/screen/OcrSettingsScreen.kt` → `OcrSettingsContent` |
| 用了什么组件 | `when (mode)` 穷尽分支 + `SettingRow`/`RowDivider` |
| 一句话职责 | 云端显示地址/模型/Token；本地显示地址；端侧隐藏地址与 Token，改显「端侧模型状态」徽标 + 「管理端侧模型」入口。 |

- 端侧的「端侧模型状态」值来自 `deviceReady: Boolean?` 参数（`null`=查询中），由 `OcrApp` 轮询后传入。
- 顺带把「识别引擎」行改成按模式显示（端侧 = PP-DocLayoutV3 + GLM-OCR）。

### 6. OcrApp 的三处接线（路由 / 轮询 / 模式切换）

| | |
|---|---|
| 属于哪个文件 | `ui/OcrApp.kt` |
| 用了什么组件 | `OcrRoute` enum + `when(route)` 路由、`LaunchedEffect(mode, settingsOpen)` 轮询、`rememberSaveable` |
| 一句话职责 | 把 Models 页挂进路由表、把就绪徽标喂给设置浮层、切模式时端侧跳过默认地址跟随。 |

- `OcrRoute.Models`：`when(route)` 新分支渲染 `OcrModelScreen`；深链映射 `startRoute 5 → Models`
  （0=首页 1=设置 2=结果页 3=流式 5=端侧模型）；BackHandler 的 else 分支自动兜住返回首页。
- `deviceReady` 轮询：只在「端侧已选中或设置浮层开着」时每 1s 查一次——端态走
  `engine.refreshReadiness()`（磁盘对账并**回写引擎内存 readiness**），非端态读内存快照。
  纯内存版冷启动恒为 unchecked 且无人推进，会导致门槛永远拒绝（死锁），所以端态必须对账。
- `runScan` 端态门槛：判定 `settings.configured` 前**先跑一次 `refreshReadiness()`**——
  否则深链 `--es scan` 冷启动时门槛会赶在轮询第一次对账之前读到过期的 unchecked。
- `onModeChange`：`if (next != OcrMode.OnDevice && wasDefault)` —— 端侧没有地址概念，
  不读 `defaultBaseFor(OnDevice)`（空串）、也不写 `base_url`。

### 7. data 层的三处配套（非 UI，改 UI 前先懂它们）

| | |
|---|---|
| 属于哪个文件 | `data/local/OcrSettingsStore.kt`、`data/remote/OcrVlApi.kt`、`data/local/LocalOcrEngine.kt` |
| 用了什么组件 | `OcrMode.OnDevice` 枚举（"端侧 · 本地流水线"，SP 值 `"device"`）；穷尽 `when`；`LocalOcrEngine.readinessSnapshot()` |
| 一句话职责 | 端侧 `configured` = 模型文件就绪（查 readiness 的 files-ready）；端侧 `defaultBaseFor` = 空串；`OcrVlApi.parse` 的 OnDevice 分支兜底抛错（端侧不走 HTTP，真分流在 WP5 导入流程接）。 |

- `readinessSnapshot()` 是同步版就绪快照：`RecognitionController.readiness()` 只序列化内存 JSON
  （无磁盘 IO），所以同步属性 `configured` 的 getter 里能安全调用——suspend 版 `readiness()` 需要协程，属性里调不了。
- 就绪状态机有两条 UI 可达的对账路径（都写回引擎内存 readiness，不加载引擎）：
  `LocalOcrEngine.refreshReadiness()`（轮询/门槛判定前调用）与 `DownloadService` 下载完成时的自动对账；
  底层实现是 `RecognitionController.refreshReadiness()`，复用 prepare() 的磁盘核对逻辑。
- 引擎 Java 簇（`cn/local/ocr/`）保持包私有，UI 一律经 `LocalOcrEngine` / `LocalOcrApi` 门面。

---

## WP4 · 流水识别测试页

### 1. OcrPipelineScreen —— 流水识别测试页（整页）

| | |
|---|---|
| 属于哪个文件 | `ui/screen/OcrPipelineScreen.kt` |
| 用了什么组件 | `Column`/`Box` 布局、`OcrTopBar`、`SettingCard`（输入卡）、`Pill`（阶段徽标）、`LinearProgressIndicator`（区域进度，`progress = {..}` 比例态 / 无参转圈态）、`rememberLauncherForActivityResult(PickVisualMedia)`（Photo Picker 选图）、`LaunchedEffect` 双轮询（状态轮询 + 深链直达）、`LazyColumn + itemsIndexed`（区域流式文本）、`AlertDialog`（错误弹窗）、`AnimatedVisibility`（提示浮条）、`produceState`（区域框底图 IO 解码） |
| 一句话职责 | 端侧流水线整条链路的验证载体：选图（或深链直达）→ createInput+start → 400ms 轮询 status → 阶段徽标 / region i/n / 逐区域 token 流式 → 完成后展示 document.md 与区域框。 |

- 入口：首页「流水识别（测试）」行卡（验证期临时入口）、深链 `--ei ocr 4`（`OcrApp.kt` 的 `OcrRoute.Pipeline`）。
- **两个去重门槛（本页最容易做错的地方）**：
  ① status 快照按 `sequence` 去重——引擎无进展时不写状态、不重组；
  ② 流式文本按 `stream.revision` 去重——revision 相同的快照直接丢弃。
  `raw` 由 native 层截断成**合法 UTF-8 但句子可能不完整**，UI 原样显示，绝不补全/截断。
- **轮询**：`LaunchedEffect(docId)` 里 `while(isActive)` 每 400ms 调一次 `engine.status(id)`（要求区间
  300–500ms 取中值）；`s.terminal` 后读 `engine.readExport(id)` 并停止轮询。
- **取消**：`engine.cancel(id, jobId)`——jobId 只在运行快照里有（start 返回的 doc JSON 不带），
  所以从轮询的 status 里取；没拿到 jobId 时提示"稍后再取消"而不是硬调。
- **启动顺序**：先 `refreshReadiness()` 磁盘对账（与 `OcrApp.runScan` 端态门槛同源），模型没就绪
  直接弹错误；然后 `createInput`（落盘 + autoStart）+ `start`（兜底"autoStart 只落盘没启动"，
  对运行中作业幂等）。
- 改哪里：轮询节奏/去重逻辑 → `OcrPipelineScreen` 顶层 `LaunchedEffect(docId)`；
  流式文本的显示样式 → `RegionStreamList`；按钮行 → 输入卡与 Tab 行的 `SmallChip`。

### 2. RegionOverlayView —— 区域框视图

| | |
|---|---|
| 属于哪个文件 | `ui/screen/OcrPipelineScreen.kt`（私有 Composable） |
| 用了什么组件 | `BoxWithConstraints`（拿可用宽高算 Fit 缩放）、`produceState + withContext(Dispatchers.IO)`（大图 IO 解码）、`Image(ContentScale.FillBounds)`、`Canvas` 画 bbox 虚线框（`PathEffect.dashPathEffect`） |
| 一句话职责 | 完成后在输入图上叠画 DocumentIR 的区域框，验证"框的位置对不对"。 |

- 数据来自 `LocalIrExport.page`（`DocumentIrMapper` 把 document.json 映射成 `OcrStreamPage`），
  与「流式渲染演示」页共用同一个数据模型——两页渲染效果可以直接对照。
- **坐标系陷阱**：bbox 是引擎**规范化输入图**（记录目录 `source.png`）的栅格坐标，底图优先用
  `export.inputPath`（readExport 从 state.json 的 `"input"` 读出），没有才退回用户选的原图；
  用原图且分辨率策略改过尺寸时框会整体偏，属已知取舍。
- 画法与 `OcrStreamScreen` 的 Canvas 相同（`ContentScale.Fit` 等效换算），但不搬调序/合并交互。

### 3. SmallChip —— 小号动作按钮（页内私有）

| | |
|---|---|
| 属于哪个文件 | `ui/screen/OcrPipelineScreen.kt`（私有 Composable） |
| 用了什么组件 | `Box` + `clip(RoundedCornerShape)` + `background` + `clickable(enabled=)` |
| 一句话职责 | 34dp 胶囊按钮：选图 / 取消识别 / 重新识别本图 / 流式演示对照 / 三个完成视图 Tab。 |

- 与 `OcrModelScreen.ChipButton` 同款但**各留一份私有副本**：名字相同、语义不同，合并只会多一层参数。
- `accent = true` 珊瑚强调（当前 Tab）、`danger = true` 危险色（取消识别）。

### 4. OcrApp / MainActivity 的接线（WP4 部分）

| | |
|---|---|
| 属于哪个文件 | `ui/OcrApp.kt`、`MainActivity.kt` |
| 用了什么组件 | `OcrRoute.Pipeline` 枚举分支 + `startRoute 4→Pipeline` 映射、`startPipelinePath` 参数透传、`intent.getStringExtra("scanpath")` |
| 一句话职责 | 把 Pipeline 页挂进路由表；深链 `--ei ocr 4 [--es scanpath <路径>]` 直达；首页加临时入口行卡 `PipelineEntry`。 |

- `scan` 与 `scanpath` 是两条调试通道：`scan` → 结果页 runScan（云端/HTTP）；`scanpath` → Pipeline
  页端侧流水线。MainActivity 注释里有对照表。
- 深链前必须先 `adb shell am force-stop`（无 onNewIntent，extra 会被已有实例忽略）——
  `ADB-COMMANDS.txt` 3.10/3.11 有完整命令与把测试图推进 `files/inbox/` 的写法。
- Home 的入口是**验证期临时**的：WP5 正式分流接进「导入文件识别」后摘掉 `PipelineEntry`。

### 5. data 层两处配套（非 UI，改 UI 前先懂它们）

| | |
|---|---|
| 属于哪个文件 | `data/local/DocumentIrMapper.kt`、`data/local/LocalOcrEngine.kt` |
| 用了什么组件 | `DocumentIrMapper`（纯 Kotlin object，org.json → `OcrStreamPage`/manifest 摘要，**无 Compose 类型**）；`LocalOcrEngine.readExport(id)`（新增产物读取） |
| 一句话职责 | readExport 定位并读取记录目录的 document.json / document.md / run-manifest.json；DocumentIrMapper 把 document.json 的 pages[0] 映射成 OcrStreamPage（复用 `OcrStreamDemo.parse`，同一导出 schema），manifest 解析成键值摘要。 |

- 产物目录布局（引擎 `DocumentStore.java:11`）：记录根 `filesDir/documents/<id>/`，识别成功时
  `recognized/<jobId>/` 整棵 copyTree 进根，所以产物一般在根；readExport 找不到时回退
  `recognized/` 下 lastModified 最新的作业目录（UUID 无时间序）。
- 本页按**单页**映射（pages[0]）：端侧流水线一次一张图；WP5 PDF 多页时再扩成 List。

## WP5 · PDF 导入 + 端侧分流（导入文件识别）

### 1. OcrApp.runScan 的端侧分流（本轮最核心的一处改动）

| | |
|---|---|
| 属于哪个文件 | `ui/OcrApp.kt` → `runScan(uris, merge, pdfUri)`（本地私有 suspend 函数） |
| 用了什么组件 | `ScanState`（进度弹窗状态）、`LocalOcrEngine.recognizeFile`（端侧单图全流程）、`OcrVlApi.parse`（云端/本地不变）、文件级私有数据类 `PageInput` / `PageOutcome` |
| 一句话职责 | 「导入文件识别」按 `settings.mode` 分流：端态逐页走引擎流水线，云端/本地仍走 HTTP；落盘也按模式分流（`saveOnDevice` / `save`）。 |

- **引擎标签不再写死**：原来是 `val engine = "PaddleOCR-VL 1.6"`；现在端侧用
  `LocalOcrEngine.engineLabel(modelChoice)` 动态生成（如「端侧 PP-DocLayoutV3 + GLM-OCR」），
  profile 名来自 `model-profiles.json`（ovis→OvisOCR2、glm→GLM-OCR）。
- **端侧进度**：`recognizeFile` 的 `onStatus` 回调把「区域 i / n」写进 `ScanState.Running` 的
  label，`ScanOverlay` 弹窗直接展示——不需要额外的进度 UI。
- **PDF 语义**：`pdfUri` 非 null 时先 `PdfPageSource.rasterize` 成页 PNG，然后与图片页走完全
  相同的链路；PDF 固定**整卷合并**（一份 PDF = 一份试卷的多页，页序固定，不弹 MergeChoiceDialog）。
- **临时文件清理**：`finally` 里删 stageUri 的单图缓存 + 整目录删 PDF 页 PNG。
- 深链 `--es scan <路径>` 现在按扩展名识别 PDF（`.pdf`）→ 走栅格化分支。
- `OcrVlApi.parse` 的 OnDevice 拦截分支**保留**作为兜底：分流后 parse 不该再收到端态，
  万一未来新调用点误传，直接抛错而不是打到 HTTP。

### 2. LocalOcrEngine.recognizeFile —— 端侧单图全流程（data 层可复用入口）

| | |
|---|---|
| 属于哪个文件 | `data/local/LocalOcrEngine.kt` |
| 用了什么组件 | 复用门面自身的 `refreshReadiness` / `createInput` / `start` / `status` / `readExport`；`withContext(Dispatchers.Main)`（回调切主线程）；返回数据类 `LocalScanResult` |
| 一句话职责 | 把 WP4 流水页的"门槛→建记录→轮询→读产物"收拢成一次 suspend 调用，给 runScan 复用。 |

- 门槛与流水页同源（先磁盘对账）；终态 succeeded/partial 算成功，blank/cancelled/failed/
  missing-models 抛 `IllegalStateException`（文案可直接进失败弹窗）。
- 调用方协程取消时轮询随之终止，引擎记录留在 `documents/`，与流水页行为一致。
- `engineLabel(modelChoice)` 在同文件 companion：profile key → 展示名，未知 key 只首字母大写。

### 3. PdfPageSource —— PDF 逐页栅格化（新增文件）

| | |
|---|---|
| 属于哪个文件 | `data/local/PdfPageSource.kt`（WP5 新增，data 层 object） |
| 用了什么组件 | `android.graphics.pdf.PdfRenderer`（系统自带，无 pdfbox 依赖）、`ParcelFileDescriptor`（`content://` 与 `file://` 统一走 `ContentResolver.openFileDescriptor`）、`Matrix` 缩放、`Bitmap.CompressFormat.PNG` |
| 一句话职责 | 一份 PDF → 一叠页 PNG（页序 = 返回列表顺序），尺寸预算与引擎 balanced 档对齐（≤8MP、最长边 4096）。 |

- A4 试卷页渲到约 287dpi（≈8MP），文字清晰且引擎不会二次缩小；超大海报页自动缩小，
  永不越过引擎 `ImageResolution` 的 64MP 硬上限。
- 白底渲染（`eraseColor(WHITE)`，否则透明底在深色阅读器里渲成黑底）；`RENDER_MODE_FOR_PRINT`
  拿打印质量；加密 PDF 的 `SecurityException` 统一转成可读的 `IOException`。

### 4. OcrStore.saveOnDevice —— 端侧记录落盘（含图片引用改写）

| | |
|---|---|
| 属于哪个文件 | `data/local/OcrStore.kt`（新增方法 + 文件级数据类 `OnDevicePage` + 常量 `META_ON_DEVICE`） |
| 用了什么组件 | `File.copyRecursively`（引擎裁剪图整棵拷）、`Uri.fromFile`（file:// 前缀）、私有 `rewriteAssetRefs` |
| 一句话职责 | markdown 用引擎 export 的 document.md 原文；裁剪图拷进 `filesDir/ocr/<id>/assets/`；引用改写成 file:// 绝对路径。 |

- **引用改写**：document.md 里的 `assets/xxx.png` → `file:///data/.../files/ocr/<id>/assets/xxx.png`。
  `render.html` 的 `resolveSrc` 对 `file:` 前缀直接放行（render.html:231），WebView 开着
  `allowFileAccess`（OcrMarkdownView），所以私有目录里的裁剪图能直接加载。
- **meta 写死点修正**：`readRecord` 原来无条件写 `meta = "VLM 结构化输出"`；现在记录里带
  `meta` 字段（saveOnDevice 写入 `META_ON_DEVICE = "端侧流水线 · 区域结构化输出"`），
  老记录按 engine 前缀兜底。confidence 维持 "—"（两套引擎都不回传置信度，不编数值）。

### 5. PdfEntry —— 首页 PDF 试卷导入入口

| | |
|---|---|
| 属于哪个文件 | `ui/screen/OcrHomeScreen.kt`（私有 Composable）+ `ui/OcrApp.kt` 的 launcher |
| 用了什么组件 | `Row + clip + background + clickable`（与 StreamEntry 同款整行卡）；`OcrApp` 侧 `rememberLauncherForActivityResult(OpenDocument)`、状态 `pendingPdf: Uri?` |
| 一句话职责 | 「相册导入」的 Photo Picker 只认图片，PDF 从这里走 `OpenDocument("application/pdf")` 单选。 |

- 选中 PDF 后**不弹** MergeChoiceDialog（`pendingPdf` 独立状态），直接 `runScan(merge=true, pdfUri=…)`
  整卷合并；图片多选的合并/分开弹窗语义不变。
- 「相册导入」hint 从「支持 JPG / PNG / PDF」改为「支持 JPG / PNG」——PDF 有自己的入口，
  不再虚标。OpenDocument 与 Photo Picker 一样不需要存储权限。
- `launchPdfPicker` 里 `runCatching` 包住 SAF 偶发的「文档树被吊销」SecurityException。

### 6. OcrModelScreen 的「识别模型」切换（降级做成设置项）

| | |
|---|---|
| 属于哪个文件 | `ui/screen/OcrModelScreen.kt` |
| 用了什么组件 | `SettingRow(onClick=)`（行变可点）、`AlertDialog` 选择弹窗（`CircleShape` 圆点标记当前项、`RowDivider` 分行）、`LocalOcrEngine.modelChoices()` 拉清单（拉一次）、`switchModel` 协程动作 |
| 一句话职责 | 「识别模型」行从只展示改为可点击切换 ovis/glm，走 `engine.setModel`（会卸载并重载引擎，需数秒，hint 提示）。 |

- 清单来自 `model-profiles.json`（经 `LocalOcrApi.modelChoices()` 新转发，
  `LocalModelChoice(id, name, repo)`），UI 不写死 profile 名。
- `busy`（下载/删除/切换进行中）时行禁点；识别运行中引擎会拒绝切换，错误走 hint 浮条。

### 7. 流式识别渲染页（OcrStreamScreen 升级 + 首页入口 StreamRecEntry）

| | |
|---|---|
| 属于哪个文件 | `ui/screen/OcrStreamScreen.kt`（主体改造）、`ui/screen/OcrHomeScreen.kt`（StreamRecEntry 入口）、`ui/OcrApp.kt`（`OcrRoute.StreamRec` 路由，深链 `--ei ocr 6`） |
| 用了什么组件 | 三态状态机 `StreamPhase`（Demo/Recognizing/Done，普通 enum）；识别会话 = `remember` 的 `jobDocId/jobStatus/liveRegions/recPage` 状态组 + `LaunchedEffect(jobDocId)` 400ms 轮询（`sequence` 去重、流式按 `stream.revision` 去重）；`produceState + Dispatchers.IO` 解码三种图源；`rememberLauncherForActivityResult(PickVisualMedia)` 选图；进度浮层 `CircularProgressIndicator + Column(background 半透明)`；错误 `AlertDialog` |
| 一句话职责 | 把「流式渲染演示页」升级为真实识别页：选图/深链后跑端侧流水线，识别中下半区实时渲染逐区域流式输出（边生成边渲染），终态自动切 DocumentIR 区域页——框/正文联动、调序、合并复制、导出、打字机回放全部挂真实数据。 |

- **三态**：`Demo`（没识别过：内置样本回放，旧调用点行为不变）→ `Recognizing`（上半用户原图+进度浮层——
  此时引擎还没给 bbox 画不了框；下半把 `liveRegions` 的 raw 按生成顺序拼接、经平滑上屏喂 `OcrMarkdownView`，
  `render.html` 的 `balance()` 让半截公式也能边生成边渲染）→ `Done`（`readExport` 的 `page`
  （DocumentIrMapper → OcrStreamPage）与规范化图 `inputPath` 就位，框/手势/调序/导出全启用，
  正文直接展示终稿——识别中已经流式放过一遍，不再清屏重打）。
- **平滑上屏（追赶式打字机）**：引擎每 400ms 轮询回来的是一批新字，直接上屏会"突然蹦出一坨"。
  `liveShownLen` 记录"已显示到第几个字"，`LaunchedEffect(liveMarkdown, phase)` 里的循环每 26ms
  推进一步：一步走 `积压字数/12`（钳在 1~24 字）——积压越多走得越快（一大段约 1 秒铺完，
  对数式收敛），快追上时一步 1 字（观感是逐字打出）。上屏的是 `liveMarkdown` 的前
  `liveShownLen` 个字（末尾避开代理对劈开 emoji），结尾跟假光标 `▌` 表示"正在生成"，
  终态自动消失。26ms 与演示打字机同节奏，是 WebView 刷得动的上限。
- **打字机门控**：`LaunchedEffect(playKey, data, phase)` 在 `Recognizing` 态直接 return，
  下半区让位给实时输出；`Done` 且 `playKey==0`（本轮没按过重播）直接展示终稿不清屏；
  按「重播」（`playKey++`）才以打字机节奏重放——`runOne` 每轮把 `playKey` 归零。
- **入口行 `RecognitionEntryRow`**（仅 engine 非 null 显示）：40dp 缩略图 + 文件名 + 引擎消息 +
  `EntryChip`（选图/重新识别/取消）；识别中右侧工具条换成 阶段徽标+区域 i/n+取消胶囊，重播隐藏。
- **首页入口**：`StreamRecEntry` 整行卡（与 PipelineEntry/StreamEntry 同级），放在三行最上——
  它是唯一"真识别"入口；深链 `--ei ocr 6 --es scanpath <应用可读路径>` 直达即自动开跑。
- 分层不变：识别调用全走 `data/local/LocalOcrEngine` 门面，`ui/` 无直接 IO；
  `cn/local/ocr` 仍无 Compose。
- **导入队列（拍照/相册/PDF 端侧模式分流）**：`OcrApp.startScanOrStream` 在端侧模式下
  把输入落地成页文件（stageUri / PDF 栅格化）存进 `pendingStreamPages`（saveable），
  路由到本页 `initialQueue`。页面逐张 runOne：终态时轮询循环收集记录 id 并清掉队首 +
  **把 jobDocId 一并置 null**（推进效应的门槛，不清则第 2 张永不开跑）；全部跑完后由
  `LocalOcrEngine.exportOutcome`（从已完成的引擎记录组装产物，不重跑）+ `OcrStore.saveOnDevice`
  落历史（合并=一条多页 / 分开=每张一条），回调 `onRecordsSaved` 刷新首页列表。
  云端/本地模式导入仍走旧 `runScan`（ScanOverlay 进度流），行为不变。
- **readiness 两级就绪态（踩坑记录）**：引擎 state 有 `files-ready`（磁盘对账通过）和
  `ready`（已实际加载，更强）两级。**门槛判定一律用 `LocalReadiness.usable`
  （两级都算就绪）**——只认 `filesReady` 的话，引擎一旦加载、state 变 ready，
  第二次识别（选图/导入）会被误拒「模型还没就绪」，报错文案还自相矛盾。
  模型页徽标那种要区分两级的展示才用 filesReady / engineReady。
- **图片块（插图）的路径两种根**：引擎把插图区域检出为 `type=image` 块（text 空、
  `resource=assets/p0001-bXXX.png`，相对**记录目录**）。`DocumentIrMapper.resolveResources`
  在 readExport 时把真实记录的 resource 改写成裁剪图**绝对路径**；`OcrStreamDemo.toMarkdown`
  见绝对路径就 `![](file:///…)` 原样嵌入（render.html 的 resolveSrc 放行 file:），
  见相对路径（演示样本）仍补 `stream/` 前缀归到 android_asset。不改写会拼到
  android_asset 下静默 404——插图在正文里凭空消失。真机验证：两幅 SVG 插图均嵌在
  正文正确位置显示。
- **公式**：引擎流式 raw 自带 `$...$` 定界（GLM-OCR 行内公式），所以识别中边生成边渲染
  的就是 KaTeX 排版（半截公式由 render.html 的 balance() 补齐）；独立公式块
  （format=latex、裸 LaTeX）由 toMarkdown 补 `$$…$$`。真机验证：分式/根号/积分/Σ
  流式与终态全部排版渲染。
