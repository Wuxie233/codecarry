package dev.wuxie233.codecarry.ui.screens.chat

import dev.wuxie233.codecarry.domain.model.Message
import dev.wuxie233.codecarry.domain.model.Part

internal fun suppressRepeatedPatchCards(messages: List<ChatMessage>): List<ChatMessage> {
    var lastVisiblePatchHash: String? = null

    return messages.map { chatMessage ->
        val assistantMessage = chatMessage.message as? Message.Assistant
        if (assistantMessage == null) {
            chatMessage
        } else {
            val filteredParts = buildList {
                for (part in chatMessage.parts) {
                    if (part is Part.Patch) {
                        val normalizedHash = part.hash.trim()
                        val isRepeatedPatch = normalizedHash.isNotEmpty() && normalizedHash == lastVisiblePatchHash
                        if (!isRepeatedPatch) {
                            add(part)
                            if (normalizedHash.isNotEmpty()) {
                                lastVisiblePatchHash = normalizedHash
                            }
                        }
                    } else {
                        add(part)
                    }
                }
            }
            chatMessage.copy(parts = filteredParts)
        }
    }
}
