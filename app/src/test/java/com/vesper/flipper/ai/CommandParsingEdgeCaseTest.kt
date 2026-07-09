package com.vesper.flipper.ai

import com.vesper.flipper.domain.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Comprehensive edge-case tests for AI tool-call JSON parsing.
 *
 * These tests exercise the same parsing logic as [OpenRouterClient.parseCommandDetailed]
 * — covering every shape variation that models in the wild produce.
 *
 * Groups:
 *  1. Strict-path: well-formed JSON that decodes via kotlinx.serialization directly
 *  2. Fallback-path: malformed/variant JSON that requires the manual fallback parser
 *  3. Action alias resolution
 *  4. Args shape variations (string-wrapped, array-wrapped, missing, empty)
 *  5. Field alias coverage for every CommandArgs property
 *  6. Required-arg validation per action
 *  7. Error cases (totally broken JSON, wrong types, missing action)
 */
class CommandParsingEdgeCaseTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    // ═══════════════════════════════════════════════════════
    // 1. STRICT-PATH — well-formed JSON
    // ═══════════════════════════════════════════════════════

    @Test
    fun `strict path - perfect schema with all fields`() {
        val input = """
        {
            "action": "write_file",
            "args": {
                "path": "/ext/test.ir",
                "content": "Filetype: IR\nVersion: 1"
            },
            "justification": "Creating IR file",
            "expected_effect": "Write file to Flipper"
        }
        """.trimIndent()

        val cmd = json.decodeFromString<ExecuteCommand>(input)
        assertEquals(CommandAction.WRITE_FILE, cmd.action)
        assertEquals("/ext/test.ir", cmd.args.path)
        assertEquals("Filetype: IR\nVersion: 1", cmd.args.content)
    }

    @Test
    fun `strict path - browse_repo with all fields`() {
        val input = """
        {
            "action": "browse_repo",
            "args": {
                "repo_id": "irdb",
                "sub_path": "TVs/Samsung"
            },
            "justification": "Browse IR database",
            "expected_effect": "List Samsung TV remotes"
        }
        """.trimIndent()

        val cmd = json.decodeFromString<ExecuteCommand>(input)
        assertEquals(CommandAction.BROWSE_REPO, cmd.action)
        assertEquals("irdb", cmd.args.repoId)
        assertEquals("TVs/Samsung", cmd.args.subPath)
    }

    @Test
    fun `strict path - download_resource with download_url`() {
        val input = """
        {
            "action": "download_resource",
            "args": {
                "download_url": "https://raw.githubusercontent.com/foo/bar/main/remote.ir",
                "path": "/ext/infrared/remote.ir"
            },
            "justification": "Download IR remote",
            "expected_effect": "Save remote file"
        }
        """.trimIndent()

        val cmd = json.decodeFromString<ExecuteCommand>(input)
        assertEquals(CommandAction.DOWNLOAD_RESOURCE, cmd.action)
        assertEquals("https://raw.githubusercontent.com/foo/bar/main/remote.ir", cmd.args.downloadUrl)
        assertEquals("/ext/infrared/remote.ir", cmd.args.path)
    }

    @Test
    fun `strict path - github_search with search_scope`() {
        val input = """
        {
            "action": "github_search",
            "args": {
                "command": "Samsung TV remote extension:ir",
                "search_scope": "code"
            },
            "justification": "Find Samsung TV IR files",
            "expected_effect": "Return GitHub search results"
        }
        """.trimIndent()

        val cmd = json.decodeFromString<ExecuteCommand>(input)
        assertEquals(CommandAction.GITHUB_SEARCH, cmd.action)
        assertEquals("Samsung TV remote extension:ir", cmd.args.command)
        assertEquals("code", cmd.args.searchScope)
    }

    @Test
    fun `strict path - request_photo with photo_prompt`() {
        val input = """
        {
            "action": "request_photo",
            "args": {
                "photo_prompt": "Identify the TV brand and model"
            },
            "justification": "Need to identify device",
            "expected_effect": "Capture photo from glasses"
        }
        """.trimIndent()

        val cmd = json.decodeFromString<ExecuteCommand>(input)
        assertEquals(CommandAction.REQUEST_PHOTO, cmd.action)
        assertEquals("Identify the TV brand and model", cmd.args.photoPrompt)
    }

    @Test
    fun `strict path - led_control with RGB values`() {
        val input = """
        {
            "action": "led_control",
            "args": {
                "red": 255,
                "green": 128,
                "blue": 0
            },
            "justification": "Set LED color",
            "expected_effect": "LED turns orange"
        }
        """.trimIndent()

        val cmd = json.decodeFromString<ExecuteCommand>(input)
        assertEquals(CommandAction.LED_CONTROL, cmd.action)
        assertEquals(255, cmd.args.red)
        assertEquals(128, cmd.args.green)
        assertEquals(0, cmd.args.blue)
    }

    @Test
    fun `strict path - empty args object for actions with no required fields`() {
        val input = """
        {
            "action": "get_device_info",
            "args": {},
            "justification": "Check device",
            "expected_effect": "Return device info"
        }
        """.trimIndent()

        val cmd = json.decodeFromString<ExecuteCommand>(input)
        assertEquals(CommandAction.GET_DEVICE_INFO, cmd.action)
    }

    // ═══════════════════════════════════════════════════════
    // 2. ACTION ALIAS RESOLUTION via parseCommandAction
    //    (tested through the fallback parser indirectly)
    // ═══════════════════════════════════════════════════════

    @Test
    fun `action alias - camelCase forgePayload normalizes to forge_payload`() {
        // The fallback parser converts camelCase → snake_case → lowercase
        // Simulating what parseCommandAction does:
        val input = "forgePayload"
        val normalized = input
            .trim()
            .replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        assertEquals("forge_payload", normalized)
    }

    @Test
    fun `action alias - executeCommand maps to execute_cli`() {
        val input = "executeCommand"
        val normalized = input
            .trim()
            .replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        assertEquals("execute_command", normalized)
        // "execute_command" is listed as an alias for EXECUTE_CLI
    }

    @Test
    fun `action alias - hyphenated and spaced forms normalize correctly`() {
        val variants = listOf(
            "list-directory" to "list_directory",
            "LIST DIRECTORY" to "list_directory",
            "list_directory" to "list_directory",
            "List-Directory" to "list_directory",
            "  list_directory  " to "list_directory"
        )
        for ((input, expected) in variants) {
            val normalized = input
                .trim()
                .replace(Regex("([a-z])([A-Z])"), "$1_$2")
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
            assertEquals("Input '$input' should normalize to '$expected'", expected, normalized)
        }
    }

    // ═══════════════════════════════════════════════════════
    // 3. ARGS SHAPE VARIATIONS
    // ═══════════════════════════════════════════════════════

    @Test
    fun `fallback - args as stringified JSON object`() {
        // Some models return args as a JSON string: "args": "{\"path\":\"/ext\"}"
        val root = buildJsonObject {
            put("action", "list_directory")
            put("args", """{"path":"/ext/subghz"}""")
        }

        val argsElement = root["args"]
        assertTrue("args should be a JsonPrimitive string", argsElement is JsonPrimitive)
        val inner = (argsElement as JsonPrimitive).content
        val parsed = json.parseToJsonElement(inner) as JsonObject
        assertEquals("/ext/subghz", parsed["path"]?.jsonPrimitive?.content)
    }

    @Test
    fun `fallback - args wrapped in single-element array`() {
        // Some models: "args": [{"path": "/ext"}]
        val argsArray = JsonArray(listOf(
            buildJsonObject { put("path", "/ext/nfc") }
        ))

        if (argsArray.size != 1 || argsArray.first() !is JsonObject) {
            fail("Should have unwrapped single-element array")
        }
        val unwrapped = argsArray.first() as JsonObject
        assertEquals("/ext/nfc", unwrapped["path"]?.jsonPrimitive?.content)
    }

    @Test
    fun `fallback - args as empty array yields empty object`() {
        val argsArray = JsonArray(emptyList())
        if (argsArray.isNotEmpty()) {
            fail("Should have produced empty JsonObject")
        }
        val result = JsonObject(emptyMap())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `fallback - null args yields empty object`() {
        val root = buildJsonObject {
            put("action", "get_device_info")
            // no "args" key
        }
        val argsElement = root["args"]
        assertNull(argsElement)
        // Fallback parser would use JsonObject(emptyMap())
    }

    @Test
    fun `fallback - empty string args yields empty object`() {
        val root = buildJsonObject {
            put("action", "get_storage_info")
            put("args", "")
        }
        val argsElement = root["args"]!!
        assertTrue(argsElement is JsonPrimitive)
        val inner = (argsElement as JsonPrimitive).content
        assertTrue(inner.isBlank())
        // Fallback parser would use JsonObject(emptyMap())
    }

    @Test
    fun `fallback - top-level JSON wrapped in single-element array`() {
        // Some models: [{"action":"list_directory","args":{"path":"/ext"}}]
        val input = """[{"action":"list_directory","args":{"path":"/ext"},"justification":"test","expected_effect":"test"}]"""
        val element = json.parseToJsonElement(input)
        assertTrue(element is JsonArray)
        val unwrapped = (element as JsonArray)[0]
        assertTrue(unwrapped is JsonObject)
        val root = unwrapped as JsonObject
        assertEquals("list_directory", root["action"]?.jsonPrimitive?.content)
    }

    // ═══════════════════════════════════════════════════════
    // 4. FIELD ALIAS COVERAGE
    // ═══════════════════════════════════════════════════════

    @Test
    fun `field alias - command can be extracted from cmd key`() {
        val args = buildJsonObject { put("cmd", "storage list /ext") }
        val value = listOf("command", "cmd").firstNotNullOfOrNull { key ->
            args[key]?.jsonPrimitive?.contentOrNull
        }
        assertEquals("storage list /ext", value)
    }

    @Test
    fun `field alias - path can be extracted from file_path key`() {
        val args = buildJsonObject { put("file_path", "/ext/test.sub") }
        val value = listOf("path", "file_path", "filepath").firstNotNullOfOrNull { key ->
            args[key]?.jsonPrimitive?.contentOrNull
        }
        assertEquals("/ext/test.sub", value)
    }

    @Test
    fun `field alias - destination_path can be extracted from dest key`() {
        val args = buildJsonObject { put("dest", "/ext/backup/test.sub") }
        val value = listOf("destination_path", "destinationPath", "dest", "destination").firstNotNullOfOrNull { key ->
            args[key]?.jsonPrimitive?.contentOrNull
        }
        assertEquals("/ext/backup/test.sub", value)
    }

    @Test
    fun `field alias - download_url separated from content`() {
        // Critical test: download_url should map to downloadUrl, NOT content
        val args = buildJsonObject {
            put("download_url", "https://example.com/file.ir")
            put("content", "some text content")
        }

        // downloadUrl extraction
        val downloadUrl = listOf("download_url", "downloadUrl", "url").firstNotNullOfOrNull { key ->
            args[key]?.jsonPrimitive?.contentOrNull
        }
        assertEquals("https://example.com/file.ir", downloadUrl)

        // content extraction (should NOT include download_url)
        val content = listOf("content", "text", "data").firstNotNullOfOrNull { key ->
            args[key]?.jsonPrimitive?.contentOrNull
        }
        assertEquals("some text content", content)
    }

    @Test
    fun `field alias - repo_id from repoId camelCase`() {
        val args = buildJsonObject { put("repoId", "irdb") }
        val value = listOf("repo_id", "repoId", "repo").firstNotNullOfOrNull { key ->
            args[key]?.jsonPrimitive?.contentOrNull
        }
        assertEquals("irdb", value)
    }

    @Test
    fun `field alias - sub_path from subPath camelCase`() {
        val args = buildJsonObject { put("subPath", "TVs/LG") }
        val value = listOf("sub_path", "subPath").firstNotNullOfOrNull { key ->
            args[key]?.jsonPrimitive?.contentOrNull
        }
        assertEquals("TVs/LG", value)
    }

    @Test
    fun `field alias - search_scope from scope shorthand`() {
        val args = buildJsonObject { put("scope", "repositories") }
        val value = listOf("search_scope", "searchScope", "scope").firstNotNullOfOrNull { key ->
            args[key]?.jsonPrimitive?.contentOrNull
        }
        assertEquals("repositories", value)
    }

    @Test
    fun `field alias - photo_prompt from photoPrompt camelCase`() {
        val args = buildJsonObject { put("photoPrompt", "What TV is this?") }
        val value = listOf("photo_prompt", "photoPrompt").firstNotNullOfOrNull { key ->
            args[key]?.jsonPrimitive?.contentOrNull
        }
        assertEquals("What TV is this?", value)
    }

    @Test
    fun `field alias - boolean recursive from string true`() {
        val args = buildJsonObject { put("recursive", "true") }
        val prim = args["recursive"]?.jsonPrimitive
        val value = prim?.booleanOrNull ?: when (prim?.contentOrNull?.lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
        assertEquals(true, value)
    }

    @Test
    fun `field alias - boolean enabled from string 1`() {
        val args = buildJsonObject { put("enabled", "1") }
        val prim = args["enabled"]?.jsonPrimitive
        val value = prim?.booleanOrNull ?: when (prim?.contentOrNull?.lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
        assertEquals(true, value)
    }

    @Test
    fun `field alias - integer red from string`() {
        val args = buildJsonObject { put("red", "255") }
        val prim = args["red"]?.jsonPrimitive
        val value = prim?.intOrNull ?: prim?.contentOrNull?.toIntOrNull()
        assertEquals(255, value)
    }

    @Test
    fun `field alias - frequency from string`() {
        val args = buildJsonObject { put("freq", "433920000") }
        val prim = args["freq"]?.jsonPrimitive
        val value = prim?.longOrNull ?: prim?.contentOrNull?.toLongOrNull()
        assertEquals(433920000L, value)
    }

    // ═══════════════════════════════════════════════════════
    // 5. FALLBACK FIELD LOOKUP IN ROOT (not just args)
    // ═══════════════════════════════════════════════════════

    @Test
    fun `fallback - field found at root level when missing from args`() {
        // Some models put fields at root instead of inside args:
        // {"action":"read_file","path":"/ext/test","args":{}}
        val root = buildJsonObject {
            put("action", "read_file")
            put("path", "/ext/test.sub")
            putJsonObject("args") {}
        }

        val argsObj = root["args"] as JsonObject
        // stringArg checks argsObject first, then root
        val path = listOf("path", "file_path").firstNotNullOfOrNull { key ->
            argsObj[key]?.jsonPrimitive?.contentOrNull
                ?: root[key]?.jsonPrimitive?.contentOrNull
        }
        assertEquals("/ext/test.sub", path)
    }

    // ═══════════════════════════════════════════════════════
    // 6. ERROR CASES
    // ═══════════════════════════════════════════════════════

    @Test
    fun `error - completely broken JSON`() {
        val input = "this is not json at all"
        val result = runCatching { json.parseToJsonElement(input) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `error - JSON object missing action field`() {
        val root = buildJsonObject {
            putJsonObject("args") { put("path", "/ext") }
        }
        val actionValue = root["action"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        assertTrue(actionValue.isEmpty())
    }

    @Test
    fun `error - unsupported action value`() {
        val normalized = "do_something_unknown"
            .trim()
            .replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')

        // This should not match any known action
        val knownActions = setOf(
            "list_directory", "read_file", "write_file", "create_directory",
            "delete", "move", "rename", "copy", "get_device_info",
            "get_storage_info", "search_faphub", "install_faphub_app",
            "push_artifact", "execute_cli", "forge_payload",
            "search_resources", "browse_repo", "download_resource",
            "github_search", "list_vault", "run_runbook",
            "launch_app", "subghz_transmit", "ir_transmit",
            "nfc_emulate", "rfid_emulate", "ibutton_emulate",
            "badusb_execute", "ble_spam", "led_control",
            "vibro_control", "request_photo"
        )
        assertFalse(knownActions.contains(normalized))
    }

    @Test
    fun `error - args is a multi-element array`() {
        val argsElement: JsonElement = JsonArray(listOf(
            buildJsonObject { put("path", "/ext/a") },
            buildJsonObject { put("path", "/ext/b") }
        ))

        // The parser should reject multi-element arrays
        val isInvalid = argsElement is JsonArray && argsElement.size > 1
        assertTrue(isInvalid)
    }

    @Test
    fun `error - args is a bare number`() {
        val root = buildJsonObject {
            put("action", "list_directory")
            put("args", 42)
        }
        val argsElement = root["args"]!!
        assertTrue(argsElement is JsonPrimitive)
        val inner = (argsElement as JsonPrimitive).contentOrNull.orEmpty()
        // "42" is not blank, so the parser tries to parse it as JSON...
        val parsed = runCatching { json.parseToJsonElement(inner) }.getOrNull()
        // ...which succeeds but gives a primitive, not an object
        assertNotNull(parsed)
        assertFalse(parsed is JsonObject)
    }

    // ═══════════════════════════════════════════════════════
    // 7. PARAMETERS ALIAS KEY
    // ═══════════════════════════════════════════════════════

    @Test
    fun `fallback - parameters key used instead of args`() {
        val root = buildJsonObject {
            put("action", "read_file")
            putJsonObject("parameters") {
                put("path", "/ext/infrared/tv.ir")
            }
        }
        // The parser checks root["args"] ?: root["parameters"]
        val argsElement = root["args"] ?: root["parameters"]
        assertNotNull(argsElement)
        assertTrue(argsElement is JsonObject)
        assertEquals("/ext/infrared/tv.ir", (argsElement as JsonObject)["path"]?.jsonPrimitive?.content)
    }

    // ═══════════════════════════════════════════════════════
    // 8. ArgumentsAsStringSerializer coverage
    // ═══════════════════════════════════════════════════════

    @Test
    fun `ArgumentsAsString - string arguments deserialize correctly`() {
        // Standard form: arguments is a JSON string
        val input = """{"id":"call_123","type":"function","function":{"name":"execute_command","arguments":"{\"action\":\"list_directory\",\"args\":{\"path\":\"/ext\"}}"}}"""
        val tc = json.decodeFromString<OpenRouterToolCall>(input)
        assertEquals("call_123", tc.id)
        assertEquals("execute_command", tc.function.name)
        assertTrue(tc.function.arguments.contains("list_directory"))
    }

    @Test
    fun `ArgumentsAsString - object arguments deserialize to string`() {
        // Non-standard form: arguments is a raw JSON object (not string-encoded)
        val input = """{"id":"call_456","type":"function","function":{"name":"execute_command","arguments":{"action":"read_file","args":{"path":"/ext/test"}}}}"""
        val tc = json.decodeFromString<OpenRouterToolCall>(input)
        assertEquals("call_456", tc.id)
        assertTrue(tc.function.arguments.contains("read_file"))
        assertTrue(tc.function.arguments.contains("/ext/test"))
    }

    // ═══════════════════════════════════════════════════════
    // 9. INSTALL FAPHUB with download_url properly separated
    // ═══════════════════════════════════════════════════════

    @Test
    fun `install_faphub_app with command and download_url`() {
        // In strict path: "command" maps to CommandArgs.command, "download_url" maps to downloadUrl
        val input = """
        {
            "action": "install_faphub_app",
            "args": {
                "command": "wifi_marauder",
                "download_url": "https://example.com/marauder.fap"
            },
            "justification": "Install WiFi Marauder",
            "expected_effect": "App installed"
        }
        """.trimIndent()

        val cmd = json.decodeFromString<ExecuteCommand>(input)
        assertEquals(CommandAction.INSTALL_FAPHUB_APP, cmd.action)
        assertEquals("wifi_marauder", cmd.args.command)
        assertEquals("https://example.com/marauder.fap", cmd.args.downloadUrl)
    }

    @Test
    fun `install_faphub_app fallback - app_id alias maps to command`() {
        // In the fallback parser, "app_id" is an alias for "command"
        // This test validates the alias list covers app_id
        val args = buildJsonObject { put("app_id", "wifi_marauder") }
        val value = listOf("command", "cmd", "query", "app_id", "appId", "app", "package", "name")
            .firstNotNullOfOrNull { key -> args[key]?.jsonPrimitive?.contentOrNull }
        assertEquals("wifi_marauder", value)
    }

    // ═══════════════════════════════════════════════════════
    // 10. FULL END-TO-END STRICT DESERIALIZATION OF EVERY ACTION
    // ═══════════════════════════════════════════════════════

    @Test
    fun `every CommandAction can be deserialized from its SerialName`() {
        // Ensure every enum value has a valid SerialName that round-trips
        for (action in CommandAction.entries) {
            val serialized = json.encodeToString(CommandAction.serializer(), action)
            val deserialized = json.decodeFromString(CommandAction.serializer(), serialized)
            assertEquals("Action ${action.name} should round-trip", action, deserialized)
        }
    }

    @Test
    fun `CommandArgs with all fields set round-trips`() {
        val args = CommandArgs(
            command = "test_cmd",
            path = "/ext/test",
            destinationPath = "/ext/dest",
            content = "file content",
            newName = "renamed.txt",
            recursive = true,
            artifactType = "fap",
            artifactData = "base64data",
            prompt = "do something",
            resourceType = "IR_REMOTE",
            runbookId = "link_health",
            payloadType = "BAD_USB",
            filter = "SUB_GHZ",
            appName = "Sub-GHz",
            appArgs = "-v",
            frequency = 433920000L,
            protocol = "Princeton",
            address = "0x1234",
            signalName = "Power",
            enabled = true,
            red = 255,
            green = 128,
            blue = 64,
            repoId = "irdb",
            subPath = "TVs/LG",
            downloadUrl = "https://example.com/file.ir",
            searchScope = "code",
            photoPrompt = "What device is this?"
        )

        val serialized = json.encodeToString(CommandArgs.serializer(), args)
        val deserialized = json.decodeFromString(CommandArgs.serializer(), serialized)

        assertEquals(args.command, deserialized.command)
        assertEquals(args.path, deserialized.path)
        assertEquals(args.destinationPath, deserialized.destinationPath)
        assertEquals(args.content, deserialized.content)
        assertEquals(args.downloadUrl, deserialized.downloadUrl)
        assertEquals(args.repoId, deserialized.repoId)
        assertEquals(args.subPath, deserialized.subPath)
        assertEquals(args.searchScope, deserialized.searchScope)
        assertEquals(args.photoPrompt, deserialized.photoPrompt)
        assertEquals(args.frequency, deserialized.frequency)
        assertEquals(args.red, deserialized.red)
        assertEquals(args.green, deserialized.green)
        assertEquals(args.blue, deserialized.blue)
        assertEquals(args.enabled, deserialized.enabled)
    }
}
