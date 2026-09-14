package dev.wuxie233.codecarry.ui.screens.codex

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #39: required booleans without a schema default submit the displayed false
 * directly; displayed and submitted values never disagree across
 * required/optional × default/no-default × true/false.
 */
class CodexMcpFormBooleanTest {
    @Test
    fun `required boolean without default submits the displayed false directly`() {
        val field = booleanField(default = null)
        val properties = propertiesOf(field)

        val values = initialMcpFormValues(properties)
        val displayed = displayedMcpBoolean(values["flag"], field)
        val valid = validateMcpFormValue(field, values["flag"], required = true)
        val submitted = buildMcpFormSubmission(properties, values)["flag"]

        assertEquals(false, displayed)
        assertTrue(valid)
        assertEquals(false, submitted?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `every boolean combination keeps display and submission identical`() {
        for (required in listOf(true, false)) {
            for (default in listOf(null, false, true)) {
                val field = booleanField(default = default)
                val properties = propertiesOf(field)
                val values = initialMcpFormValues(properties)
                val displayed = displayedMcpBoolean(values["flag"], field)
                val submitted = buildMcpFormSubmission(properties, values)["flag"]?.jsonPrimitive?.booleanOrNull

                assertEquals(displayed, submitted)
                // Validation passes exactly when a value exists for the field.
                assertEquals(true, validateMcpFormValue(field, values["flag"], required = required))
                // The schema default (when boolean) is the untouched starting point.
                assertEquals(default ?: false, displayed)
            }
        }
    }

    @Test
    fun `toggling a boolean keeps display and submission identical`() {
        for (start in listOf(false, true)) {
            val field = booleanField(default = null)
            val properties = propertiesOf(field)
            val values = initialMcpFormValues(properties).toMutableMap()
            values["flag"] = JsonPrimitive(!start)

            val displayed = displayedMcpBoolean(values["flag"], field)
            val submitted = buildMcpFormSubmission(properties, values)["flag"]?.jsonPrimitive?.booleanOrNull

            assertEquals(!start, displayed)
            assertEquals(displayed, submitted)
        }
    }

    @Test
    fun `non boolean default on a boolean field falls back to a consistent false`() {
        val field = buildJsonObject {
            put("type", "boolean")
            put("default", "yes")
        }
        val properties = propertiesOf(field)
        val values = initialMcpFormValues(properties)

        assertEquals(false, displayedMcpBoolean(values["flag"], field))
        assertEquals(false, buildMcpFormSubmission(properties, values)["flag"]?.jsonPrimitive?.booleanOrNull)
        assertTrue(validateMcpFormValue(field, values["flag"], required = true))
    }

    @Test
    fun `required non boolean fields without default stay invalid until answered`() {
        val properties = buildJsonObject {
            put("count", buildJsonObject { put("type", "integer") })
            put("label", buildJsonObject { put("type", "string") })
        }
        val values = initialMcpFormValues(properties)

        assertNull(values["count"])
        assertNull(values["label"])
        assertFalse(validateMcpFormValue(properties["count"]!!.jsonObject, values["count"], required = true))
        assertFalse(validateMcpFormValue(properties["label"]!!.jsonObject, values["label"], required = true))
        // Optional unanswered fields stay absent from the submission.
        assertNull(buildMcpFormSubmission(properties, values)["count"])
    }

    @Test
    fun `unrelated field defaults keep their schema values`() {
        val properties = buildJsonObject {
            put("mode", buildJsonObject { put("type", "string"); put("default", "fast") })
        }
        val values = initialMcpFormValues(properties)

        assertEquals("fast", (values["mode"] as JsonPrimitive).content)
        assertEquals("fast", buildMcpFormSubmission(properties, values)["mode"]?.jsonPrimitive?.content)
    }

    private fun booleanField(default: Boolean?): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("title", "Flag")
        default?.let { put("default", it) }
    }

    private fun propertiesOf(field: JsonObject): JsonObject = buildJsonObject {
        put("flag", field)
    }
}
