package com.doclayout.recognize.data.local

import android.content.Context
import com.doclayout.recognize.BuildConfig
import com.doclayout.recognize.data.remote.OcrVlApi

/**
 * 识别服务配置：mode / baseUrl / model / credential 四项，落在 SharedPreferences "ocr_vl"。
 * 只有四个短字符串，用 DataStore 会多引依赖、多一层 Flow，这个规模不值。
 *
 * Token 是明文存在应用私有目录里的（别的 App 读不到，但 root / 备份能拿到）。
 * 真上生产应该走 EncryptedSharedPreferences 或干脆放到自己的服务端换临时票据。
 */

/** 识别走哪儿：云端星河社区异步接口 / 本地自建 PaddleX 服务 / 端侧本地流水线（MNN） */
enum class OcrMode(val label: String) {
    Cloud("云端 · 星河社区"),
    Local("本地 · PaddleX"),
    OnDevice("端侧 · 本地流水线"),
}

class OcrSettingsStore(context: Context) {
    private val sp = context.getSharedPreferences("ocr_vl", Context.MODE_PRIVATE)

    /** 引擎门面只在这几处同步读取时才可能被首次初始化（懒加载单例） */
    private val appContext = context.applicationContext

    var mode: OcrMode
        get() = when (sp.getString("mode", "cloud")) {
            "local" -> OcrMode.Local
            "device" -> OcrMode.OnDevice
            else -> OcrMode.Cloud
        }
        set(v) = sp.edit().putString(
            "mode",
            when (v) {
                OcrMode.Cloud -> "cloud"
                OcrMode.Local -> "local"
                OcrMode.OnDevice -> "device"
            },
        ).apply()

    /** 服务根地址。云端是固定域名；本地一般是 http://127.0.0.1:8080 或局域网 IP */
    var baseUrl: String
        get() = sp.getString("base_url", defaultBaseFor(mode)).orEmpty()
            .ifBlank { defaultBaseFor(mode) }
        set(v) = sp.edit().putString("base_url", v.trim()).apply()

    var model: String
        get() = sp.getString("model", OcrVlApi.MODEL).orEmpty()
        set(v) = sp.edit().putString("model", v.trim()).apply()

    /**
     * Access Token。
     *
     * 三层兜底：用户填过的值 → 构建期注入的 [BuildConfig.OCR_TOKEN]（来自 local.properties，
     * 不进仓库）→ 空。用 `sp.contains` 而不是 `ifBlank` 判断，是为了让"主动清空"生效：
     * 用户把输入框清掉写回 "" 之后，不应该又冒出预置值。
     */
    var credential: String
        get() = if (sp.contains("credential")) {
            sp.getString("credential", "").orEmpty()
        } else {
            BuildConfig.OCR_TOKEN
        }
        set(v) = sp.edit().putString("credential", v.trim()).apply()

    /**
     * 云端必须有 Token；本地服务通常不鉴权，地址填了就算配好；
     * 端侧没有地址/Token 的概念，"配好"= **模型文件就绪**（经 [LocalOcrEngine] 查引擎
     * readiness 快照的 files-ready）。
     */
    val configured: Boolean
        get() = when (mode) {
            OcrMode.Cloud -> credential.isNotBlank()
            OcrMode.Local -> baseUrl.isNotBlank()
            // RecognitionController.readiness() 是纯内存快照（无磁盘 IO），同步读不卡主线程；
            // 引擎尚未初始化/快照解析失败按"未就绪"处理。
            OcrMode.OnDevice -> runCatching {
                LocalOcrEngine.get(appContext).readinessSnapshot().filesReady
            }.getOrDefault(false)
        }

    companion object {
        fun defaultBaseFor(mode: OcrMode) = when (mode) {
            OcrMode.Cloud -> OcrVlApi.DEFAULT_BASE
            OcrMode.Local -> OcrVlApi.DEFAULT_LOCAL_BASE
            // 端侧识别完全不经过 HTTP 服务，没有"服务地址"的概念，给空串；
            // OcrApp.onModeChange 对端侧会跳过默认地址写入（无地址可跟）
            OcrMode.OnDevice -> ""
        }
    }
}
