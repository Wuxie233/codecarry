package dev.wuxie233.codecarry.ui.screens.codex

import dev.wuxie233.codecarry.data.codex.CodexThreadSession
import dev.wuxie233.codecarry.data.codex.threadPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #40: the policy projection decodes authoritative tokens and leaves unknowns null. */
class CodexThreadPolicyTest {
    @Test
    fun `decodes tagged sandbox objects with network and roots`() {
        val session = session(
            """
            {"approvalPolicy":"unlessTrusted","sandbox":{"type":"workspaceWrite",
             "writableRoots":["/a","/b"],"networkAccess":true}}
            """.trimIndent(),
        )

        val policy = session.threadPolicy()

        assertEquals("unlessTrusted", policy.approvalPolicy)
        assertEquals("workspaceWrite", policy.sandboxMode)
        assertEquals(true, policy.sandboxNetworkEnabled)
        assertEquals(listOf("/a", "/b"), policy.sandboxWritableRoots)
    }

    @Test
    fun `decodes legacy string sandbox and external network tokens`() {
        val restricted = session("""{"approvalPolicy":"never","sandbox":"workspace-write"}""").threadPolicy()
        assertEquals("never", restricted.approvalPolicy)
        assertEquals("workspace-write", restricted.sandboxMode)
        assertNull(restricted.sandboxNetworkEnabled)

        val external = session(
            """{"sandbox":{"type":"externalSandbox","networkAccess":"restricted"}}""",
        ).threadPolicy()
        assertEquals("externalSandbox", external.sandboxMode)
        assertEquals(false, external.sandboxNetworkEnabled)

        val enabled = session(
            """{"sandbox":{"type":"externalSandbox","networkAccess":"enabled"}}""",
        ).threadPolicy()
        assertEquals(true, enabled.sandboxNetworkEnabled)
    }

    @Test
    fun `missing fields stay unknown instead of inventing values`() {
        val policy = session("""{"model":"gpt-5"}""").threadPolicy()

        assertNull(policy.approvalPolicy)
        assertNull(policy.sandboxMode)
        assertNull(policy.sandboxNetworkEnabled)
        assertTrue(policy.sandboxWritableRoots.isEmpty())
    }

    @Test
    fun `label lookup resolves token spellings and passes unknown tokens through`() {
        assertEquals("workspaceWrite", codexPolicyTokenKey("workspace-write"))
        assertEquals("dangerFullAccess", codexPolicyTokenKey("danger_full_access"))
        assertEquals("onFailure", codexPolicyTokenKey("on-failure"))
        assertEquals("unlessTrusted", codexPolicyTokenKey("unlessTrusted"))

        assertTrue(codexApprovalPolicyLabelRes("on-request") != null)
        assertTrue(codexSandboxModeLabelRes("dangerFullAccess") != null)
        // Unknown tokens render verbatim rather than with a wrong label.
        assertNull(codexApprovalPolicyLabelRes("sometimes"))
        assertNull(codexSandboxModeLabelRes("partialTrust"))
        assertFalse(codexApprovalPolicyLabelRes("never") == codexApprovalPolicyLabelRes("untrusted"))
    }

    private fun session(payload: String): CodexThreadSession =
        CodexThreadSession.fromJson(Json.parseToJsonElement(payload).jsonObject)
}

/** Issue #37: metadata state transitions keep stale data visible and retryable. */
class CodexMetadataLoadStateTest {
    @Test
    fun `failed refresh keeps the last good value as stale data`() {
        val loaded: CodexMetadataLoadState<String> = CodexMetadataLoadState.Loaded("old")

        val refreshing = loaded.beginRefresh()
        assertTrue(refreshing is CodexMetadataLoadState.Loaded && refreshing.stale)
        assertEquals("old", refreshing.valueOrNull())

        val failed = refreshing.failedKeepingValue("backend down")
        assertTrue(failed is CodexMetadataLoadState.Failed)
        assertEquals("backend down", failed.message)
        assertEquals("old", failed.staleValue)
    }

    @Test
    fun `first load failure has no stale value and loading stays neutral`() {
        val loading: CodexMetadataLoadState<Int> = CodexMetadataLoadState.Loading

        assertEquals(loading, loading.beginRefresh())
        assertEquals(loading, loading.markStale())
        assertNull(loading.valueOrNull())

        val failed = loading.failedKeepingValue("offline")
        assertNull(failed.staleValue)
        assertEquals(failed, failed.markStale())
    }
}
