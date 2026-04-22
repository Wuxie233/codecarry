package dev.minios.ocremote.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SessionListPreferencesRepositoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun createRepo(): SessionListPreferencesRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmpFolder.newFile("test_session_list_prefs.preferences_pb") },
        )
        return SessionListPreferencesRepository(dataStore = dataStore)
    }

    @Test
    fun `default preferences match DEFAULT constant`() = testScope.runTest {
        val repo = createRepo()
        val prefs = repo.preferences.first()
        assertEquals(SessionListPreferences.DEFAULT, prefs)
    }

    @Test
    fun `setCollapsed true then false returns correct state`() = testScope.runTest {
        val repo = createRepo()
        repo.setCollapsed("/home/user/project", collapsed = true)
        val afterCollapse = repo.preferences.first()
        assertTrue(afterCollapse.collapsedDirs.contains("/home/user/project"))

        repo.setCollapsed("/home/user/project", collapsed = false)
        val afterExpand = repo.preferences.first()
        assertFalse(afterExpand.collapsedDirs.contains("/home/user/project"))
    }

    @Test
    fun `togglePinned twice returns to empty pinnedDirs`() = testScope.runTest {
        val repo = createRepo()
        repo.togglePinned("/home/user/project")
        val afterFirst = repo.preferences.first()
        assertEquals(listOf("/home/user/project"), afterFirst.pinnedDirs)

        repo.togglePinned("/home/user/project")
        val afterSecond = repo.preferences.first()
        assertTrue(afterSecond.pinnedDirs.isEmpty())
    }

    @Test
    fun `addPinned emits pinned directory for ViewModel refresh`() = testScope.runTest {
        val repo = createRepo()

        repo.addPinned("/home/user/empty-project")

        val prefs = repo.preferences.first()
        assertEquals(listOf("/home/user/empty-project"), prefs.pinnedDirs)
    }

    @Test
    fun `setSort and setFilter are persisted correctly`() = testScope.runTest {
        val repo = createRepo()
        repo.setSort(SessionSort.TITLE_ALPHA)
        repo.setFilter(SessionFilter.HAS_ERRORS)
        val prefs = repo.preferences.first()
        assertEquals(SessionSort.TITLE_ALPHA, prefs.sort)
        assertEquals(SessionFilter.HAS_ERRORS, prefs.filter)
    }

    @Test
    fun `markMainSessionUnread adds session to unread set`() = testScope.runTest {
        val repo = createRepo()

        repo.markMainSessionUnread("session-1")

        val prefs = repo.preferences.first()
        assertTrue(prefs.unreadMainSessionIds.contains("session-1"))
    }

    @Test
    fun `markMainSessionRead removes session from unread set`() = testScope.runTest {
        val repo = createRepo()
        repo.markMainSessionUnread("session-1")

        repo.markMainSessionRead("session-1")

        val prefs = repo.preferences.first()
        assertFalse(prefs.unreadMainSessionIds.contains("session-1"))
    }

    @Test
    fun `markMainSessionsRead removes only requested sessions`() = testScope.runTest {
        val repo = createRepo()
        repo.markMainSessionUnread("session-1")
        repo.markMainSessionUnread("session-2")
        repo.markMainSessionUnread("session-3")

        repo.markMainSessionsRead(listOf("session-1", "session-3"))

        val prefs = repo.preferences.first()
        assertFalse(prefs.unreadMainSessionIds.contains("session-1"))
        assertTrue(prefs.unreadMainSessionIds.contains("session-2"))
        assertFalse(prefs.unreadMainSessionIds.contains("session-3"))
    }
}
