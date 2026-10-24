package com.vesper.flipper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.ui.theme.Vesper

/**
 * One screen shell for the whole app.
 *
 * Insets are applied exactly once: the top bar consumes the status bar
 * inset, and the host (AppShell) supplies the bottom/navigation inset.
 * Screens must NOT add their own statusBarsPadding or nested Scaffold.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VesperScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    topBarContent: (@Composable () -> Unit)? = null,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit
) {
    val colors = Vesper.colors
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentColor = colors.textPrimary,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = snackbarHost,
        topBar = {
            if (topBarContent != null) {
                topBarContent()
            } else {
                VesperTopBar(
                    title = title,
                    subtitle = subtitle,
                    navigationIcon = navigationIcon,
                    actions = actions
                )
            }
        },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VesperTopBar(
    title: String,
    subtitle: String? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val colors = Vesper.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.canvas.copy(alpha = 0.94f))
    ) {
        TopAppBar(
            title = {
                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            navigationIcon = { navigationIcon?.invoke() },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                titleContentColor = colors.textPrimary,
                navigationIconContentColor = colors.textSecondary,
                actionIconContentColor = colors.textSecondary
            )
        )
        VHairline()
    }
}

/** 1dp divider that reads as a hairline on both themes. */
@Composable
fun VHairline(modifier: Modifier = Modifier) {
    val color = Vesper.colors.hairline
    androidx.compose.foundation.layout.Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .drawBehind {
                drawLine(
                    color = color,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1f
                )
            }
    )
}
