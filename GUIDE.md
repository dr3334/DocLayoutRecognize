# 怎么读这个项目

给第一次打开这个仓库的人（也包括三个月后的自己）的阅读路线。

先记住一句话，剩下的都能推出来：

> **一个 Activity 挂一个 Compose 树，树里用 `when(route)` 切三个屏幕；屏幕是纯 UI，真正干活的是 `ui/OcrApp.kt`；它只碰两个外部依赖 —— `data/remote/OcrVlApi`（图进去、Markdown 出来）和 `data/local/OcrStore`（记录落盘、再读回来）。**

包结构一眼看懂：

```
MainActivity.kt   入口
ui/               表现层（画界面）
  OcrApp.kt       路由 + 状态 + 流程
  theme/ icon/ component/ layout/ render/ screen/
data/             数据层（不碰 Compose）
  model/ local/ remote/ sample/
```

依赖只会从上往下走，看到 `import com.doclayout.recognize.data.*` 就知道跨到了数据层。

---

## Pass 0 · 先让它跑起来（10 分钟）

不要先读代码。先看它长什么样，读代码时才有画面。

```powershell
$env:JAVA_HOME="D:\Android_Studio\jbr"
cd D:\android-lab\DocLayoutRecognize
.\gradlew.bat assembleDebug --no-daemon
```

```powershell
$adb = "C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -n com.doclayout.recognize/.MainActivity
```

然后用 intent 参数把四个关键界面各看一眼（不用手点）：

```powershell
& $adb shell am start -n com.doclayout.recognize/.MainActivity --ei ocr 1            # 设置页
& $adb shell am start -n com.doclayout.recognize/.MainActivity --ei ocr 2            # 结果页·竖屏抽屉
& $adb shell am start -n com.doclayout.recognize/.MainActivity --ei ocr 2 --ei split 25   # 结果页·横屏 1:3
& $adb shell am start -n com.doclayout.recognize/.MainActivity --ei ocr 2 --ei open 1     # 竖屏抽屉展开
```

横屏要看真效果，先转屏：`& $adb shell settings put system accelerometer_rotation 0; & $adb shell settings put system user_rotation 1`。

> 这一步会看到**示例数据**（`data/sample/SampleRecords.kt`），不是真实识别结果。示例走 `OcrTex`（RaTeX 排版公式），真实记录走 `OcrMarkdownView`（WebView）。两条渲染路径是两套代码，别混淆。

## Pass 1 · 骨架：只有 100 行（20 分钟）

| 顺序 | 文件 | 只找这三件事 |
|---|---|---|
| 1 | `MainActivity.kt` 全文 41 行 | `setContent` 挂了谁；`intent.getXxxExtra` 有几个 |
| 2 | `ui/OcrApp.kt:82`（`fun OcrApp`） | 状态声明在 98–116 行；`when (route)` 在哪 |
| 3 | `ui/OcrApp.kt:66`（`enum OcrRoute`） | 只有 Home / Settings / Result 三个值 —— 没有 Navigation 库，没有 Fragment |

看完这三处，你就知道"页面切换"是什么：**一个 `mutableStateOf<OcrRoute>` 加一个 `when`**。

## Pass 2 · 数据流：一条主线（40 分钟）

只读一条路径，不要发散：**图 → Markdown → 磁盘 → 界面**。

```
① OcrVlApi.parse()   data/remote/OcrVlApi.kt:58    图进 → VlPage(markdown, elapsedMs, pages) 出
② OcrStore.save()    data/local/OcrStore.kt:42     落 filesDir/ocr/<id>/：record.json + pN.jpg + pN.md
③ OcrStore.list()    data/local/OcrStore.kt:32     扫目录 → List<OcrRecord>，按 createdAt 倒序
④ OcrResultScreen    ui/screen/OcrResultScreen.kt:103 → PanelBody:536 → 按 record.isReal 分流
```

配套要一起看的是 `data/model/OcrModels.kt`（`OcrRecord` / `OcrScanPage` 的字段），因为 ①→②→③ 之间传递的就是它们。

**读到 ① 时注意分叉**：`OcrMode.Cloud` 走「提交 job → 轮询 → 取预签名 URL」三步，`OcrMode.Local` 走 PaddleX 一步同步。同一函数两个分支，是故意的（云端同步接口的域名每个账号一份，换人就改代码；异步接口域名固定，只要 Token）。

**读到 ② 时注意取舍**：一条记录一个目录，不是一个大 JSON。原因写在 `OcrStore` 的类注释里 —— 大 JSON 每次改一条要重写全文，图片 base64 混进去会膨胀到几十 MB。

## Pass 3 · UI：先看断点，再看屏幕（40 分钟）

UI 有 9 个文件、2000 多行，但**入口只有一个判断**：

| 顺序 | 文件 | 看什么 |
|---|---|---|
| 1 | `ui/layout/OcrWindow.kt:54` 全文 67 行 | 方向 + 宽度 → `OcrLayoutMode`。这是结果页布局的唯一依据 |
| 2 | `ui/screen/OcrResultScreen.kt:103` | `OcrResultScreen` 只做 `when(layoutMode)`，然后调 `StackedResult`(:118) 或 `SplitResult`(:143) |
| 3 | `ui/screen/OcrResultScreen.kt:355` `FloatingSheet` | 竖屏那套"可拖抽屉"，`sheetDrag`(:471) 是手势 |
| 4 | `ui/layout/OcrSplitPane.kt:76` `SplitPaneState` | 横屏分栏。吸附档位 `[0,.25,.5,.75,1]`，拖到 0 会出现 `RestoreSplitPill` |
| 5 | `ui/component/` · `ui/icon/` · `ui/theme/` | **最后再看**，纯样式。要改配色只翻 `ui/theme/OcrTheme.kt:186` 的 `object Ocr` |

先看 1、2 就够理解结构了；3、4 是想改交互时才钻进去。

## Pass 4 · 渲染：两套并存（30 分钟）

这是这个项目最容易看错的地方 —— **两个渲染器，服务两种数据**。

| 数据来源 | 渲染器 | 为什么 |
|---|---|---|
| `sample/SampleRecords.kt`（示例） | `ui/render/OcrFormula.kt:59` `OcrTex` → RaTeX | 结构化 `OcrLine` 列表，一行一行排；公式矢量渲染，不启 WebView |
| `local/OcrStore.list()`（真实） | `ui/render/OcrMarkdownView.kt:50` → `assets/render.html` | 服务返回的是**一整篇 Markdown**，含表格/列表/行内公式，自己排版等于造个 Markdown 引擎 |

读 `OcrMarkdownView.kt` 抓三个约束（注释里也写了）：
1. `loadUrl` 只做一次，之后一律 `evaluateJavascript("setMarkdown(...)")`
2. 文本必须 `JSONObject.quote` 转义，否则换行和引号会打断脚本
3. WebView 是重对象，`remember` 复用 + 离开时 `stopLoading()` + `destroy()`

## Pass 5 · 该盯的 6 个设计决策

读完上面，这 6 条你应该能自己回答；答不上来就回去看对应文件。

| # | 决策 | 在哪 |
|---|---|---|
| 1 | 单 Activity + 自定义路由，不引 Navigation | `MainActivity.kt` / `ui/OcrApp.kt:66` |
| 2 | 状态全在 `remember`，没有 ViewModel、没有 Repository | `ui/OcrApp.kt:98-116` |
| 3 | 一条记录一个目录，删记录 = 删目录 | `data/local/OcrStore.kt:27` |
| 4 | 云端用异步 job 接口而非同步接口 | `data/remote/OcrVlApi.kt:11-25` 的类注释 |
| 5 | 公式有两条路：RaTeX（示例）/ KaTeX+WebView（真实） | `ui/render/OcrFormula.kt` vs `ui/render/OcrMarkdownView.kt` |
| 6 | Kotlin 编译器跑在 Gradle 进程内，`-Xmx1200m` | `gradle.properties` |

第 2 条是当前最大的架构债：状态、路由、业务流程全在 `ui/OcrApp.kt` 一个 410 行的 composable 里。要加"识别历史搜索""批量重试"这类功能，就得先把它拆成 ViewModel。

## 改代码前必须知道的地雷

| 坑 | 现象 | 处理 |
|---|---|---|
| 本地服务是 `http` | Android 9+ 默认拦明文 | Manifest 已开 `usesCleartextTraffic`；生产该换 `network-security-config` |
| USB 调试连不上电脑 `127.0.0.1` | 一直转圈到超时 | `& $adb reverse tcp:8080 tcp:8080` |
| 相机拍的照片要大图 | 默认预览缩略图 | 已在用 FileProvider 写 `cacheDir/camera/`，全尺寸 |
| WebView 泄漏 | 反复进出结果页内存涨 | 必须 `DisposableEffect { onDispose { destroy() } }`，已有，别删 |
| `assets/render.html` 缺 KaTeX | 真实记录公式显示成 `\frac{1}{2}` 源码 | `assets/` 下没有 `katex/` 目录，需补离线包 |
| `--no-daemon` | 不加就看 JVM crash / errno 1455 | 本机 16G 内存，Kotlin in-process + 单 JVM，别改回去 |

## 阅读时间预算

| Pass | 内容 | 时间 |
|---|---|---|
| 0 | 跑起来 + 四个界面 | 10 min |
| 1 | 骨架 100 行 | 20 min |
| 2 | 数据流一条线 | 40 min |
| 3 | UI 断点 → 屏幕 | 40 min |
| 4 | 两套渲染 | 30 min |
| 5 | 六个决策自测 | 20 min |

两小时能到"能改"的程度。之后每次只改一处，用 Pass 0 的 adb 参数直接跳到改动的界面验证，不用手点菜单。
