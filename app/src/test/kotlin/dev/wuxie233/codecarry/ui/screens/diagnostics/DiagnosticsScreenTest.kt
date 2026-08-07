package dev.wuxie233.codecarry.ui.screens.diagnostics

import dev.wuxie233.codecarry.data.diagnostics.DiagnosticsLogItem
import dev.wuxie233.codecarry.data.diagnostics.DiagnosticsLogType
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsScreenTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val timestamp = GregorianCalendar(utc, Locale.US).apply {
        set(2026, Calendar.MAY, 26, 4, 30, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `maps app session and network logs to display rows`() {
        val state = DiagnosticsUiState(
            logs = listOf(
                item("app", DiagnosticsLogType.APP_EVENT, "App events", timestamp, 512),
                item("session", DiagnosticsLogType.SESSION_DIAGNOSTIC, "Session summary", timestamp + 1, 1536),
                item("network", DiagnosticsLogType.NETWORK_DIAGNOSTIC, "Network summary", timestamp + 2, 2_097_152),
            ),
            selectedLogIds = setOf("session"),
        )

        val rows = state.toDiagnosticsLogRows(locale = Locale.US, timeZone = utc)

        assertEquals(3, rows.size)
        assertEquals("App", rows[0].typeLabel)
        assertEquals("App events", rows[0].displayName)
        assertEquals("May 26, 04:30", rows[0].timestampLabel)
        assertEquals("512 B", rows[0].sizeLabel)
        assertFalse(rows[0].isSelected)

        assertEquals("Session", rows[1].typeLabel)
        assertEquals("Session summary", rows[1].displayName)
        assertEquals("1.5 KB", rows[1].sizeLabel)
        assertTrue(rows[1].isSelected)

        assertEquals("Network", rows[2].typeLabel)
        assertEquals("Network summary", rows[2].displayName)
        assertEquals("2.00 MB", rows[2].sizeLabel)
        assertFalse(rows[2].isSelected)
    }

    @Test
    fun `blank display name falls back to type label`() {
        val row = item("app", DiagnosticsLogType.APP_EVENT, "", timestamp, 0)
            .toDiagnosticsLogRowState(isSelected = false, locale = Locale.US, timeZone = utc)

        assertEquals("App", row.displayName)
        assertEquals("0 B", row.sizeLabel)
    }

    @Test
    fun `upload action requires selection url token and idle upload state`() {
        val logs = listOf(item("app", DiagnosticsLogType.APP_EVENT, "App", timestamp, 1))

        assertFalse(
            DiagnosticsUiState(
                logs = logs,
                selectedLogIds = emptySet(),
                uploadUrlConfigured = true,
                uploadTokenConfigured = true,
            ).toDiagnosticsActionState().canUpload,
        )
        assertFalse(
            DiagnosticsUiState(
                logs = logs,
                selectedLogIds = setOf("app"),
                uploadUrlConfigured = false,
                uploadTokenConfigured = true,
            ).toDiagnosticsActionState().canUpload,
        )
        assertFalse(
            DiagnosticsUiState(
                logs = logs,
                selectedLogIds = setOf("app"),
                uploadUrlConfigured = true,
                uploadTokenConfigured = false,
            ).toDiagnosticsActionState().canUpload,
        )
        assertFalse(
            DiagnosticsUiState(
                logs = logs,
                selectedLogIds = setOf("app"),
                uploadUrlConfigured = true,
                uploadTokenConfigured = true,
                isUploading = true,
            ).toDiagnosticsActionState().canUpload,
        )
        assertTrue(
            DiagnosticsUiState(
                logs = logs,
                selectedLogIds = setOf("app"),
                uploadUrlConfigured = true,
                uploadTokenConfigured = true,
            ).toDiagnosticsActionState().canUpload,
        )
    }

    @Test
    fun `selection actions expose selected count select all clear and delete state`() {
        val logs = listOf(
            item("app", DiagnosticsLogType.APP_EVENT, "App", timestamp, 1),
            item("session", DiagnosticsLogType.SESSION_DIAGNOSTIC, "Session", timestamp, 1),
        )

        val oneSelected = DiagnosticsUiState(
            logs = logs,
            selectedLogIds = setOf("app"),
            uploadUrlConfigured = true,
            uploadTokenConfigured = true,
        ).toDiagnosticsActionState()

        assertEquals(1, oneSelected.selectedCount)
        assertTrue(oneSelected.hasLogs)
        assertTrue(oneSelected.hasSelection)
        assertTrue(oneSelected.canSelectAll)
        assertTrue(oneSelected.canClearSelection)
        assertTrue(oneSelected.canDeleteSelected)
        assertTrue(oneSelected.canUpload)

        val allSelected = DiagnosticsUiState(
            logs = logs,
            selectedLogIds = setOf("app", "session"),
        ).toDiagnosticsActionState()

        assertEquals(2, allSelected.selectedCount)
        assertFalse(allSelected.canSelectAll)
        assertTrue(allSelected.canClearSelection)
        assertTrue(allSelected.canDeleteSelected)
        assertFalse(allSelected.canUpload)
    }

    private fun item(
        id: String,
        type: DiagnosticsLogType,
        displayName: String,
        createdAtMillis: Long,
        sizeBytes: Long,
    ): DiagnosticsLogItem = DiagnosticsLogItem(
        id = id,
        type = type,
        displayName = displayName,
        createdAtMillis = createdAtMillis,
        sizeBytes = sizeBytes,
        relativePath = "diagnostics/$id",
    )
}
