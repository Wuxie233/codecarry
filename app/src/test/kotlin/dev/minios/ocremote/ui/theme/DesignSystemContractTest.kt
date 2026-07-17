package dev.minios.ocremote.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DesignSystemContractTest {

    @Test
    fun `shared dimensions keep the product spacing and target contract`() {
        val source = File("src/main/kotlin/dev/minios/ocremote/ui/theme/Dimensions.kt").readText()

        assertTrue(source.contains("val minTouchTarget = 48.dp"))
        assertTrue(source.contains("val space1 = 4.dp"))
        assertTrue(source.contains("val space2 = 8.dp"))
    }

    @Test
    fun `theme exposes only the approved component radii`() {
        val source = File("src/main/kotlin/dev/minios/ocremote/ui/theme/Theme.kt").readText()

        assertTrue(source.contains("RoundedCornerShape(6.dp)"))
        assertTrue(source.contains("RoundedCornerShape(10.dp)"))
        assertTrue(source.contains("RoundedCornerShape(14.dp)"))
    }

    @Test
    fun `typography does not use tracked or negative letter spacing`() {
        val source = File("src/main/kotlin/dev/minios/ocremote/ui/theme/Type.kt").readText()

        assertFalse(source.contains("letterSpacing = (-"))
        assertFalse(Regex("letterSpacing = (?!0\\.sp)").containsMatchIn(source))
    }
}
