package com.example.lixing.data.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.hypot

enum class OcrBlockKind { TEXT, INLINE_FORMULA, DISPLAY_FORMULA, TABLE }

data class OcrBlock(
    val kind: OcrBlockKind,
    val text: String,
    val bounds: Rect,
    val confidence: Float,
)

data class OcrPageResult(
    val pageIndex: Int,
    val width: Int,
    val height: Int,
    val blocks: List<OcrBlock>,
)

data class OcrDocumentResult(
    val markdown: String,
    val pages: List<OcrPageResult>,
    val warnings: List<String>,
    val hasBlockingErrors: Boolean,
)

data class OcrProgress(
    val page: Int,
    val totalPages: Int,
    val stage: String,
    val fraction: Float,
)

private data class FormulaBox(val bounds: Rect, val score: Float, val kind: OcrBlockKind)
private data class TextGlyph(val text: String, val bounds: Rect)
private data class TextLine(val text: String, val bounds: Rect, val glyphs: List<TextGlyph>)
private data class TableGrid(val bounds: Rect, val cells: List<List<Rect>>)
private data class EncoderOutput(
    val values: FloatArray,
    val batchSize: Int,
    val sequenceLength: Long,
    val hiddenSize: Long,
)

/**
 * Offline exam OCR. ML Kit keeps the complete text as a safety net. Formula recognition
 * only replaces regions that do not contain Chinese glyphs and pass LaTeX quality checks.
 */
@Singleton
class LocalTextRecognizer @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val mathEngine: MathOcrEngine? by lazy {
        runCatching { MathOcrEngine(context) }.getOrNull()
    }

    suspend fun recognize(photoPath: String): String = recognizeDocument(listOf(photoPath)).markdown

    suspend fun recognizeDocument(
        photoPaths: List<String>,
        onProgress: (OcrProgress) -> Unit = {},
    ): OcrDocumentResult = withContext(Dispatchers.Default) {
        val pages = mutableListOf<OcrPageResult>()
        val warnings = mutableListOf<String>()
        val formulaEngine = mathEngine
        if (formulaEngine == null) warnings += "公式模型初始化失败，当前仅使用普通文字 OCR"
        photoPaths.forEachIndexed { index, path ->
            ensureActive()
            onProgress(OcrProgress(index, photoPaths.size, "正在读取并校正图片", index.toFloat() / photoPaths.size.coerceAtLeast(1)))
            val original = loadOrientedBitmap(path)
            val page = runCatching { OpenCvPagePreprocessor.deskewAndEnhance(original) }.getOrElse { original }
            if (page !== original) original.recycle()
            try {
                onProgress(OcrProgress(index, photoPaths.size, "正在识别中文题干", (index + .15f) / photoPaths.size.coerceAtLeast(1)))
                val textLines = recognizeBestText(page)
                onProgress(OcrProgress(index, photoPaths.size, "正在检测公式区域", (index + .45f) / photoPaths.size.coerceAtLeast(1)))
                val detectedFormulas = runCatching { formulaEngine?.detect(page).orEmpty() }.getOrElse { error ->
                    warnings += "图片 ${index + 1} 的公式区域检测失败：${error.message ?: "未知错误"}"
                    emptyList()
                }
                val formulas = detectedFormulas.filterNot { formula -> formula.coversChineseGlyph(textLines) }
                val tables = runCatching { OpenCvPagePreprocessor.detectTables(page) }.getOrDefault(emptyList())
                onProgress(OcrProgress(index, photoPaths.size, "正在识别公式并整理格式", (index + .75f) / photoPaths.size.coerceAtLeast(1)))
                val recognizedFormulas = runCatching { formulaEngine?.recognizeFormulas(page, formulas).orEmpty() }.getOrElse { error ->
                    warnings += "图片 ${index + 1} 的公式识别失败：${error.message ?: "未知错误"}"
                    emptyList()
                }
                val formulaBlocks = recognizedFormulas.filter { formula ->
                    isFormulaRecognitionUsable(
                        formula.text,
                        formula.confidence,
                        formula.bounds.width(),
                        formula.bounds.height(),
                    )
                }
                val safeTextLines = subtractFormulaRegions(textLines, formulaBlocks)
                val pageBlocks = mergeBlocks(safeTextLines, formulaBlocks, tables)
                val fallbackCount = detectedFormulas.size - formulaBlocks.size
                if (fallbackCount > 0) {
                    warnings += "图片 ${index + 1} 中有 $fallbackCount 处公式已回退为普通文字，请在预览中核对"
                }
                if (formulaBlocks.any { it.confidence < FORMULA_WARNING_THRESHOLD }) {
                    warnings += "图片 ${index + 1} 中有公式置信度较低，请在预览中核对"
                }
                pages += OcrPageResult(index, page.width, page.height, pageBlocks)
            } catch (error: Exception) {
                warnings += "图片 ${index + 1} 识别失败：${error.message ?: "未知错误"}"
                pages += OcrPageResult(index, page.width, page.height, emptyList())
            } finally {
                page.recycle()
            }
        }
        val markdown = pages.joinToString("\n\n") { page -> pageToMarkdown(page) }.trim()
        OcrDocumentResult(
            markdown = markdown,
            pages = pages,
            warnings = warnings,
            hasBlockingErrors = markdown.isBlank() || pages.any { page ->
                page.blocks.any { it.text == FORMULA_FAILURE_PLACEHOLDER }
            },
        )
    }

    private suspend fun recognizeBestText(bitmap: Bitmap): List<TextLine> {
        val candidates = listOf(bitmap, OpenCvPagePreprocessor.enhance(bitmap), OpenCvPagePreprocessor.binarize(bitmap))
        return try {
            candidates.map { recognizeLines(it) }
                .maxByOrNull { lines -> textQuality(lines.joinToString("\n") { it.text }) }
                .orEmpty()
        } finally {
            candidates.drop(1).forEach(Bitmap::recycle)
        }
    }

    private suspend fun recognizeLines(bitmap: Bitmap): List<TextLine> =
        suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(result.toLines())
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }

    private fun textQuality(text: String): Int = text.count { it.isLetterOrDigit() } * 2 +
        text.count(::isCjk) * 3 + text.count { it == '\n' } * 3 +
        text.count { it in "+-=<>≤≥()[]{}_^/" } * 3

    private fun Text.toLines(): List<TextLine> = textBlocks.flatMap { block ->
        block.lines.mapNotNull { line ->
            val bounds = line.boundingBox ?: return@mapNotNull null
            val symbols = line.elements.flatMap { element -> element.symbols }.mapNotNull { symbol ->
                val symbolBounds = symbol.boundingBox ?: return@mapNotNull null
                TextGlyph(symbol.text, symbolBounds)
            }
            val glyphs = symbols.ifEmpty {
                line.elements.mapNotNull { element ->
                    val elementBounds = element.boundingBox ?: return@mapNotNull null
                    TextGlyph(element.text, elementBounds)
                }
            }
            TextLine(line.text.trim(), bounds, glyphs)
        }
    }.filter { it.text.isNotBlank() }
}

private const val FORMULA_WARNING_THRESHOLD = .55f
private const val FORMULA_ACCEPT_THRESHOLD = .2f
private const val FORMULA_FAILURE_PLACEHOLDER = "公式识别失败"
private val Rect.pixelWidth: Int get() = right - left
private val Rect.pixelHeight: Int get() = bottom - top
private val Rect.centerXPx: Int get() = (left + right) / 2
private val Rect.centerYPx: Int get() = (top + bottom) / 2
private fun Rect.containsCenter(other: Rect): Boolean = other.centerXPx in left until right && other.centerYPx in top until bottom

private fun isCjk(char: Char): Boolean = char in '\u3400'..'\u4DBF' ||
    char in '\u4E00'..'\u9FFF' || char in '\uF900'..'\uFAFF'

private fun Rect.intersectionArea(other: Rect): Int =
    max(0, min(right, other.right) - max(left, other.left)) *
        max(0, min(bottom, other.bottom) - max(top, other.top))

private fun FormulaBox.coversChineseGlyph(lines: List<TextLine>): Boolean = lines.any { line ->
    line.glyphs.any { glyph ->
        glyph.text.any(::isCjk) && (
            bounds.containsCenter(glyph.bounds) ||
                bounds.intersectionArea(glyph.bounds) > glyph.bounds.width() * glyph.bounds.height() * .3f
            )
    }
}

internal fun isFormulaRecognitionUsable(
    latex: String,
    confidence: Float,
    boxWidth: Int? = null,
    boxHeight: Int? = null,
): Boolean {
    val value = latex.trim()
    if (confidence < FORMULA_ACCEPT_THRESHOLD || value.isBlank() || value == FORMULA_FAILURE_PLACEHOLDER) return false
    if (value.length > 512 || value.any(::isCjk) || '\uFFFD' in value || '\u0000' in value) return false
    if (value.first() == '^' || value.first() == '_') return false
    if (Regex("""\\[A-Za-z](?![A-Za-z])""").containsMatchIn(value)) return false
    if (boxWidth != null && boxHeight != null && boxWidth > 0 && boxHeight > 0) {
        val aspectRatio = boxWidth.toFloat() / boxHeight
        val maximumPlausibleLength = (40 + aspectRatio * 24).toInt()
        if (value.length > maximumPlausibleLength) return false
    }
    var braceDepth = 0
    value.forEach { char ->
        when (char) {
            '{' -> braceDepth++
            '}' -> if (--braceDepth < 0) return false
        }
    }
    if (braceDepth != 0) return false
    val beginCount = Regex("""\\begin\s*\{""").findAll(value).count()
    val endCount = Regex("""\\end\s*\{""").findAll(value).count()
    return beginCount == endCount && value.any { it.isLetterOrDigit() || it in "+-=<>≤≥()[]{}_^/|" }
}

private fun subtractFormulaRegions(
    lines: List<TextLine>,
    formulas: List<OcrBlock>,
): List<TextLine> = lines.flatMap { line ->
    val rowFormulas = formulas.filter { formula ->
        val overlap = min(line.bounds.bottom, formula.bounds.bottom) - max(line.bounds.top, formula.bounds.top)
        overlap > min(line.bounds.height(), formula.bounds.height()) * .25f
    }
    if (rowFormulas.isEmpty() || line.glyphs.isEmpty()) return@flatMap listOf(line)

    val retained = line.glyphs.sortedBy { it.bounds.left }.filterNot { glyph ->
        rowFormulas.any { formula ->
            formula.bounds.containsCenter(glyph.bounds) ||
                formula.bounds.intersectionArea(glyph.bounds) > glyph.bounds.width() * glyph.bounds.height() * .45f
        }
    }
    if (retained.isEmpty()) return@flatMap emptyList()

    val groups = mutableListOf<MutableList<TextGlyph>>()
    retained.forEach { glyph ->
        val previous = groups.lastOrNull()?.lastOrNull()
        val formulaBetween = previous != null && rowFormulas.any { formula ->
            formula.bounds.centerXPx in previous.bounds.right..glyph.bounds.left
        }
        if (groups.isEmpty() || formulaBetween) groups += mutableListOf(glyph) else groups.last() += glyph
    }
    groups.mapNotNull { glyphs ->
        val text = buildString {
            glyphs.forEachIndexed { glyphIndex, glyph ->
                val previous = glyphs.getOrNull(glyphIndex - 1)
                if (previous != null) {
                    val gap = glyph.bounds.left - previous.bounds.right
                    val previousChar = previous.text.lastOrNull()
                    val nextChar = glyph.text.firstOrNull()
                    val latinBoundary = previousChar?.let { it.isLetterOrDigit() && !isCjk(it) } == true &&
                        nextChar?.let { it.isLetterOrDigit() && !isCjk(it) } == true
                    if (latinBoundary && gap > min(previous.bounds.width(), glyph.bounds.width()) * .45f) append(' ')
                }
                append(glyph.text)
            }
        }.trim()
        if (text.isBlank()) return@mapNotNull null
        TextLine(
            text,
            Rect(
                glyphs.minOf { it.bounds.left },
                glyphs.minOf { it.bounds.top },
                glyphs.maxOf { it.bounds.right },
                glyphs.maxOf { it.bounds.bottom },
            ),
            glyphs,
        )
    }
}

private fun mergeBlocks(
    textLines: List<TextLine>,
    formulaBlocks: List<OcrBlock>,
    tables: List<TableGrid>,
): List<OcrBlock> {
    val sourceBlocks = (textLines.map { OcrBlock(OcrBlockKind.TEXT, it.text, it.bounds, 1f) } + formulaBlocks).toMutableList()
    val tableBlocks = tables.mapNotNull { table ->
        val rows = table.cells.map { cells ->
            cells.map { cell ->
                sourceBlocks.filter { block -> cell.containsCenter(block.bounds) }
                    .sortedBy { it.bounds.left }
                    .joinToString(" ") { block ->
                        if (block.kind == OcrBlockKind.TEXT) block.text else "$$${block.text}$$"
                    }
                    .replace("|", "\\|")
            }
        }
        val columnCount = rows.maxOfOrNull { it.size } ?: 0
        if (rows.size < 2 || columnCount < 2) return@mapNotNull null
        sourceBlocks.removeAll { table.bounds.containsCenter(it.bounds) }
        val normalized = rows.map { row -> row + List(columnCount - row.size) { "" } }
        val markdown = buildString {
            append("| ").append(normalized.first().joinToString(" | ")).append(" |\n")
            append("| ").append(List(columnCount) { "---" }.joinToString(" | ")).append(" |")
            normalized.drop(1).forEach { row -> append("\n| ").append(row.joinToString(" | ")).append(" |") }
        }
        OcrBlock(OcrBlockKind.TABLE, markdown, table.bounds, 1f)
    }
    val blocks = (sourceBlocks + tableBlocks)
        .sortedWith(compareBy<OcrBlock> { it.bounds.top }.thenBy { it.bounds.left })
    if (blocks.isEmpty()) return emptyList()
    val rows = mutableListOf<MutableList<OcrBlock>>()
    blocks.forEach { block ->
        if (block.kind == OcrBlockKind.TABLE) {
            rows += mutableListOf(block)
            return@forEach
        }
        val row = rows.lastOrNull { existing ->
            if (existing.any { it.kind == OcrBlockKind.TABLE }) return@lastOrNull false
            val anchor = existing.maxBy { it.bounds.bottom }
            val overlap = min(anchor.bounds.bottom, block.bounds.bottom) - max(anchor.bounds.top, block.bounds.top)
            overlap > min(anchor.bounds.pixelHeight, block.bounds.pixelHeight) * .25f ||
                abs(anchor.bounds.centerYPx - block.bounds.centerYPx) < max(anchor.bounds.pixelHeight, block.bounds.pixelHeight) * .55f
        }
        if (row == null) rows += mutableListOf(block) else row += block
    }
    return rows.flatMap { it.sortedBy { block -> block.bounds.left } }
}

internal fun pageToMarkdown(page: OcrPageResult): String {
    val rows = mutableListOf<MutableList<OcrBlock>>()
    page.blocks.sortedWith(compareBy<OcrBlock> { it.bounds.top }.thenBy { it.bounds.left }).forEach { block ->
        if (block.kind == OcrBlockKind.TABLE) {
            rows += mutableListOf(block)
            return@forEach
        }
        val row = rows.lastOrNull { existing ->
            if (existing.any { it.kind == OcrBlockKind.TABLE }) return@lastOrNull false
            val anchor = existing.maxBy { it.bounds.bottom }
            val overlap = min(anchor.bounds.bottom, block.bounds.bottom) - max(anchor.bounds.top, block.bounds.top)
            overlap > min(anchor.bounds.pixelHeight, block.bounds.pixelHeight) * .25f ||
                abs(anchor.bounds.centerYPx - block.bounds.centerYPx) < max(anchor.bounds.pixelHeight, block.bounds.pixelHeight) * .55f
        }
        if (row == null) rows += mutableListOf(block) else row += block
    }
    return rows.joinToString("\n") { row ->
        val sorted = row.sortedBy { it.bounds.left }
        if (sorted.any { it.kind == OcrBlockKind.DISPLAY_FORMULA }) {
            sorted.joinToString("\n") { block ->
                when {
                    block.kind == OcrBlockKind.TEXT || block.kind == OcrBlockKind.TABLE -> block.text
                    block.text == FORMULA_FAILURE_PLACEHOLDER -> "[$FORMULA_FAILURE_PLACEHOLDER]"
                    else -> "$$\n${block.text}\n$$"
                }
            }
        } else {
            sorted.joinToString(" ") { block ->
                when {
                    block.kind == OcrBlockKind.TEXT || block.kind == OcrBlockKind.TABLE -> block.text
                    block.text == FORMULA_FAILURE_PLACEHOLDER -> "[$FORMULA_FAILURE_PLACEHOLDER]"
                    else -> "$$${block.text}$$"
                }
            }
        }
    }.replace(Regex("\\n{3,}"), "\n\n")
}

private fun loadOrientedBitmap(path: String): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2800) sample *= 2
    val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("无法读取图片")
    val rotation = runCatching {
        when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    if (rotation == 0f) return decoded
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation) }, true)
        .also { decoded.recycle() }
}

private class OpenCvPagePreprocessor private constructor() {
    companion object {
        private var initialized = false

        private fun ensureLoaded() {
            if (!initialized) {
                check(OpenCVLoader.initLocal()) { "OpenCV 初始化失败" }
                initialized = true
            }
        }

        fun deskewAndEnhance(source: Bitmap): Bitmap {
            ensureLoaded()
            val input = Mat()
            val gray = Mat()
            val blurred = Mat()
            val edges = Mat()
            var perspective: Mat? = null
            try {
                Utils.bitmapToMat(source, input)
                perspective = perspectiveCorrect(input)
                Imgproc.cvtColor(perspective, gray, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.GaussianBlur(gray, blurred, Size(3.0, 3.0), 0.0)
                Imgproc.Canny(blurred, edges, 50.0, 150.0)
                val lines = Mat()
                Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180.0, 60, source.width * .2, 20.0)
                val angles = mutableListOf<Double>()
                for (i in 0 until lines.rows()) {
                    val line = lines.get(i, 0) ?: continue
                    val angle = Math.toDegrees(kotlin.math.atan2(line[3] - line[1], line[2] - line[0]))
                    if (abs(angle) < 15.0) angles += angle
                }
                lines.release()
                val angle = angles.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
                val target = if (abs(angle) > .35) {
                    val rotated = Mat()
                    val center = org.opencv.core.Point(perspective.cols() / 2.0, perspective.rows() / 2.0)
                    val rotation = Imgproc.getRotationMatrix2D(center, angle, 1.0)
                    Imgproc.warpAffine(perspective, rotated, rotation, perspective.size(), Imgproc.INTER_CUBIC, org.opencv.core.Core.BORDER_CONSTANT, org.opencv.core.Scalar(255.0, 255.0, 255.0, 255.0))
                    rotation.release()
                    rotated
                } else perspective.clone()
                val output = Bitmap.createBitmap(target.cols(), target.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(target, output)
                target.release()
                return output
            } finally {
                perspective?.release()
                input.release(); gray.release(); blurred.release(); edges.release()
            }
        }

        private fun perspectiveCorrect(input: Mat): Mat {
            val gray = Mat(); val blurred = Mat(); val edges = Mat(); val hierarchy = Mat()
            val contours = mutableListOf<MatOfPoint>()
            return try {
                Imgproc.cvtColor(input, gray, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
                Imgproc.Canny(blurred, edges, 60.0, 180.0)
                Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                val minimumArea = input.cols().toDouble() * input.rows() * .35
                val candidate = contours.asSequence()
                    .filter { Imgproc.contourArea(it) >= minimumArea }
                    .sortedByDescending(Imgproc::contourArea)
                    .mapNotNull { contour ->
                        val curve = MatOfPoint2f(*contour.toArray())
                        val approximation = MatOfPoint2f()
                        Imgproc.approxPolyDP(curve, approximation, Imgproc.arcLength(curve, true) * .02, true)
                        curve.release()
                        if (approximation.total() == 4L) approximation else approximation.also(MatOfPoint2f::release).let { null }
                    }
                    .firstOrNull() ?: return input.clone()
                val points = candidate.toArray().toList()
                candidate.release()
                val topLeft = points.minBy { it.x + it.y }
                val bottomRight = points.maxBy { it.x + it.y }
                val topRight = points.maxBy { it.x - it.y }
                val bottomLeft = points.minBy { it.x - it.y }
                val width = max(hypot(topRight.x - topLeft.x, topRight.y - topLeft.y), hypot(bottomRight.x - bottomLeft.x, bottomRight.y - bottomLeft.y)).toInt()
                val height = max(hypot(bottomLeft.x - topLeft.x, bottomLeft.y - topLeft.y), hypot(bottomRight.x - topRight.x, bottomRight.y - topRight.y)).toInt()
                if (width < 200 || height < 200) return input.clone()
                val sourcePoints = MatOfPoint2f(topLeft, topRight, bottomRight, bottomLeft)
                val targetPoints = MatOfPoint2f(
                    org.opencv.core.Point(0.0, 0.0),
                    org.opencv.core.Point(width - 1.0, 0.0),
                    org.opencv.core.Point(width - 1.0, height - 1.0),
                    org.opencv.core.Point(0.0, height - 1.0),
                )
                val transform = Imgproc.getPerspectiveTransform(sourcePoints, targetPoints)
                val output = Mat()
                Imgproc.warpPerspective(input, output, transform, Size(width.toDouble(), height.toDouble()), Imgproc.INTER_CUBIC, org.opencv.core.Core.BORDER_CONSTANT, org.opencv.core.Scalar(255.0, 255.0, 255.0, 255.0))
                sourcePoints.release(); targetPoints.release(); transform.release()
                output
            } finally {
                contours.forEach(MatOfPoint::release)
                gray.release(); blurred.release(); edges.release(); hierarchy.release()
            }
        }

        fun enhance(source: Bitmap): Bitmap {
            ensureLoaded()
            val input = Mat(); val gray = Mat(); val output = Mat()
            return try {
                Utils.bitmapToMat(source, input)
                Imgproc.cvtColor(input, gray, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.createCLAHE(2.0, Size(8.0, 8.0)).apply(gray, output)
                Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(output, it) }
            } finally { input.release(); gray.release(); output.release() }
        }

        fun binarize(source: Bitmap): Bitmap {
            ensureLoaded()
            val input = Mat(); val gray = Mat(); val output = Mat()
            return try {
                Utils.bitmapToMat(source, input)
                Imgproc.cvtColor(input, gray, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.adaptiveThreshold(gray, output, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 31, 11.0)
                Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(output, it) }
            } finally { input.release(); gray.release(); output.release() }
        }

        fun detectTables(source: Bitmap): List<TableGrid> {
            ensureLoaded()
            val input = Mat(); val gray = Mat(); val binary = Mat(); val horizontal = Mat(); val vertical = Mat(); val grid = Mat(); val hierarchy = Mat()
            val contours = mutableListOf<MatOfPoint>()
            return try {
                Utils.bitmapToMat(source, input)
                Imgproc.cvtColor(input, gray, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.adaptiveThreshold(gray, binary, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 31, 12.0)
                val horizontalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(max(12, source.width / 28).toDouble(), 1.0))
                val verticalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, max(12, source.height / 28).toDouble()))
                Imgproc.morphologyEx(binary, horizontal, Imgproc.MORPH_OPEN, horizontalKernel)
                Imgproc.morphologyEx(binary, vertical, Imgproc.MORPH_OPEN, verticalKernel)
                horizontalKernel.release()
                verticalKernel.release()
                org.opencv.core.Core.add(horizontal, vertical, grid)
                Imgproc.findContours(grid, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)
                val cells = contours.map(Imgproc::boundingRect)
                    .filter { rect ->
                        rect.width > source.width * .04f && rect.height > source.height * .025f &&
                            rect.width < source.width * .9f && rect.height < source.height * .7f
                    }
                    .sortedWith(compareBy<org.opencv.core.Rect> { it.y }.thenBy { it.x })
                    .map { Rect(it.x, it.y, it.x + it.width, it.y + it.height) }
                    .fold(mutableListOf<Rect>()) { unique, rect ->
                        if (unique.none { existing ->
                                abs(existing.left - rect.left) < 4 && abs(existing.top - rect.top) < 4 &&
                                    abs(existing.right - rect.right) < 4 && abs(existing.bottom - rect.bottom) < 4
                            }) unique += rect
                        unique
                    }
                if (cells.size < 4) return emptyList()
                val medianHeight = cells.map { it.height() }.sorted()[cells.size / 2]
                val rows = mutableListOf<MutableList<Rect>>()
                cells.forEach { cell ->
                    val row = rows.lastOrNull { existing -> abs(existing.first().centerY() - cell.centerY()) < medianHeight * .5f }
                    if (row == null) rows += mutableListOf(cell) else row += cell
                }
                val gridRows = rows.filter { it.size >= 2 }.map { it.sortedBy(Rect::left) }
                if (gridRows.size < 2) return emptyList()
                val commonColumns = gridRows.groupingBy { it.size }.eachCount().maxByOrNull { it.value }?.key ?: return emptyList()
                val normalizedRows = gridRows.filter { it.size == commonColumns }
                if (normalizedRows.size < 2) return emptyList()
                val all = normalizedRows.flatten()
                listOf(
                    TableGrid(
                        Rect(all.minOf { it.left }, all.minOf { it.top }, all.maxOf { it.right }, all.maxOf { it.bottom }),
                        normalizedRows,
                    ),
                )
            } finally {
                contours.forEach(MatOfPoint::release)
                input.release(); gray.release(); binary.release(); horizontal.release(); vertical.release(); grid.release(); hierarchy.release()
            }
        }
    }
}

private class MathOcrEngine(private val context: Context) {
    private val environment = ai.onnxruntime.OrtEnvironment.getEnvironment()
    private val options = ai.onnxruntime.OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
    private val mfd = environment.createSession(asset("math_ocr/pix2text-mfd-1.5.onnx"), options)
    private val encoder = environment.createSession(asset("math_ocr/encoder_model.onnx"), options)
    private val decoder = environment.createSession(asset("math_ocr/decoder_model.onnx"), options)
    private val tokenizer = LatexTokenizer(context)

    private fun asset(path: String): ByteArray = context.assets.open(path).use { it.readBytes() }

    fun detect(source: Bitmap): List<FormulaBox> {
        val detections = formulaDetectionTiles(source.width, source.height).flatMap { tileBounds ->
            val tile = if (
                tileBounds.left == 0 && tileBounds.top == 0 &&
                tileBounds.right == source.width && tileBounds.bottom == source.height
            ) {
                source
            } else {
                Bitmap.createBitmap(source, tileBounds.left, tileBounds.top, tileBounds.width(), tileBounds.height())
            }
            try {
                detectTile(tile).map { box ->
                    box.copy(
                        bounds = Rect(box.bounds).apply { offset(tileBounds.left, tileBounds.top) },
                    )
                }
            } finally {
                if (tile !== source) tile.recycle()
            }
        }.sortedByDescending { it.score }
        val kept = mutableListOf<FormulaBox>()
        for (candidate in detections) {
            if (kept.none { intersectionOverUnion(it.bounds, candidate.bounds) > .45f }) kept += candidate
            if (kept.size == MAX_FORMULA_BOXES) break
        }
        return kept
    }

    private fun detectTile(source: Bitmap): List<FormulaBox> {
        val input = letterbox(source, MFD_INPUT_SIZE)
        val plane = MFD_INPUT_SIZE * MFD_INPUT_SIZE
        val values = FloatArray(3 * plane)
        val pixels = IntArray(plane)
        input.getPixels(pixels, 0, MFD_INPUT_SIZE, 0, 0, MFD_INPUT_SIZE, MFD_INPUT_SIZE)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            values[i] = (pixel shr 16 and 0xFF) / 255f
            values[plane + i] = (pixel shr 8 and 0xFF) / 255f
            values[2 * plane + i] = (pixel and 0xFF) / 255f
        }
        input.recycle()
        val tensor = ai.onnxruntime.OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(values),
            longArrayOf(1, 3, MFD_INPUT_SIZE.toLong(), MFD_INPUT_SIZE.toLong()),
        )
        val inputs = mapOf(mfd.inputNames.first() to tensor)
        return try {
            mfd.run(inputs).use { outputs ->
                val output = outputs[0] as ai.onnxruntime.OnnxTensor
                val info = output.info as ai.onnxruntime.TensorInfo
                val shape = info.shape
                val buffer = output.floatBuffer.duplicate()
                val raw = FloatArray(buffer.remaining()).also(buffer::get)
                decodeDetections(raw, shape, source.width, source.height, MFD_INPUT_SIZE)
            }
        } finally { tensor.close() }
    }

    suspend fun recognizeFormulas(source: Bitmap, boxes: List<FormulaBox>): List<OcrBlock> =
        boxes.chunked(FORMULA_BATCH_SIZE).flatMap { batch ->
            val oneSize = 3 * MFR_INPUT_SIZE * MFR_INPUT_SIZE
            val input = FloatArray(batch.size * oneSize)
            batch.forEachIndexed { index, box ->
                val crop = Bitmap.createBitmap(source, box.bounds.left, box.bounds.top, box.bounds.width(), box.bounds.height())
                try {
                    prepareFormula(crop).copyInto(input, index * oneSize)
                } finally { crop.recycle() }
            }
            val pixelTensor = ai.onnxruntime.OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(input),
                longArrayOf(batch.size.toLong(), 3, MFR_INPUT_SIZE.toLong(), MFR_INPUT_SIZE.toLong()),
            )
            val hidden = try {
                encoder.run(mapOf(encoder.inputNames.first() to pixelTensor)).use { result ->
                    val tensor = result[0] as ai.onnxruntime.OnnxTensor
                    val shape = (tensor.info as ai.onnxruntime.TensorInfo).shape
                    val values = tensor.floatBuffer.duplicate()
                    EncoderOutput(
                        values = FloatArray(values.remaining()).also(values::get),
                        batchSize = shape.first().toInt(),
                        sequenceLength = shape[shape.size - 2],
                        hiddenSize = shape.last(),
                    )
                }
            } finally { pixelTensor.close() }
            val decoded = decodeBatch(hidden)
            batch.zip(decoded).map { (box, result) ->
                OcrBlock(
                    box.kind,
                    result.first.ifBlank { FORMULA_FAILURE_PLACEHOLDER },
                    box.bounds,
                    (box.score * result.second).coerceIn(0f, 1f),
                )
            }
        }

    private suspend fun decodeBatch(hidden: EncoderOutput): List<Pair<String, Float>> {
        val tokenRows = Array(hidden.batchSize) { mutableListOf(decoderStartId()) }
        val probabilities = Array(hidden.batchSize) { mutableListOf<Float>() }
        val finished = BooleanArray(hidden.batchSize)
        val hiddenTensor = ai.onnxruntime.OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(hidden.values),
            longArrayOf(hidden.batchSize.toLong(), hidden.sequenceLength, hidden.hiddenSize),
        )
        try {
            for (step in 0 until MAX_FORMULA_TOKENS) {
                currentCoroutineContext().ensureActive()
                val sequenceLength = tokenRows[0].size
                val flatIds = LongArray(hidden.batchSize * sequenceLength)
                tokenRows.forEachIndexed { batchIndex, row ->
                    row.forEachIndexed { tokenIndex, token -> flatIds[batchIndex * sequenceLength + tokenIndex] = token }
                }
                val inputIds = ai.onnxruntime.OnnxTensor.createTensor(
                    environment,
                    LongBuffer.wrap(flatIds),
                    longArrayOf(hidden.batchSize.toLong(), sequenceLength.toLong()),
                )
                try {
                    val inputs = decoder.inputNames.associateWith { name ->
                        if (name.contains("input_ids")) inputIds else hiddenTensor
                    }
                    decoder.run(inputs).use { result ->
                        val tensor = result[0] as ai.onnxruntime.OnnxTensor
                        val shape = (tensor.info as ai.onnxruntime.TensorInfo).shape
                        val buffer = tensor.floatBuffer.duplicate()
                        val logits = FloatArray(buffer.remaining()).also(buffer::get)
                        val vocab = shape.last().toInt()
                        for (batchIndex in 0 until hidden.batchSize) {
                            if (finished[batchIndex]) {
                                tokenRows[batchIndex] += PAD_TOKEN_ID
                                continue
                            }
                            val start = ((batchIndex * sequenceLength + sequenceLength - 1) * vocab)
                                .coerceIn(0, (logits.size - vocab).coerceAtLeast(0))
                            var best = 0
                            var bestLogit = Float.NEGATIVE_INFINITY
                            for (index in 0 until vocab) if (logits[start + index] > bestLogit) {
                                best = index
                                bestLogit = logits[start + index]
                            }
                            var denominator = 0.0
                            for (index in 0 until vocab) denominator += exp((logits[start + index] - bestLogit).toDouble())
                            probabilities[batchIndex] += (1.0 / denominator).toFloat()
                            tokenRows[batchIndex] += best.toLong()
                            if (best.toLong() == EOS_TOKEN_ID) finished[batchIndex] = true
                        }
                    }
                } finally { inputIds.close() }
                if (finished.all { it }) break
            }
        } finally { hiddenTensor.close() }
        return tokenRows.indices.map { index ->
            val tokens = tokenRows[index].drop(1).takeWhile { it != EOS_TOKEN_ID && it != PAD_TOKEN_ID }
            val score = probabilities[index].take(tokens.size.coerceAtLeast(1)).let { values ->
                if (values.isEmpty()) 0f else exp(values.sumOf { kotlin.math.ln((it + 1e-8f).toDouble()) } / values.size).toFloat()
            }
            tokenizer.decode(tokens) to score
        }
    }

    private fun decoderStartId(): Long = 2L

    private fun prepareFormula(source: Bitmap): FloatArray {
        // Pix2Text MFR uses DeiTImageProcessor with an explicit 384x384 resize.
        // Preserving aspect ratio here makes wide formulas much smaller than the model saw in training.
        val resized = Bitmap.createScaledBitmap(source, MFR_INPUT_SIZE, MFR_INPUT_SIZE, true)
        val pixels = IntArray(MFR_INPUT_SIZE * MFR_INPUT_SIZE)
        resized.getPixels(pixels, 0, MFR_INPUT_SIZE, 0, 0, MFR_INPUT_SIZE, MFR_INPUT_SIZE)
        if (resized !== source) resized.recycle()
        val plane = MFR_INPUT_SIZE * MFR_INPUT_SIZE
        val result = FloatArray(plane * 3)
        pixels.forEachIndexed { index, pixel ->
            result[index] = ((pixel shr 16 and 0xFF) / 255f - .5f) / .5f
            result[plane + index] = ((pixel shr 8 and 0xFF) / 255f - .5f) / .5f
            result[2 * plane + index] = ((pixel and 0xFF) / 255f - .5f) / .5f
        }
        return result
    }

    private fun letterbox(source: Bitmap, size: Int): Bitmap {
        val canvas = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(canvas).drawColor(Color.WHITE)
        val scale = min(size.toFloat() / source.width, size.toFloat() / source.height)
        val resized = Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
        Canvas(canvas).drawBitmap(resized, (size - resized.width) / 2f, (size - resized.height) / 2f, null)
        resized.recycle()
        return canvas
    }

    private fun decodeDetections(
        raw: FloatArray,
        shape: LongArray,
        width: Int,
        height: Int,
        inputSize: Int,
    ): List<FormulaBox> {
        if (shape.size != 3) return emptyList()
        val channelFirst = shape[1] <= 10
        val channels = if (channelFirst) shape[1].toInt() else shape[2].toInt()
        val count = if (channelFirst) shape[2].toInt() else shape[1].toInt()
        if (channels < 5) return emptyList()
        val scale = min(inputSize.toFloat() / width, inputSize.toFloat() / height)
        val padX = (inputSize - width * scale) / 2f
        val padY = (inputSize - height * scale) / 2f
        val candidates = (0 until count).mapNotNull { index ->
            fun value(channel: Int): Float = if (channelFirst) raw[channel * count + index] else raw[index * channels + channel]
            val inlineScore = value(4)
            val displayScore = if (channels > 5) value(5) else 0f
            val score = max(inlineScore, displayScore)
            if (score < .25f) return@mapNotNull null
            val cx = (value(0) - padX) / scale
            val cy = (value(1) - padY) / scale
            val boxW = value(2) / scale
            val boxH = value(3) / scale
            FormulaBox(
                Rect(
                    (cx - boxW / 2).toInt().coerceIn(0, width - 1),
                    (cy - boxH / 2).toInt().coerceIn(0, height - 1),
                    (cx + boxW / 2).toInt().coerceIn(1, width),
                    (cy + boxH / 2).toInt().coerceIn(1, height),
                ),
                score,
                if (displayScore > inlineScore) OcrBlockKind.DISPLAY_FORMULA else OcrBlockKind.INLINE_FORMULA,
            )
        }.filter { it.bounds.width() > 3 && it.bounds.height() > 3 }.sortedByDescending { it.score }
        val kept = mutableListOf<FormulaBox>()
        for (candidate in candidates) {
            if (kept.none { intersectionOverUnion(it.bounds, candidate.bounds) > .45f }) kept += candidate
            if (kept.size == MAX_FORMULA_BOXES) break
        }
        return kept
    }

    private fun intersectionOverUnion(a: Rect, b: Rect): Float {
        val intersection = max(0, min(a.right, b.right) - max(a.left, b.left)) * max(0, min(a.bottom, b.bottom) - max(a.top, b.top))
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union == 0) 0f else intersection.toFloat() / union
    }

    companion object {
        private const val PAD_TOKEN_ID = 0L
        private const val EOS_TOKEN_ID = 2L
        private const val MAX_FORMULA_TOKENS = 256
        private const val FORMULA_BATCH_SIZE = 4
        private const val MFD_INPUT_SIZE = 640
        private const val MFR_INPUT_SIZE = 384
        private const val MAX_FORMULA_BOXES = 96
    }
}

internal fun formulaDetectionTiles(width: Int, height: Int): List<Rect> {
    require(width > 0 && height > 0)
    val fullPage = Rect(0, 0, width, height)
    val shortSide = min(width, height)
    val longSide = max(width, height)
    if (longSide <= shortSide * 1.6f) return listOf(fullPage)

    val tileLongSide = min(longSide, (shortSide * 1.5f).toInt().coerceAtLeast(shortSide))
    val stride = (tileLongSide * .75f).toInt().coerceAtLeast(1)
    val starts = mutableListOf(0)
    while (starts.last() + tileLongSide < longSide) {
        val next = min(starts.last() + stride, longSide - tileLongSide)
        if (next == starts.last()) break
        starts += next
    }
    val detailTiles = starts.map { start ->
        if (width >= height) Rect(start, 0, start + tileLongSide, height)
        else Rect(0, start, width, start + tileLongSide)
    }
    return listOf(fullPage) + detailTiles.filterNot { it == fullPage }
}

private class LatexTokenizer(context: Context) {
    private val idToToken: Map<Int, String> = run {
        val root = Json.parseToJsonElement(context.assets.open("math_ocr/tokenizer.json").bufferedReader().use { it.readText() }).jsonObject
        root["model"]!!.jsonObject["vocab"]!!.jsonObject.entries.associate { it.value.jsonPrimitive.content.toInt() to it.key }
    }
    private val byteDecoder: Map<Char, Byte> = buildByteDecoder()

    fun decode(ids: List<Long>): String {
        val bytes = ArrayList<Byte>()
        ids.forEach { id ->
            val token = idToToken[id.toInt()] ?: return@forEach
            if (id in 0L..4L) return@forEach
            token.forEach { char -> bytes += (byteDecoder[char] ?: char.code.toByte()) }
        }
        return bytes.toByteArray().toString(Charsets.UTF_8).replace("\u0000", "").trim()
    }

    private fun buildByteDecoder(): Map<Char, Byte> {
        val bytes = mutableListOf<Int>(); (33..126).forEach(bytes::add); (161..172).forEach(bytes::add); (174..255).forEach(bytes::add)
        val chars = bytes.map { it.toChar() }.toMutableList(); var next = 0
        for (value in 0..255) if (value !in bytes) { bytes += value; chars += (256 + next++).toChar() }
        return chars.mapIndexed { index, char -> char to bytes[index].toByte() }.toMap()
    }
}

/** Keeps the old deterministic merge API used by unit tests and older callers. */
internal fun mergeOcrResults(chinese: String, latin: String): String {
    val primary = chinese.trim(); val auxiliary = latin.trim()
    if (primary.isEmpty()) return auxiliary
    if (auxiliary.isEmpty()) return primary
    if (primary.filterNot(Char::isWhitespace).equals(auxiliary.filterNot(Char::isWhitespace), ignoreCase = true)) return primary
    val symbolCount = auxiliary.count { it in "+-=<>≤≥()[]{}_^/" }
    val formulaTokens = Regex("""[A-Za-z]\s*[\[(]|\d\s*[A-Za-z]|[A-Za-z]\s*\d""").findAll(auxiliary).count()
    if (symbolCount + formulaTokens < 3) return primary
    return "中文 OCR 主结果：\n$primary\n\n公式与拉丁字符辅助结果（请在预览中交叉核对）：\n$auxiliary"
}
