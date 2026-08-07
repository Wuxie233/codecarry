package dev.wuxie233.codecarry.ui.screens.chat

import dev.wuxie233.codecarry.domain.model.Message
import dev.wuxie233.codecarry.domain.model.Part
import dev.wuxie233.codecarry.domain.model.TimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiSenderBubbleMetadataTest {

    @Test
    fun legacyAssistantWithoutSenderMetadataHasNoPiIdentity() {
        val message = assistant("legacy")

        assertNull(piSenderIdentity(message))
    }

    @Test
    fun piAssistantBuildsIdentityFromSenderFields() {
        val message = assistant(
            id = "turn-ada-1",
            senderId = "ada",
            senderName = "Ada",
            mbti = "INTP",
            senderRole = "persona",
            colorSeed = "ada-seed",
            actionTag = "陈述",
        )

        val identity = piSenderIdentity(message)

        assertEquals("ada", identity?.id)
        assertEquals("Ada", identity?.name)
        assertEquals("INTP", identity?.mbti)
        assertEquals("persona", identity?.role)
        assertEquals("ada-seed", identity?.colorSeed)
        assertEquals("陈述", message.actionTag)
    }

    @Test
    fun stableSenderColorUsesColorSeed() {
        val ada = PiSenderIdentity("ada", "Ada", "INTP", "persona", "ada-seed")
        val curie = PiSenderIdentity("curie", "Curie", "ENTJ", "persona", "curie-seed")

        assertEquals(piSenderAccentColor(ada), piSenderAccentColor(ada.copy(name = "Ada Lovelace")))
        assertNotEquals(piSenderAccentColor(ada), piSenderAccentColor(curie))
    }

    @Test
    fun consecutiveMessagesFromSameSenderCollapseHeader() {
        val first = chatMessage(assistant("turn-1", senderId = "ada", senderName = "Ada", colorSeed = "ada-seed"))
        val second = chatMessage(assistant("turn-2", senderId = "ada", senderName = "Ada", colorSeed = "ada-seed"))
        val third = chatMessage(assistant("turn-3", senderId = "curie", senderName = "Curie", colorSeed = "curie-seed"))

        assertTrue(isSamePiSender(second, first))
        assertFalse(isSamePiSender(third, second))
    }

    @Test
    fun moderatorRoleIsDetectedForDistinctBlockStyling() {
        val identity = piSenderIdentity(
            assistant(
                id = "turn-moderator",
                senderId = "moderator-main",
                senderName = "Moderator",
                mbti = "INFJ",
                senderRole = "moderator",
                colorSeed = "moderator-main",
            )
        )

        assertTrue(isPiModerator(identity))
    }

    @Test
    fun inShortHighlightIsSeparatedFromMarkdownBody() {
        val split = splitPiInShortHighlight(
            """
            开场说明

            - **简言之：保留核心分歧。**

            继续展开
            """.trimIndent()
        )

        assertEquals("简言之：保留核心分歧。", split.highlight)
        assertEquals(
            """
            开场说明

            继续展开
            """.trimIndent(),
            split.markdown
        )
    }

    private fun chatMessage(message: Message.Assistant): ChatMessage = ChatMessage(
        message = message,
        parts = listOf(
            Part.Text(
                id = "${message.id}-text",
                sessionId = message.sessionId,
                messageId = message.id,
                text = "body",
            )
        ),
    )

    private fun assistant(
        id: String,
        senderId: String? = null,
        senderName: String? = null,
        mbti: String? = null,
        senderRole: String? = null,
        colorSeed: String? = null,
        actionTag: String? = null,
    ): Message.Assistant = Message.Assistant(
        id = id,
        sessionId = "round-1",
        time = TimeInfo(created = 1L),
        senderId = senderId,
        senderName = senderName,
        mbti = mbti,
        senderRole = senderRole,
        colorSeed = colorSeed,
        actionTag = actionTag,
    )
}
