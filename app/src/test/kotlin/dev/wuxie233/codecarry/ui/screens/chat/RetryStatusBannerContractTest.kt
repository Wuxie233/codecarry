package dev.wuxie233.codecarry.ui.screens.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RetryStatusBannerContractTest {

    private val chatScreenSource = File("src/main/kotlin/dev/wuxie233/codecarry/ui/screens/chat/ChatScreen.kt").readText()
    private val chatHeaderSource = File("src/main/kotlin/dev/wuxie233/codecarry/ui/screens/chat/ChatHeader.kt").readText()
    private val bannerSource = chatScreenSource
        .substringAfter("private fun RetryStatusBanner(")
        .substringBefore("/**\n * Edit tool card")

    @Test
    fun `retry banner exposes visible localized retry-now action with pending feedback`() {
        assertTrue(bannerSource.contains("onRetryNow: () -> Unit"))
        assertTrue(bannerSource.contains("isRetryingNow: Boolean"))
        assertTrue(bannerSource.contains("stringResource(R.string.chat_retry_now)"))
        assertTrue(bannerSource.contains("stringResource(R.string.chat_retrying_now)"))
        assertTrue(bannerSource.contains("TextButton("))
        assertTrue(bannerSource.contains("CircularProgressIndicator("))
    }

    @Test
    fun `retry banner actions expose descriptions and forty-eight-dp targets`() {
        assertTrue(bannerSource.contains("contentDescription = retryActionDescription"))
        assertFalse(bannerSource.contains(".weight(1f, fill = false)"))
        assertTrue(
            Regex(
                """Row\(\s*modifier\s*=\s*Modifier\.fillMaxWidth\(\),\s*horizontalArrangement\s*=\s*Arrangement\.End,[\s\S]*?TextButton\([\s\S]*?IconButton\(""",
            ).containsMatchIn(bannerSource),
        )
        assertTrue(
            Regex(
                """TextButton\([\s\S]*?defaultMinSize\(\s*minWidth\s*=\s*48\.dp,\s*minHeight\s*=\s*48\.dp\s*\)""",
            ).containsMatchIn(bannerSource),
        )
        assertTrue(bannerSource.contains("contentDescription = stringResource(R.string.chat_stop)"))
        assertTrue(
            Regex(
                """IconButton\([\s\S]*?onClick\s*=\s*onStop,[\s\S]*?Modifier\.size\(48\.dp\)""",
            ).containsMatchIn(bannerSource),
        )
    }

    @Test
    fun `retry stop descriptions use localized resources`() {
        assertFalse(chatScreenSource.contains("停止重试"))
        assertTrue(chatScreenSource.contains("canStop = uiState.sessionStatus.isInterruptible && uiState.supportsAbort"))
        assertTrue(chatHeaderSource.contains("if (canStop)"))
        assertTrue(chatHeaderSource.contains("contentDescription = stringResource(R.string.chat_stop)"))
    }
}
