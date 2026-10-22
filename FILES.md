# 文件职责清单（按层分类）

> 更新于 2026-10-22 · 代码已按 `ui/` + `data/` 分层，包名与目录一一对应
> 只列源码与工程配置；`build/`、`app/build/`、`.gradle/`、`.idea/`、`.kotlin/`、`.temp/` 全是生成物或临时脚本

## 分层总览

```
com.doclayout.recognize
├─ MainActivity.kt                    入口层：只挂 OcrApp
├─ ui/                                表现层：只画界面，不做 IO
│   ├─ OcrApp.kt                      编排层（事实上的 ViewModel + Router）
│   ├─ theme/     OcrTheme.kt         design token
│   ├─ icon/      OcrIcons.kt         图标
│   ├─ component/ OcrComponents.kt    业务组件
│   ├─ layout/    OcrWindow.kt        环境判定（方向 / 宽度）
│   │             OcrSplitPane.kt     分栏手势
│   ├─ render/    OcrFormula.kt       单条公式
│   │             OcrMarkdownView.kt  整篇 Markdown
│   └─ screen/    Ocr{Home,Settings,Result}Screen.kt
└─ data/                              数据层：不认识 Compose，可单独测
    ├─ model/   OcrModels.kt          模型
    ├─ local/   OcrStore.kt           记录持久化
    │           OcrSettingsStore.kt   配置持久化
    ├─ remote/  OcrVlApi.kt           识别服务
    └─ sample/  SampleRecords.kt      示例数据
```

依赖方向单向：`MainActivity → ui → data`。两条硬规则：

1. `data/` 下不许出现 `androidx.compose.*` 的 UI 类型（`OcrStore` / `OcrSettingsStore` 只借用 `Context` 拿目录和 SP，属正常）。
2. `ui/` 下不许直接 `import org.json.*` 或 `java.net.*`——要发请求、要解析 JSON，走 `data/remote`。

---

## 1. 工程 / 构建层

| 文件 | 关键内容 | 什么时候改 |
|---|---|---|
| `settings.gradle` | 仓库镜像（阿里云优先）、`rootProject.name`、`include ':app'`、`FAIL_ON_PROJECT_REPOS` | 加模块、换镜像 |
| `build.gradle`（根） | 插件版本：AGP 8.7.3 / Kotlin 2.1.21 / Compose 插件 2.1.21，全 `apply false` | 升 AGP 或 Kotlin |
| `app/build.gradle` | namespace、compileSdk 35 / minSdk 26、`abiFilters 'arm64-v8a','x86_64'`、Compose 开关、依赖清单 | 加依赖、改 ABI、改版本号 |
| `gradle.properties` | `-Xmx1200m` + `kotlin.compiler.execution.strategy=in-process`（本机 16G 内存对策，防双 JVM 打爆） | 换机器 / 内存变大 |
| `gradlew` / `gradlew.bat` | Gradle 启动脚本（Unix / Windows 两份） | 一般不动 |
| `gradle/wrapper/gradle-wrapper.properties` | 锁定 Gradle 8.13 | 升 Gradle |
| `gradle/wrapper/gradle-wrapper.jar` | wrapper 本体，**必须入库** | 一般不动 |
| `local.properties` | 只有 `sdk.dir`，机器相关 → **已忽略** | clone 后自己建 |
| `.gitignore` | 排除生成物、`.idea`、`local.properties`、`build_out.txt`、`.temp/` | 新增产物类型时 |
| `.gitattributes` | 源码统一 LF，`*.bat` 用 CRLF，`*.jar/*.png` 标二进制 | 一般不动 |
| `README.md` | 现状、包结构、构建/安装命令、adb 直达参数 | 流程变化时 |
| `FILES.md` | 本文件：每个文件干什么 | 增删文件时 |
| `GUIDE.md` | 阅读路线：先看什么后看什么 | 结构大改时 |

## 2. 入口层

| 文件 | 行数 | 作用 |
|---|---|---|
| `app/src/main/AndroidManifest.xml` | 43 | INTERNET 权限；`<queries>` 声明 `IMAGE_CAPTURE`（Android 11+ 包可见性，否则调相机 resolve 为 null）；`usesCleartextTraffic="true"` 放行本地 http；`configChanges="orientation\|screenSize\|keyboardHidden"` 让旋转不重建 Activity（分栏比例才保得住）；FileProvider 注册 |
| `MainActivity.kt` | 41 | 唯一 Activity（`class MainActivity` 在 :22）。`setContent { MaterialTheme { OcrApp(...) } }`，把 intent extra（`ocr/dark/empty/record/open/split/scan`）全部透传给 OcrApp。这些 extra 只服务于 adb 直达截图 |

## 3. 编排层（`ui/OcrApp.kt`，410 行）

事实上的 ViewModel + Router。四件事：

| 位置 | 内容 |
|---|---|
| `:66` | `private enum class OcrRoute { Home, Settings, Result }` —— 没有 Navigation 库 |
| `:69` | `private sealed interface ScanState { Idle / Running(done,total,label) / Failed(msg) }` |
| `:82` | `fun OcrApp(...)`：入口参数全是 adb 调试开关 |
| `:98-116` | 全部状态：`route` / `dark` / `scanned` / `currentIndex` / `scanState` / `pendingUris` / `camUri`，都用 `remember` 或 `rememberSaveable` |
| `:134` | `suspend fun runScan(uris, merge)` —— 业务流程主干：逐张调 `OcrVlApi.parse()` → 收齐后 `OcrStore.save()` |
| `:331` | `ScanOverlay` —— 识别进度 / 失败提示 |
| `:385` | `MergeChoiceDialog` —— 多选后「合并成一份 / 各成一条」 |

## 4. 数据层（`data/`）

拆成四个子包，各回答一个问题：**数据长什么样 / 存在哪 / 从哪来 / 演示用什么**。

### 4.1 `model/` —— 数据结构

| 文件 | 行数 | 内容 |
|---|---|---|
| `model/OcrModels.kt` | 55 | `OcrLineKind`(:14)、`OcrLine`、`OcrScanPage`、`OcrRecord`。零 Android 依赖，可被纯 JVM 单测直接引用 |
| `model/OcrStreamModels.kt` | 55 | 区域级模型：`OcrStreamBlock`（bbox + 自己的裁剪图 + 该区域 Markdown）、`OcrStreamPage`（width/height/readingOrder/blocks）。流式页专用 |

两套结构故意不合并：`OcrLine`（结构化文本块，样例 / 将来端侧 OCR 用）vs `OcrScanPage`（整页图 + Markdown，云端 VLM 用），用 `OcrRecord.isReal` 区分，渲染层据此换实现。

### 4.2 `local/` —— 持久化

| 文件 | 行数 | 内容 |
|---|---|---|
| `local/OcrStore.kt` | 160 | 记录仓库。`class OcrStore`(:27)、`list()`(:32)、`save()`(:42)、`delete()`(:90)、`clear()`(:95)、`stageUri()`(:132)。一条记录一个目录 `filesDir/ocr/<id>/`：`record.json` + `pN.jpg` + `pN.md`，删记录 = 删目录 |
| `local/OcrSettingsStore.kt` | 66 | 识别服务配置。`OcrMode`(:15)`{Cloud,Local}`、`class OcrSettingsStore`(:20)、`credential`（三层兜底：用户填过 → `BuildConfig.OCR_TOKEN` → 空）。落在 SharedPreferences `"ocr_vl"`，四个键：mode / base_url / model / credential，**明文** |
| `local/OcrStreamDemo.kt` | 96 | 读 `assets/stream/document.json` + 区域裁剪图，解析成 `OcrStreamPage`；`toMarkdown()` 把区域转成 Markdown 片段。`parse()` 不依赖 Context，可单测 |

### 4.3 `remote/` —— 识别服务

| 文件 | 行数 | 内容 |
|---|---|---|
| `remote/OcrVlApi.kt` | 363 | `object OcrVlApi.parse()`(:68)、`normalizeBase()`(:321)、`OPTIONAL_PAYLOAD`(:44)。云端走星河社区异步任务：`POST /api/v2/ocr/jobs` → 轮询 `GET /api/v2/ocr/jobs/{id}` → 取预签名 `jsonUrl`（**不能带 Authorization**）；本地走 PaddleX `/layout-parsing` 同步一步出结果。模型 `PaddleOCR-VL-1.6`，超时 20s/120s，轮询 1.2s、上限 150s |

### 4.4 `sample/` —— 演示数据

| 文件 | 行数 | 内容 |
|---|---|---|
| `sample/SampleRecords.kt` | 86 | `sampleRecords`：3 条假记录（数学 / 英语 / 物理），带 `tex` 公式，只在没有任何真实记录时兜底 |

## 5. 渲染层（`ui/render/`）

| 文件 | 行数 | 作用 |
|---|---|---|
| `render/OcrFormula.kt` | 111 | 单条公式。`OcrTex`(:59) 走 RaTeX（Rust 内核 + Canvas，矢量、不启 WebView），`OcrTexCache` 缓存；解析失败返回 null → 调用方降级纯文本 |
| `render/OcrMarkdownView.kt` | 124 | 整篇 Markdown。`OcrMarkdownView`(:50) 用 WebView + `assets/render.html`；`loadUrl` 只做一次（带 `#app` 跳过内置 demo），之后 `evaluateJavascript("setMarkdown(...)")`；文本必须 `JSONObject.quote` 转义；`joinMarkdown()`(:120) 拼多页 |
| `assets/render.html` | 466 行 / 89KB | 自研渲染器：`inline()` 在同一次正则扫描里切分 `![]()` 图片与 `$...$` 公式，`renderMath()` 调 KaTeX，`setMarkdown()` 是 Kotlin 侧唯一入口 |

> KaTeX 已补：`assets/katex/`（`katex.min.js` 275KB + `katex.min.css` 23KB + 20 个 woff2 字体，共 545KB）。
> 缺它时 `renderMath()` 的 try/catch 会把 `katex is not defined` 吞掉，公式退化成纯 TeX 源码（不崩溃）。

## 6. 布局适配层（`ui/layout/`）

| 文件 | 行数 | 作用 |
|---|---|---|
| `layout/OcrWindow.kt` | 67 | `rememberOcrWindowInfo()`(:54)：读方向 + 宽度 → `OcrWidthClass{Compact,Medium,Expanded}` → `OcrLayoutMode`。结果页选竖屏还是横屏布局，只由它决定 |
| `layout/OcrSplitPane.kt` | 294 | 横屏分栏。`SplitPaneState`(:76)、`rememberSplitPaneState`(:150)、`ResizableRow`(:171)、`RestoreSplitPill`(:269)。吸附档位 `[0,.25,.5,.75,1]`，拖到 0 后出现恢复胶囊 |

## 7. 屏幕层（`ui/screen/`）

| 文件 | 行数 | 作用 |
|---|---|---|
| `screen/OcrHomeScreen.kt` | 181 | `OcrHomeScreen`(:58)：两块主操作卡（拍照 / 相册）+ 最近记录 + 空状态 + 页脚 |
| `screen/OcrSettingsScreen.kt` | 267 | `OcrSettingsScreen`(:49)：识别（模式 / 地址 / 模型 / Token，Token 打码）、外观、数据、关于；`EditValueDialog` 通用编辑弹窗 |
| `screen/OcrResultScreen.kt` | 822 | `OcrResultScreen`(:103) 只做布局选择：竖屏 `StackedResult`(:118)、横屏 `SplitResult`(:143)。竖屏抽屉 `FloatingSheet`(:355) + `sheetDrag`(:471)；面板 `PanelHeader`(:495) / `PanelBody`(:536) / `ResultPanel`(:639) / `ResultLine`(:670)；两套纸面 `PaperStage`(:196)（示例，走 RaTeX）、`RealPaperStage`(:727)（真实图片） |
| `screen/OcrStreamScreen.kt` | 218 | 区域级流式渲染演示。上半原图 + 当前区域 bbox 高亮（按 `ContentScale.Fit` 换算），下半 `OcrMarkdownView` 逐字累积；按 `readingOrder` 逐区域推进，图片块整块淡入、文本块每 3 字符刷一次 |

## 8. 组件层（`ui/theme` `ui/icon` `ui/component`）

| 文件 | 行数 | 作用 |
|---|---|---|
| `theme/OcrTheme.kt` | 189 | design token：`OcrColors`（Light/Dark）、`OcrType`、`Space`、`Radii`、`LocalOcrColors`、`object Ocr`(:186)。**改配色只动这里** |
| `icon/OcrIcons.kt` | 240 | 全部图标 Canvas 手绘（`IconCamera` / `IconGallery` / `IconBack` / `IconCopy` / `IconExport` / `IconDoc` / `IconChevronRight` / `IconChevronDown` / `IconSliders` / `SparkMark`），刻意不引 material-icons-extended |
| `component/OcrComponents.kt` | 565 | 业务组件库：`CircleIconButton`(:64)、`OcrTopBar`(:85)、`ActionCard`(:129)、`PaperThumb`(:176)、`RecordCard`(:259)、`EmptyRecords`(:315)、`SectionHeader`(:339)、`SectionLabel`(:369)、`SettingCard`(:381)、`RowDivider`(:395)、`SettingRow`(:404)、`SwitchRow`(:438)、`PrimaryButton`(:474)、`SecondaryButton`(:498)、`Pill`(:522)、`GlassCircleButton`(:535)、`Dot`(:550) |

## 9. 资源层

| 文件 | 作用 |
|---|---|
| `res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` | 五套密度启动图标（棕底 + 米色文档分栏，由 `D:\android-lab\make_icon.py` 生成） |
| `res/xml/file_paths.xml` | FileProvider 只暴露 `cacheDir/camera/` 一个子目录 |
| `assets/img/parabola.png`、`triangle.png` | 示例记录里的插图 |

## 10. 运行时产物（不入库）

| 路径 | 内容 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 构建出的 APK |
| `app/build/intermediates/…` | 资源合并、dex、AAR 解包、strip 后的 `.so` |
| 设备上 `filesDir/ocr/<id>/` | `record.json` + `pN.jpg` + `pN.md` |

---

## 按"我要改什么"索引

| 需求 | 动哪个文件 |
|---|---|
| 改配色 / 字号 | `ui/theme/OcrTheme.kt` |
| 加一个图标 | `ui/icon/OcrIcons.kt` |
| 改首页布局 | `ui/screen/OcrHomeScreen.kt` |
| 改结果页分栏行为 | `ui/layout/OcrSplitPane.kt` |
| 改竖/横屏切换阈值 | `ui/layout/OcrWindow.kt` |
| 改识别接口（换模型 / 换服务） | `data/remote/OcrVlApi.kt` + `data/local/OcrSettingsStore.kt` 的默认值 |
| 改存储结构 | `data/local/OcrStore.kt` |
| 改数据结构 | `data/model/OcrModels.kt`（会牵动渲染层分流） |
| 修公式 / Markdown 渲染 | `ui/render/OcrFormula.kt`（单条）/ `OcrMarkdownView.kt` + `assets/render.html`（整篇） |
| 加页面 | `ui/OcrApp.kt` 的 `OcrRoute` + `ui/screen/` 下新增 `*Screen.kt` |
| 接端侧推理（glmocr_cpp JNI） | `data/` 下新增 `local/OcrLocalEngine.kt`，在 `ui/OcrApp.kt:134` 的 `runScan` 里分流 |
