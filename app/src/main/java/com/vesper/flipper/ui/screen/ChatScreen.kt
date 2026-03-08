package com.vesper.flipper.ui.screen

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vesper.flipper.data.database.ChatSessionSummary
import com.vesper.flipper.domain.model.*
import com.vesper.flipper.ui.components.ApprovalDialog
import com.vesper.flipper.ui.components.DiffViewer
import com.vesper.flipper.ui.theme.*
import com.vesper.flipper.ui.viewmodel.ChatViewModel
import com.vesper.flipper.voice.SpeechState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    onNavigateToDevice: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToFiles: () -> Unit,
    onNavigateToAudit: () -> Unit
) {
    val conversationState by viewModel.conversationState.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val pendingImages by viewModel.pendingImages.collectAsState()
    val isProcessingImage by viewModel.isProcessingImage.collectAsState()
    val sessionHistory by viewModel.sessionHistory.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showHistoryDrawer by remember { mutableStateOf(false) }

    // Photo picker launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.addImage(it) }
    }

    // Multi-photo picker launcher
    val multiPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)
    ) { uris: List<Uri> ->
        uris.forEach { viewModel.addImage(it) }
    }

    // Voice input state
    val voiceState by viewModel.voiceState.collectAsState()
    val voicePartialResult by viewModel.voicePartialResult.collectAsState()
    val voiceError by viewModel.voiceError.collectAsState()
    var hasMicPermission by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    // Microphone permission launcher
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
        if (isGranted) {
            viewModel.startVoiceInput()
        }
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(conversationState.messages.size) {
        if (conversationState.messages.isNotEmpty()) {
            listState.animateScrollToItem(conversationState.messages.size - 1)
        }
    }

    // Show approval dialog if pending
    conversationState.pendingApproval?.let { approval ->
        ApprovalDialog(
            approval = approval,
            onApprove = { viewModel.approveAction() },
            onReject = { viewModel.rejectAction() }
        )
    }

    // Delete confirmation dialog
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            icon = {
                Icon(
                    Icons.Default.DeleteSweep,
                    contentDescription = null,
                    tint = RiskHigh
                )
            },
            title = { Text("Clear Chat") },
            text = { Text("Delete this conversation? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.clearConversation()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = RiskHigh)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Chat history bottom sheet
    if (showHistoryDrawer) {
        ChatHistorySheet(
            sessions = sessionHistory,
            currentSessionId = conversationState.sessionId,
            onSelectSession = { sessionId ->
                showHistoryDrawer = false
                viewModel.loadSession(sessionId)
            },
            onDeleteSession = { sessionId ->
                viewModel.deleteSession(sessionId)
            },
            onNewThread = {
                showHistoryDrawer = false
                viewModel.startNewSession()
            },
            onDismiss = { showHistoryDrawer = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "VESPER",
                            fontWeight = FontWeight.Light,
                            fontFamily = FontFamily.Serif,
                            letterSpacing = 3.sp
                        )
                        Text(
                            "Flipper Zero Command",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            letterSpacing = 1.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToDevice) {
                        Icon(
                            Icons.Default.Bluetooth,
                            contentDescription = "Device",
                            tint = VesperOrange
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showHistoryDrawer = true }) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = "Chat History"
                        )
                    }
                    IconButton(onClick = {
                        if (conversationState.messages.isNotEmpty()) {
                            showDeleteConfirmation = true
                        }
                    }) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = "Clear"
                        )
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "More"
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("File Browser") },
                                onClick = {
                                    showOverflowMenu = false
                                    onNavigateToFiles()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Audit Log") },
                                onClick = {
                                    showOverflowMenu = false
                                    onNavigateToAudit()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            ChatInputBar(
                value = inputText,
                onValueChange = { viewModel.updateInput(it) },
                onSend = { viewModel.sendMessage() },
                onAttachImage = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onVoiceInput = {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onStopVoice = { viewModel.stopVoiceInput() },
                pendingImages = pendingImages,
                onRemoveImage = { viewModel.removeImage(it) },
                isLoading = conversationState.isLoading,
                isProcessingImage = isProcessingImage,
                voiceState = voiceState,
                voicePartialResult = voicePartialResult,
                enabled = !conversationState.isLoading && conversationState.pendingApproval == null
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (conversationState.messages.isEmpty()) {
                EmptyChat()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(conversationState.messages) { message ->
                        ChatMessageItem(message = message)
                    }

                    if (conversationState.isLoading) {
                        item {
                            LoadingIndicator(progress = conversationState.progress)
                        }
                    }
                }
            }

            // Error snackbar
            conversationState.error?.let { error ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    containerColor = RiskHigh
                ) {
                    Text(error)
                }
            }
        }
    }
}

@Composable
private fun EmptyChat() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Barrel ring icon — matches logo aesthetic
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(VesperOrange.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .then(
                            Modifier.background(
                                brush = Brush.radialGradient(
                                    colors = listOf(VesperOrange.copy(alpha = 0.15f), Color.Transparent)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "V",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Light,
                        color = VesperOrange,
                        fontFamily = FontFamily.Serif
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                "VESPER",
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 4.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                "Your Flipper. Your rules.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SuggestionChip("List files on the SD card")
                SuggestionChip("Forge a Sub-GHz signal")
                SuggestionChip("Install an app from FapHub")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Voice and image input supported",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            )
        }
    }
}

@Composable
private fun SuggestionChip(text: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChatMessageItem(message: ChatMessage) {
    val isUser = message.role == MessageRole.USER
    val isAssistant = message.role == MessageRole.ASSISTANT
    val isTool = message.role == MessageRole.TOOL
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            if (isAssistant) {
                // Vesper monogram avatar — wine circle with gold V
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(VesperWine, VesperWineDark)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "V",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Light,
                        fontFamily = FontFamily.Serif,
                        color = VesperGold
                    )
                }
            } else {
                // Tool avatar
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = if (isUser) 16.dp else 4.dp,
                    topEnd = if (isUser) 4.dp else 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp
                ),
                color = when {
                    isUser -> VesperOrange
                    isTool -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    // Show image attachments if present
                    if (!message.imageAttachments.isNullOrEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            message.imageAttachments.forEach { attachment ->
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(attachment.localUri)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "Attached image",
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                        if (message.content.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    if (message.content.isNotEmpty()) {
                        Text(
                            text = message.content,
                            color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    // Show tool calls
                    message.toolCalls?.forEach { toolCall ->
                        Spacer(modifier = Modifier.height(8.dp))
                        ToolCallDisplay(toolCall)
                    }

                    // Show tool results with execution details
                    message.toolResults?.forEach { toolResult ->
                        if (message.content.isNotEmpty() || !message.toolCalls.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        ToolResultDisplay(toolResult)
                    }
                }
            }

            // Status indicator
            if (message.status == MessageStatus.AWAITING_APPROVAL) {
                Text(
                    "Awaiting approval...",
                    style = MaterialTheme.typography.bodySmall,
                    color = RiskMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun ToolCallDisplay(toolCall: ToolCall) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = VesperGold
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "execute_command",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = VesperGold
                )
            }
        }
    }
}

@Composable
private fun LoadingIndicator(progress: AgentProgress?) {
    val label = progress?.detail ?: when (progress?.stage) {
        AgentProgressStage.MODEL_REQUEST -> "Contacting model..."
        AgentProgressStage.TOOL_PLANNED -> "Preparing tool execution..."
        AgentProgressStage.TOOL_EXECUTING -> "Sending command to Flipper..."
        AgentProgressStage.TOOL_COMPLETED -> "Command finished. Summarizing..."
        AgentProgressStage.WAITING_APPROVAL -> "Waiting for your approval..."
        null -> "Thinking..."
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(VesperWine, VesperWineDark)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "V",
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Serif,
                color = VesperGold
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            color = VesperGold,
            strokeWidth = 2.dp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private val toolResultJson = Json { ignoreUnknownKeys = true }

@Composable
private fun ToolResultDisplay(toolResult: ToolResult) {
    val parsed = remember(toolResult.content, toolResult.success) {
        parseToolResult(toolResult)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (parsed.success) {
            VesperGold.copy(alpha = 0.12f)
        } else {
            RiskHigh.copy(alpha = 0.14f)
        }
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (parsed.success) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (parsed.success) VesperGold else RiskHigh
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = parsed.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (parsed.success) VesperGold else RiskHigh
                )
            }

            if (parsed.detail.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = parsed.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3
                )
            }
        }
    }
}

private data class ParsedToolResult(
    val success: Boolean,
    val title: String,
    val detail: String
)

private fun parseToolResult(toolResult: ToolResult): ParsedToolResult {
    return runCatching {
        val root = toolResultJson.parseToJsonElement(toolResult.content).jsonObject
        val success = root["success"]?.jsonPrimitive?.booleanOrNull ?: toolResult.success
        val action = root["action"]?.jsonPrimitive?.contentOrNull
            ?.replace('_', ' ')
            ?.lowercase()
            ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            ?: if (success) "Command Executed" else "Command Failed"
        val error = root["error"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val data = root["data"]?.jsonObject
        val message = data?.get("message")?.jsonPrimitive?.contentOrNull
        val content = data?.get("content")?.jsonPrimitive?.contentOrNull
        val detail = when {
            error.isNotBlank() -> error
            !message.isNullOrBlank() -> message
            !content.isNullOrBlank() -> content.lineSequence().firstOrNull().orEmpty()
            else -> toolResult.content.take(120)
        }
        ParsedToolResult(
            success = success,
            title = action,
            detail = detail
        )
    }.getOrElse {
        ParsedToolResult(
            success = toolResult.success,
            title = if (toolResult.success) "Command Executed" else "Command Failed",
            detail = toolResult.content.take(120)
        )
    }
}

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachImage: () -> Unit,
    onVoiceInput: () -> Unit,
    onStopVoice: () -> Unit,
    pendingImages: List<ImageAttachment>,
    onRemoveImage: (String) -> Unit,
    isLoading: Boolean,
    isProcessingImage: Boolean,
    voiceState: SpeechState,
    voicePartialResult: String,
    enabled: Boolean
) {
    val context = LocalContext.current
    val hasContent = value.isNotBlank() || pendingImages.isNotEmpty()
    val isListening = voiceState is SpeechState.Listening
    val isProcessingVoice = voiceState is SpeechState.Processing

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Voice listening indicator
            AnimatedVisibility(
                visible = isListening || isProcessingVoice,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                VoiceInputIndicator(
                    isListening = isListening,
                    partialResult = voicePartialResult,
                    onStop = onStopVoice
                )
            }

            // Image preview row
            AnimatedVisibility(
                visible = pendingImages.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pendingImages.forEach { image ->
                        ImagePreviewChip(
                            image = image,
                            onRemove = { onRemoveImage(image.id) }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Attachment button
                IconButton(
                    onClick = onAttachImage,
                    enabled = enabled && !isProcessingImage && !isListening
                ) {
                    if (isProcessingImage) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = VesperOrange,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = "Attach image",
                            tint = VesperOrange
                        )
                    }
                }

                // Voice input button
                IconButton(
                    onClick = {
                        if (isListening) {
                            onStopVoice()
                        } else {
                            onVoiceInput()
                        }
                    },
                    enabled = enabled && !isProcessingImage
                ) {
                    if (isProcessingVoice) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = VesperOrange,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (isListening) "Stop listening" else "Voice input",
                            tint = if (isListening) MaterialTheme.colorScheme.error else VesperOrange
                        )
                    }
                }

                OutlinedTextField(
                    value = if (isListening && voicePartialResult.isNotBlank()) {
                        "$value $voicePartialResult".trim()
                    } else value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            when {
                                isListening -> "Listening..."
                                pendingImages.isNotEmpty() -> "Add a message..."
                                else -> "Command your Flipper..."
                            }
                        )
                    },
                    enabled = enabled && !isListening,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (hasContent) onSend() }),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VesperOrange,
                        cursorColor = VesperOrange
                    ),
                    maxLines = 4
                )

                Spacer(modifier = Modifier.width(8.dp))

                FilledIconButton(
                    onClick = onSend,
                    enabled = enabled && hasContent && !isListening,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = VesperOrange
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Send"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceInputIndicator(
    isListening: Boolean,
    partialResult: String,
    onStop: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = VesperOrange.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Animated mic icon
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(VesperOrange),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (isListening) "Listening..." else "Processing...",
                    style = MaterialTheme.typography.labelMedium,
                    color = VesperOrange
                )
                if (partialResult.isNotBlank()) {
                    Text(
                        partialResult,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 2
                    )
                }
            }

            IconButton(onClick = onStop) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Cancel",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ImagePreviewChip(
    image: ImageAttachment,
    onRemove: () -> Unit
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier.size(64.dp)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(image.localUri)
                .crossfade(true)
                .build(),
            contentDescription = "Attached image",
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )

        // Remove button
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 4.dp, y = (-4).dp)
                .size(20.dp)
                .clickable { onRemove() },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.error
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove image",
                modifier = Modifier
                    .padding(2.dp)
                    .size(16.dp),
                tint = Color.White
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatHistorySheet(
    sessions: List<ChatSessionSummary>,
    currentSessionId: String,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onNewThread: () -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    var sessionToDelete by remember { mutableStateOf<String?>(null) }

    // Confirm delete for a history item
    sessionToDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("Delete Chat") },
            text = { Text("Delete this conversation from history?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSession(id)
                        sessionToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = RiskHigh)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) { Text("Cancel") }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Chat History",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = onNewThread,
                    colors = ButtonDefaults.buttonColors(containerColor = VesperAccent)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New Chat")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (sessions.isEmpty()) {
                Text(
                    "No saved conversations yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sessions, key = { it.sessionId }) { session ->
                        val isCurrent = session.sessionId == currentSessionId
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectSession(session.sessionId) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isCurrent) {
                                VesperAccent.copy(alpha = 0.15f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            border = if (isCurrent) BorderStroke(1.dp, VesperAccent) else null
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Chat,
                                    contentDescription = null,
                                    tint = if (isCurrent) VesperAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = dateFormat.format(Date(session.lastTimestamp)),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        text = "${session.messageCount} messages",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isCurrent) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = VesperAccent
                                    ) {
                                        Text(
                                            "Active",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White
                                        )
                                    }
                                }
                                if (!isCurrent) {
                                    IconButton(
                                        onClick = { sessionToDelete = session.sessionId },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
