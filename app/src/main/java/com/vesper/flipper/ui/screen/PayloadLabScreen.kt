package com.vesper.flipper.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VesperScaffold
import com.vesper.flipper.ui.screen.payload.BadUsbTab
import com.vesper.flipper.ui.screen.payload.EvilPortalTab
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.viewmodel.PayloadLabViewModel

/**
 * Payload Lab.
 *
 * Two generators that end the same way: a generated file, a validated name,
 * and a save with progress. Each tab leads with what the payload actually
 * does, because both are only meaningful in context.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayloadLabScreen(
    onNavigateBack: () -> Unit,
    viewModel: PayloadLabViewModel = hiltViewModel()
) {
    val colors = Vesper.colors
    var tabIndex by rememberSaveable { mutableStateOf(0) }

    VesperScaffold(
        title = "Payload Lab",
        subtitle = "BadUSB scripts and portal pages",
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = tabIndex,
                containerColor = colors.surface1,
                contentColor = colors.gold,
                divider = { VHairline() },
                indicator = { positions ->
                    TabRowDefaults.Indicator(
                        modifier = Modifier.tabIndicatorOffset(positions[tabIndex]),
                        color = colors.gold
                    )
                }
            ) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    text = { Text("BadUSB") },
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Usb,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    selectedContentColor = colors.gold,
                    unselectedContentColor = colors.textSecondary
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    text = { Text("Evil Portal") },
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Language,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    selectedContentColor = colors.gold,
                    unselectedContentColor = colors.textSecondary
                )
            }

            when (tabIndex) {
                0 -> BadUsbTab(viewModel)
                else -> EvilPortalTab(viewModel)
            }
        }
    }
}
