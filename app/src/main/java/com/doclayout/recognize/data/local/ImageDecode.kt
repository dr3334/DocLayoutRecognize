package com.doclayout.recognize.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一的按需采样解码工具（此前 OcrResultScreen / OcrComponents / OcrPipelineScreen /
 * OcrStreamScreen 各有一份逐字相同的私有实现，其中三处还在组合期主线程同步调用）。
 *
 * 两对函数**刻意不同名**而不是仅 suspend 之差的重载：Kotlin 禁止只差 suspend 的重载
 * （Conflicting overloads），同步版给"已在 IO 线程"的调用方，挂起版给组合层。
 *
 * 解码纪律（来源见 OcrResultScreen 旧注释）：手机拍的试卷是 4000×3000，
 * 直接 decodeFile 会吃掉 48MB 内存，连开两页就 OOM——先只读边界拿尺寸、
 * 算出 2 的幂次 inSampleSize，再真正解码。
 *
 * 本文件属 data 层：只用 android.graphics，不出现任何 Compose 类型。
 */

/** 同步采样解码一个文件；已确定在 IO/Default 线程时用这个，省一次协程调度 */
fun decodeSampledFile(path: String, maxSide: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return@runCatching null

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2

    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

/** 同步采样解码一个 assets 文件（assets 没有 File 路径，走 decodeStream） */
fun decodeSampledAssetFile(context: Context, assetPath: String, maxSide: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0) return@runCatching null

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2

    context.assets.open(assetPath).use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}.getOrNull()

/** 挂起包装：组合层统一走这里，解码落在 Dispatchers.IO */
suspend fun decodeSampled(path: String, maxSide: Int): Bitmap? =
    withContext(Dispatchers.IO) { decodeSampledFile(path, maxSide) }

/** 挂起包装（assets 版） */
suspend fun decodeSampledAsset(context: Context, assetPath: String, maxSide: Int): Bitmap? =
    withContext(Dispatchers.IO) { decodeSampledAssetFile(context, assetPath, maxSide) }
