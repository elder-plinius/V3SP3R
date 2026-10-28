package com.vesper.flipper.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-level UI wiring checks; does not replace an on-device Compose interaction test. */
class ToolCardsUiContractTest {
    private val screen: String by lazy {
        val relative = "src/main/java/com/vesper/flipper/ui/screen/ChatScreen.kt"
        listOf(File(relative), File("app/$relative")).first { it.exists() }.readText()
    }

    private fun section(start: String, end: String): String =
        screen.substringAfter(start).substringBefore(end)

    @Test fun `tool call is concise by default and raw arguments require expansion`() {
        val card = section("private fun ToolCallDisplay(", "private fun LoadingIndicator(")
        assertTrue(card.contains("toolCallSummary(toolCall)"))
        assertTrue(card.contains("rememberSaveable(toolCall.id) { mutableStateOf(false) }"))
        assertTrue(card.contains("onClick = { expanded = !expanded }"))
        assertTrue(card.contains("if (expanded)"))
        assertTrue(card.substringAfter("if (expanded)").contains("toolCall.arguments"))
        assertFalse(card.substringBefore("if (expanded)").contains("toolCall.arguments"))
    }

    @Test fun `result shows outcome and errors collapsed while raw result expands on tap`() {
        val card = section("private fun ToolResultDisplay(", "private data class ParsedToolResult(")
        assertTrue(card.contains("rememberSaveable(toolResult.toolCallId) { mutableStateOf(false) }"))
        assertTrue(card.contains("onClick = { expanded = !expanded }"))
        assertTrue(card.substringBefore("if (expanded) {").contains("parsed.title"))
        assertTrue(card.substringBefore("if (expanded) {").contains("parsed.detail"))
        assertTrue(card.substringAfter("if (expanded) {").contains("toolResult.content"))
        val parser = section("private fun parseToolResult(", "private fun ChatInputBar(")
        assertTrue(parser.contains("requires_confirmation"))
        assertTrue(parser.contains("error.isNotBlank()"))
    }

    @Test fun `pending approval is shown on the matching call without claiming execution`() {
        val message = section("private fun ChatMessageItem(", "private fun ToolCallDisplay(")
        val call = section("private fun ToolCallDisplay(", "private fun LoadingIndicator(")
        assertTrue(screen.contains("pendingApprovalId = conversationState.pendingApproval?.id"))
        assertTrue(message.contains("message.metadata.pendingApprovalId == pendingApprovalId"))
        assertTrue(message.contains("awaitingApproval = message.status == MessageStatus.AWAITING_APPROVAL"))
        assertTrue(call.contains("if (awaitingApproval) \"Awaiting approval\" else \"Requested\""))
        assertFalse(call.contains("Executed"))
    }

    @Test fun `fact display uses one wrapping text surface so a long label cannot starve its value`() {
        val renderer = section("private fun AssistantMessageBody(", "private data class ParsedToolResult(")
        val fact = renderer.substringAfter("\"fact\" ->").substringBefore("\"code\" ->")
        assertFalse(fact.contains("Row {"))
        assertTrue(fact.contains("buildAnnotatedString"))
    }

    @Test fun `assistant bubble is formatted but copy and speech use original model content`() {
        val message = section("private fun ChatMessageItem(", "private fun ToolCallDisplay(")
            .replace("\r\n", "\n")
        assertTrue(message.contains("if (isAssistant) {\n                                AssistantMessageBody(message.content)"))
        assertTrue(message.contains("ClipData.newPlainText(\"message\", message.content)"))
        assertTrue(message.contains("onSpeak!!(message.content)"))
        assertFalse(message.contains("message = message.copy(content = assistantBlocks"))
    }

    @Test fun `model prose remains visible independent of tool cards`() {
        val message = section("private fun ChatMessageItem(", "private fun ToolCallDisplay(")
        assertTrue(message.contains("if (message.content.isNotEmpty() || !message.imageAttachments.isNullOrEmpty())"))
        assertTrue(message.contains("text = message.content"))
        assertTrue(message.contains("ToolCallDisplay("))
        assertTrue(message.contains("ToolResultDisplay(toolResult)"))
        assertTrue(message.indexOf("text = message.content") < message.indexOf("ToolCallDisplay("))
    }
}
