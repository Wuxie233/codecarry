package dev.wuxie233.codecarry.ui.screens.chat

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.wuxie233.codecarry.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@org.robolectric.annotation.Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class DshRewindActionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirmationSharesGateAndBusyWarningWhileCancelAndDuplicateClicksDoNotExecute() {
        val gate = ChatConfirmationGate()
        val inFlight = mutableStateOf(false)
        var calls = 0
        compose.setContent {
            CompositionLocalProvider(LocalChatConfirmationGate provides gate) {
                MaterialTheme { DshRewindAction("message-2", inFlight.value, busy = true) {
                    calls++; inFlight.value = true
                } }
            }
        }
        compose.runOnIdle { gate.acquire("compact") }
        compose.onNodeWithText(compose.activity.getString(R.string.dsh_rewind_action)).assertIsNotEnabled()
        compose.runOnIdle { gate.release("compact") }
        compose.onNodeWithText(compose.activity.getString(R.string.dsh_rewind_action)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.dsh_rewind_body)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.dsh_rewind_busy)).assertIsDisplayed()
        compose.runOnIdle { assertFalse(gate.acquire("compact")); assertEquals(0, calls) }
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(null, gate.owner); assertEquals(0, calls) }
        compose.onNodeWithText(compose.activity.getString(R.string.dsh_rewind_action)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.dsh_rewind_confirm)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.dsh_rewind_action)).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls) }
    }
}
