package dev.wuxie233.codecarry.ui.screens.codex

import dev.wuxie233.codecarry.data.codex.CodexSkill
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexSkillMatchingTest {
    @Test
    fun `typing tospec finds the to-spec skill without its separator`() {
        val skill = CodexSkill("to-spec", "Create a specification", null, "/skills/to-spec", true)

        assertEquals(listOf(skill), matchingCodexSkills(listOf(skill), "tospec"))
    }

    @Test
    fun `picker prioritizes exact and close names before description matches`() {
        val description = skill("author", description = "Use tospec to draft requirements")
        val fuzzy = skill("to-spec")
        val substring = skill("my-tospec")
        val prefix = skill("tospec-extra")
        val exact = skill("ToSpec")
        val secondFuzzy = skill("to-spec-extended")

        assertEquals(
            listOf(exact, prefix, substring, fuzzy, secondFuzzy, description),
            matchingCodexSkills(listOf(description, fuzzy, substring, prefix, exact, secondFuzzy), "TOSPEC"),
        )
    }

    @Test
    fun `fuzzy query preserves character order and repeated characters`() {
        val intended = skill("test-tool")
        val wrongOrder = skill("tool-test")
        val missingRepeat = skill("test-ol")

        assertEquals(listOf(intended), matchingCodexSkills(listOf(wrongOrder, missingRepeat, intended), "tstool"))
    }

    @Test
    fun `empty picker keeps catalog order with enabled paths only once`() {
        val first = skill("zeta")
        val second = skill("alpha")
        val disabled = skill("disabled").copy(enabled = false)

        assertEquals(
            listOf(first, second),
            matchingCodexSkills(listOf(disabled, first, second, first.copy(name = "duplicate")), ""),
        )
    }

    @Test
    fun `short description remains searchable without fuzzy prose matches`() {
        val shortDescription = skill("draft").copy(shortDescription = "Plan details")
        val unrelated = skill("other", description = "Please arrange language notes")

        assertEquals(listOf(shortDescription), matchingCodexSkills(listOf(unrelated, shortDescription), "PLAN"))
    }

    private fun skill(name: String, description: String = "") =
        CodexSkill(name, description, null, "/skills/$name", true)
}
