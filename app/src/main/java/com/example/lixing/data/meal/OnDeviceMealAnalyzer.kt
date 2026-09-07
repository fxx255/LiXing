package com.example.lixing.data.meal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.example.lixing.di.IoDispatcher
import com.example.lixing.domain.meal.FoodNutritionEstimator
import com.example.lixing.domain.meal.MealAnalysis
import com.example.lixing.domain.meal.MealAnalyzer
import com.example.lixing.domain.meal.MealType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.classifier.ImageClassifier
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnDeviceMealAnalyzer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : MealAnalyzer {
    private val classifier: ImageClassifier by lazy {
        val options = ImageClassifier.ImageClassifierOptions.builder()
            .setMaxResults(3)
            .setScoreThreshold(0.03f)
            .build()
        ImageClassifier.createFromFileAndOptions(context, MODEL_FILE, options)
    }

    override suspend fun analyze(photoPath: String, mealType: MealType): MealAnalysis = withContext(io) {
        require(File(photoPath).isFile) { "照片不存在" }
        val bitmap = decodeUpright(photoPath) ?: error("无法读取照片")
        val categories = classifier.classify(TensorImage.fromBitmap(bitmap))
            .flatMap { it.categories }
            .sortedByDescending { it.score }
        val best = categories.firstOrNull()
        val second = categories.getOrNull(1)
        val rawBestLabel = best?.displayName?.takeIf { it.isNotBlank() }
            ?: best?.label?.takeIf { it.isNotBlank() }
        val confidence = best?.score ?: 0f
        // 单张照片无法测克重；低置信度或前两类过于接近时不冒充精确识别。
        val reliable = best != null && confidence >= MIN_RELIABLE_CONFIDENCE &&
            (second == null || confidence - second.score >= MIN_CLASS_MARGIN)
        val label = rawBestLabel.takeIf { reliable } ?: "待确认餐食"
        val estimated = FoodNutritionEstimator.estimate(
            labelOrName = label,
            mealType = mealType,
            // 照片只能分类，统一使用餐次默认克重，保证克重、热量和宏量营养来自同一次换算。
            grams = mealType.defaultPortionGrams,
            modelLabel = if (reliable) rawBestLabel.orEmpty() else "未可靠识别",
            confidence = if (reliable) confidence else 0f,
        )
        if (reliable) {
            estimated.copy(
                advice = "份量按${mealType.label}默认${mealType.defaultPortionGrams}g估算；照片无法准确测克重，建议点“校正”后再记录。${estimated.advice}",
            )
        } else {
            estimated.copy(
                advice = "照片识别置信度不足，已按${mealType.label}默认${mealType.defaultPortionGrams}g做保守估算；请点“校正”填写食物和实际份量。",
            )
        }
    }

    private fun decodeUpright(path: String): Bitmap? {
        val source = BitmapFactory.decodeFile(path) ?: return null
        val orientation = runCatching {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return source
        return Bitmap.createBitmap(
            source, 0, 0, source.width, source.height,
            Matrix().apply { postRotate(degrees) }, true,
        )
    }

    private companion object {
        const val MODEL_FILE = "food_classifier.tflite"
        const val MIN_RELIABLE_CONFIDENCE = 0.20f
        const val MIN_CLASS_MARGIN = 0.05f
    }
}
