# LEARNING.md · 用这个仓库学 Jetpack Compose

这份文档不是功能说明（那个看 `README.md`），而是**按知识点组织的阅读地图**。
每个条目都指向真实文件，读完能回答「为什么这么写、不这么写会怎样」。

---

## 0. 先看结构，再看细节

```
MainActivity (41 行)          入口，只做 setContent
  └── OcrApp (~380 行)        状态 + 路由 + 一条业务流程  ← 全项目最该精读的文件
        ├── OcrHomeScreen     首页：操作卡 + 记录
        ├── OcrSettingsScreen 设置：整页形态（也当右侧面板的内容用）
        ├── OcrResultScreen   结果：竖屏抽屉 / 横屏分栏
        └── OcrStreamScreen   流式渲染演示
              └── OcrMarkdownView → assets/render.html → KaTeX
```

依赖方向只有一条：`MainActivity → ui → data`。
`data/` 不认识 Compose，`ui/` 不碰 `org.json` / `java.net`。
验证方法：`grep -rn "androidx.compose" data/` 应该搜不到。

## 阅读顺序（按依赖，不按文件夹）

| 顺序 | 文件 | 先想一个问题 |
|---|---|---|
| 1 | `data/model/OcrModels.kt` | 一个"识别记录"到底要存几个字段？ |
| 2 | `data/local/OcrStore.kt` | 记录存在哪、为什么一个目录一条 |
| 3 | `ui/theme/OcrTheme.kt` | 颜色/字号为什么要做成常量而不是散在界面里 |
| 4 | `ui/component/OcrComponents.kt` | 一个 `SettingRow` 为什么值得抽出来 |
| 5 | `ui/screen/OcrHomeScreen.kt` | 界面拿到的数据从哪来 |
| 6 | **`ui/OcrApp.kt`** | 状态放哪、路由怎么切、业务怎么串 |
| 7 | `ui/screen/OcrResultScreen.kt` | 同一份数据怎么长成两套布局 |
| 8 | `data/remote/OcrVlApi.kt` | 手写 multipart 时长度为什么必须自己算 |
| 9 | `ui/render/OcrMarkdownView.kt` | Compose 怎么和 WebView 通信 |
| 10 | `ui/screen/OcrStreamScreen.kt` | "流式"到底流的是什么 |

---

## 一、Compose 的四个核心概念，在这个项目里的位置

### 1. State 与重组

**知识点**：Compose 只跟踪 `MutableState` 的变化。读 SharedPreferences **不会**触发重组。

| 现象 | 代码位置 | 教训 |
|---|---|---|
| 改了设置界面不动 | `OcrApp.kt` 的 `mode/baseUrl/credential` 用 `mutableStateOf` 镜像 | 持久层不是 State |
| 改完还重启才生效 | 同上，写入时**双写**（State + SP） | 只写一边必出问题 |
| 转屏后面板还开着 | `settingsOpen` 用 `rememberSaveable` | `remember` 扛不住重建 |

读法：找 `by remember` / `by rememberSaveable` / `mutableStateOf`，问一句"这个值变化时谁会重组"。

### 2. Modifier 的顺序和职责

**知识点**：`Modifier` 是链式的，顺序决定行为的嵌套关系；`fillMaxSize` 表示"占满父给的约束"。

| 写法 | 含义 | 出现在 |
|---|---|---|
| `fillMaxSize()` | 吃掉父容器的全部约束 | 整页 / 主界面 |
| `fillMaxWidth()` | 只吃宽度，高度按内容 | 面板内容 |
| `padding` vs `offset` vs `graphicsLayer` | 分别改**布局内边距** / **绘制偏移** / **图层变换** | 见下 |

**性能分水岭**：
- `width/height/padding` 变化 → 重跑 Layout（贵）
- `graphicsLayer { translationX }` 变化 → 只重绘图层（便宜）

`OcrApp.kt` 的面板动画就是这条规则的实例：一开始用 `animateDpAsState` 改宽度（每帧重布局 30 个设置行），后来改成固定宽度 + `translationX`。

### 3. 作用域（Scope）

**知识点**：`Modifier.weight` 只在 `RowScope`/`ColumnScope` 里能用；`matchParentSize` 只在 `BoxScope` 里能用。**它们不能 import**（import 会指向 internal 符号，编译报 `Cannot access ... it is internal`）。

项目里的实例：`OcrSettingsScreen` 里 `OcrSettingsContent(modifier = Modifier.weight(1f))` —— 这个 `weight` 属于外层 `Column` 的作用域。

### 4. 声明式 vs 命令式

| 命令式（不要） | 声明式（本项目） |
|---|---|
| `adapter.notifyDataSetChanged()` | `scanned = store.list()` |
| `textView.text = ...` | `Text(shown)` |

`OcrStreamScreen` 是最好例子：它只做一件事 —— 不断更新 `typing` 这个 String，界面自己跟着变。

---

## 二、三个"最容易误解"的设计

### 1. 为什么 `reading_order` 和 `block_order` 不一样

`OcrStreamModels.kt` 的 `ordered`：

```kotlin
val ordered get() = readingOrder.mapNotNull { byId[it] }
```

`block_order` 是模型输出的 rank（可能有空值、可能重排），`reading_order` 才是**最终阅读顺序**（人工/规则校正过）。
按 `block_order` 排会把标题放到正文后面。**永远以 `reading_order` 为准**，缺 id 时才退回 `block_order`。

动手实验：把 `ordered` 改成 `blocks.sortedBy { it.order }`，跑流式页，看顺序怎么错。

### 2. 公式怎么能"边打边渲染"

不是本项目实现的，是 `assets/render.html` 里两条机制：

1. `parseBlocks` 把没闭合的 `$$...` 标成 `closed: false`
2. `balance(tex)` 把半截命令补齐（`\fra` 砍成 `\f`，`{` 补 `}`）

所以 `$-\frac{y^{2}` 这种半截状态也能先渲一版。
**如果换成"必须完整 LaTeX 才渲染"的库，流式就只能显示源码。**

### 3. 为什么文本每 3 个字符才刷一次

`OcrStreamScreen.kt`：

```kotlin
cut += 3
delay(26)
```

每次 `typing` 变化都会 `evaluateJavascript(setMarkdown)`。逐字符是 80+ 次/秒的跨进程调用，WebView 会掉帧。
3 字符 ≈ 30 次/秒，视觉上是正常打字机速度。

---

## 三、数据流全景（一次扫描）

```
ActionCard.onClick                      ui/screen/OcrHomeScreen.kt
  → launchCamera / launchPicker         ui/OcrApp.kt
  → runScan(uris, merge)                ui/OcrApp.kt     suspend，一张张串行
  → OcrVlApi.parse()                    data/remote/OcrVlApi.kt
      云端：POST /api/v2/ocr/jobs → 轮询 → 取 markdown
      本地：POST /layout-parsing → 一次性返回
  → OcrStore.save()                     data/local/OcrStore.kt
      filesDir/ocr/<id>/ meta.json + page-*.jpg
  → scanned = store.list()              ui/OcrApp.kt      ← State 变，界面重组
  → OcrResultScreen                     ui/screen/OcrResultScreen.kt
```

整条链路只有 `parse` 和 `save` 两步离开 `ui` 层。**换识别引擎只需要替换 `parse`。**

---

## 四、改代码前必须知道的四件事

| 坑 | 现象 | 原因 |
|---|---|---|
| 改设置不生效 | 界面不动 / 重启丢失 | 漏了 State 或漏了 SP，见上文"双写" |
| `OcrRoute` 加一个枚举值 | 编译过但界面不变 | 忘了在 `when (route)` 里加分支（Kotlin 不强制） |
| 在 `ui/` 里 `import org.json` | 能跑，但分层破了 | 解析属于 `data`，界面不该知道 JSON 长什么样 |
| 面板里加输入框 | 键盘弹起布局乱 | `ModalBottomSheet`/自建面板对 IME 的 inset 处理不同；本项目的编辑走 `AlertDialog` 就是为了绕开它 |

---

## 五、可以动手的实验（每个都能验证一个概念）

1. **验证重组**：把 `OcrApp` 里 `mode` 的 `remember` 改成直接读 `settings.mode`，改设置 → 界面不动。改回来。
2. **验证 Modifier**：把 `OcrSettingsContent` 的 `fillMaxWidth()` 改成 `fillMaxSize()`，塞进面板 → 面板撑满、拖不动。
3. **验证作用域**：删掉 `weight(1f)` 传 `Modifier`，`OcrSettingsContent` 会失去高度约束，列表不滚。
4. **验证绘制 vs 布局**：把面板动画从 `graphicsLayer` 改回 `animateDpAsState(width)`，用 `adb shell dumpsys gfxinfo` 对比帧数（本机差距不明显，低端机会）。
5. **验证 reading_order**：改 `ordered`，看流式顺序乱掉。

---

## 六、这套代码的取舍（不是"最佳实践"，是当时的选择）

| 选择 | 好处 | 代价 |
|---|---|---|
| 不用 Navigation 库 | 三个页面 + 共享状态，一个 `enum + when` 更好读 | 页面上到 8 个就该换 Navigation |
| 手写 `HttpURLConnection` | 无依赖、请求可完全控制 | multipart 长度要自己算（`OcrVlApi.multipartLength`） |
| WebView 渲 Markdown + KaTeX | 表格/公式/图片全交给成熟解析器 | 多一层 JS 桥，字体要自己打包（`assets/katex/`） |
| 记录存文件系统不用数据库 | 一条记录 = 一个目录，导出/删除都是文件操作 | 没有查询能力，列表全量读 |
| 设置存 SharedPreferences | 简单、同步 | 没有类型安全，字段名是字符串 |
