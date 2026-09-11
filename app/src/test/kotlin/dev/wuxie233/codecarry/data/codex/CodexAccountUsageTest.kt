package dev.wuxie233.codecarry.data.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class CodexAccountUsageTest {
    private fun payload(value: String) = Json.parseToJsonElement(value).jsonObject
    @Test fun `late read hydrates other fields without replacing newer sparse push`() {
        val store = CodexAccountUsageStore()
        val read = store.beginRead()
        store.applyPush(payload("""{"rateLimits":{"limitId":"codex","primary":{"usedPercent":40},"credits":null}}"""), 2)
        store.applyRead(read, payload("""{"rateLimitsByLimitId":{"codex":{"primary":{"usedPercent":20,"windowDurationMins":300},"secondary":{"usedPercent":5},"credits":{"balance":"3.50","hasCredits":true}},"spark":{"primary":{"usedPercent":80}}}}"""), 3)
        val bucket = store.state.buckets.first { it.id == "codex" }
        assertEquals(40.0, bucket.primary!!.usedPercent!!, 0.0)
        assertEquals(300L, bucket.primary.windowDurationMins)
        assertEquals(5.0, bucket.secondary!!.usedPercent!!, 0.0)
        assertEquals("3.50", bucket.credits!!.balance)
        assertEquals(2, store.state.buckets.size)
    }
    @Test fun `disconnect retains stale data and rejects old generation receipts`() {
        val store = CodexAccountUsageStore()
        store.applyPush(payload("""{"rateLimits":{"primary":{"usedPercent":10}}}"""), 1)
        val oldRead = store.beginRead()
        store.invalidate()
        store.applyRead(oldRead, payload("""{"rateLimits":{"primary":{"usedPercent":90}}}"""), 2)
        assertFalse(store.state.isCurrent)
        assertFalse(store.state.isRefreshing)
        assertEquals(90.0, store.state.buckets.single().primary!!.remainingPercent!!, 0.0)
    }
    @Test fun `account changes clear prior buckets and invalidate pending reads`() {
        val store = CodexAccountUsageStore()
        val read = store.beginRead()
        store.applyRead(read, payload("""{"accountId":"one","rateLimits":{"primary":{"usedPercent":20}}}"""), 1)
        val next = store.beginRead()
        store.applyRead(next, payload("""{"accountId":"two","rateLimits":{"secondary":{"usedPercent":8}}}"""), 2)
        assertNull(store.state.buckets.single().primary)
        val old = store.beginRead()
        store.invalidate(clear = true)
        store.applyRead(old, payload("""{"rateLimits":{"primary":{"usedPercent":99}}}"""), 3)
        assertTrue(store.state.buckets.isEmpty())
    }
    @Test fun `missing usage stays missing and unsupported differs from transient failure`() {
        val store = CodexAccountUsageStore()
        store.applyRead(store.beginRead(), payload("""{"rateLimits":{"primary":{"resetsAt":12}}}"""), 1)
        assertNull(store.state.buckets.single().primary!!.remainingPercent)
        store.fail(store.beginRead(), CodexRpcException(-32601, "Method not found"))
        assertTrue(store.state.unsupported)
        store.fail(store.beginRead(), IllegalStateException("offline"))
        assertFalse(store.state.unsupported)
        assertEquals("offline", store.state.errorMessage)
    }
    @Test fun `sparse null metadata preserves known credits and windows`() {
        val store = CodexAccountUsageStore()
        store.applyPush(payload("""{"rateLimits":{"planType":"plus","primary":{"usedPercent":15,"resetsAt":10},"credits":{"balance":"5"}}}"""), 1)
        store.applyPush(payload("""{"rateLimits":{"planType":null,"primary":{"usedPercent":25,"resetsAt":null},"credits":null}}"""), 2)
        val bucket = store.state.buckets.single()
        assertEquals("plus", bucket.planType)
        assertEquals(10L, bucket.primary!!.resetsAt)
        assertEquals("5", bucket.credits!!.balance)
    }
}
