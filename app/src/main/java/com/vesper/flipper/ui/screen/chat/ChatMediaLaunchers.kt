package com.vesper.flipper.ui.screen.chat

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Ready-to-launch attachment actions for the composer. */
data class ChatMediaActions(
    val pickFromGallery: () -> Unit,
    val takePhoto: () -> Unit,
    val recordVideo: () -> Unit,
    val startVoiceInput: () -> Unit
)

/**
 * Wires the system photo picker, camera, video capture and microphone permission
 * so the screen itself only deals with layout and state.
 */
@Composable
fun rememberChatMediaActions(
    onImage: (Uri) -> Unit,
    onVoiceInput: () -> Unit
): ChatMediaActions {
    val context = LocalContext.current
    var cameraUriString by rememberSaveable { mutableStateOf<String?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)
    ) { uris: List<Uri> ->
        uris.forEach { onImage(it) }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = cameraUriString?.let(Uri::parse)
        if (uri != null) {
            if (success) {
                onImage(uri)
            } else {
                // Some camera apps write the file but report failure.
                val exists = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.available() > 0 } == true
                }.getOrDefault(false)
                if (exists) onImage(uri)
            }
        }
    }

    val videoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CaptureVideo()
    ) { success ->
        val uri = cameraUriString?.let(Uri::parse)
        if (success && uri != null) onImage(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = createCameraCaptureUri(context, video = false)
            cameraUriString = uri.toString()
            cameraLauncher.launch(uri)
        }
    }

    val videoPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = createCameraCaptureUri(context, video = true)
            cameraUriString = uri.toString()
            videoLauncher.launch(uri)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onVoiceInput()
    }

    return ChatMediaActions(
        pickFromGallery = {
            galleryLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        takePhoto = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
        recordVideo = { videoPermissionLauncher.launch(Manifest.permission.CAMERA) },
        startVoiceInput = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    )
}

/** Temporary FileProvider URI for camera and video capture. */
private fun createCameraCaptureUri(
    context: android.content.Context,
    video: Boolean
): Uri {
    val cacheDir = java.io.File(context.cacheDir, "camera").also { it.mkdirs() }
    val cutoff = System.currentTimeMillis() - 3_600_000L
    cacheDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }

    val extension = if (video) "mp4" else "jpg"
    val file = java.io.File(cacheDir, "capture_" + System.currentTimeMillis() + "." + extension)
    return androidx.core.content.FileProvider.getUriForFile(
        context,
        context.packageName + ".fileprovider",
        file
    )
}
