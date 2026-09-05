package dev.wuxie233.codecarry.ui.screens.codex

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.codex.CodexThreadItem
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class CodexSentImageInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sentUserImageRendersActualPixelsInTimeline() {
        val item = CodexThreadItem.fromJson(buildJsonObject {
            put("id", "sent-image-message")
            put("type", "userMessage")
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "Sent screenshot") })
                add(buildJsonObject { put("type", "image"); put("url", greenPngDataUrl()) })
            })
        })
        rule.setContent { MaterialTheme { CodexTimelineItem(item, onOpenThread = {}) } }
        rule.onNodeWithText("Sent screenshot").assertIsDisplayed()
        // Assert the decoded payload itself: an image count, icon, or empty
        // AsyncImage would satisfy semantics but must fail this regression.
        rule.waitUntil(timeoutMillis = 5_000) { renderedGreenPixelCount() >= 20 }
    }

    @Test fun historicalLocalImageLoadsFromRemotePathAndRendersPixels() {
        val remotePath = "/home/remote/screenshots/mobile.png"
        val requests = mutableListOf<String>()
        val item = localImageItem(remotePath)
        rule.setContent {
            MaterialTheme {
                CodexTimelineItem(item, onOpenThread = {}, loadRemoteImage = { path ->
                    requests.add(path)
                    greenPngBytes()
                })
            }
        }
        rule.waitUntil(timeoutMillis = 5_000) { renderedGreenPixelCount() >= 20 }
        rule.runOnIdle { assertEquals(listOf(remotePath), requests) }
    }

    @Test fun failedRemoteImageCanRetryWithoutLosingItsMessage() {
        val remotePath = "/home/remote/screenshots/retry.png"
        val requests = mutableListOf<String>()
        val item = localImageItem(remotePath)
        rule.setContent {
            MaterialTheme {
                CodexTimelineItem(item, onOpenThread = {}, loadRemoteImage = { path ->
                    requests.add(path)
                    if (requests.size == 1) error("Temporary remote read failure")
                    greenPngBytes()
                })
            }
        }
        val retry = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.codex_image_retry)
        rule.waitUntil(timeoutMillis = 5_000) { rule.onAllNodesWithText(retry).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(retry).assertIsDisplayed().performClick()
        rule.waitUntil(timeoutMillis = 5_000) { renderedGreenPixelCount() >= 20 }
        rule.onNodeWithText("Historical screenshot").assertIsDisplayed()
        rule.runOnIdle { assertEquals(listOf(remotePath, remotePath), requests) }
    }

    private fun localImageItem(path: String) = CodexThreadItem.fromJson(buildJsonObject {
        put("id", "historical-image-message")
        put("type", "userMessage")
        put("content", buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "Historical screenshot") })
            add(buildJsonObject { put("type", "localImage"); put("path", path) })
        })
    })

    private fun greenPngDataUrl() = "data:image/png;base64," + Base64.encodeToString(greenPngBytes(), Base64.NO_WRAP)

    private fun greenPngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(20, 210, 70))
        val bytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
        bitmap.recycle()
        return bytes
    }

    private fun renderedGreenPixelCount(): Int {
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        var count = 0
        val stride = maxOf(1, minOf(pixels.width, pixels.height) / 80)
        for (y in 0 until pixels.height step stride) {
            for (x in 0 until pixels.width step stride) {
                val color = pixels[x, y]
                if (color.red < 0.15f && color.green > 0.75f && color.blue in 0.2f..0.35f) count++
            }
        }
        return count
    }
}
