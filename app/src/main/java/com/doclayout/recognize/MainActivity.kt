package com.doclayout.recognize

import com.doclayout.recognize.ui.OcrApp
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                OcrApp(
                    onExit = { finish() },
                    startRoute = intent.getIntExtra("ocr", 0),
                    startDark = intent.getIntExtra("dark", 0) == 1,
                    startEmpty = intent.getIntExtra("empty", 0) == 1,
                    startRecord = intent.getIntExtra("record", 0),
                    startOpen = intent.getIntExtra("open", 0) == 1,
                    // split = 左栏占比百分比（50 = 1:1，25 = 1:3，0 = 只看文本）
                    startSplit = intent.getIntExtra("split", 50) / 100f,
                    startScanPath = intent.getStringExtra("scan"),
                )
            }
        }
    }
}
