package dev.wuxie233.codecarry.ui.screens.codex

import dev.wuxie233.codecarry.data.codex.CodexThread
import dev.wuxie233.codecarry.data.codex.CodexEventReducer
import dev.wuxie233.codecarry.data.codex.CodexNotification
import dev.wuxie233.codecarry.data.codex.CodexThreadStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class CodexThreadTopologyTest {
    private val root = CodexThread("parent", cwd = "/repo", updatedAt = 1)
    private val child = CodexThread("child", name = "Research", parentThreadId = "parent", updatedAt = 9,
        status = CodexThreadStatus(type = "active"))

    @Test fun `unverified event threads stay hidden until snapshot supplies ancestry`() {
        val reducer = CodexEventReducer(listOf(root))
        reducer.process(CodexNotification.fromJson(
            Json.parseToJsonElement("""{"method":"item/agentMessage/delta","params":{"threadId":"child","turnId":"turn","itemId":"item","delta":"live"}}""").jsonObject,
        ))
        val pending = reducer.state.value.threads.getValue("child")
        assertFalse(pending.hasMetadata)
        val list = CodexThreadListUiState().applyCodexEventState(null, reducer.state.value)
        assertEquals(listOf("parent"), list.activeThreads.map { it.id })
        assertEquals(listOf("parent"), buildCodexThreadTopology(reducer.state.value.threads.values.toList()).map { it.thread.id })

        val baseline = reducer.state.value
        reducer.reconcileThreads(listOf(root, child), emptyList(), baseline)
        val hydrated = reducer.state.value.threads.getValue("child")
        assertTrue(hydrated.hasMetadata)
        assertEquals("live", hydrated.turns.single().items.single().text)
        assertEquals("child", buildCodexThreadTopology(reducer.state.value.threads.values.toList()).single().children.single().thread.id)
    }

    @Test fun `control and completion events cannot advertise phantom threads`() {
        listOf(
            """{"method":"turn/completed","params":{"threadId":"ghost","turn":{"id":"turn","status":"completed"}}}""",
            """{"method":"turn/diff/updated","params":{"threadId":"ghost","turnId":"turn","diff":"patch"}}""",
            """{"method":"thread/status/changed","params":{"threadId":"ghost","status":{"type":"active"}}}""",
        ).forEach { wire ->
            val reducer = CodexEventReducer()
            reducer.process(CodexNotification.fromJson(Json.parseToJsonElement(wire).jsonObject))
            assertFalse(reducer.state.value.threads.getValue("ghost").hasMetadata)
            assertTrue(buildCodexThreadTopology(reducer.state.value.threads.values.toList()).isEmpty())
        }
    }

    @Test fun `wire source supplies subagent ancestry without treating forks as children`() {
        val thread = CodexThread.fromJson(Json.parseToJsonElement("""{"id":"child","source":{"subAgent":{"thread_spawn":{"parent_thread_id":"parent","depth":1}}}}""").jsonObject)
        assertEquals("parent", thread.parentThreadId)
        assertTrue(thread.isSubagent)
        assertFalse(CodexThread("fork", forkedFromId = "parent").isSubagent)
    }

    @Test fun `subagent nickname supplies title and remains searchable`() {
        val child = CodexThread.fromJson(Json.parseToJsonElement("""{"id":"child","name":null,"agentNickname":"Kepler","preview":"Do the research","source":{"subAgent":{"thread_spawn":{"parent_thread_id":"parent","agent_nickname":"Old name","depth":1}}}}""").jsonObject)
        assertEquals("Kepler", child.displayTitle)
        assertEquals("Custom", child.copy(name = "Custom").displayTitle)
        assertEquals("Kepler", child.copy(name = " ").displayTitle)
        val state = CodexThreadListUiState(activeThreads = listOf(root, child), searchQuery = "kepler")
        assertEquals(listOf("parent", "child"), state.visibleThreads.map { it.id })
    }

    @Test fun `legacy spawn nickname precedes preview while ordinary title fallback stays intact`() {
        val child = CodexThread.fromJson(Json.parseToJsonElement("""{"id":"child","source":{"subAgent":{"thread_spawn":{"parent_thread_id":"parent","agent_nickname":"Darwin","depth":1}}}}""").jsonObject)
        assertEquals("Darwin", child.displayTitle)
        assertEquals("First line", CodexThread("normal", preview = "First line\nSecond line").displayTitle)
        assertNull(CodexThread("empty", name = " ", preview = " ").displayTitle)
    }

    @Test fun `child without cwd belongs to parent project and contributes activity`() {
        val state = CodexThreadListUiState(activeThreads = listOf(root, child))
        val project = state.projects.single()
        assertEquals("/repo", project.directory)
        assertEquals(listOf(root), project.threads)
        assertEquals(listOf(child), project.roots.single().children.map { it.thread })
        assertEquals(1, project.roots.single().runningCount)
        assertEquals(listOf(root), state.activityThreads)
        assertEquals(9L, project.roots.single().recency)
    }

    @Test fun `search and pending filters retain ancestors and descendants`() {
        val grandchild = CodexThread("grandchild", parentThreadId = "child")
        val state = CodexThreadListUiState(activeThreads = listOf(root, child, grandchild),
            searchQuery = "Research", filter = CodexThreadFilter.PENDING, pendingRequestCounts = mapOf(child.id to 1))
        assertEquals(listOf("parent", "child", "grandchild"), state.visibleThreads.map { it.id })
        assertEquals(emptyList<CodexThread>(), state.copy(searchQuery = "unknown").visibleThreads)
    }

    @Test fun `missing parent and cycle remain visible without becoming ordinary ungrouped threads`() {
        val orphan = child.copy(parentThreadId = "missing")
        val a = CodexThread("a", parentThreadId = "b")
        val b = CodexThread("b", parentThreadId = "a")
        val roots = buildCodexThreadTopology(listOf(root, orphan, a, b))
        assertEquals(setOf("parent", "child", "a", "b"), roots.flatMap { it.members }.map { it.id }.toSet())
        assertEquals(4, roots.sumOf { it.members.size })
        assertEquals(2, roots.count { it.orphan })
        val projects = buildCodexThreadProjects(listOf(root, orphan), CodexProjectPreferences(), false)
        assertTrue(projects.single { it.orphan }.roots.single().orphan)
    }

    @Test fun `ordinary forks are separate roots and archive scope cannot steal live children`() {
        val fork = CodexThread("fork", forkedFromId = root.id, cwd = "/other")
        val state = CodexThreadListUiState(activeThreads = listOf(child, fork), archivedThreads = listOf(root))
        assertEquals(2, state.projects.size)
        assertTrue(state.projects.single { it.orphan }.roots.single().orphan)
        assertEquals(listOf(root), state.copy(showArchived = true).visibleThreads)
        assertFalse(buildCodexThreadTopology(listOf(root, fork)).any { it.orphan })
    }

    @Test fun `hidden parent hides the tree even when child has a different directory`() {
        val state = CodexThreadListUiState(activeThreads = listOf(root, child.copy(cwd = "/other")),
            projectPreferences = CodexProjectPreferences(hidden = setOf("/repo")))
        assertTrue(state.projects.isEmpty())
        assertEquals(1, state.copy(showHiddenProjects = true).projects.size)
    }
}
