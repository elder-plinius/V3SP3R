package com.vesper.flipper.ui.screen.payload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.domain.model.BadUsbPlatform
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VChip
import com.vesper.flipper.ui.components.VErrorBanner
import com.vesper.flipper.ui.components.VInlineLoading
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.components.VQuietButton
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.PayloadLabViewModel
import kotlinx.coroutines.delay

/**
 * BadUSB tab: describe a script, generate it, check the output, save it.
 * The explainer stays at the top because the file only makes sense as a
 * keystroke script typed into a host.
 */
@Composable
fun BadUsbTab(viewModel: PayloadLabViewModel) {
    val colors = Vesper.colors
    val context = LocalContext.current

    val prompt by viewModel.badUsbPrompt.collectAsState()
    val platform by viewModel.badUsbPlatform.collectAsState()
    val script by viewModel.generatedScript.collectAsState()
    val name by viewModel.scriptName.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val phaseMessage by viewModel.phaseMessage.collectAsState()
    val warnings by viewModel.warnings.collectAsState()
    val error by viewModel.error.collectAsState()
    val success by viewModel.saveSuccess.collectAsState()

    LaunchedEffect(success) {
        if (success != null) {
            delay(4_000)
            viewModel.clearSuccess()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = VesperSpace.gutter)
            .padding(top = VesperSpace.xs, bottom = VesperSpace.xxl),
        verticalArrangement = Arrangement.spacedBy(VesperSpace.sm)
    ) {
        ExplainerCard(
            title = "A keyboard that types for you",
            body = "The Flipper presents itself as a USB keyboard and types the script into " +
                "whatever computer it is plugged into.",
            points = listOf(
                "Works on the machine it is plugged into, at typing speed",
                "Scripts are plain text stored under /ext/badusb",
                "Only run this on hardware you own or may test"
            )
        )

        VCard(level = 1) {
            PayloadGroupLabel("TARGET AND INTENT")
            Spacer(Modifier.height(VesperSpace.xxs))
            PayloadPromptField(
                value = prompt,
                onValueChange = viewModel::updateBadUsbPrompt,
                label = "What should the script do",
                placeholder = "Open Notepad and type a short note, then close it",
                enabled = !isGenerating
            )

            Spacer(Modifier.height(VesperSpace.xs))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(VesperSpace.xxs)) {
                items(BadUsbPlatform.entries, key = { it.name }) { option ->
                    VChip(
                        text = platformLabel(option),
                        selected = platform == option,
                        onClick = { viewModel.updateBadUsbPlatform(option) }
                    )
                }
            }

            Spacer(Modifier.height(VesperSpace.sm))

            VPrimaryButton(
                text = if (isGenerating) "Generating" else "Generate script",
                onClick = viewModel::generateBadUsbScript,
                modifier = Modifier.fillMaxWidth(),
                enabled = prompt.isNotBlank(),
                loading = isGenerating,
                icon = Icons.Rounded.AutoAwesome
            )

            if (isGenerating && phaseMessage.isNotBlank()) {
                VInlineLoading(text = phaseMessage)
            }
        }

        if (error != null) {
            VErrorBanner(
                message = error.orEmpty(),
                onDismiss = viewModel::clearError,
                visible = true
            )
        }

        if (warnings.isNotEmpty()) {
            PayloadNotice(
                text = warnings.first(),
                tone = PayloadNoticeTone.WARNING
            )
        }

        val generated = script
        if (generated != null) {
            VCard(level = 2) {
                GeneratedCodePanel(
                    label = "BADUSB SCRIPT",
                    code = generated,
                    onCopy = {
                        copyText(context, name.ifBlank { "badusb" }, generated)
                    }
                )

                Spacer(Modifier.height(VesperSpace.md))

                SaveFileBlock(
                    name = name,
                    onNameChange = viewModel::updateScriptName,
                    hint = "Saved as /ext/badusb/" + name.ifBlank { "payload" } + ".txt",
                    isSaving = isSaving,
                    canSave = !isGenerating,
                    onSave = viewModel::saveBadUsbScript
                )

                if (success != null) {
                    Spacer(Modifier.height(VesperSpace.xs))
                    PayloadNotice(text = success.orEmpty(), tone = PayloadNoticeTone.SUCCESS)
                }
            }
        }

        VCard(level = 1) {
            PayloadGroupLabel("START FROM A TEMPLATE")
            Spacer(Modifier.height(VesperSpace.xxs))
            Text(
                text = "Templates fill the editor with a known-good script you can edit before saving.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(VesperSpace.xs))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(VesperSpace.xxs),
                contentPadding = PaddingValues(vertical = VesperSpace.xxs)
            ) {
                items(viewModel.badUsbTemplates, key = { it.name }) { template ->
                    VQuietButton(
                        text = template.name,
                        onClick = { viewModel.loadBadUsbTemplate(template) }
                    )
                }
            }
        }
    }
}

private fun platformLabel(platform: BadUsbPlatform): String = when (platform) {
    BadUsbPlatform.WINDOWS -> "Windows"
    BadUsbPlatform.MACOS -> "macOS"
    BadUsbPlatform.LINUX -> "Linux"
    BadUsbPlatform.CROSS_PLATFORM -> "Cross-platform"
}
