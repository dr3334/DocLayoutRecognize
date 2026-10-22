# 文件职责清单（按层分类）

> 生成于 2026-10-22 · 对应提交 `7d41147`
> 只列源码与工程配置；`build/`、`app/build/`、`.gradle/`、`.idea/`、`.kotlin/` 全是生成物，已 git 忽略。

## 分层总览

```
┌─ 入口层 ────────── MainActivity.kt · AndroidManifest.xml
├─ 编排层 ────────── OcrApp.kt（路由 + 状态 + 业务流程）
├─ 数据层 ────────── OcrData.kt（模型）
│                    OcrStore.kt（本地持久化 + 配置）
│                    OcrVlApi.kt（远程识别服务）
├─ 渲染层 ────────── OcrFormula.kt · OcrMarkdownView.kt ← assets/render.html
├─ 布局层 ────────── OcrWindow.kt（断点判定）· OcrSplitPane.kt（可拖分栏）
├─ 屏幕层 ────────── OcrHomeScreen.kt · OcrSettingsScreen.kt · OcrResultScreen.kt
├─ 组件层 ────────── OcrComponents.kt（业务组件）
│                    OcrIcons.kt（图标）· OcrTheme.kt（design token）
└─ 资源 / 构建 ───── res/ · assets/ · *.gradle · gradle/wrapper/
```

依赖方向单向向下：`入口 → 编排 → 数据 / 屏幕 → 组件 / 主题`。数据层不反向依赖 UI。

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
| `gradle/wrapper/gradle-wrapper.jar` | wrapper 本体，**必须入库**，否则别人 clone 后跑不起来 | 一般不动 |
| `local.properties` | 只有 `sdk.dir`，机器相关 → **已忽略** | clone 后自己建 |
| `.gitignore` | 排除生成物、`.idea`、`local.properties`、`build_out.txt` | 新增产物类型时 |
| `.gitattributes` | 源码统一 LF，`*.bat` 用 CRLF，`*.jar/*.png` 标二进制 | 一般不动 |
| `README.md` | 现状、构建/安装命令、adb 直达参数、存储结构 | 流程变化时 |
| `build_out.txt` | 上次 `assembleDebug` + `adb install` 的控制台输出（BUILD SUCCESSFUL in 34s）→ 已忽略 | 不看 |

## 2. 入口层

| 文件 | 作用 |
|---|---|
| `app/src/main/AndroidManifest.xml` | INTERNET 权限；`<queries>` 声明 `IMAGE_CAPTURE`（Android 11+ 包可见性，否则调相机 resolve 为 null）；`usesCleartextTraffic="true"` 放行本地 http；`configChanges="orientation\|screenSize\|keyboardHidden"` 让旋转不重建 Activity（分栏比例才保得住）；FileProvider 注册 |
| `MainActivity.kt`（40 行） | 唯一 Activity。`setContent { MaterialTheme { OcrApp(...) } }`，把 intent extra（`ocr/dark/empty/record/open/split/scan`）全部透传给 OcrApp。这些 extra 只服务于 adb 直达截图 |

## 3. 编排层

| 文件 | 作用 |
|---|---|
| `OcrApp.kt`（397 行） | 事实上的 ViewModel + Router。① `OcrRoute{Home,Settings,Result}` 内部路由；② 跨页状态（记录列表、当前索引、深色、扫描状态）用 `remember/rememberSaveable` 持有；③ 业务流程：选图 / 拍照 / 扫描 → 调 `OcrVlApi.parse()` → 调 `OcrStore.save()`；④ `ScanState{Idle,Running,Failed}` + `ScanOverlay`；⑤ 多图合并的 `MergeChoiceDialog` |

## 4. 数据层

拆成三个文件，各自只回答一个问题：**数据结构长什么样 / 存在哪 / 从哪来**。

| 文件 | 子职责 | 作用 |
|---|---|---|
| `OcrData.kt`（124 行） | 模型 | `OcrLineKind{Heading,Body,Formula,Answer}`、`OcrLine`、`OcrScanPage`、`OcrRecord`、`sampleRecords`（首页无真实记录时的兜底数据） |
| `OcrStore.kt`（207 行） | 本地持久化 + 配置 | `OcrMode{Cloud,Local}`；`OcrSettingsStore` = SharedPreferences `ocr_vl`（mode/baseUrl/model/token，明文，生产应换 EncryptedSharedPreferences）；`OcrStore` = `filesDir/ocr/<id>/` 目录，内含 `record.json` + `pN.jpg` + `pN.md`，删记录即删目录 |
| `OcrVlApi.kt`（339 行） | 远程服务 | `object OcrVlApi.parse(mode, baseUrl, token, image, model)`。云端走星河社区**异步任务**：`POST /api/v2/ocr/jobs` → 轮询 `GET /api/v2/ocr/jobs/{id}` → 取预签名 `jsonUrl`（**不能带 Authorization**）；本地走 PaddleX `/layout-parsing` 同步一步出结果。模型 `PaddleOCR-VL-1.6`，超时 20s/120s，轮询间隔 1.2s、上限 150s |

## 5. 渲染层（富文本 / 公式）

| 文件 | 作用 |
|---|---|
| `OcrFormula.kt`（110 行） | 单条公式渲染。`OcrTex` 走 RaTeX（Rust 内核 + Canvas，矢量、不启 WebView），`OcrTexCache` 缓存渲染结果；解析失败返回 null → 调用方降级显示纯文本 |
| `OcrMarkdownView.kt`（121 行） | 整篇 Markdown 渲染。WebView + `assets/render.html`；`loadUrl` 只做一次（带 `#app` 跳过内置 demo），之后一律 `evaluateJavascript("setMarkdown(...)")`；文本必须 `JSONObject.quote` 转义；`HeightBridge` 收内容高度；`joinMarkdown()` 拼多页（页间加 `---`） |
| `app/src/main/assets/render.html`（466 行 / 89KB） | 自研渲染器。`inline()` 在同一次正则扫描里切分 `![]()` 图片和 `$...$` 公式；`renderMath()` 调 KaTeX；`setMarkdown()` 是 Kotlin 侧唯一入口；CSS 里 `-webkit-user-select:none` 是为了压制 Chromium 长按系统菜单 |

> ⚠️ 该文件第 6、70 行引 `katex/katex.min.css` 与 `katex/katex.min.js`，但 `assets/` 下没有 `katex/` 目录 → KaTeX 未定义，公式会被 `renderMath` 的 try/catch 吞掉并退化成纯 TeX 源码。补 KaTeX 离线资源即可修好。

## 6. 布局适配层

| 文件 | 作用 |
|---|---|
| `OcrWindow.kt`（67 行） | `rememberOcrWindowInfo()`：读方向 + 宽度，映射成 `OcrWidthClass{Compact,Medium,Expanded}` → `OcrLayoutMode`。结果页选竖屏还是横屏布局，只由它决定 |
| `OcrSplitPane.kt`（290 行） | 横屏分栏。`SplitPaneState` + 吸附档位 `DefaultSplitAnchors = [0, .25, .5, .75, 1]`、`ResizableRow`、`SplitDivider`、`RestoreSplitPill`（左栏拖到 0 后的恢复胶囊） |

## 7. 屏幕层

| 文件 | 作用 |
|---|---|
| `OcrHomeScreen.kt`（167 行） | 首页：两块主操作卡（拍照 / 相册）+ 最近记录列表 + `EmptyRecords` 空状态 + 页脚 |
| `OcrSettingsScreen.kt`（257 行） | 设置四段：识别（模式 / 地址 / 模型 / Token，Token 打码 `maskToken`）、外观（深色）、数据（清空）、关于。`EditValueDialog` 为通用文本编辑弹窗 |
| `OcrResultScreen.kt`（751 行，最大文件） | 只做布局选择 + 两套布局。竖屏 `StackedResult` + 可拖 `FloatingSheet`（`sheetDrag`）；横屏 `SplitResult` + `ResizableRow`。`PaperStage` 渲染示例数据的纸面排版（配 `OcrTex`），`RealPaperStage` 渲染真实原图，`PanelBody` 按 `record.isReal` 分流到 `OcrMarkdownView` |

## 8. 组件层

| 文件 | 作用 |
|---|---|
| `OcrTheme.kt`（189 行） | design token：`OcrColors`（Light/Dark 两个 data class）、`OcrType`（字号字重）、`Space`、`Radii`、`LocalOcrColors`、`Ocr` 单例入口。改配色只动这里 |
| `OcrIcons.kt`（240 行） | 全部图标用 `Canvas` 手绘（`IconCamera`/`IconGallery`/`IconCopy`/`IconExport`/`IconBack`/`SparkMark`…），刻意不引 `material-icons-extended` 以省体积 |
| `OcrComponents.kt`（556 行） | 业务组件库：`OcrTopBar`、`ActionCard`、`PaperThumb`（缩略图 + `decodeThumb` 采样解码防 OOM）、`RecordCard`、`EmptyRecords`、`SectionHeader/Label`、`SettingCard/SettingRow/SwitchRow/RowDivider`、`PrimaryButton/SecondaryButton`、`Pill`、`GlassCircleButton`、`Dot` |

## 9. 资源层

| 文件 | 作用 |
|---|---|
| `res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` | 五套密度启动图标（棕底 + 米色文档分栏图形，由 `D:\android-lab\make_icon.py` 生成） |
| `res/xml/file_paths.xml` | FileProvider 只暴露 `cacheDir/camera/` 一个子目录，相机拍完写这里，别的 App 拿不到其它东西 |
| `assets/img/parabola.png`、`triangle.png` | 示例记录（`sampleRecords`）里引用的插图 |

> 注：`README.md` 第 106 行提到 `ic_launcher_round.png`，实际仓库里没有这个文件，文档与实物不符。

## 10. 运行时产物（不入库）

| 路径 | 内容 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 构建出的 APK |
| `app/build/intermediates/…` | 资源合并、dex、AAR 解包、strip 后的 `.so` 等中间产物 |
| `filesDir/ocr/<id>/`（设备上，非仓库） | 运行时记录：`record.json` + `pN.jpg` + `pN.md` |

---

## 按"我要改什么"索引

| 需求 | 动哪个文件 |
|---|---|
| 改配色 / 字号 | `OcrTheme.kt` |
| 加一个图标 | `OcrIcons.kt` |
| 改首页布局 | `OcrHomeScreen.kt` |
| 改结果页分栏行为 | `OcrSplitPane.kt` |
| 改竖/横屏切换阈值 | `OcrWindow.kt` |
| 改识别接口（换模型 / 换服务） | `OcrVlApi.kt` + `OcrStore.kt` 的默认值 |
| 改存储结构 | `OcrStore.kt` |
| 修公式 / Markdown 渲染 | `OcrFormula.kt`（单条）/ `OcrMarkdownView.kt` + `render.html`（整篇） |
| 加页面 | `OcrApp.kt` 的 `OcrRoute` + 新 `*Screen.kt` |
| 接端侧推理（glmocr_cpp JNI） | 新增 `OcrEngine.kt` 替换 `OcrVlApi` 的调用点，`OcrApp.kt` 里改分流 |
