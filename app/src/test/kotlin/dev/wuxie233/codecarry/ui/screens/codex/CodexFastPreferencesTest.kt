package dev.wuxie233.codecarry.ui.screens.codex

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.wuxie233.codecarry.data.codex.CodexModel
import dev.wuxie233.codecarry.data.codex.CodexModelServiceTier
import dev.wuxie233.codecarry.data.codex.CodexThreadSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodexFastPreferencesTest {
    @Test fun unsentChoiceSurvivesRecreationAndIsScopedToServerAndThread() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("codex_fast_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        val first = CodexFastPreferences(context)
        first.setPending("server", "thread", false)
        val restored = CodexFastPreferences(context)
        assertEquals(false, restored.pending("server", "thread"))
        assertNull(restored.pending("other", "thread"))
        assertNull(restored.pending("server", "other"))
        assertTrue(first.consumeFirstEnable())
        assertFalse(restored.consumeFirstEnable())
        restored.clearPending("server", "thread")
        assertNull(first.pending("server", "thread"))
    }

    @Test fun explicitOffClearsWhileNoChoiceLeavesServerTierAlone() {
        val model = CodexModel("gpt", "gpt", "GPT", serviceTiers = listOf(CodexModelServiceTier("priority", "Fast")))
        assertEquals("priority", model.codexFastTier())
        assertEquals(JsonNull, codexFastTurnParams(model, false, true)["serviceTier"])
        assertFalse(codexFastTurnParams(model, false, false).containsKey("serviceTier"))
        assertFalse(codexFastTurnParams(null, true, true).containsKey("serviceTier"))
    }

    @Test fun resumeRestoresExplicitTierWithoutGuessingFromModelDefault() {
        val fast = CodexThreadSession.fromJson(Json.parseToJsonElement("""{"thread":{"id":"t"},"serviceTier":"fast"}"""))
        val normal = CodexThreadSession.fromJson(Json.parseToJsonElement("""{"thread":{"id":"t"},"serviceTier":null}"""))
        assertTrue(isCodexFastTier(fast.serviceTier))
        assertFalse(isCodexFastTier(normal.serviceTier))
    }
}
