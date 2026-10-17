package dev.wuxie233.codecarry.ui.screens.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.wuxie233.codecarry.data.dsh.DshSkillEntry
import org.junit.Assert.*
import org.junit.Test

class DshSkillCandidatesTest {
    private val grill = DshSkillEntry("grill", "Discuss a plan", modelInvocable = true)
    private val graph = DshSkillEntry("graph", "Draw", modelInvocable = true)
    private val state = DshSkillCandidateState(available = true, skills = listOf(graph, grill))
    private fun input(text: String, cursor: Int = text.length) = TextFieldValue(text, TextRange(cursor))

    @Test fun `only standalone name tokens offer prefix matches in host order`() {
        for (text in listOf("/", "before /", "before\n/", "before\t/")) {
            assertEquals(state.skills, state.matching(dshSkillTarget(input(text))!!.query))
        }
        for (text in listOf("word/gr", "https://gr", "./gr", "/dir/file", "/gr.", "/gr ")) {
            assertNull(text, dshSkillTarget(input(text)))
        }
        assertEquals(listOf(graph, grill), state.matching("GR"))
        assertTrue(state.matching("Draw").isEmpty())
        assertTrue(state.matching("zzz").isEmpty())
    }

    @Test fun `selection replaces whole current token and retains surroundings`() {
        val original = input("Before /GRold after", 10)
        val result = insertDshSkill(original, dshSkillTarget(original)!!, grill, state)!!
        assertEquals("Before /grill  after", result.text)
        assertEquals(TextRange(14), result.selection)
    }

    @Test fun `composition selections and stale rows cannot change draft`() {
        val original = input("/gr")
        val target = dshSkillTarget(original)!!
        assertNull(dshSkillTarget(original.copy(composition = TextRange(1, 3))))
        assertNull(dshSkillTarget(original.copy(selection = TextRange(1, 3))))
        assertNull(insertDshSkill(input("/zz"), target, grill, state))
        assertNull(insertDshSkill(original, target, grill, state.copy(skills = listOf(graph))))
        for (unavailable in listOf(state.copy(available = false), state.copy(loading = true), state.copy(failed = true))) {
            assertTrue(unavailable.matching("").isEmpty())
            assertNull(insertDshSkill(original, target, grill, unavailable))
        }
    }
}
