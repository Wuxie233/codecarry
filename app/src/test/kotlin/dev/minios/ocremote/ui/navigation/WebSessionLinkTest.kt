package dev.minios.ocremote.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64 as JavaBase64

/**
 * Verifies buildWebSessionLink produces exactly the same URL the in-app WebView
 * loads (WebViewScreen: serverUrl.trimEnd('/') + initialPath, where
 * initialPath = "/<base64url(directory)>/session/<sessionId>"), and never embeds
 * credentials. This is the link copied to the clipboard by the chat overflow menu.
 */
class WebSessionLinkTest {

    /** Reference url-safe, unpadded base64 (matches original NavGraph +/->-_ + strip '=' behavior). */
    private fun refEncodedDir(dir: String): String =
        JavaBase64.getUrlEncoder().withoutPadding().encodeToString(dir.toByteArray(Charsets.UTF_8))

    @Test
    fun `happy path builds serverUrl + base64url dir + session path`() {
        val dir = "/home/user/proj"
        val expected = "http://192.168.0.10:4096/${refEncodedDir(dir)}/session/ses_abc123"
        assertEquals(expected, buildWebSessionLink("http://192.168.0.10:4096", dir, "ses_abc123"))
    }

    @Test
    fun `trailing slash on serverUrl is trimmed`() {
        val dir = "/home/user/proj"
        val expected = "http://192.168.0.10:4096/${refEncodedDir(dir)}/session/ses_abc123"
        assertEquals(expected, buildWebSessionLink("http://192.168.0.10:4096/", dir, "ses_abc123"))
    }

    @Test
    fun `empty directory yields empty base64 segment matching original behavior`() {
        // base64url("") == "" -> path "//session/<id>", same as the original NavGraph code.
        assertEquals(
            "http://h:4096//session/ses1",
            buildWebSessionLink("http://h:4096", "", "ses1")
        )
    }

    @Test
    fun `encoding is url-safe with no padding`() {
        // A directory whose base64 would contain '+' '/' '=' in the standard alphabet.
        val link = buildWebSessionLink("http://h:4096", "/aaa/bbb/ccc?>", "ses1")
        val segment = link.removePrefix("http://h:4096/").substringBefore("/session/")
        assertFalse("must not contain '+'", segment.contains('+'))
        assertFalse("must not contain '/'", segment.contains('/'))
        assertFalse("must not contain '=' padding", segment.contains('='))
    }

    @Test
    fun `does not embed basic auth credentials`() {
        val link = buildWebSessionLink("http://192.168.0.10:4096", "/proj", "ses1")
        assertFalse(link, link.contains("@"))
        assertTrue(link, link.startsWith("http://192.168.0.10:4096/"))
    }
}
