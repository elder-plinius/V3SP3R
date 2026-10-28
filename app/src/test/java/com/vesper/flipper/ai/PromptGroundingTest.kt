package com.vesper.flipper.ai

import org.junit.Assert.*
import org.junit.Test

class PromptGroundingTest {
    @Test fun `core prompt fits a bounded system context and grounds unavailable transports`() {
        val prompt = VesperPrompts.SYSTEM_PROMPT
        assertTrue("Core instructions must not crowd out chat and tools", prompt.length <= 5000)
        assertTrue(prompt.contains("verify", ignoreCase = true))
        assertTrue(prompt.contains("unknown", ignoreCase = true))
        assertTrue(prompt.contains("RPC-only", ignoreCase = true))
        assertTrue(prompt.contains("UART", ignoreCase = true))
        assertFalse("Do not promise unconditional hardware control", prompt.contains("FULL control"))
        assertFalse("Do not invite unsupported command guessing", prompt.contains("scanap"))
        assertTrue(prompt.contains("execute_command"))
        assertTrue(prompt.contains("tool result", ignoreCase = true))
        assertTrue(prompt.contains("approval", ignoreCase = true))
    }

    @Test fun `core prompt does not spend context on presentation template`() {
        val prompt = VesperPrompts.SYSTEM_PROMPT
        assertFalse(prompt.contains("## Response format"))
        assertFalse(prompt.contains("`Status:`"))
        assertFalse(prompt.contains("`Observed:`"))
        assertFalse(prompt.contains("`Not verified:`"))
    }

    @Test fun `existing action families remain documented in core prompt`() {
        val prompt = VesperPrompts.SYSTEM_PROMPT
        for (action in listOf("list_directory", "read_file", "write_file", "execute_cli",
            "launch_app", "subghz_transmit", "ir_transmit", "nfc_emulate", "rfid_emulate",
            "ibutton_emulate", "badusb_execute", "ble_spam", "search_faphub", "run_runbook",
            "get_device_info", "get_storage_info", "forge_payload", "request_photo")) {
            assertTrue("Missing action family $action", prompt.contains(action))
        }
    }
}
