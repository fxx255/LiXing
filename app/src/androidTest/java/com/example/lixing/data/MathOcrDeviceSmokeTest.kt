package com.example.lixing.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lixing.data.assistant.LocalTextRecognizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class MathOcrDeviceSmokeTest {
    @Test
    fun bundledModelsAndOpenCvRunOnDevice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f }
            drawText("求极限", 100f, 180f, paint)
            drawText("lim  sin(x) / x = 1", 100f, 360f, paint)
            drawText("x -> 0", 160f, 440f, paint.apply { textSize = 44f })
        }
        val file = File(context.cacheDir, "math-ocr-smoke.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()

        val result = try {
            LocalTextRecognizer(context).recognizeDocument(listOf(file.absolutePath))
        } finally {
            file.delete()
        }

        assertFalse(result.hasBlockingErrors)
        assertTrue(result.markdown.isNotBlank())
    }
}
