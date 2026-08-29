package dev.wuxie233.codecarry.ui.screens.sessions

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.wuxie233.codecarry.data.api.OpenCodeApi
import dev.wuxie233.codecarry.data.dsh.DshApiClient
import dev.wuxie233.codecarry.data.dsh.DshConnectionManager
import dev.wuxie233.codecarry.data.dsh.DshSessionSummary
import dev.wuxie233.codecarry.data.dsh.unusedDshApi
import dev.wuxie233.codecarry.data.dsh.unusedDshConnectionManager
import dev.wuxie233.codecarry.data.dsh.unusedDshDownlinks
import dev.wuxie233.codecarry.data.diagnostics.AppEventDiagnosticsGenerator
import dev.wuxie233.codecarry.data.diagnostics.DiagnosticsLogRepository
import dev.wuxie233.codecarry.data.preferences.SessionFilter
import dev.wuxie233.codecarry.data.preferences.SessionListPreferencesRepository
import dev.wuxie233.codecarry.data.preferences.SessionScope
import dev.wuxie233.codecarry.data.repository.EventReducer
import dev.wuxie233.codecarry.data.repository.SettingsRepository
import dev.wuxie233.codecarry.domain.model.Session
import dev.wuxie233.codecarry.domain.model.SessionStatus
import dev.wuxie233.codecarry.domain.model.SseEvent
import dev.wuxie233.codecarry.ui.screens.sessions.components.ConversationStatus
import dev.wuxie233.codecarry.ui.navigation.Screen
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
class SessionListViewModelTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val testScope = TestScope(dispatcher)
    private val collectJobs = mutableListOf<Job>()
    private val viewModels = mutableListOf<SessionListViewModel>()

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        testScope.runCurrent()
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        testScope.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun archivedSessionsAreHiddenInInboxScope() {
        val item = SessionItem(session = testSession(id = "archived", archived = 1_000L), status = SessionStatus.Idle)

        assertFalse(matchesScopeAndFilter(item, SessionScope.INBOX, SessionFilter.ALL))
        assertTrue(matchesScopeAndFilter(item, SessionScope.ARCHIVED, SessionFilter.ALL))
    }

    @Test
    fun activeSessionsAreHiddenInArchivedScope() {
        val item = SessionItem(session = testSession(id = "active", archived = null), status = SessionStatus.Idle)

        assertTrue(matchesScopeAndFilter(item, SessionScope.INBOX, SessionFilter.ALL))
        assertFalse(matchesScopeAndFilter(item, SessionScope.ARCHIVED, SessionFilter.ALL))
    }

    @Test
    fun statusFilterIsIgnoredInArchivedScope() {
        // An archived idle session would not match WORKING in inbox,
        // but in Archived scope status filters are forced to ALL.
        val item = SessionItem(session = testSession(id = "a", archived = 1_000L), status = SessionStatus.Idle)

        assertTrue(matchesScopeAndFilter(item, SessionScope.ARCHIVED, SessionFilter.WORKING))
        assertTrue(matchesScopeAndFilter(item, SessionScope.ARCHIVED, SessionFilter.HAS_ERRORS))
    }

    @Test
    fun inboxScopeStillRespectsStatusFilter() {
        val idleActive = SessionItem(session = testSession(id = "idle", archived = null), status = SessionStatus.Idle)

        assertTrue(matchesScopeAndFilter(idleActive, SessionScope.INBOX, SessionFilter.ALL))
        assertFalse(matchesScopeAndFilter(idleActive, SessionScope.INBOX, SessionFilter.WORKING))
    }

    @Test
    fun matchesScopeReportsArchivedFlagDirectly() {
        val active = SessionItem(session = testSession(id = "a", archived = null), status = SessionStatus.Idle)
        val archived = SessionItem(session = testSession(id = "b", archived = 1L), status = SessionStatus.Idle)

        assertTrue(matchesScope(active, SessionScope.INBOX))
        assertFalse(matchesScope(active, SessionScope.ARCHIVED))
        assertFalse(matchesScope(archived, SessionScope.INBOX))
        assertTrue(matchesScope(archived, SessionScope.ARCHIVED))
    }

    @Test
    fun archiveableRootSessionIdsOnlyIncludeActiveRootsInDirectory() {
        val ids = archiveableRootSessionIds(
            sessions = listOf(
                testSession(id = "root-active", directory = "/workspace/project", archived = null),
                testSession(id = "root-archived", directory = "/workspace/project", archived = 1_000L),
                testSession(id = "child-active", directory = "/workspace/project", parentId = "root-active", archived = null),
                testSession(id = "other-root", directory = "/workspace/other", archived = null),
            ),
            directory = "/workspace/project",
            normalizeDirectory = { it.trimEnd('/').ifEmpty { "/" } },
        )

        assertEquals(listOf("root-active"), ids)
    }

    @Test
    fun `deriveAllDirectories includes pinned empty directory`() {
        val directories = deriveAllDirectories(
            projectDirectories = emptyList(),
            rootSessionDirectories = emptyList(),
            pinnedDirectories = listOf("/workspace/empty-project"),
        )

        assertEquals(listOf("/workspace/empty-project"), directories)
    }

    @Test
    fun `deriveAllDirectories deduplicates pinned and active directories`() {
        val directories = deriveAllDirectories(
            projectDirectories = listOf("/workspace/project-a"),
            rootSessionDirectories = listOf("/workspace/project-b", "/workspace/project-a"),
            pinnedDirectories = listOf("/workspace/project-b", "/workspace/project-c"),
        )

        assertEquals(
            listOf("/workspace/project-a", "/workspace/project-b", "/workspace/project-c"),
            directories,
        )
    }

    @Test
    fun `Pi Stack directory browser keeps server paths absolute`() {
        assertEquals("/root/CODE", directoryDisplayPath("/root/CODE", "/root/CODE", useTilde = false))
        assertEquals("/root/CODE/oc-remote", directoryDisplayPath("/root/CODE/oc-remote", "/root/CODE", useTilde = false))
    }

    @Test
    fun `OpenCode directory browser abbreviates only paths inside home`() {
        assertEquals("~", directoryDisplayPath("/root", "/root", useTilde = true))
        assertEquals("~/CODE", directoryDisplayPath("/root/CODE", "/root", useTilde = true))
        assertEquals("/rooted/project", directoryDisplayPath("/rooted/project", "/root", useTilde = true))
    }

    @Test
    fun `pinned empty project group stays visible`() {
        val pinnedEmptyGroup = ProjectGroup(
            directory = "/workspace/empty-project",
            projectName = "empty-project",
            tildeDirectory = "~/empty-project",
            isPinned = true,
            isCollapsed = false,
            isHidden = false,
            sessionCount = 0,
            activeCount = 0,
            additionsSum = 0,
            deletionsSum = 0,
            sessions = emptyList(),
            subagentRowsByParent = emptyMap(),
        )
        val unpinnedEmptyGroup = pinnedEmptyGroup.copy(isPinned = false, directory = "/workspace/unpinned")

        assertTrue(isProjectGroupVisible(pinnedEmptyGroup, showHiddenProjects = false))
        assertFalse(isProjectGroupVisible(unpinnedEmptyGroup, showHiddenProjects = false))
    }

    @Test
    fun `registered Pi Stack project stays visible without sessions or local pin`() {
        val group = ProjectGroup(
            projectId = "opaque-project-id",
            directory = "/srv/projects/empty",
            projectName = "empty",
            tildeDirectory = "/srv/projects/empty",
            isPinned = false,
            isCollapsed = false,
            isHidden = false,
            sessionCount = 0,
            activeCount = 0,
            additionsSum = 0,
            deletionsSum = 0,
            sessions = emptyList(),
            subagentRowsByParent = emptyMap(),
            isRegistered = true,
        )

        assertTrue(isProjectGroupVisible(group, showHiddenProjects = false))
        assertTrue(computeSessionListEmptyState(0, 1, registeredProjectCount = 1).hasAnySessions)
    }

    @Test
    fun `pinDirectoryRefreshTargets keeps session refresh explicit after duplicate pin attempts`() {
        assertEquals(
            setOf(PinDirectoryRefreshTarget.PROJECTS, PinDirectoryRefreshTarget.SESSIONS),
            pinDirectoryRefreshTargets(changed = true),
        )
        assertEquals(
            setOf(PinDirectoryRefreshTarget.SESSIONS),
            pinDirectoryRefreshTargets(changed = false),
        )
    }

    @Test
    fun `running child subagent keeps idle parent conversation active`() = runTest(dispatcher) {
        val eventReducer = EventReducer()
        val parent = testSession(id = "parent")
        val child = testSession(id = "child", parentId = parent.id)
        val vm = newSessionListViewModel(eventReducer = eventReducer)
        collectUiState(vm)
        eventReducer.setSessions("srv-session-list", listOf(parent, child))

        eventReducer.updateSessionStatus(child.id, SessionStatus.Busy)
        advanceUntilIdle()

        val active = vm.uiState.value.activeConversations.single()
        assertEquals(parent.id, active.sessionId)
        assertEquals(ConversationStatus.BUSY, active.status)
        val group = vm.uiState.value.groups.single()
        assertEquals(SessionStatus.Busy, group.sessions.single().status)
        assertEquals(1, group.activeCount)
    }

    @Test
    fun `retrying child subagent keeps idle parent conversation active`() = runTest(dispatcher) {
        val eventReducer = EventReducer()
        val parent = testSession(id = "parent")
        val child = testSession(id = "child", parentId = parent.id)
        val vm = newSessionListViewModel(eventReducer = eventReducer)
        collectUiState(vm)
        eventReducer.setSessions("srv-session-list", listOf(parent, child))

        eventReducer.updateSessionStatus(
            child.id,
            SessionStatus.Retry(attempt = 1, message = "retrying", next = 0L),
        )
        advanceUntilIdle()

        val active = vm.uiState.value.activeConversations.single()
        assertEquals(parent.id, active.sessionId)
        assertEquals(ConversationStatus.RETRY, active.status)
        assertEquals(SessionStatus.Retry::class, vm.uiState.value.groups.single().sessions.single().status::class)
    }

    @Test
    fun `busy child outranks retrying parent conversation`() = runTest(dispatcher) {
        val eventReducer = EventReducer()
        val parent = testSession(id = "parent")
        val child = testSession(id = "child", parentId = parent.id)
        val vm = newSessionListViewModel(eventReducer = eventReducer)
        collectUiState(vm)
        eventReducer.setSessions("srv-session-list", listOf(parent, child))
        eventReducer.updateSessionStatus(
            parent.id,
            SessionStatus.Retry(attempt = 1, message = "retrying", next = 0L),
        )

        eventReducer.updateSessionStatus(child.id, SessionStatus.Busy)
        advanceUntilIdle()

        assertEquals(ConversationStatus.BUSY, vm.uiState.value.activeConversations.single().status)
        assertEquals(SessionStatus.Busy, vm.uiState.value.groups.single().sessions.single().status)
    }

    @Test
    fun `parent pending question outranks running child subagent`() = runTest(dispatcher) {
        val eventReducer = EventReducer()
        val parent = testSession(id = "parent")
        val child = testSession(id = "child", parentId = parent.id)
        val vm = newSessionListViewModel(eventReducer = eventReducer)
        collectUiState(vm)
        eventReducer.setSessions("srv-session-list", listOf(parent, child))
        eventReducer.updateSessionStatus(child.id, SessionStatus.Busy)

        eventReducer.setQuestions(
            "srv-session-list",
            parent.id,
            listOf(SseEvent.QuestionAsked(id = "question", sessionId = parent.id, questions = emptyList())),
        )
        advanceUntilIdle()

        assertEquals(ConversationStatus.AWAITING_QUESTION, vm.uiState.value.activeConversations.single().status)
    }

    @Test
    fun `child questions and permissions make the parent conversation pending`() = runTest(dispatcher) {
        val eventReducer = EventReducer()
        val parent = testSession(id = "parent")
        val child = testSession(id = "child", parentId = parent.id)
        val vm = newSessionListViewModel(eventReducer = eventReducer)
        collectUiState(vm)
        eventReducer.setSessions("srv-session-list", listOf(parent, child))

        eventReducer.processEvent(
            SseEvent.PermissionAsked(id = "child-permission", sessionId = child.id, permission = "write"),
            serverId = "srv-session-list",
        )
        eventReducer.processEvent(
            SseEvent.QuestionAsked(id = "child-question", sessionId = child.id, questions = emptyList()),
            serverId = "srv-session-list",
        )
        advanceUntilIdle()

        val active = vm.uiState.value.activeConversations.single()
        assertEquals(parent.id, active.sessionId)
        assertEquals(ConversationStatus.AWAITING_QUESTION, active.status)
        assertEquals(1, vm.uiState.value.activityQueue.pendingSessionCount)
        assertEquals(1, vm.uiState.value.activityQueue.signalCountsByKind[SessionActivityKind.QUESTION])
        assertEquals(1, vm.uiState.value.activityQueue.signalCountsByKind[SessionActivityKind.PERMISSION])
    }

    @Test
    fun `pending child uses selected server topology when session ids collide`() = runTest(dispatcher) {
        val eventReducer = EventReducer()
        val localRoot = testSession(id = "local-root")
        val localChild = testSession(id = "shared-child", parentId = localRoot.id)
        val otherRoot = testSession(id = "other-root")
        val otherChild = testSession(id = localChild.id, parentId = otherRoot.id)
        val vm = newSessionListViewModel(eventReducer = eventReducer)
        collectUiState(vm)
        eventReducer.setSessions("srv-session-list", listOf(localRoot, localChild))
        eventReducer.setSessions("other-server", listOf(otherRoot, otherChild))

        eventReducer.processEvent(
            SseEvent.QuestionAsked(id = "shared-request", sessionId = localChild.id, questions = emptyList()),
            serverId = "srv-session-list",
        )
        advanceUntilIdle()

        val active = vm.uiState.value.activeConversations.single()
        assertEquals(localRoot.id, active.sessionId)
        assertEquals(ConversationStatus.AWAITING_QUESTION, active.status)
    }

    @Test
    fun `pending child becomes visible when its session topology arrives later`() = runTest(dispatcher) {
        val eventReducer = EventReducer()
        val parent = testSession(id = "parent")
        val child = testSession(id = "child", parentId = parent.id)
        val vm = newSessionListViewModel(eventReducer = eventReducer)
        collectUiState(vm)

        eventReducer.processEvent(
            SseEvent.PermissionAsked(id = "early-permission", sessionId = child.id, permission = "write"),
            serverId = "srv-session-list",
        )
        advanceUntilIdle()
        assertTrue(vm.uiState.value.activeConversations.isEmpty())

        eventReducer.setSessions("srv-session-list", listOf(parent, child))
        advanceUntilIdle()

        val active = vm.uiState.value.activeConversations.single()
        assertEquals(parent.id, active.sessionId)
        assertEquals(ConversationStatus.AWAITING_PERMISSION, active.status)
    }

    @Test
    fun `pending activity is scoped to the selected server when session ids collide`() = runTest(dispatcher) {
        val eventReducer = EventReducer()
        val shared = testSession(id = "shared")
        val vm = newSessionListViewModel(eventReducer = eventReducer)
        collectUiState(vm)
        eventReducer.setSessions("srv-session-list", listOf(shared))
        eventReducer.setSessions("other-server", listOf(shared))
        eventReducer.processEvent(
            SseEvent.QuestionAsked(id = "other-question", sessionId = shared.id, questions = emptyList()),
            serverId = "other-server",
        )
        advanceUntilIdle()

        assertTrue(vm.uiState.value.activeConversations.isEmpty())

        eventReducer.processEvent(
            SseEvent.PermissionAsked(id = "local-permission", sessionId = shared.id, permission = "write"),
            serverId = "srv-session-list",
        )
        advanceUntilIdle()

        assertEquals(ConversationStatus.AWAITING_PERMISSION, vm.uiState.value.activeConversations.single().status)
    }

    @Test
    fun `session search keeps project results scoped to selected server`() = runTest(dispatcher) {
        val eventReducer = EventReducer()
        val localMatch = testSession(id = "local-match", title = "Needle local")
        val localOther = testSession(id = "local-other", title = "Unrelated")
        val remoteMatch = testSession(id = "remote-match", title = "Needle remote")
        val vm = newSessionListViewModel(eventReducer = eventReducer)
        collectUiState(vm)
        eventReducer.setSessions("srv-session-list", listOf(localMatch, localOther))
        eventReducer.setSessions("other-server", listOf(remoteMatch))

        vm.setSearchQuery("needle")
        advanceUntilIdle()

        assertEquals(listOf(localMatch.id), vm.uiState.value.groups.single().sessions.map { it.session.id })
        assertEquals(listOf(localMatch.id), vm.uiState.value.recentWork.filter { it.title?.contains("Needle") == true }.map { it.sessionId })
    }

    @Test
    fun `project collapse normalizes directory and updates selected server group`() = runTest(dispatcher) {
        val eventReducer = EventReducer()
        val session = testSession(id = "local")
        val vm = newSessionListViewModel(eventReducer = eventReducer)
        collectUiState(vm)
        eventReducer.setSessions("srv-session-list", listOf(session))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.groups.single().isCollapsed)

        vm.toggleCollapsed("/workspace/project/")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.groups.single().isCollapsed)
    }

    @Test
    fun `parent conversation stops being active after child subagent finishes`() = runTest(dispatcher) {
        val eventReducer = EventReducer()
        val parent = testSession(id = "parent")
        val child = testSession(id = "child", parentId = parent.id)
        val vm = newSessionListViewModel(eventReducer = eventReducer)
        collectUiState(vm)
        eventReducer.setSessions("srv-session-list", listOf(parent, child))
        eventReducer.updateSessionStatus(child.id, SessionStatus.Busy)
        advanceUntilIdle()

        eventReducer.updateSessionStatus(child.id, SessionStatus.Idle)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.activeConversations.isEmpty())
    }

    @Test
    fun `session list new conversation succeeds with special-character directory and safe chat route`() = runTest(dispatcher) {
        val directory = "/work/100% ready/a+b?#中"
        val createDirectoryHeaders = Collections.synchronizedList(mutableListOf<String?>())
        val eventReducer = EventReducer()
        val diagnosticsRepository = diagnosticsLogRepository()
        val vm = newSessionListViewModel(
            eventReducer = eventReducer,
            api = sessionListApi(
                createDirectoryHeaders = createDirectoryHeaders,
                createResponseBody = """{"id":"ses_created","directory":"","time":{}}""",
            ),
            diagnosticsRepository = diagnosticsRepository,
        )
        val navigated = Collections.synchronizedList(mutableListOf<Session>())
        collectNavigation(vm) { navigated.add(it) }
        collectUiState(vm)

        vm.createNewSession(directory = directory)
        advanceUntilIdle()

        val session = navigated.single()
        assertEquals("ses_created", session.id)
        assertEquals(directory, session.directory)
        assertEquals(directory, eventReducer.sessions.value.first { it.id == "ses_created" }.directory)
        assertTrue(createDirectoryHeaders.single()?.contains("%25") == true)

        val route = Screen.Chat.createRoute(
            serverUrl = "http://example.test:4096",
            username = "",
            password = "",
            serverName = "Local",
            serverId = "srv-session-list",
            sessionId = session.id,
            directory = session.directory,
        )
        assertEquals(directory, decodedRouteQueryValue(route, "directory"))

        val appEventContents = diagnosticsRepository.appEventContents()
        assertTrue(appEventContents.any { it.contains("create_new_tapped") })
        assertTrue(appEventContents.any { it.contains("create_new_success") && it.contains("ses_created") })
    }

    @Test
    fun `session list new conversation API exception leaves navigation empty`() = runTest(dispatcher) {
        val diagnosticsRepository = diagnosticsLogRepository()
        val vm = newSessionListViewModel(
            api = sessionListApi(createFailure = IOException("create exploded")),
            diagnosticsRepository = diagnosticsRepository,
        )
        val navigated = Collections.synchronizedList(mutableListOf<Session>())
        collectNavigation(vm) { navigated.add(it) }
        collectUiState(vm)

        vm.createNewSession(directory = "/work/project")
        advanceUntilIoSettles { vm.uiState.value.error != null }

        assertTrue(navigated.isEmpty())
        assertEquals("create exploded", vm.uiState.value.error)

        val appEventContents = diagnosticsRepository.appEventContents()
        assertTrue(appEventContents.any { it.contains("create_new_tapped") })
        assertTrue(appEventContents.any { content ->
            content.contains("create_new_failure") &&
                content.contains("java.io.IOException") &&
                content.contains("create exploded")
        })
    }

    @Test
    fun `session list new conversation malformed response leaves navigation empty`() = runTest(dispatcher) {
        val vm = newSessionListViewModel(
            api = sessionListApi(createResponseBody = "{not-json"),
        )
        val navigated = Collections.synchronizedList(mutableListOf<Session>())
        collectNavigation(vm) { navigated.add(it) }
        collectUiState(vm)

        vm.createNewSession(directory = "/work/project")
        advanceUntilIdle()

        assertTrue(navigated.isEmpty())
        assertTrue(vm.uiState.value.error?.isNotBlank() == true)
    }

    @Test
    fun `session list new conversation blank session id does not navigate`() = runTest(dispatcher) {
        val vm = newSessionListViewModel(
            api = sessionListApi(createResponseBody = """{"id":"","directory":"/work/project","time":{}}"""),
        )
        val navigated = Collections.synchronizedList(mutableListOf<Session>())
        collectNavigation(vm) { navigated.add(it) }
        collectUiState(vm)

        vm.createNewSession(directory = "/work/project")
        advanceUntilIdle()

        assertTrue("blank session id must not navigate to Chat", navigated.none { it.id.isBlank() })
    }

    @Test
    fun `session list new conversation repeated rapid create calls navigate once`() = runTest(dispatcher) {
        var createCount = 0
        val vm = newSessionListViewModel(
            api = sessionListApi(onCreate = { createCount++ }),
        )
        val navigated = Collections.synchronizedList(mutableListOf<Session>())
        collectNavigation(vm) { navigated.add(it) }
        collectUiState(vm)

        vm.createNewSession(directory = "/work/project")
        vm.createNewSession(directory = "/work/project")
        vm.createNewSession(directory = "/work/project")
        advanceUntilIdle()

        assertEquals("rapid taps should only create one conversation", 1, createCount)
        assertEquals("rapid taps should only navigate once", 1, navigated.size)
        assertEquals("ses_created_1", navigated.single().id)
    }

    @Test
    fun `dsh new conversation registers workspace then creates with workspaceId`() = runTest(dispatcher) {
        val captured = Collections.synchronizedList(mutableListOf<String>())
        val eventReducer = EventReducer()
        val manager = unusedDshConnectionManager(testScope.backgroundScope, json)
        val vm = newSessionListViewModel(
            eventReducer = eventReducer,
            dshApi = dshSessionApi(captured),
            dshConnectionManager = manager,
            savedStateHandle = dshSavedStateHandle(),
        )
        val navigated = Collections.synchronizedList(mutableListOf<Session>())
        collectNavigation(vm) { navigated.add(it) }
        collectUiState(vm)

        captured.clear()
        vm.createNewSession(directory = "/work/new")
        advanceUntilIoSettles { "session/create" in captured && navigated.isNotEmpty() }

        assertEquals(
            listOf("workspace/create", "session/create"),
            captured.filter { it == "workspace/create" || it == "session/create" },
        )
        val session = navigated.single()
        assertEquals("s-new", session.id)
        assertEquals("/work/new", session.directory)
        assertTrue(vm.uiState.value.supportsProjectRegister)
        assertTrue(vm.uiState.value.supportsNoRepoCreate)
    }

    @Test
    fun `dsh no-repo create posts empty session create`() = runTest(dispatcher) {
        val captured = Collections.synchronizedList(mutableListOf<String>())
        val vm = newSessionListViewModel(
            dshApi = dshSessionApi(captured),
            dshConnectionManager = unusedDshConnectionManager(testScope.backgroundScope, json),
            savedStateHandle = dshSavedStateHandle(),
        )
        val navigated = Collections.synchronizedList(mutableListOf<Session>())
        collectNavigation(vm) { navigated.add(it) }
        collectUiState(vm)

        captured.clear()
        vm.createNoRepoSession()
        advanceUntilIoSettles { "session/create" in captured && navigated.isNotEmpty() }

        assertEquals(listOf("session/create"), captured.filter { it == "session/create" || it == "workspace/create" })
        assertEquals("s-no-repo", navigated.single().id)
    }

    @Test
    fun `dsh create reuses blank member without session create`() = runTest(dispatcher) {
        val captured = Collections.synchronizedList(mutableListOf<String>())
        val manager = unusedDshConnectionManager(testScope.backgroundScope, json)
        manager.reducer("srv-session-list").applySessionList(
            listOf(
                DshSessionSummary(
                    sessionId = "blank",
                    updatedAt = 1L,
                    running = false,
                    blank = true,
                    cwd = "/work/a",
                ),
            ),
        )
        val vm = newSessionListViewModel(
            dshApi = dshSessionApi(captured, reuseBlank = true),
            dshConnectionManager = manager,
            savedStateHandle = dshSavedStateHandle(),
        )
        val navigated = Collections.synchronizedList(mutableListOf<Session>())
        collectNavigation(vm) { navigated.add(it) }
        collectUiState(vm)

        captured.clear()
        vm.createNewSession(directory = "/work/a")
        advanceUntilIoSettles { navigated.isNotEmpty() }

        assertEquals(
            listOf("workspace/create"),
            captured.filter { it == "workspace/create" || it == "session/create" },
        )
        assertEquals("blank", navigated.single().id)
    }


    /** MockEngine answers on real IO threads; drain the scheduler until the
     *  condition holds or the bounded budget expires. */
    private fun advanceUntilIoSettles(condition: () -> Boolean) {
        repeat(80) {
            if (condition()) return
            testScope.advanceUntilIdle()
            Thread.sleep(10)
        }
        testScope.advanceUntilIdle()
    }

    private fun collectNavigation(
        vm: SessionListViewModel,
        onSession: (Session) -> Unit,
    ) {
        collectJobs += testScope.backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) {
            vm.navigateToSession.collect { onSession(it) }
        }
    }

    private fun collectUiState(vm: SessionListViewModel) {
        collectJobs += testScope.backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) {
            vm.uiState.collect { }
        }
    }

    private fun testSession(
        id: String,
        directory: String = "/workspace/project",
        parentId: String? = null,
        archived: Long? = null,
        title: String? = null,
    ) = Session(
        id = id,
        directory = directory,
        parentId = parentId,
        title = title,
        time = Session.Time(
            created = 1L,
            updated = 1L,
            archived = archived,
        ),
    )

    private fun newSessionListViewModel(
        eventReducer: EventReducer = EventReducer(),
        api: OpenCodeApi = sessionListApi(),
        diagnosticsRepository: DiagnosticsLogRepository = diagnosticsLogRepository(),
        dshApi: DshApiClient = unusedDshApi(json),
        dshConnectionManager: DshConnectionManager = unusedDshConnectionManager(testScope.backgroundScope, json),
        savedStateHandle: SavedStateHandle = SavedStateHandle(
            mapOf(
                "serverUrl" to "http%3A%2F%2Fexample.test%3A4096",
                "username" to "",
                "password" to "",
                "serverName" to "Local",
                "serverId" to "srv-session-list",
            )
        ),
    ): SessionListViewModel {
        return SessionListViewModel(
            savedStateHandle = savedStateHandle,
            eventReducer = eventReducer,
            api = api,
            preferencesRepo = sessionListPreferencesRepository(),
            settingsRepository = settingsRepository(),
            appEventDiagnosticsGenerator = AppEventDiagnosticsGenerator(diagnosticsRepository),
            dshApi = dshApi,
            dshConnectionManager = dshConnectionManager,
        ).also { viewModels.add(it) }
    }

    private fun dshSavedStateHandle(): SavedStateHandle = SavedStateHandle(
        mapOf(
            "serverUrl" to "http%3A%2F%2F192.168.1.8%3A3080",
            "username" to "",
            "password" to "",
            "serverName" to "DSH",
            "serverId" to "srv-session-list",
            "serverType" to "DSH",
        )
    )

    private fun dshSessionApi(
        captured: MutableList<String>,
        reuseBlank: Boolean = false,
    ): DshApiClient {
        val engine = MockEngine { request ->
            val body = (request.body as TextContent).text
            val envelope = json.parseToJsonElement(body).jsonObject
            val rpcId = envelope.getValue("rpcId").jsonPrimitive.content
            val method = envelope.getValue("method").jsonPrimitive.content
            captured += method
            val payload = envelope.getValue("payload").jsonObject
            val value = when (method) {
                "session/list" -> """{"items":[]}"""
                "directoryPicker/list" -> """{"path":"/root","home":"/root","crumbs":[],"entries":[],"truncated":false}"""
                "workspace/create" -> {
                    val request = payload.getValue("args").jsonObject.getValue("request").jsonObject
                    val path = request.getValue("path").jsonPrimitive.content
                    val sessionIds = if (reuseBlank) """["blank"]""" else "[]"
                    """{"workspace":{"workspaceId":"w1","path":"$path","folders":[],"title":"dir","sessionIds":$sessionIds,"createdAt":"t","updatedAt":"t"},"created":true}"""
                }
                "session/create" -> {
                    val request = payload.getValue("args").jsonObject.getValue("request").jsonObject
                    val sessionId = if (request.isEmpty()) "s-no-repo" else "s-new"
                    """{"sessionId":"$sessionId"}"""
                }
                else -> "{}"
            }
            respond(
                content = """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":$value}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return DshApiClient(client, json, downlinkFactory = unusedDshDownlinks())
    }


    private fun sessionListApi(
        createResponseBody: String = """{"id":"ses_created_1","directory":"/work/project","time":{}}""",
        createFailure: Throwable? = null,
        createDirectoryHeaders: MutableList<String?> = Collections.synchronizedList(mutableListOf()),
        onCreate: () -> Unit = {},
    ): OpenCodeApi {
        val engine = MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/path" -> """{"home":"/home/test","worktree":"/work/project"}"""
                "/project" -> """[{"id":"project-1","worktree":"/work/project","name":"project"}]"""
                "/session" -> {
                    if (request.method.value == "POST") {
                        onCreate()
                        createFailure?.let { throw it }
                        createDirectoryHeaders.add(request.headers["x-opencode-directory"])
                        createResponseBody
                    } else {
                        "[]"
                    }
                }
                else -> "{}"
            }
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return OpenCodeApi(client, json)
    }

    private fun sessionListPreferencesRepository(): SessionListPreferencesRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmpFolder.newFile("session-list-${System.nanoTime()}.preferences_pb") },
        )
        return SessionListPreferencesRepository(dataStore)
    }

    private fun settingsRepository(): SettingsRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmpFolder.newFile("settings-${System.nanoTime()}.preferences_pb") },
        )
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = tmpFolder.root
        }
        return SettingsRepository(dataStore, context)
    }

    private fun diagnosticsLogRepository(): DiagnosticsLogRepository {
        val filesDir = tmpFolder.newFolder("diagnostics-files-${System.nanoTime()}")
        val cacheDir = tmpFolder.newFolder("diagnostics-cache-${System.nanoTime()}")
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
            override fun getCacheDir(): File = cacheDir
        }
        return DiagnosticsLogRepository(context)
    }

    private fun DiagnosticsLogRepository.appEventContents(): List<String> {
        return listLogs().mapNotNull { item -> getArtifactFile(item)?.readText() }
    }

    private fun decodedRouteQueryValue(route: String, key: String): String {
        val encoded = route.substringAfter('?')
            .split('&')
            .first { it.substringBefore('=') == key }
            .substringAfter('=')
        return URLDecoder.decode(encoded, "UTF-8")
    }
}
