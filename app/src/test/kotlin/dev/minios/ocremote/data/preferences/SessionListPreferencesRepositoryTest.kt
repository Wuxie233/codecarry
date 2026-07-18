package dev.minios.ocremote.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
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
    fun `view mode defaults to projects independently for each server`() = testScope.runTest {
        val repo = createRepo()

        assertEquals(SessionListViewMode.PROJECTS, repo.viewMode("server-a").first())
        assertEquals(SessionListViewMode.PROJECTS, repo.viewMode("server-b").first())
    }

    @Test
    fun `view mode is remembered per server`() = testScope.runTest {
        val repo = createRepo()

        repo.setViewMode("server-a", SessionListViewMode.ACTIVITY)

        assertEquals(SessionListViewMode.ACTIVITY, repo.viewMode("server-a").first())
        assertEquals(SessionListViewMode.PROJECTS, repo.viewMode("server-b").first())
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

        val firstAddChanged = repo.addPinned("/home/user/empty-project")
        val duplicateAddChanged = repo.addPinned("/home/user/empty-project")

        val prefs = repo.preferences.first()
        assertTrue(firstAddChanged)
        assertFalse(duplicateAddChanged)
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
    fun `setScope persists value to ARCHIVED then INBOX`() = testScope.runTest {
        val repo = createRepo()

        repo.setScope(SessionScope.ARCHIVED)
        assertEquals(SessionScope.ARCHIVED, repo.preferences.first().scope)

        repo.setScope(SessionScope.INBOX)
        assertEquals(SessionScope.INBOX, repo.preferences.first().scope)
    }

    @Test
    fun `default scope is INBOX`() = testScope.runTest {
        val repo = createRepo()
        assertEquals(SessionScope.INBOX, repo.preferences.first().scope)
    }

    @Test
    fun `legacy filter ARCHIVED migrates to scope ARCHIVED and filter ALL`() = testScope.runTest {
        // Seed the underlying DataStore directly with the legacy persisted string
        // before constructing the repository instance under test.
        val file = tmpFolder.newFile("legacy_session_list_prefs.preferences_pb")
        val seedDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
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
