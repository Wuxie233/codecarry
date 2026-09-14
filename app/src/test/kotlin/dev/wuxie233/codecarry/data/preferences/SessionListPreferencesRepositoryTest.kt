package dev.wuxie233.codecarry.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.wuxie233.codecarry.domain.model.Message
import dev.wuxie233.codecarry.domain.model.Part
import dev.wuxie233.codecarry.domain.model.TimeInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SessionListPreferencesRepositoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun createRepo(scope: kotlinx.coroutines.CoroutineScope): SessionListPreferencesRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tmpFolder.newFile("test_session_list_prefs.preferences_pb") },
        )
        return SessionListPreferencesRepository(dataStore = dataStore)
    }

    @Test
    fun `default preferences match DEFAULT constant`() = runTest {
        val repo = createRepo(backgroundScope)
        val prefs = repo.preferences.first()
        assertEquals(SessionListPreferences.DEFAULT, prefs)
    }

    @Test
    fun `view mode defaults to projects independently for each server`() = runTest {
        val repo = createRepo(backgroundScope)

        assertEquals(SessionListViewMode.PROJECTS, repo.viewMode("server-a").first())
        assertEquals(SessionListViewMode.PROJECTS, repo.viewMode("server-b").first())
    }

    @Test
    fun `view mode is remembered per server`() = runTest {
        val repo = createRepo(backgroundScope)

        repo.setViewMode("server-a", SessionListViewMode.ACTIVITY)

        assertEquals(SessionListViewMode.ACTIVITY, repo.viewMode("server-a").first())
        assertEquals(SessionListViewMode.PROJECTS, repo.viewMode("server-b").first())
    }

    @Test
    fun `setCollapsed true then false returns correct state`() = runTest {
        val repo = createRepo(backgroundScope)
        repo.setCollapsed("/home/user/project", collapsed = true)
        val afterCollapse = repo.preferences.first()
        assertTrue(afterCollapse.collapsedDirs.contains("/home/user/project"))

        repo.setCollapsed("/home/user/project", collapsed = false)
        val afterExpand = repo.preferences.first()
        assertFalse(afterExpand.collapsedDirs.contains("/home/user/project"))
    }

    @Test
    fun `togglePinned twice returns to empty pinnedDirs`() = runTest {
        val repo = createRepo(backgroundScope)
        repo.togglePinned("/home/user/project")
        val afterFirst = repo.preferences.first()
        assertEquals(listOf("/home/user/project"), afterFirst.pinnedDirs)

        repo.togglePinned("/home/user/project")
        val afterSecond = repo.preferences.first()
        assertTrue(afterSecond.pinnedDirs.isEmpty())
    }

    @Test
    fun `addPinned emits pinned directory for ViewModel refresh`() = runTest {
        val repo = createRepo(backgroundScope)

        val firstAddChanged = repo.addPinned("/home/user/empty-project")
        val duplicateAddChanged = repo.addPinned("/home/user/empty-project")

        val prefs = repo.preferences.first()
        assertTrue(firstAddChanged)
        assertFalse(duplicateAddChanged)
        assertEquals(listOf("/home/user/empty-project"), prefs.pinnedDirs)
    }

    @Test
    fun `setSort and setFilter are persisted correctly`() = runTest {
        val repo = createRepo(backgroundScope)
        repo.setSort(SessionSort.TITLE_ALPHA)
        repo.setFilter(SessionFilter.HAS_ERRORS)
        val prefs = repo.preferences.first()
        assertEquals(SessionSort.TITLE_ALPHA, prefs.sort)
        assertEquals(SessionFilter.HAS_ERRORS, prefs.filter)
    }

    @Test
    fun `setScope persists value to ARCHIVED then INBOX`() = runTest {
        val repo = createRepo(backgroundScope)

        repo.setScope(SessionScope.ARCHIVED)
        assertEquals(SessionScope.ARCHIVED, repo.preferences.first().scope)

        repo.setScope(SessionScope.INBOX)
        assertEquals(SessionScope.INBOX, repo.preferences.first().scope)
    }

    @Test
    fun `default scope is INBOX`() = runTest {
        val repo = createRepo(backgroundScope)
        assertEquals(SessionScope.INBOX, repo.preferences.first().scope)
    }

    @Test
    fun `legacy filter ARCHIVED migrates to scope ARCHIVED and filter ALL`() = runTest {
        // Seed the underlying DataStore directly with the legacy persisted string
        // before constructing the repository instance under test.
        val file = tmpFolder.newFile("legacy_session_list_prefs.preferences_pb")
        val seedDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file },
        )
        seedDataStore.edit { mutable ->
            mutable[androidx.datastore.preferences.core.stringPreferencesKey("filter")] = "ARCHIVED"
        }
        // Build a repository against the seeded DataStore. Reusing the instance
        // avoids DataStore's single-active-instance-per-file guard in tests.
        val repo = SessionListPreferencesRepository(
            dataStore = seedDataStore,
        )

        val migrated = repo.preferences.first()

        assertEquals(SessionScope.ARCHIVED, migrated.scope)
        assertEquals(SessionFilter.ALL, migrated.filter)
    }

    // ============ Server-scoped unread marks (issue #34) ============

    @Test
    fun `markConversationUnread adds conversation to that server's set only`() = runTest {
        val repo = createRepo(backgroundScope)

        repo.markConversationUnread("server-a", "session-1")

        assertTrue("session-1" in repo.unreadConversationIds("server-a").first())
        assertFalse("session-1" in repo.unreadConversationIds("server-b").first())
    }

    @Test
    fun `duplicate session id on two servers never shares read state`() = runTest {
        val repo = createRepo(backgroundScope)
        val sharedId = "ses_shared"

        repo.markConversationUnread("server-a", sharedId)
        repo.markConversationUnread("server-b", sharedId)

        // Reading on server A must not clear the mark on server B.
        repo.markConversationRead("server-a", sharedId)

        assertFalse(sharedId in repo.unreadConversationIds("server-a").first())
        assertTrue(sharedId in repo.unreadConversationIds("server-b").first())

        // Marking unread on server B after A read must not resurrect A's mark.
        repo.markConversationUnread("server-b", sharedId)
        assertFalse(sharedId in repo.unreadConversationIds("server-a").first())
        assertTrue(sharedId in repo.unreadConversationIds("server-b").first())

        repo.markConversationRead("server-b", sharedId)
        assertFalse(sharedId in repo.unreadConversationIds("server-b").first())
    }

    @Test
    fun `markConversationRead removes only the requested conversation`() = runTest {
        val repo = createRepo(backgroundScope)
        repo.markConversationUnread("server-a", "session-1")
        repo.markConversationUnread("server-a", "session-2")
        repo.markConversationUnread("server-a", "session-3")

        repo.markConversationRead("server-a", "session-1")
        repo.markConversationRead("server-a", "session-3")

        val unread = repo.unreadConversationIds("server-a").first()
        assertFalse("session-1" in unread)
        assertTrue("session-2" in unread)
        assertFalse("session-3" in unread)
    }

    @Test
    fun `legacy global unread ids are attributed to every server and self-heal on read`() = runTest {
        val file = tmpFolder.newFile("legacy_unread.preferences_pb")
        val seedDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file },
        )
        seedDataStore.edit { mutable ->
            mutable[stringSetPreferencesKey("unread_main_session_ids")] = setOf("legacy-1", "legacy-2")
        }
        val repo = SessionListPreferencesRepository(dataStore = seedDataStore)

        // Over-attribution: every server sees the legacy ids on first read.
        assertTrue("legacy-1" in repo.unreadConversationIds("server-a").first())
        assertTrue("legacy-1" in repo.unreadConversationIds("server-b").first())

        // Reading on one server heals the legacy mark everywhere (spec C2).
        repo.markConversationRead("server-a", "legacy-1")
        assertFalse("legacy-1" in repo.unreadConversationIds("server-a").first())
        assertFalse("legacy-1" in repo.unreadConversationIds("server-b").first())
        assertTrue("legacy-2" in repo.unreadConversationIds("server-b").first())

        // New marks stay per-server and never re-enter the legacy set.
        repo.markConversationUnread("server-b", "legacy-1")
        assertFalse("legacy-1" in repo.unreadConversationIds("server-a").first())
        assertTrue("legacy-1" in repo.unreadConversationIds("server-b").first())
    }

    // ============ Read anchors (issue #35) ============

    @Test
    fun `read anchor is persisted per server and conversation pair`() = runTest {
        val repo = createRepo(backgroundScope)

        repo.setReadAnchor("server-a", "session-1", "msg-9")
        repo.setReadAnchor("server-b", "session-1", "msg-4")

        assertEquals("msg-9", repo.readAnchor("server-a", "session-1").first())
        assertEquals("msg-4", repo.readAnchor("server-b", "session-1").first())
        assertNull(repo.readAnchor("server-a", "session-2").first())

        repo.setReadAnchor("server-a", "session-1", null)
        assertNull(repo.readAnchor("server-a", "session-1").first())
    }

    @Test
    fun `anchor keys survive ids containing slashes`() = runTest {
        val repo = createRepo(backgroundScope)

        repo.setReadAnchor("srv/odd", "ses/a", "m1")
        repo.setReadAnchor("srv", "odd ses/a", "m2")

        assertEquals("m1", repo.readAnchor("srv/odd", "ses/a").first())
        assertEquals("m2", repo.readAnchor("srv", "odd ses/a").first())
    }

    // ============ Pure unread derivation helpers (issues #33/#35) ============

    @Test
    fun `no readable output means not unread regardless of anchor`() {
        assertFalse(hasUnreadReplyBeyondReadAnchor(anchor = null, readableAssistantMessageIdsInOrder = emptyList()))
        assertFalse(hasUnreadReplyBeyondReadAnchor(anchor = "m1", readableAssistantMessageIdsInOrder = emptyList()))
    }

    @Test
    fun `missing anchor with readable output is unread`() {
        assertTrue(hasUnreadReplyBeyondReadAnchor(anchor = null, readableAssistantMessageIdsInOrder = listOf("m1", "m2")))
    }

    @Test
    fun `anchor at the latest readable output is read`() {
        assertFalse(hasUnreadReplyBeyondReadAnchor(anchor = "m2", readableAssistantMessageIdsInOrder = listOf("m1", "m2")))
    }

    @Test
    fun `readable output beyond the anchor is unread`() {
        assertTrue(hasUnreadReplyBeyondReadAnchor(anchor = "m1", readableAssistantMessageIdsInOrder = listOf("m1", "m2")))
        assertTrue(hasUnreadReplyBeyondReadAnchor(anchor = "m1", readableAssistantMessageIdsInOrder = listOf("m1", "m2", "m3")))
    }

    @Test
    fun `anchor that no longer resolves in known history is conservatively unread`() {
        assertTrue(hasUnreadReplyBeyondReadAnchor(anchor = "gone", readableAssistantMessageIdsInOrder = listOf("m1", "m2")))
    }

    @Test
    fun `readableAssistantOutputIds keeps only assistant text or reasoning output in order`() {
        val user = Message.User(id = "u1", sessionId = "s", time = TimeInfo(created = 1))
        val toolOnly = Message.Assistant(id = "a1", sessionId = "s", time = TimeInfo(created = 2))
        val withText = Message.Assistant(id = "a2", sessionId = "s", time = TimeInfo(created = 3))
        val withReasoning = Message.Assistant(id = "a3", sessionId = "s", time = TimeInfo(created = 4))
        val errorOnly = Message.Assistant(
            id = "a4",
            sessionId = "s",
            time = TimeInfo(created = 5),
            error = Message.Assistant.ErrorInfo(name = "boom"),
        )
        val partsById = mapOf(
            "a1" to listOf<Part>(Part.Tool(id = "p1", sessionId = "s", messageId = "a1", tool = "bash")),
            "a2" to listOf<Part>(Part.Text(id = "p2", sessionId = "s", messageId = "a2", text = "hello")),
            "a3" to listOf<Part>(Part.Reasoning(id = "p3", sessionId = "s", messageId = "a3", text = "thinking")),
            "a4" to emptyList<Part>(),
        )

        val ids = readableAssistantOutputIds(
            messages = listOf(user, toolOnly, withText, withReasoning, errorOnly),
            partsById = { id -> partsById[id].orEmpty() },
        )

        // Tool-only and error-only assistant output must not fabricate unread.
        assertEquals(listOf("a2", "a3"), ids)
    }
}
