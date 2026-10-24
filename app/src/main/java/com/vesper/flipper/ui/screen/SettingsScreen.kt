package com.vesper.flipper.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.vesper.flipper.ui.components.VesperScaffold
import com.vesper.flipper.ui.screen.settings.aboutSection
import com.vesper.flipper.ui.screen.settings.deviceSection
import com.vesper.flipper.ui.screen.settings.experienceSection
import com.vesper.flipper.ui.screen.settings.glassesCard
import com.vesper.flipper.ui.screen.settings.intelligenceSection
import com.vesper.flipper.ui.screen.settings.privacySection
import com.vesper.flipper.ui.screen.settings.safetyCards
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.SettingsViewModel

/**
 * Settings.
 *
 * Five sections in the order a new user needs them: what powers the agent,
 * what it may touch, how it feels, what it keeps, and what this app is.
 * The screen owns the scaffold and the transient message; each section owns
 * its rows and its own dialog.
 */
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val message by viewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.refreshLockedPaths()
    }

    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(current)
        viewModel.clearMessage()
    }

    VesperScaffold(
        title = "Settings",
        subtitle = "Model access, device scope, and data",
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = VesperSpace.gutter,
                end = VesperSpace.gutter,
                top = 0.dp,
                bottom = VesperSpace.xxl
            ),
            verticalArrangement = Arrangement.spacedBy(VesperSpace.sm)
        ) {
            intelligenceSection(viewModel)
            deviceSection(viewModel)
            safetyCards(viewModel)
            glassesCard(viewModel)
            experienceSection(viewModel)
            privacySection(viewModel)
            aboutSection()
        }
    }
}
