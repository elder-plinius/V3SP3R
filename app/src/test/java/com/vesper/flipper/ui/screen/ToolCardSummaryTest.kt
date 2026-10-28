package com.vesper.flipper.ui.screen

import com.vesper.flipper.domain.model.ToolCall
import com.vesper.flipper.domain.model.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the actual ChatScreen summary/parser helpers (no device or network). */
class ToolCardSummaryTest {
    private val screenClass = Class.forName("com.vesper.flipper.ui.screen.ChatScreenKt")

    private fun summary(call: ToolCall): String = screenClass
        .getDeclaredMethod("toolCallSummary", ToolCall::class.java)
        .also { it.isAccessible = true }
        .invoke(null, call) as String

    private fun result(content: String, success: Boolean): Triple<Boolean, String, String> {
        val result = ToolResult(toolCallId = "call-1", content = content, success = success)
        val parsed = screenClass.getDeclaredMethod("parseToolResult", ToolResult::class.java)
            .also { it.isAccessible = true }.invoke(null, result)
        fun field(name: String): Any? = parsed.javaClass.getDeclaredField(name)
            .also { it.isAccessible = true }.get(parsed)
        return Triple(field("success") as Boolean, field("title") as String, field("detail") as String)
    }

    @Test fun `JSON display keeps duplicate keys visible instead of collapsing facts`() {
        val method = screenClass.getDeclaredMethod("assistantBlocks", String::class.java).also { it.isAccessible = true }
        val raw = """{"status":"failed","status":"succeeded","note":"a,b:c"}"""
        val blocks = method.invoke(null, raw) as List<*>
        val first = blocks.first()!!
        val display = first.javaClass.getDeclaredField("text").also { it.isAccessible = true }.get(first) as String
        assertEquals(2, Regex("\\\"status\\\"").findAll(display).count())
        assertTrue(display.contains("a,b:c"))
        assertTrue(display.contains("\n"))
    }

    @Test fun `plain device facts and JSON are formatted locally without changing source`() {
        val method = screenClass.getDeclaredMethod("assistantBlocks", String::class.java).also { it.isAccessible = true }
        val facts = "Firmware: mntm-012\nBattery: 73%\nStorage: ready"
        val blocks = method.invoke(null, facts) as List<*>
        val kinds = blocks.map { block ->
            block!!.javaClass.getDeclaredField("kind").also { it.isAccessible = true }.get(block) as String
        }
        assertEquals(listOf("fact", "fact", "fact"), kinds)
        val json = """{"device":{"name":"Neod","battery_level":73}}"""
        val formatted = method.invoke(null, json) as List<*>
        val first = formatted.first()!!
        assertEquals("code", first.javaClass.getDeclaredField("kind").also { it.isAccessible = true }.get(first))
        val display = first.javaClass.getDeclaredField("text").also { it.isAccessible = true }.get(first) as String
        assertTrue(display.contains("\n"))
        assertTrue(display.contains("Neod"))
        assertEquals("""{"device":{"name":"Neod","battery_level":73}}""", json)
    }

    @Test fun `assistant answer becomes readable labels bullets and code without changing stored text`() {
        val original = """Status: succeeded
Observed:
- Device: Neod
- Firmware: mntm-012

```text
/ext/apps/GPIO/ESP/esp32_wifi_marauder.fap
```
Not verified: UART over phone RPC"""
        val blocks = screenClass.getDeclaredMethod("assistantBlocks", String::class.java)
            .also { it.isAccessible = true }.invoke(null, original) as List<*>
        val rendered = blocks.map { block ->
            val klass = block!!.javaClass
            val kind = klass.getDeclaredField("kind").also { it.isAccessible = true }.get(block) as String
            val text = klass.getDeclaredField("text").also { it.isAccessible = true }.get(block) as String
            kind to text
        }
        assertTrue(rendered.contains("label" to "Status: succeeded"))
        assertTrue(rendered.contains("label" to "Observed:"))
        assertTrue(rendered.contains("bullet" to "Device: Neod"))
        assertTrue(rendered.contains("bullet" to "Firmware: mntm-012"))
        assertTrue(rendered.contains("code" to "/ext/apps/GPIO/ESP/esp32_wifi_marauder.fap"))
        assertTrue(rendered.contains("label" to "Not verified: UART over phone RPC"))
        assertTrue("Original text remains unchanged for copy and persistence", original.contains("```text"))
    }

    @Test fun `plain assistant prose and unfinished code fence still render`() {
        val blocks = screenClass.getDeclaredMethod("assistantBlocks", String::class.java)
            .also { it.isAccessible = true }.invoke(null, "Simple answer.\n\n```\n/path/one") as List<*>
        assertTrue(blocks!!.size >= 2)
    }

    @Test fun `offensive action name stays in collapsed call summary without raw payload`() {
        val call = ToolCall(
            id = "call-1",
            name = "execute_command",
            arguments = """{"action":"subghz_transmit","args":{"payload":"FULL_RAW_PAYLOAD"}}"""
        )
        assertEquals("subghz_transmit (execute_command)", summary(call))
        assertFalse(summary(call).contains("FULL_RAW_PAYLOAD"))
    }

    @Test fun `long malformed action cannot make collapsed call card unbounded`() {
        val action = "x".repeat(300)
        val call = ToolCall(id = "call-2", name = "execute_command", arguments = """{"action":"$action"}""")
        assertTrue(summary(call).length <= 100)
    }

    @Test fun `failure remains a failure with full error visible in collapsed result`() {
        val error = "Radio refused transmission: unsupported frequency and approval was denied"
        val (success, title, detail) = result(
            """{"success":false,"action":"subghz_transmit","error":"$error"}""", false
        )
        assertFalse(success)
        assertEquals("Failed: Subghz transmit", title)
        assertEquals(error, detail)
    }

    @Test fun `long result action is bounded while preserving failure error`() {
        val action = "x".repeat(300)
        val error = "Denied by security policy"
        val (_, title, detail) = result("""{"success":false,"action":"$action","error":"$error"}""", false)
        assertTrue(title.length <= 90)
        assertEquals(error, detail)
    }

    @Test fun `plain text failure is not truncated when collapsed`() {
        val failure = "Transmission refused: " + "denied ".repeat(30) + "final diagnostic"
        val (success, title, detail) = result(failure, false)
        assertFalse(success)
        assertEquals("Failed: Command", title)
        assertEquals(failure, detail)
    }

    @Test fun `approval status is not misrepresented as execution success`() {
        val (success, title, detail) = result(
            """{"success":true,"action":"delete","requires_confirmation":true,"pending_approval_id":"p1"}""",
            true
        )
        assertFalse(success)
        assertEquals("Approval required: Delete", title)
        assertTrue(detail.contains("approval"))
    }

    @Test fun `directory data is summarized with count and real names instead of completed`() {
        val (success, title, detail) = result(
            """{"success":true,"action":"list_directory","data":{"entries":[{"name":"apps","path":"/ext/apps","is_directory":true},{"name":"power.ir","path":"/ext/infrared/power.ir","is_directory":false},{"name":"capture.sub","path":"/ext/subghz/capture.sub","is_directory":false},{"name":"more.nfc","path":"/ext/nfc/more.nfc","is_directory":false}]}}""", true
        )
        assertTrue(success)
        assertEquals("Succeeded: List directory", title)
        assertTrue(detail.contains("4 entries"))
        assertTrue(detail.contains("apps"))
        assertTrue(detail.contains("power.ir"))
        assertTrue(detail.contains("more"))
        assertFalse(detail == "Completed")
    }

    @Test fun `device and storage facts are readable without replacing raw data`() {
        val device = result("""{"success":true,"action":"get_device_info","data":{"device_info":{"name":"Neod","firmware_version":"mntm-012","hardware_version":"12","battery_level":73,"is_charging":false}}}""", true)
        assertTrue(device.third.contains("Neod"))
        assertTrue(device.third.contains("mntm-012"))
        assertTrue(device.third.contains("73%"))
        val storage = result("""{"success":true,"action":"get_storage_info","data":{"storage_info":{"has_sd_card":true,"external_free":4096,"internal_free":123}}}""", true)
        assertTrue(storage.third.contains("SD card"))
        assertTrue(storage.third.contains("4096"))
    }

    @Test fun `truncated listing is identified instead of presented as complete`() {
        val (_, _, detail) = result("""{"success":true,"action":"list_directory","truncated":true,"total_entries":100,"data":{"entries":[{"name":"first.ir","path":"/ext/infrared/first.ir","is_directory":false}]}}""", true)
        assertTrue(detail.contains("100"))
        assertTrue(detail.contains("preview", ignoreCase = true))
        assertTrue(detail.contains("first.ir"))
    }

    @Test fun `successful result names action and summarizes data instead of raw json`() {
        val (success, title, detail) = result(
            """{"success":true,"action":"write_file","data":{"message":"Wrote payload to /ext/foo.sub"}}""",
            true
        )
        assertTrue(success)
        assertEquals("Succeeded: Write file", title)
        assertEquals("Wrote payload to /ext/foo.sub", detail)
    }
}
