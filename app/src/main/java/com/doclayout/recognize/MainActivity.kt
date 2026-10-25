package com.doclayout.recognize

import com.doclayout.recognize.data.local.LocalOcrEngine
import com.doclayout.recognize.ui.OcrApp
import android.content.ComponentCallbacks2
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme

/**
 * 唯一的 Activity。
 *
 * 整个应用只有三个界面（首页 / 设置 / 结果），它们共享同一份状态
 * （外观、记录列表、当前选中记录），所以走「单 Activity + 内部路由」，
 * 而不是每个页面一个 Activity。
 *
 * 所有 intent extra 只是为了能直接从 adb 落到某个界面截图，正常点击进入时都不用：
 *   adb shell am start -n com.doclayout.recognize/.MainActivity
 *   adb shell am start -n com.doclayout.recognize/.MainActivity --ei ocr 1 --ei dark 1
 *   adb shell am start -n com.doclayout.recognize/.MainActivity --ei ocr 2 --ei split 25
 *   adb shell am start -n com.doclayout.recognize/.MainActivity --es scan /data/user/0/com.doclayout.recognize/files/inbox/t.jpg
 *   adb shell am start -n com.doclayout.recognize/.MainActivity --ei ocr 4 --es scanpath /data/user/0/com.doclayout.recognize/files/inbox/t.jpg
 *
 * 注意：本 Activity 没实现 onNewIntent —— 应用已在前台时带 extra 的 am start 不生效，
 * 每次深链前先 adb shell am force-stop com.doclayout.recognize。
 *
 * scan 与 scanpath 的区别：
 *   scan     → --ei ocr 2（结果页）走云端/HTTP 的 runScan 流程（选图→OcrVlApi→落盘 ocr/）
 *   scanpath → --ei ocr 4（流水识别测试页）走端侧流水线 LocalOcrEngine.createInput+start
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 窗口延伸到系统栏后面：不调用的话 minSdk 26~34 上内容不绘入状态栏区域，
        // 浅色主题的深色状态栏底 + OcrApp 画的深色图标 = 图标看不见（API 35+ 才默认延伸）
        enableEdgeToEdge()
        // intent extra 先读进局部值、读完即清：调试深链（scan / scanpath）只在本次
        // 启动生效，不留在 Activity intent 里被后续重建反复读入。顺序必须"先读后清"——
        // setContent 的 content lambda 执行时 intent 里已经没有这些 extra 了。
        // 注意 removeExtra 只能挡"同进程 relaunch"（旋转等场景复用同一个 intent 对象）；
        // force-stop 后从最近任务重开投递的是 system_server 里存的原始 base intent，
        // 应用进程改不到它 —— 那条路靠 OcrApp 的深链消费门闩（saved state 恢复）+
        // 调试完从最近任务划掉兜底（见 OcrApp.kt / ADB-COMMANDS.txt）
        val startRoute = intent.getIntExtra("ocr", 0)
        val startDark = intent.getIntExtra("dark", 0) == 1
        val startEmpty = intent.getIntExtra("empty", 0) == 1
        val startRecord = intent.getIntExtra("record", 0)
        val startOpen = intent.getIntExtra("open", 0) == 1
        // split = 左栏占比百分比（50 = 1:1，25 = 1:3，0 = 只看文本）
        val startSplit = intent.getIntExtra("split", 50) / 100f
        val startScanPath = intent.getStringExtra("scan")
        val startPipelinePath = intent.getStringExtra("scanpath")
        intent.removeExtra("scan")
        intent.removeExtra("scanpath")
        setContent {
            MaterialTheme {
                OcrApp(
                    onExit = { finish() },
                    startRoute = startRoute,
                    startDark = startDark,
                    startEmpty = startEmpty,
                    startRecord = startRecord,
                    startOpen = startOpen,
                    startSplit = startSplit,
                    startScanPath = startScanPath,
                    startPipelinePath = startPipelinePath,
                )
            }
        }
    }

    /**
     * 内存压力转发：引擎在 data 层缓存了模型权重，低内存时必须让它主动卸载。
     * 阈值逐字对齐源项目 suishou-ocr-android 的 MainActivity：刻意**不含**
     * TRIM_MEMORY_UI_HIDDEN(20) —— 它在每次切后台都会触发，而 onMemoryPressure
     * 是无条件卸载（回来识别要整轮重新加载模型），不能拿"切了下后台"当内存告急。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            LocalOcrEngine.get(applicationContext).onMemoryPressure()
        }
    }
}
