package dev.wuxie233.codecarry.ui.screens.chat

import dev.wuxie233.codecarry.domain.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForkDirectoryResolverTest {

    @Test
    fun `returns in-memory sessionDirectory when populated`() {
        val result = ForkDirectoryResolver.resolve(
            sessionDirectory = "/home/user/projectA",
            sessionId = "ses_1",
            reducerSessions = listOf(testSession("ses_1", directory = "/home/user/wrong")),
        )
        assertEquals("/home/user/projectA", result)
    }

    @Test
    fun `falls back to reducer session directory when in-memory is null`() {
        val result = ForkDirectoryResolver.resolve(
            sessionDirectory = null,
            sessionId = "ses_1",
            reducerSessions = listOf(testSession("ses_1", directory = "/home/user/projectA")),
        )
        assertEquals("/home/user/projectA", result)
    }

    @Test
    fun `falls back to reducer session directory when in-memory is blank`() {
        val result = ForkDirectoryResolver.resolve(
            sessionDirectory = "  ",
            sessionId = "ses_1",
            reducerSessions = listOf(testSession("ses_1", directory = "/home/user/projectA")),
        )
        assertEquals("/home/user/projectA", result)
    }

    @Test
    fun `returns null when in-memory and reducer match are both blank`() {
        val result = ForkDirectoryResolver.resolve(
            sessionDirectory = null,
            sessionId = "ses_1",
            reducerSessions = listOf(testSession("ses_1", directory = "")),
        )
        assertNull(result)
    }

    @Test
    fun `returns null when reducer has no matching session id`() {
        val result = ForkDirectoryResolver.resolve(
            sessionDirectory = null,
            sessionId = "ses_missing",
            reducerSessions = listOf(testSession("ses_other", directory = "/home/user/projectA")),
        )
        assertNull(result)
    }

    @Test
    fun `returns null when reducer is empty and in-memory is null`() {
        val result = ForkDirectoryResolver.resolve(
            sessionDirectory = null,
            sessionId = "ses_1",
            reducerSessions = emptyList(),
        )
        assertNull(result)
    }

    @Test
    fun `prefers in-memory over reducer even when both populated`() {
        val result = ForkDirectoryResolver.resolve(
            sessionDirectory = "/home/user/inmem",
            sessionId = "ses_1",
            reducerSessions = listOf(testSession("ses_1", directory = "/home/user/reducer")),
        )
        assertEquals("/home/user/inmem", result)
    }

    private fun testSession(id: String, directory: String) = Session(
        id = id,
        directory = directory,
        time = Session.Time(created = 1L, updated = 1L, archived = null),
    )
}
