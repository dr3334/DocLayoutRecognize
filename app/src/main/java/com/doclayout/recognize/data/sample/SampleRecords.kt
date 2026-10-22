package com.doclayout.recognize.data.sample

import com.doclayout.recognize.data.model.OcrLine
import com.doclayout.recognize.data.model.OcrLineKind
import com.doclayout.recognize.data.model.OcrRecord

/**
 * 内置演示数据。真实项目里这些来自文件系统，这里只为了让界面"有内容可排版"。
 *
 * 为什么界面对着数据写：编辑式版式的重点在信息层级（标题/时间/页数/置信度），
 * 只有数据长得像真的，层级才看得出对不对。
 *
 * 只在"没有任何真实记录"时兜底（见 `OcrApp.kt`）；要空状态截图就 `--ei empty 1`。
 */
val sampleRecords: List<OcrRecord> = listOf(
    OcrRecord(
        id = "r1",
        title = "数学卷 · 期末模拟",
        time = "10 分钟前",
        pages = 2,
        engine = "PP-StructureV3",
        confidence = "98.4%",
        duration = "1.2 s",
        lines = listOf(
            OcrLine(OcrLineKind.Heading, "2026 学年第一学期 数学期末模拟卷"),
            OcrLine(OcrLineKind.Body, "一、选择题（每题 5 分，共 40 分）"),
            OcrLine(OcrLineKind.Body, "1. 已知集合 A = {x | x² − 3x + 2 < 0}，B = {x | x > 1}，则 A ∩ B ="),
            OcrLine(OcrLineKind.Formula, "A ∩ B = (1, 2)", tex = """A \cap B = (1,\ 2)"""),
            OcrLine(OcrLineKind.Answer, "学生作答：B　✓ 自动判分正确"),
            OcrLine(OcrLineKind.Body, "2. 函数 f(x) = x² + 2x − 3 的对称轴是"),
            OcrLine(OcrLineKind.Formula, "x = −b / 2a = −1", tex = """x = -\frac{b}{2a} = -1"""),
            OcrLine(OcrLineKind.Body, "二、解答题（每题 12 分）"),
            OcrLine(OcrLineKind.Body, "3. 如图，在直角三角形 ABC 中，∠C = 90°，AC = 3，BC = 4，求斜边 AB 及内切圆半径。"),
            OcrLine(
                OcrLineKind.Formula,
                "AB = √(3² + 4²) = 5，r = (3 + 4 − 5) / 2 = 1",
                tex = """AB = \sqrt{3^{2}+4^{2}} = 5,\quad r = \frac{3+4-5}{2} = 1""",
            ),
            OcrLine(OcrLineKind.Body, "4. 已知数列 {aₙ} 满足 a₁ = 1，aₙ₊₁ = 2aₙ + 1，求通项公式。"),
            OcrLine(OcrLineKind.Formula, "aₙ = 2ⁿ − 1", tex = """a_{n} = 2^{n}-1"""),
            OcrLine(OcrLineKind.Body, "三、附加题（10 分）"),
            OcrLine(OcrLineKind.Body, "5. 讨论函数 f(x) = x³ − 3x 在区间 [−2, 2] 上的单调性与极值，并画出大致图像。"),
            OcrLine(
                OcrLineKind.Formula,
                "f′(x) = 3x² − 3 = 0 → x = ±1",
                tex = """f'(x) = 3x^{2}-3 = 0 \Rightarrow x = \pm 1""",
            ),
            OcrLine(OcrLineKind.Answer, "学生作答：增区间 (−∞, −1) ∪ (1, +∞)，极大值 f(−1) = 2，极小值 f(1) = −2"),
            OcrLine(OcrLineKind.Body, "考试时间 120 分钟　满分 150 分　命题人：数学组"),
        ),
    ),
    OcrRecord(
        id = "r2",
        title = "英语卷 · 完形填空",
        time = "昨天 21:04",
        pages = 4,
        engine = "PP-StructureV3",
        confidence = "97.1%",
        duration = "2.0 s",
        lines = listOf(
            OcrLine(OcrLineKind.Heading, "Reading Comprehension · Section B"),
            OcrLine(OcrLineKind.Body, "Directions: Read the passage and choose the best answer for each blank."),
            OcrLine(OcrLineKind.Body, "When I was twelve, my grandfather handed me a box of old letters and said nothing."),
            OcrLine(OcrLineKind.Answer, "学生作答：41. C　42. A　43. D"),
        ),
    ),
    OcrRecord(
        id = "r3",
        title = "物理卷 · 力学专题",
        time = "3 天前",
        pages = 3,
        engine = "PP-StructureV3",
        confidence = "96.8%",
        duration = "1.6 s",
        lines = listOf(
            OcrLine(OcrLineKind.Heading, "力学专题 · 牛顿第二定律"),
            OcrLine(OcrLineKind.Body, "1. 质量 m = 2 kg 的物体在水平面上受到 F = 10 N 的拉力，摩擦系数 μ = 0.2。"),
            OcrLine(
                OcrLineKind.Formula,
                "a = (F − μmg) / m = (10 − 3.92) / 2 ≈ 3.04 m/s²",
                tex = """a = \frac{F-\mu mg}{m} = \frac{10-3.92}{2} \approx 3.04""",
            ),
            OcrLine(OcrLineKind.Answer, "学生作答：3.0 m/s²　✓"),
        ),
    ),
)
