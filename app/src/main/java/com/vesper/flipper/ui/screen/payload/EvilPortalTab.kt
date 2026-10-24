package com.vesper.flipper.ui.screen.payload

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vesper.flipper.ai.PortalType
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VChip
import com.vesper.flipper.ui.components.VErrorBanner
import com.vesper.flipper.ui.components.VInlineLoading
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.components.VQuietButton
import com.vesper.flipper.ui.components.VSecondaryButton
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.PayloadLabViewModel
import kotlinx.coroutines.delay

/**
 * Evil Portal tab: a captive portal page generated from a description or a
 * screenshot of a real login screen. The page is only markup; the Flipper's
 * portal app serves it.
 */
@Composable
fun EvilPortalTab(viewModel: PayloadLabViewModel) {
    val colors = Vesper.colors
    val context = LocalContext.current

    val prompt by viewModel.portalPrompt.collectAsState()
    val portalType by viewModel.portalType.collectAsState()
    val screenshot by viewModel.portalScreenshot.collectAsState()
    val html by viewModel.generatedHtml.collectAsState()
    val name by viewModel.portalName.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val phaseMessage by viewModel.phaseMessage.collectAsState()
    val warnings by viewModel.warnings.collectAsState()
    val error by viewModel.error.collectAsState()
    val success by viewModel.saveSuccess.collectAsState()

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> viewModel.setPortalScreenshot(uri) }

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
            title = "A login page on your own network",
            body = "The Flipper's portal app broadcasts a Wi-Fi network and serves this page to " +
                "anyone who joins it. Whatever a visitor types is captured by the portal.",
            points = listOf(
                "The page is plain HTML stored under /ext/apps_data/evil_portal",
                "It runs from the Flipper, so the network only exists while the app runs",
                "Only run this where you have written permission to do so"
            )
        )

        VCard(level = 1) {
            PayloadGroupLabel("PAGE AND LOOK")
            Spacer(Modifier.height(VesperSpace.xxs))
            PayloadPromptField(
                value = prompt,
                onValueChange = viewModel::updatePortalPrompt,
                label = "Describe the page",
                placeholder = "A hotel Wi-Fi login with room number and last name",
                enabled = !isGenerating
            )

            Spacer(Modifier.height(VesperSpace.xs))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(VesperSpace.xxs)) {
                items(PortalType.entries, key = { it.name }) { type ->
                    VChip(
                        text = portalLabel(type),
                        selected = portalType == type,
                        onClick = { viewModel.updatePortalType(type) }
                    )
                }
            }

            Spacer(Modifier.height(VesperSpace.sm))

            val shot = screenshot
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (shot != null) {
                    AsyncImage(
                        model = shot,
                        contentDescription = "Reference screenshot",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(Modifier.width(VesperSpace.xs))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (screenshot == null) "No reference screenshot" else "Screenshot attached",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Optional. A screenshot helps match the real brand and layout.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(VesperSpace.xs))
                VSecondaryButton(
                    text = if (screenshot == null) "Choose" else "Replace",
                    onClick = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = !isGenerating
                )
            }

            if (screenshot != null) {
                VQuietButton(
                    text = "Remove the screenshot",
                    onClick = { viewModel.setPortalScreenshot(null) }
                )
            }

            Spacer(Modifier.height(VesperSpace.sm))

            VPrimaryButton(
                text = if (isGenerating) "Generating" else "Generate page",
                onClick = viewModel::generateEvilPortal,
                modifier = Modifier.fillMaxWidth(),
                enabled = prompt.isNotBlank() || screenshot != null,
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
            PayloadNotice(text = warnings.first(), tone = PayloadNoticeTone.WARNING)
        }

        val generated = html
        if (generated != null) {
            VCard(level = 2) {
                GeneratedCodePanel(
                    label = "PORTAL PAGE",
                    code = generated,
                    onCopy = { copyText(context, name.ifBlank { "portal" }, generated) }
                )

                Spacer(Modifier.height(VesperSpace.md))

                SaveFileBlock(
                    name = name,
                    onNameChange = viewModel::updatePortalName,
                    hint = "/ext/apps_data/evil_portal/portals/" + name.ifBlank { "portal" } + "/index.html",
                    isSaving = isSaving,
                    canSave = !isGenerating,
                    onSave = viewModel::saveEvilPortal
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
                text = "Templates load a complete page you can inspect and edit before saving.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
            Spacer(Modifier.height(VesperSpace.xs))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(VesperSpace.xxs),
                contentPadding = PaddingValues(vertical = VesperSpace.xxs)
            ) {
                items(viewModel.evilPortalTemplates, key = { it.name }) { template ->
                    VQuietButton(
                        text = template.name,
                        onClick = { viewModel.loadEvilPortalTemplate(template) }
                    )
                }
            }
        }
    }
}

private fun portalLabel(type: PortalType): String = when (type) {
    PortalType.GENERIC -> "Generic"
    PortalType.CORPORATE_SSO -> "Corporate SSO"
    PortalType.GOOGLE_WORKSPACE -> "Google Workspace"
    PortalType.MICROSOFT_365 -> "Microsoft 365"
    PortalType.SOCIAL_MEDIA -> "Social media"
    PortalType.HOTEL_WIFI -> "Hotel Wi-Fi"
    PortalType.COFFEE_SHOP -> "Coffee shop"
    PortalType.AIRPORT -> "Airport"
    PortalType.BANKING -> "Banking"
}
