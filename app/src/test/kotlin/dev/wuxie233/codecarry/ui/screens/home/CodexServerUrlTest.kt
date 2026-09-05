package dev.wuxie233.codecarry.ui.screens.home

import dev.wuxie233.codecarry.domain.model.ServerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodexServerUrlTest {
    @Test fun codexDefaultsToSecureWebsocketAndPreservesProxyPath() {
        assertEquals("wss://codex.example/agent", validateAndNormalizeUrl("codex.example/agent/", ServerType.CODEX))
        assertEquals("ws://127.0.0.1:18767", validateAndNormalizeUrl("ws://127.0.0.1:18767", ServerType.CODEX))
    }
    @Test fun codexRejectsPlainRemoteWebsocketAndCredentialsInUrl() {
        assertNull(validateAndNormalizeUrl("ws://codex.example", ServerType.CODEX))
        assertNull(validateAndNormalizeUrl("wss://user:secret@codex.example", ServerType.CODEX))
        assertNull(validateAndNormalizeUrl("wss://codex.example?token=secret", ServerType.CODEX))
    }
    @Test fun openCodeAndDshKeepHttpTransport() {
        assertEquals("https://oc.example", validateAndNormalizeUrl("https://oc.example", ServerType.OPENCODE))
        assertEquals("http://dsh.example", validateAndNormalizeUrl("dsh.example", ServerType.DSH))
        assertNull(validateAndNormalizeUrl("wss://dsh.example", ServerType.DSH))
    }
}
