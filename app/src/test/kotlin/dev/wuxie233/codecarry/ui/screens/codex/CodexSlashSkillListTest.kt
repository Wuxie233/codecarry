package dev.wuxie233.codecarry.ui.screens.codex

import dev.wuxie233.codecarry.data.codex.CodexSkill
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexSlashSkillListTest {
    @Test
    fun `filter matches all descriptions while omitting disabled and duplicate skills`() {
        val skill = CodexSkill("Review", "Inspect changes", "检查代码", "/skills/review", true)
        val skills = listOf(skill, skill, skill.copy(name = "disabled", path = "/disabled", enabled = false))
        for (query in listOf("", "rev", "INSPECT", "检查")) {
            assertEquals(listOf(skill), matchingCodexSkills(skills, query))
        }
        assertEquals(emptyList<CodexSkill>(), matchingCodexSkills(skills, "missing"))
    }
}
