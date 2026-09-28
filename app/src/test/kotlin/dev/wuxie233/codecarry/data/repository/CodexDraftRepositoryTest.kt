package dev.wuxie233.codecarry.data.repository

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodexDraftRepositoryTest {
    @Test
    fun `drafts survive repository recreation and isolate server and thread keys`() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("codex_text_drafts", Context.MODE_PRIVATE).edit().clear().commit()
        val drafts = CodexDraftRepository(context)
        drafts.save("a", "b:c", "first")
        drafts.save("a:b", "c", "second")
        drafts.save("other", "b:c", "third")
        val restored = CodexDraftRepository(context)
        assertEquals("first", restored.get("a", "b:c")?.text)
        assertEquals("second", restored.get("a:b", "c")?.text)
        assertEquals("third", restored.get("other", "b:c")?.text)
    }

    @Test
    fun `old receipt cannot clear a newer identical draft`() {
        val drafts = CodexDraftRepository()
        val sent = drafts.save("server", "thread", "message")
        drafts.save("server", "thread", "")
        val next = drafts.save("server", "thread", "message")
        assertNotEquals(sent.revision, next.revision)
        assertNull(drafts.replaceIfRevision("server", "thread", sent.revision, ""))
        assertEquals("message", drafts.get("server", "thread")?.text)
        assertEquals("", drafts.replaceIfRevision("server", "thread", next.revision, "")?.text)
        assertNotNull(drafts.get("server", "thread"))
    }
}
