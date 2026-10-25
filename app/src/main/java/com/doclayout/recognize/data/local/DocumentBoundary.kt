package com.doclayout.recognize.data.local

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 纸张四角检测 + 透视校正（data 层，无 Compose）。
 *
 * 1. 属于什么文件
 *    - `data/local/DocumentBoundary.kt`。算法逐行对译自 suishou-ocr-android 的
 *      `cn.local.ocr.DocumentBoundaryDetector.java` / `CropGeometry.java`（Java→Kotlin），
 *      原注释一并保留；两项目行为一致，方便对照学习。
 * 2. 用了什么组件
 *    - 官方 OpenCV Android SDK（`org.opencv:opencv:4.12.0`，Maven Central，
 *      Java API、Kotlin 直接调用）：Canny/OTSU → findContours → approxPolyDP →
 *      getPerspectiveTransform + warpPerspective。
 * 3. 职责与边界
 *    - detect 只分析**缩小后的灰度图**（调用方负责降采样），返回归一化四角
 *      [左上, 右上, 右下, 左下]；找不到合格四边形返回 null（UI 回退到人工拖四角）。
 *    - warp 把全分辨率图按四角透视校正为矩形；输出尺寸由四边长度推导且不放大超过原图。
 *    - 只做平面单应变换；纸面**卷曲**要靠 UVDoc 矫正（后续阶段），本类不涉及。
 */

/** 归一化四角几何：面积（鞋带公式）/ 合法性校验 / 校正输出尺寸推导。独立于 Android 位图 API。 */
object CropGeometry {
    /** 全图四角（未裁剪的默认值） */
    fun full(): Array<DoubleArray> = arrayOf(
        doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 0.0),
        doubleArrayOf(1.0, 1.0), doubleArrayOf(0.0, 1.0),
    )

    /** 鞋带公式面积（归一化坐标系） */
    fun area(p: Array<DoubleArray>): Double {
        var sum = 0.0
        for (i in 0 until 4) sum += p[i][0] * p[(i + 1) % 4][1] - p[(i + 1) % 4][0] * p[i][1]
        return sum / 2
    }

    /** 校验：四点在 [0,1] 内、不自交折叠、面积不过小。不合法抛 [IllegalArgumentException]。 */
    fun validate(p: Array<DoubleArray>?) {
        if (p == null || p.size != 4) throw IllegalArgumentException("需要四个文档边角")
        for (point in p) {
            if (point.size != 2 || !point[0].isFinite() || !point[1].isFinite() ||
                point[0] < 0 || point[0] > 1 || point[1] < 0 || point[1] > 1
            ) throw IllegalArgumentException("裁剪边角必须位于图片内")
        }
        for (i in 0 until 4) {
            val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
            if ((b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]) <= 1e-6) {
                throw IllegalArgumentException("裁剪框不能交叉或折叠")
            }
        }
        if (abs(area(p)) < .005) throw IllegalArgumentException("裁剪区域过小，请扩大选框")
    }

    /** 校正输出尺寸：四边长最大值推导，且不放大超过原图像素（避免无谓变胖） */
    fun dimensions(p: Array<DoubleArray>, width: Int, height: Int): IntArray {
        validate(p)
        if (width < 2 || height < 2) throw IllegalArgumentException("图片尺寸无效")
        val top = distance(p[0], p[1], width, height)
        val bottom = distance(p[3], p[2], width, height)
        val left = distance(p[0], p[3], width, height)
        val right = distance(p[1], p[2], width, height)
        val w = max(top, bottom).roundToInt() + 1
        val h = max(left, right).roundToInt() + 1
        if (w < 16 || h < 16) throw IllegalArgumentException("裁剪区域过窄，请扩大选框")
        val scale = min(1.0, sqrt(width.toDouble() * height / (w.toDouble() * h)))
        return intArrayOf(
            max(16, (w * scale).roundToInt()),
            max(16, (h * scale).roundToInt()),
        )
    }

    private fun distance(a: DoubleArray, b: DoubleArray, w: Int, h: Int): Double =
        hypot((a[0] - b[0]) * (w - 1), (a[1] - b[1]) * (h - 1))
}

/** 四角检测（Canny/OTSU 双通道轮廓法）与透视校正（warpPerspective）。 */
object DocumentBoundary {

    /** OpenCV 本地库是否已加载（App 进程内幂等加载一次） */
    @Volatile
    private var inited = false

    /** 加载 OpenCV 本地库；重复调用安全。UI 层用位图前先确保它成功。 */
    fun ensureOpenCv(): Boolean {
        if (inited) return true
        inited = try {
            System.loadLibrary("opencv_java4")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
        return inited
    }

    /**
     * 在灰度图上找文档四边形。返回归一化四角 [左上, 右上, 右下, 左下]；找不到返回 null。
     * 双通道策略（与 suishou 一致）：pass0 Canny 边缘闭合，pass1 OTSU 整体二值——
     * 白纸深背景走 Canny，纸张与背景同亮但纹理强时靠 OTSU 补位。
     * 只分析缩小后的图（调用方降采样，最长边 ≤960 即可）。
     */
    fun detect(gray: Mat): Array<DoubleArray>? {
        val blurred = Mat(); val edges = Mat(); val binary = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val contours = ArrayList<MatOfPoint>(); val hierarchy = Mat()
        var best: Array<DoubleArray>? = null
        var bestArea = 0.0
        val canvas = gray.cols() * gray.rows().toDouble()
        try {
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            for (pass in 0 until 2) {
                if (pass == 0) {
                    Imgproc.Canny(blurred, edges, 50.0, 150.0)
                    Imgproc.morphologyEx(edges, binary, Imgproc.MORPH_CLOSE, kernel)
                } else {
                    Imgproc.threshold(blurred, binary, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                }
                Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                for (contour in contours) {
                    val contourArea = abs(Imgproc.contourArea(contour))
                    // 轮廓须占画布 12%~98.5%：太小是杂物，太大基本是画框本身
                    if (contourArea < canvas * .12 || contourArea > canvas * .985 || contourArea < bestArea) continue
                    val curve = MatOfPoint2f(*contour.toArray()); val approx = MatOfPoint2f()
                    try {
                        Imgproc.approxPolyDP(curve, approx, Imgproc.arcLength(curve, true) * .02, true)
                        val corners = approx.toArray()
                        if (corners.size != 4) continue
                        // 质心极角排序定环形顺序，再以 x+y 最小锚定左上
                        var cx = 0.0; var cy = 0.0
                        corners.forEach { cx += it.x / 4; cy += it.y / 4 }
                        corners.sortBy { atan2(it.y - cy, it.x - cx) }
                        var first = 0
                        for (i in 1 until 4) if (corners[i].x + corners[i].y < corners[first].x + corners[first].y) first = i
                        val p = Array(4) { i ->
                            val c = corners[(first + i) % 4]
                            doubleArrayOf(c.x / (gray.cols() - 1), c.y / (gray.rows() - 1))
                        }
                        try { CropGeometry.validate(p) } catch (_: IllegalArgumentException) { continue }
                        val area = abs(CropGeometry.area(p)) * canvas
                        if (area > bestArea) { bestArea = area; best = p }
                    } finally { curve.release(); approx.release() }
                }
                contours.forEach { it.release() }; contours.clear()
            }
            return best
        } finally {
            contours.forEach { it.release() }
            blurred.release(); edges.release(); binary.release(); kernel.release(); hierarchy.release()
        }
    }

    /** 按归一化四角把全分辨率图透视校正为矩形 Mat（调用方负责 release）。 */
    fun warp(source: Mat, points: Array<DoubleArray>): Mat {
        val size = CropGeometry.dimensions(points, source.cols(), source.rows())
        val p = Array(4) { i -> Point(points[i][0] * (source.cols() - 1), points[i][1] * (source.rows() - 1)) }
        val from = MatOfPoint2f(*p)
        val to = MatOfPoint2f(
            Point(0.0, 0.0), Point(size[0] - 1.0, 0.0),
            Point(size[0] - 1.0, size[1] - 1.0), Point(0.0, size[1] - 1.0),
        )
        var transform: Mat? = null
        val result = Mat()
        try {
            transform = Imgproc.getPerspectiveTransform(from, to)
            Imgproc.warpPerspective(
                source, result, transform, Size(size[0].toDouble(), size[1].toDouble()),
                Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE,
            )
            return result
        } catch (error: RuntimeException) {
            result.release(); throw error
        } finally {
            from.release(); to.release(); transform?.release()
        }
    }
}
