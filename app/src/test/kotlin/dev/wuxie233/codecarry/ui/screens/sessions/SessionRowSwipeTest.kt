package dev.wuxie233.codecarry.ui.screens.sessions

import dev.wuxie233.codecarry.data.preferences.SessionScope
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionRowSwipeTest {

    @Test
    fun `inbox swipe-left dispatches archive`() {
        var archived = 0
        var restored = 0
        val handler = resolveSwipeLeftAction(
            scope = SessionScope.INBOX,
            onArchive = { archived++ },
            onRestore = { restored++ },
        )
        handler.invoke()
        assertEquals(1, archived)
        assertEquals(0, restored)
    }

    @Test
    fun `archived swipe-left dispatches restore`() {
        var archived = 0
        var restored = 0
        val handler = resolveSwipeLeftAction(
            scope = SessionScope.ARCHIVED,
            onArchive = { archived++ },
            onRestore = { restored++ },
        )
        handler.invoke()
        assertEquals(0, archived)
        assertEquals(1, restored)
    }
}
