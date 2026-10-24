package com.vesper.flipper.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vesper.flipper.ui.components.ApprovalDialog
import com.vesper.flipper.ui.components.VErrorBanner
import com.vesper.flipper.ui.components.VInlineLoading
import com.vesper.flipper.ui.components.VSkeletonList
import com.vesper.flipper.ui.components.VesperScaffold
import com.vesper.flipper.ui.screen.chat.ApprovalReminder
import com.vesper.flipper.ui.screen.chat.ChatHistorySheet
import com.vesper.flipper.ui.screen.chat.ChatInputBar
import com.vesper.flipper.ui.screen.chat.ChatMessageList
import com.vesper.flipper.ui.screen.chat.EmptyChatState
import com.vesper.flipper.ui.screen.chat.StoppedTurnBanner
import com.vesper.flipper.ui.screen.chat.rememberChatMediaActions
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.ChatViewModel

/**
 * Thin chat shell.
 *
 * State comes from a single ChatUiState collection. The transcript, composer,
 * history, approval sheet and state-aware empty state live in ui/screen/chat
 * and ui/components/approval.
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    onNavigateToAudit: () -> Unit,
    onNavigateToSettings: () -> Unit = {}
) {
    val ui by viewModel.uiState.collectAsState()
    val sessions by viewModel.sessionHistory.collectAsState()
    val colors = Vesper.colors
    val snackbarHostState = remember { SnackbarHostState() }

    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showAttachMenu by rememberSaveable { mutableStateOf(false) }
    var dismissedApprovalId by rememberSaveable { mutableStateOf<String?>(null) }
    var dismissedError by rememberSaveable { mutableStateOf<String?>(null) }
    var lastApprovalId by rememberSaveable { mutableStateOf<String?>(null) }

    val approval = ui.pendingApproval
    // Only a genuinely new approval clears a dismissal. Re-running this effect
    // after rotation must not reopen a sheet the user closed.
    LaunchedEffect(approval?.id) {
        val currentId = approval?.id
        if (currentId != lastApprovalId) {
            lastApprovalId = currentId
            dismissedApprovalId = null
        }
    }
    LaunchedEffect(ui.isLoading) { if (ui.isLoading) dismissedError = null }

    LaunchedEffect(ui.imageError) {
        val message = ui.imageError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = message,
            withDismissAction = true,
            duration = SnackbarDuration.Short
        )
        viewModel.clearImageError()
    }

    val media = rememberChatMediaActions(
        onImage = viewModel::addImage,
        onVoiceInput = viewModel::startVoiceInput
    )

    val subtitle = when {
        ui.pendingApproval != null -> "Waiting for your approval"
        ui.isLoading -> "Working"
        ui.hasDevice -> ui.deviceName?.let { "Connected to " + it } ?: "Connected"
        else -> "No Flipper connected"
    }

    VesperScaffold(
        title = "Chat",
        subtitle = subtitle,
        navigationIcon = {
            IconButton(onClick = { showHistory = true }) {
                Icon(
                    imageVector = Icons.Rounded.History,
                    contentDescription = "Chat history",
                    tint = colors.textSecondary
                )
            }
        },
        actions = {
            IconButton(onClick = onNavigateToAudit) {
                Icon(
                    imageVector = Icons.Rounded.ReceiptLong,
                    contentDescription = "Open audit log",
                    tint = colors.textSecondary
                )
            }
            IconButton(onClick = { viewModel.startNewSession() }) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Start a new chat",
                    tint = colors.gold
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            ChatInputBar(
                value = ui.input,
                onValueChange = viewModel::updateInput,
                onSend = viewModel::sendMessage,
                onStop = viewModel::cancel,
                canStop = ui.canStop,
                isLoading = ui.isLoading,
                enabled = !ui.isLoading && ui.pendingApproval == null,
                pendingImages = ui.pendingImages,
                isProcessingImage = ui.isProcessingImage,
                onRemoveImage = viewModel::removeImage,
                onAttach = { showAttachMenu = true },
                showAttachMenu = showAttachMenu,
                onDismissAttachMenu = { showAttachMenu = false },
                onPickFromGallery = {
                    showAttachMenu = false
                    media.pickFromGallery()
                },
                onTakePhoto = {
                    showAttachMenu = false
                    media.takePhoto()
                },
                onRecordVideo = {
                    showAttachMenu = false
                    media.recordVideo()
                },
                voiceState = ui.voiceState,
                voicePartialText = ui.voicePartialText,
                voiceError = ui.voiceError,
                onVoiceInput = media.startVoiceInput,
                onStopVoice = viewModel::stopVoiceInput,
                glassesConnected = ui.glassesConnected,
                glassesMuted = ui.glassesMuted,
                onToggleGlassesMute = viewModel::toggleGlassesMute
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .semantics { paneTitle = "Chat" }
        ) {
            // The visible title lives inside VesperTopBar, so expose the screen
            // heading as a one-pixel semantics anchor for TalkBack navigation.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .clearAndSetSemantics {
                        heading()
                        contentDescription = "Chat"
                    }
            )

            if (ui.error != null && ui.error != dismissedError) {
                VErrorBanner(
                    message = ui.error.orEmpty(),
                    onRetry = viewModel::retryLastMessage,
                    onDismiss = { dismissedError = ui.error },
                    visible = true
                )
            }

            if (approval != null && dismissedApprovalId == approval.id) {
                ApprovalReminder(
                    onReview = { dismissedApprovalId = null },
                    modifier = Modifier.padding(
                        horizontal = VesperSpace.gutter,
                        vertical = VesperSpace.xs
                    )
                )
            }

            if (ui.turnStopped && !ui.isLoading && ui.messages.isNotEmpty()) {
                StoppedTurnBanner(
                    onRetry = viewModel::retryLastMessage,
                    onDismiss = viewModel::dismissStoppedTurn,
                    modifier = Modifier.padding(
                        horizontal = VesperSpace.gutter,
                        vertical = VesperSpace.xs
                    )
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    !ui.isReady -> VSkeletonList(rows = 6)

                    ui.messages.isEmpty() && ui.isLoading -> Column {
                        VSkeletonList(rows = 4)
                        VInlineLoading(text = "Contacting the model")
                    }

                    ui.messages.isEmpty() -> EmptyChatState(
                        hasDevice = ui.hasDevice,
                        hasApiKey = ui.hasApiKey,
                        onNavigateToSettings = onNavigateToSettings,
                        onSendPrompt = { prompt ->
                            viewModel.updateInput(prompt)
                            viewModel.sendMessage()
                        }
                    )

                    else -> ChatMessageList(
                        messages = ui.messages,
                        sessionId = ui.sessionId,
                        isLoading = ui.isLoading,
                        canStop = ui.canStop,
                        progress = ui.progress,
                        pendingApproval = ui.pendingApproval,
                        ttsSpeakingId = ui.ttsSpeakingId,
                        ttsLoading = ui.ttsLoading,
                        showRetryOnLast = !ui.isLoading && ui.error != null,
                        onRetry = viewModel::retryLastMessage,
                        onStop = viewModel::cancel,
                        onSpeak = viewModel::speakMessage,
                        onStopSpeaking = viewModel::stopSpeaking
                    )
                }
            }
        }
    }

    if (showHistory) {
        ChatHistorySheet(
            sessions = sessions,
            currentSessionId = ui.sessionId,
            onSelectSession = { sessionId ->
                showHistory = false
                viewModel.loadSession(sessionId)
            },
            onDeleteSession = viewModel::deleteSession,
            onClearConversation = {
                showHistory = false
                viewModel.clearConversation()
            },
            onNewThread = {
                showHistory = false
                viewModel.startNewSession()
            },
            onDismiss = { showHistory = false }
        )
    }

    if (approval != null && dismissedApprovalId != approval.id) {
        ApprovalDialog(
            approval = approval,
            onApprove = { viewModel.approveAction(approval.id) },
            onReject = { viewModel.rejectAction(approval.id) },
            onDismiss = { dismissedApprovalId = approval.id }
        )
    }
}
