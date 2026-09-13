package plot

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow

/*
 * 用方案 A（结构化参数 → 原生渲染）画出题 3.3 的三张图。
 * 参数归一化：f_c = 5、B = 2、N₀ = 1（题目只给了 H(f) 的形状，没给数值）。
 */

private const val FC = 5.0
private const val B = 2.0
private const val N0 = 1.0
private const val HB = B / 2

private fun sy(f: Double): Double =
    if (abs(abs(f) - FC) <= HB + 1e-9) (N0 / 2) * (2 * PI * f).pow(2) else 0.0

private fun sc(f: Double): Double =
    if (abs(f) <= HB + 1e-9) sy(f + FC) + sy(f - FC) else 0.0

fun main() {
    runChecks()

    val outDir = File("F:/APP/docs/prototype/kotlin-jvm/out").apply { mkdirs() }
    val renderer = PlotRenderer()

    // ── 图 1：H(f) 双带通（阶梯折线 + 面积）──
    val hSpec = PlotSpec(
        title = "H(f)：中心 ±f_c、带宽 B 的双带通",
        x = Axis(
            label = "f",
            min = -9.0,
            max = 9.0,
            ticks = listOf(-FC, 0.0, FC),
            tickLabels = mapOf(-FC to "-f_c", FC to "f_c"),
        ),
        y = Axis(label = "|H(f)|", min = 0.0, max = 1.4, ticks = listOf(0.0, 1.0)),
        series = listOf(
            Series(
                label = "|H(f)|",
                points = listOf(
                    -9.0 to 0.0,
                    -FC - HB to 0.0, -FC - HB to 1.0, -FC + HB to 1.0, -FC + HB to 0.0,
                    FC - HB to 0.0, FC - HB to 1.0, FC + HB to 1.0, FC + HB to 0.0,
                    9.0 to 0.0,
                ),
                fill = true,
            ),
        ),
        markLines = listOf(MarkLine(x = -FC, label = "-f_c"), MarkLine(x = FC, label = "f_c")),
    )
    write(renderer, hSpec, File(outDir, "1-Hf.png"))

    // ── 图 2：S_Y(f)（带内 ∝ f²，含 B 的区间标注）──
    val syPoints = (0..900).map { i ->
        val f = -9.0 + 18.0 * i / 900
        f to sy(f)
    }
    val sySpec = PlotSpec(
        // 标题只用 ASCII + 常见希腊字母：下标 ₀ 这类字符在多数字体里缺字形，会渲染成方框
        title = "S_Y(f) = (N0/2)·|H(f)|²·(2πf)²",
        x = Axis(
            label = "f",
            min = -9.0,
            max = 9.0,
            ticks = listOf(-FC, 0.0, FC),
            tickLabels = mapOf(-FC to "-f_c", FC to "f_c"),
        ),
        y = Axis(label = "S_Y(f)"),
        series = listOf(Series(label = "S_Y(f)", points = syPoints, fill = true)),
        markLines = listOf(MarkLine(x = -FC, label = "-f_c"), MarkLine(x = FC, label = "f_c")),
        markAreas = listOf(MarkArea(FC - HB, FC + HB, "B")),
    )
    write(renderer, sySpec, File(outDir, "2-SY.png"))

    // ── 图 3：同相 / 正交分量功率谱（答案图）──
    val scPoints = (0..240).map { i ->
        val f = -HB + B * i / 240
        f to sc(f)
    }
    val scSpec = PlotSpec(
        title = "同相 / 正交分量功率谱 S_c(f) = S_s(f)",
        x = Axis(label = "f", min = -1.25, max = 1.25, ticks = listOf(-1.0, -0.5, 0.0, 0.5, 1.0)),
        y = Axis(label = "PSD"),
        series = listOf(
            Series(label = "S_c(f) = 4π²N0(f_c² + f²)", points = scPoints, fill = true),
        ),
        legend = true,
    )
    write(renderer, scSpec, File(outDir, "3-ScSs.png"))

    println()
    println("已输出到 ${outDir.absolutePath}")
}

private fun write(renderer: PlotRenderer, spec: PlotSpec, file: File) {
    ImageIO.write(renderer.render(spec), "png", file)
    println("  ${file.name}  ${file.length()} bytes")
}

/** 核心层的自检：这些断言在 Android 端同样成立（纯函数）。 */
private fun runChecks() {
    var bad = 0
    fun check(label: String, cond: Boolean, extra: String = "") {
        if (!cond) {
            bad++
            println("FAIL  $label  $extra")
        } else {
            println("OK    $label  $extra")
        }
    }

    // 刻度算法
    check("niceTicks(-9,9) 落在整数且步长好看",
        niceTicks(-9.0, 9.0).let { it == listOf(-8.0, -6.0, -4.0, -2.0, 0.0, 2.0, 4.0, 6.0, 8.0) },
        niceTicks(-9.0, 9.0).toString())
    check("niceTicks(0,1) = 0.0..1.0 步长 0.2",
        niceTicks(0.0, 1.0, 5) == listOf(0.0, 0.2, 0.4, 0.6, 0.8, 1.0),
        niceTicks(0.0, 1.0, 5).toString())
    check("niceTicks 极端区间不失控", niceTicks(0.0, 1e-6).size in 2..12,
        niceTicks(0.0, 1e-6).toString())

    // 表达式求值
    check("x^2 @3 = 9", ExprEval.compile("x^2")(3.0) == 9.0)
    check("2x^2+1 @2 = 9（隐式乘法）", ExprEval.compile("2x^2+1")(2.0) == 9.0)
    check("-x^2 @2 = -4（一元负号优先级）", ExprEval.compile("-x^2")(2.0) == -4.0)
    check("2^-1 = 0.5（^ 右结合）", ExprEval.compile("2^-1")(0.0) == 0.5)
    check("sin(pi/2) = 1", abs(ExprEval.compile("sin(pi/2)")(0.0) - 1.0) < 1e-12)

    // 采样与断线
    val xs = sampleSeries(Series(expr = "x^2"), -3.0, 3.0, 100)
    check("采样点数 = 101", xs.size == 101, "n=${xs.size}")
    check("端点值正确", abs(xs.first().second!! - 9.0) < 1e-9 && abs(xs.last().second!! - 9.0) < 1e-9)
    val div = sampleSeries(Series(expr = "1/x"), -1.0, 1.0, 100)
    check("1/x 在 0 处产生断点", div.any { it.second == null },
        "断点数=${div.count { it.second == null }}")
    check("非法表达式被拦截",
        runCatching { sampleSeries(Series(expr = "nope(x)"), -1.0, 1.0) }.isFailure)

    // 题目数值
    check("S_Y(±5) = 50π²", abs(sy(5.0) - 50 * PI * PI) < 1e-9, sy(5.0).toString())
    check("S_c(0) = 100π²", abs(sc(0.0) - 100 * PI * PI) < 1e-9, sc(0.0).toString())
    check("S_c(±1) = 104π²", abs(sc(1.0) - 104 * PI * PI) < 1e-9, sc(1.0).toString())
    val ripple = sc(1.0) / sc(0.0) - 1
    check("S_c 带内上翘 ≈ 4%", ripple in 0.038..0.042, "%.1f%%".format(ripple * 100))

    println(if (bad == 0) "全部通过\n" else "有 $bad 项失败\n")
    if (bad > 0) throw IllegalStateException("自检未通过")
}
