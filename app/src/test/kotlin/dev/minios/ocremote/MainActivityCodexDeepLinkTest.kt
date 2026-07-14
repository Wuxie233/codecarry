package dev.minios.ocremote

import android.content.Intent
import dev.minios.ocremote.service.OpenCodeConnectionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityCodexDeepLinkTest {
    @Test
    fun `Codex notification deep link uses only server and thread identity`() {
        val intent = Intent(OpenCodeConnectionService.ACTION_OPEN_CODEX_THREAD).apply {
            putExtra(OpenCodeConnectionService.EXTRA_SERVER_ID, "server-1")
            putExtra(OpenCodeConnectionService.EXTRA_THREAD_ID, "thread-1")
        }

        val deepLink = codexDeepLinkFromIntent(intent)

        assertEquals("server-1", deepLink?.codexServerId)
        assertEquals("thread-1", deepLink?.codexThreadId)
        assertTrue(deepLink?.serverUrl.isNullOrEmpty())
        assertTrue(deepLink?.password.isNullOrEmpty())
    }

    @Test
    fun `Codex notification deep link rejects missing identity`() {
        val intent = Intent(OpenCodeConnectionService.ACTION_OPEN_CODEX_THREAD).apply {
            putExtra(OpenCodeConnectionService.EXTRA_SERVER_ID, "server-1")
        }

        assertNull(codexDeepLinkFromIntent(intent))
    }
}
