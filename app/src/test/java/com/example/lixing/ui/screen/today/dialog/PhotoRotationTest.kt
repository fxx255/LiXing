package com.example.lixing.ui.screen.today.dialog

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.exifinterface.media.ExifInterface
import com.example.lixing.ui.photo.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PhotoRotationTest {
    @get:Rule val compose = createComposeRule()
    private fun photo(): File = File.createTempFile("rotation-", ".jpg").apply {
        outputStream().use { Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 100, it) }
        deleteOnExit()
    }
    @Test fun `normalizes EXIF before user counterclockwise rotation and does not rotate again on reopen`() {
        val file = photo()
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
        val upright = decodeUprightPhoto(file.path)!!
        assertEquals(80, upright.width)
        assertEquals(120, upright.height)
        rotatePhotoAndSave(file.path)
        val reopened = decodeUprightPhoto(file.path)!!
        assertEquals(120, reopened.width)
        assertEquals(80, reopened.height)
        assertEquals(0, ExifInterface(file).rotationDegrees)
    }
    @Test fun `rotation in crop dialog is provisional until confirmed`() {
        val file = photo()
        val before = file.readBytes()
        var cancelled = false
        compose.setContent { androidx.compose.material3.MaterialTheme {
            PhotoCropDialog(file.path, {}, { cancelled = true }, {})
        } }
        compose.onNodeWithText("逆时针 90°").performClick()
        compose.onNodeWithText("使用整张").assertExists()
        compose.onNodeWithText("取消").performClick()
        assertTrue(cancelled)
        assertArrayEquals(before, file.readBytes())
    }
    @Test fun `crop uses rotated source coordinates instead of original orientation`() {
        val file = photo()
        val rotated = rotatePhotoBitmap(decodeUprightPhoto(file.path)!!, -1)
        cropAndSave(file.path, rotated, IntSize(80, 120), 1f, Offset.Zero, Rect(0f, 0f, 40f, 120f), -1)
        val result = decodeUprightPhoto(file.path)!!
        assertEquals(40, result.width)
        assertEquals(120, result.height)
    }
    @Test fun `four counterclockwise turns preserve original dimensions`() {
        val original = decodeUprightPhoto(photo().path)!!
        var bitmap = original
        repeat(4) { bitmap = rotatePhotoBitmap(bitmap, -1) }
        assertEquals(original.width, bitmap.width)
        assertEquals(original.height, bitmap.height)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun `counterclockwise rotation moves top left pixel to bottom left`() {
        val source = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLACK)
        source.setPixel(0, 0, Color.RED)
        val rotated = rotatePhotoBitmap(source, -1)
        assertEquals(3, rotated.width)
        assertEquals(2, rotated.height)
        val pixels = (0 until rotated.height).joinToString(" / ") { y ->
            (0 until rotated.width).joinToString(",") { x -> rotated.getPixel(x, y).toString() }
        }
        assertEquals(pixels, Color.RED, rotated.getPixel(0, 1))
    }
}
