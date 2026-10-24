package com.vesper.flipper.ui.onboarding

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.components.VQuietButton
import com.vesper.flipper.ui.components.VSecondaryButton
import com.vesper.flipper.ui.theme.S0
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/**
 * First-run onboarding. Three screens, skippable, and each one explains
 * why it is asking. No permission dialog is fired before the user
 * understands what it is for.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun OnboardingScreen(
    blePermissionGranted: Boolean,
    onRequestBlePermissions: () -> Unit,
    onFinish: (openSettings: Boolean) -> Unit
) {
    val colors = Vesper.colors
    var step by rememberSaveable { mutableStateOf(0) }
    val pages = listOf(
        OnboardingPage(
            icon = Icons.Rounded.LockOpen,
            eyebrow = "WELCOME",
            title = "Your Flipper, with a brain.",
            body = "Describe what you want in plain language. Vesper reads the device, " +
                "proposes the command, and tells you exactly what it will change before it runs."
        ),
        OnboardingPage(
            icon = Icons.Rounded.Bluetooth,
            eyebrow = "ACCESS",
            title = "Find your device.",
            body = "Vesper needs Bluetooth access to scan for and connect to the Flipper. " +
                "No location, contacts, or background data is used for this."
        ),
        OnboardingPage(
            icon = Icons.Rounded.Key,
            eyebrow = "BRAIN",
            title = "Bring your own model.",
            body = "Vesper talks to OpenRouter. Add a key in Settings and pick a model. " +
                "The key is stored encrypted on this phone."
        )
    )
    val page = pages[step]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(S0, colors.canvas, colors.surface1)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = VesperSpace.lg)
                .padding(top = VesperSpace.xxl, bottom = VesperSpace.lg)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(VesperSpace.xxs)
            ) {
                pages.indices.forEach { index ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    index <= step -> colors.gold
                                    else -> colors.surface3
                                }
                            )
                    )
                }
            }

            Spacer(Modifier.height(VesperSpace.xxl))
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 12 })
                        .togetherWith(fadeOut(tween(120)) + slideOutHorizontally(tween(120)) { -it / 12 })
                },
                label = "onboarding"
            ) { index ->
                val current = pages[index]
                Column(verticalArrangement = Arrangement.spacedBy(VesperSpace.md)) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(colors.surface2),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            current.icon,
                            contentDescription = null,
                            tint = colors.gold,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Text(
                        text = current.eyebrow,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.gold
                    )
                    Text(
                        text = current.title,
                        style = MaterialTheme.typography.displaySmall,
                        color = colors.textPrimary
                    )
                    Text(
                        text = current.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textSecondary
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            when (step) {
                0 -> {
                    VPrimaryButton(
                        text = "Get started",
                        onClick = { step = 1 },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(VesperSpace.xs))
                    VQuietButton(
                        text = "Skip",
                        onClick = { onFinish(false) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                1 -> {
                    if (blePermissionGranted) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = colors.success,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "  Bluetooth access granted",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.success
                            )
                        }
                        Spacer(Modifier.height(VesperSpace.sm))
                    }
                    VPrimaryButton(
                        text = if (blePermissionGranted) "Continue" else "Allow Bluetooth",
                        onClick = {
                            if (blePermissionGranted) step = 2 else onRequestBlePermissions()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(VesperSpace.xs))
                    VQuietButton(
                        text = if (blePermissionGranted) "Skip" else "Not now",
                        onClick = { step = 2 },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                else -> {
                    VPrimaryButton(
                        text = "Add API key",
                        onClick = { onFinish(true) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(VesperSpace.xs))
                    VSecondaryButton(
                        text = "Explore first",
                        onClick = { onFinish(false) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(VesperSpace.md))
            Text(
                text = "Use Vesper only on hardware you own or are authorized to test.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private data class OnboardingPage(
    val icon: ImageVector,
    val eyebrow: String,
    val title: String,
    val body: String
)

/** One-time onboarding flag, deliberately independent of app settings. */
object OnboardingState {
    private const val PREFS = "vesper_onboarding"
    private const val KEY = "completed_v2"

    fun isCompleted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun markCompleted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, true)
            .apply()
    }
}
