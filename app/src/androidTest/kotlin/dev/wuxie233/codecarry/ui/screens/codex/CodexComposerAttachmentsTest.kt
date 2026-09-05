package dev.wuxie233.codecarry.ui.screens.codex

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import dev.wuxie233.codecarry.data.codex.CodexUserInput
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

@RunWith(AndroidJUnit4::class)
class CodexComposerAttachmentsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

    @Test
    fun imageIsEmbeddedAsRemoteDataUrlWithMatchingPreviewBytes() = runBlocking {
        val source = createImage("image.jpg", 160, 80)

        val attachment = loadCodexImage(InstrumentationRegistry.getInstrumentation().targetContext, Uri.fromFile(source), "Screenshot")

        assertEquals("Screenshot", attachment.label)
        assertTrue(attachment.id.isNotBlank())
        assertTrue(attachment.input is CodexUserInput.Image)
        val wire = attachment.input.toJson()
        assertEquals("image", wire.getValue("type").jsonPrimitive.content)
        assertFalse(wire.containsKey("path"))
        val url = wire.getValue("url").jsonPrimitive.content
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        assertFalse(url.contains(source.absolutePath))
        val payload = Base64.decode(url.substringAfter(','), Base64.NO_WRAP)
        assertArrayEquals(payload, attachment.previewBytes)
        val decoded = BitmapFactory.decodeByteArray(payload, 0, payload.size)
        try {
            assertEquals(160, decoded.width)
            assertEquals(80, decoded.height)
        } finally { decoded.recycle() }
    }

    @Test
    fun largePhotoIsDownsampledAndExifRotationIsAppliedToPixels() = runBlocking {
        val source = createImage("rotated.jpg", 4096, 1024)
        ExifInterface(source.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val attachment = loadCodexImage(InstrumentationRegistry.getInstrumentation().targetContext, Uri.fromFile(source), "Camera")
        val bytes = requireNotNull(attachment.previewBytes)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        try {
            assertEquals(512, decoded.width)
            assertEquals(2048, decoded.height)
            assertTrue(maxOf(decoded.width, decoded.height) <= 2048)
            // The original red left half becomes the top after clockwise EXIF rotation.
            val top = decoded.getPixel(decoded.width / 2, decoded.height / 4)
            val bottom = decoded.getPixel(decoded.width / 2, decoded.height * 3 / 4)
            assertTrue(Color.red(top) > Color.blue(top) + 100)
            assertTrue(Color.blue(bottom) > Color.red(bottom) + 100)
        } finally { decoded.recycle() }
    }

    @Test
    fun corruptImageFailsInsteadOfEmittingLocalOrEmptyAttachment() = runBlocking {
        val source = temporaryFolder.newFile("corrupt.jpg").apply { writeText("This is not image data") }

        val failure = runCatching {
            loadCodexImage(InstrumentationRegistry.getInstrumentation().targetContext, Uri.fromFile(source), "Broken")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("Unsupported image", failure?.message)
    }

    private fun createImage(name: String, width: Int, height: Int): File {
        val source = temporaryFolder.newFile(name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLUE)
            canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = Color.RED })
            source.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
            }
        } finally { bitmap.recycle() }
        return source
    }
}
