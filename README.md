# DocLayout Recognize

试卷 / 文档识别 App。界面层从 `D:\android-lab\LearnAndroid` 的 `ocr/` 包整体拆出来，
现在是一个独立工程，可以单独改、单独跑。

## 现在是什么状态

**界面 + 业务流程是完整的，识别引擎走的是云端或本地 HTTP 服务。**

```
选图 / 拍照 → OcrVlApi.parse() → 落盘（filesDir/ocr/<id>/） → 结果页渲染 Markdown
```

两种接入方式（设置页里切）：

| 模式 | 地址 | 鉴权 |
|---|---|---|
| 云端 · 星河社区 | `https://paddleocr.aistudio-app.com`（固定） | Access Token |
| 本地 · PaddleX | `http://<IP>:8080` | 一般不需要 |

**还没做的**：把 C++ 的 `glmocr_cpp` 流水线通过 JNI 接进来，做成端侧推理。

## 目录

包结构按「表现层 / 数据层」分家，依赖单向：`ui → data`，data 里不出现任何 Compose 类型。

```
app/src/main/java/com/doclayout/recognize/
├── MainActivity.kt                    唯一 Activity，只负责挂载 OcrApp
│
├── ui/                                表现层：只画界面，不做 IO
│   ├── OcrApp.kt                      路由 + 跨页面状态 + 选图/拍照/扫描流程
│   ├── theme/OcrTheme.kt              颜色令牌 + 排版 + 间距（浅/深两套）
│   ├── icon/OcrIcons.kt               Canvas 手绘线描图标（不引 material-icons-extended）
│   ├── component/OcrComponents.kt     顶栏 / 操作卡 / 缩略图 / 按钮 / 胶囊
│   ├── layout/OcrWindow.kt            布局判定（方向 + 宽度断点）
│   ├── layout/OcrSplitPane.kt         可拖分割线 + 吸附档位
│   ├── render/OcrFormula.kt           公式渲染（RaTeX，矢量，不用 WebView）
│   ├── render/OcrMarkdownView.kt      Markdown 渲染（WebView + assets/render.html）
│   ├── screen/OcrHomeScreen.kt        首页：两块主操作卡 + 最近记录 + 空状态
│   ├── screen/OcrSettingsScreen.kt    设置：识别 / 外观 / 数据 / 关于
│   └── screen/OcrResultScreen.kt      结果页：只做布局选择 + 两套布局
│
└── data/                              数据层：不认识 Compose，可单独测
    ├── model/OcrModels.kt             模型（OcrLine / OcrScanPage / OcrRecord）
    ├── local/OcrStore.kt              记录落盘（一条记录一个目录）
    ├── local/OcrSettingsStore.kt      识别服务配置（SharedPreferences "ocr_vl"）
    ├── remote/OcrVlApi.kt             识别服务调用（云端异步 / 本地同步）
    └── sample/SampleRecords.kt        内置示例数据（无真实记录时兜底）
```

包与目录一一对应，所以看 `import` 就知道谁在跨层。唯一的例外是 `ui/OcrApp.kt`——
它既做路由又做流程编排，是当前最大的架构债，要加功能前先考虑拆 ViewModel。

资产：

```
app/src/main/assets/render.html    WebView + KaTeX，渲染整篇 Markdown
app/src/main/assets/img/           示例插图
```

## 构建

```powershell
$env:JAVA_HOME="D:\Android_Studio\jbr"
cd D:\android-lab\DocLayoutRecognize
.\gradlew.bat assembleDebug --no-daemon
```

必须 `--no-daemon`：本机内存紧，双 JVM 会把系统打爆（详见 `gradle.properties` 里的注释）。

## 安装

```powershell
C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

## 直达某个状态（不点按钮也能截图）

```powershell
$adb = "C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe"

# 首页
& $adb shell am start -n com.doclayout.recognize/.MainActivity

# 空状态 / 设置页 / 深色设置
& $adb shell am start -n com.doclayout.recognize/.MainActivity --ei empty 1
& $adb shell am start -n com.doclayout.recognize/.MainActivity --ei ocr 1
& $adb shell am start -n com.doclayout.recognize/.MainActivity --ei ocr 1 --ei dark 1

# 结果页（竖屏抽屉 / 抽屉展开 / 横屏分栏比例）
& $adb shell am start -n com.doclayout.recognize/.MainActivity --ei ocr 2
& $adb shell am start -n com.doclayout.recognize/.MainActivity --ei ocr 2 --ei open 1
& $adb shell am start -n com.doclayout.recognize/.MainActivity --ei ocr 2 --ei split 25

# 跳过选图，直接扫一张本应用能读到的图
& $adb shell am start -n com.doclayout.recognize/.MainActivity --es scan /data/user/0/com.doclayout.recognize/files/inbox/t.jpg
```

参数：`ocr` 0=首页 1=设置 2=结果页；`dark` 深色；`empty` 无记录空状态；
`record` 第几条记录；`open` 竖屏抽屉默认展开；`split` 横屏左栏占比百分比（50=1:1、25=1:3、0=只看文本）。

## 本地 PaddleX 注意事项

1. 本地是 `http`，Android 9+ 默认禁明文 → Manifest 已开 `usesCleartextTraffic="true"`。
   生产环境应换 `network-security-config` 只放行内网。
2. USB 调试时手机连不到电脑的 `127.0.0.1`，先做端口映射：

   ```
   adb reverse tcp:8080 tcp:8080
   ```

3. 本地 PaddleX 的 `markdown.images` 默认是 base64（云端是预签名 URL）。

## 图标

`res/mipmap-*/ic_launcher.png` + `ic_launcher_round.png`，来自设计稿
（棕色底 + 米色文档分栏图形）。重新生成见 `D:\android-lab\make_icon.py`。

## 存储结构

```
filesDir/ocr/<id>/record.json   元信息（标题 / 时间 / 引擎 / 耗时 / 页列表）
                /p1.jpg          原图，页序 = 文件名序
                /p1.md           该页 Markdown
```

删记录 = 删目录。
