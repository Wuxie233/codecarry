package dev.wuxie233.codecarry.ui.screens.codex

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import dev.wuxie233.codecarry.data.codex.CodexThread
import dev.wuxie233.codecarry.data.codex.CodexThreadStatus
import dev.wuxie233.codecarry.ui.theme.OpenCodeTheme
import java.io.File
import org.junit.Rule
import org.junit.Test

/** Captures the same stateless screen that the live Hilt route renders. */
class CodexListVisualParityTest {
    @get:Rule val rule = createComposeRule()

    @Test fun captureLightProjectList() = capture(dark = false, amoled = false, name = "codex-project-list-light.png")
    @Test fun captureAmoledProjectList() = capture(dark = true, amoled = true, name = "codex-project-list-amoled.png")

    private fun capture(dark: Boolean, amoled: Boolean, name: String) {
        val threads = listOf(
            CodexThread("layout", name = "Improve mobile chat layout", cwd = "/workspace/codecarry",
                preview = "Duplicated preview must not occupy the compact session row", updatedAt = 1788670200,
                status = CodexThreadStatus(type = "active")),
            CodexThread("images", name = "Restore image previews", cwd = "/workspace/codecarry", updatedAt = 1788669600),
            CodexThread("presets", name = "Review existing conversation presets", cwd = "/workspace/dsh", updatedAt = 1788666000),
        )
        rule.setContent {
            OpenCodeTheme(darkTheme = dark, dynamicColor = false, amoledDark = amoled) {
                CodexThreadListContent(
                    state = CodexThreadListUiState(serverName = "Development server", activeThreads = threads,
                        isLoading = false, pendingRequestCounts = mapOf("presets" to 1)),
                    onNavigateBack = {}, onOpenThread = {}, actions = CodexThreadListActions(),
                )
            }
        }
        rule.onNodeWithText("Duplicated preview must not occupy the compact session row").assertDoesNotExist()
        rule.mainClock.autoAdvance = false
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("visual-review")!!
        directory.mkdirs()
        File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
