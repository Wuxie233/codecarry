package dev.wuxie233.codecarry.ui.screens.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.wuxie233.codecarry.data.dsh.DshSkillEntry
import dev.wuxie233.codecarry.data.codex.CodexSkill
import dev.wuxie233.codecarry.ui.screens.codex.matchingCodexSkills
import org.junit.Assert.*
import org.junit.Test

class DshSkillCandidatesTest {
    private val grill = DshSkillEntry("grill", "Discuss a plan", modelInvocable = true)
    private val graph = DshSkillEntry("graph", "Draw", modelInvocable = true)
    private val state = DshSkillCandidateState(available = true, skills = listOf(graph, grill))
    private fun input(text: String, cursor: Int = text.length) = TextFieldValue(text, TextRange(cursor))

    @Test fun `cursor queries match Codex while paths and URLs remain excluded`() {
        for (text in listOf("/", "before /", "before\n/", "before\t/", "请用/", "（/")) {
            assertEquals(state.skills, state.matching(dshSkillTarget(input(text))!!.query))
        }
        for (text in listOf("word/gr", "https://gr", "./gr", "/dir/file", "/gr.", "/gr ")) {
            assertNull(text, dshSkillTarget(input(text)))
        }
        assertEquals(listOf(graph, grill), state.matching("GR"))
        assertEquals(listOf(graph), state.matching("Draw"))
        assertTrue(state.matching("zzz").isEmpty())
    }

    @Test fun `DSH and Codex rank the same skill names and descriptions identically`() {
        val skills = listOf(
            DshSkillEntry("build-spec", "Build it", modelInvocable = true),
            DshSkillEntry("to-spec", "Write a spec", modelInvocable = true),
            DshSkillEntry("spec", "Review", modelInvocable = true),
            DshSkillEntry("review", "Read a specification", modelInvocable = false),
        )
        val codex = skills.map { CodexSkill(it.name, it.description, null, "/skills/${it.name}", true) }
        for (query in listOf("", "spec", "tospec", "REVIEW", "specification", "none")) {
            assertEquals(query, matchingCodexSkills(codex, query).map { it.name },
                state.copy(skills = skills).matching(query).map { it.name })
        }
    }

    @Test fun `selection consumes only current query for a chip and retains surroundings`() {
        val original = input("Before /GRold after", 10)
        val result = consumeDshSkillQuery(original, dshSkillTarget(original)!!, grill, state)!!
        assertEquals("Before  after", result.text)
        assertEquals(TextRange(7), result.selection)
    }

    @Test fun `unrelated composition selections and stale rows cannot change draft`() {
        val original = input("/gr")
        val target = dshSkillTarget(original)!!
        assertEquals(target, dshSkillTarget(original.copy(composition = TextRange(1, 3))))
        val surrounding = input("hello /gr")
        assertNull(dshSkillTarget(surrounding.copy(composition = TextRange(0, 5))))
        assertNull(dshSkillTarget(original.copy(selection = TextRange(1, 3))))
        assertNull(consumeDshSkillQuery(input("/zz"), target, grill, state))
        assertNull(consumeDshSkillQuery(original, target, grill, state.copy(skills = listOf(graph))))
        for (unavailable in listOf(state.copy(available = false), state.copy(loading = true), state.copy(failed = true))) {
            assertTrue(unavailable.matching("").isEmpty())
            assertNull(consumeDshSkillQuery(original, target, grill, unavailable))
        }
    }
}
