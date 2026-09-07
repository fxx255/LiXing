package com.example.lixing

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.classifier.ImageClassifier

/** 真机冒烟测试：不仅检查文件头，而是让 JNI 实际加载模型并完成一次推理。 */
@RunWith(AndroidJUnit4::class)
class MealModelSmokeTest {
    @Test
    fun bundledFoodModelLoadsAndRunsOnDevice() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val classifier = ImageClassifier.createFromFile(context, "food_classifier.tflite")
        val bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(180, 120, 70))
        }

        val result = classifier.classify(TensorImage.fromBitmap(bitmap))

        assertTrue("Classifier should return at least one classification head", result.isNotEmpty())
        assertTrue("Classifier should return at least one category", result.first().categories.isNotEmpty())
    }
}
