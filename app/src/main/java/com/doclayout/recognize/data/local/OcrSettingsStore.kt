package com.doclayout.recognize.data.local

import android.content.Context
import com.doclayout.recognize.data.remote.OcrVlApi

/**
 * 识别服务配置：mode / baseUrl / model / credential 四项，落在 SharedPreferences "ocr_vl"。
 * 只有四个短字符串，用 DataStore 会多引依赖、多一层 Flow，这个规模不值。
 *
 * Token 是明文存在应用私有目录里的（别的 App 读不到，但 root / 备份能拿到）。
 * 真上生产应该走 EncryptedSharedPreferences 或干脆放到自己的服务端换临时票据。
 */

/** 识别走哪儿：云端星河社区异步接口 / 本地自建 PaddleX 服务 */
enum class OcrMode(val label: String) {
    Cloud("云端 · 星河社区"),
    Local("本地 · PaddleX"),
}

class OcrSettingsStore(context: Context) {
    private val sp = context.getSharedPreferences("ocr_vl", Context.MODE_PRIVATE)

    var mode: OcrMode
        get() = if (sp.getString("mode", "cloud") == "local") OcrMode.Local else OcrMode.Cloud
        set(v) = sp.edit().putString("mode", if (v == OcrMode.Local) "local" else "cloud").apply()

    /** 服务根地址。云端是固定域名；本地一般是 http://127.0.0.1:8080 或局域网 IP */
    var baseUrl: String
        get() = sp.getString("base_url", defaultBaseFor(mode)).orEmpty()
            .ifBlank { defaultBaseFor(mode) }
        set(v) = sp.edit().putString("base_url", v.trim()).apply()

    var model: String
        get() = sp.getString("model", OcrVlApi.MODEL).orEmpty()
        set(v) = sp.edit().putString("model", v.trim()).apply()

    var credential: String
        get() = sp.getString("credential", "").orEmpty()
        set(v) = sp.edit().putString("credential", v.trim()).apply()

    /** 云端必须有 Token；本地服务通常不鉴权，地址填了就算配好 */
    val configured: Boolean
        get() = when (mode) {
            OcrMode.Cloud -> credential.isNotBlank()
            OcrMode.Local -> baseUrl.isNotBlank()
        }

    companion object {
        fun defaultBaseFor(mode: OcrMode) = when (mode) {
            OcrMode.Cloud -> OcrVlApi.DEFAULT_BASE
            OcrMode.Local -> OcrVlApi.DEFAULT_LOCAL_BASE
        }
    }
}
