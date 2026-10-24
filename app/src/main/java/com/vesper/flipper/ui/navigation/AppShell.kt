package com.vesper.flipper.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vesper.flipper.BuildConfig
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VErrorBanner
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VListRow
import com.vesper.flipper.ui.components.VSectionHeader
import com.vesper.flipper.ui.components.VesperScaffold
import com.vesper.flipper.ui.screen.AlchemyLabScreen
import com.vesper.flipper.ui.screen.AuditScreen
import com.vesper.flipper.ui.screen.ChatScreen
import com.vesper.flipper.ui.screen.DeviceScreen
import com.vesper.flipper.ui.screen.FapHubScreen
import com.vesper.flipper.ui.screen.FileBrowserScreen
import com.vesper.flipper.ui.screen.OpsCenterScreen
import com.vesper.flipper.ui.screen.PayloadLabScreen
import com.vesper.flipper.ui.screen.SettingsScreen
import com.vesper.flipper.ui.screen.SignalArsenalScreen
import com.vesper.flipper.ui.screen.SpectralOracleScreen
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperMotion
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.theme.vesperBackdrop

private const val NAV_ANIM_MS = 220
private const val NAV_EXIT_MS = 150

/**
 * Application shell: one Scaffold, one nav graph, one bottom bar.
 *
 * Screens are responsible for their own top bar (VesperScaffold) but must
 * not add window insets — the shell owns the bottom/navigation inset and
 * the IME inset.
 */
@OptIn(ExperimentalAnimationApi::class, ExperimentalLayoutApi::class)
@Composable
fun VesperApp(
    blePermissionGranted: Boolean,
    onRequestBlePermissions: () -> Unit,
    startRoute: String = VesperDestination.Chat.route,
    routeRequest: String? = null,
    onRouteRequestHandled: () -> Unit = {}
) {
    val navController = rememberNavController()

    LaunchedEffect(routeRequest) {
        val request = routeRequest ?: return@LaunchedEffect
        navController.navigate(request) { launchSingleTop = true }
        onRouteRequestHandled()
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoot = currentDestination?.hierarchy
        ?.firstOrNull { dest -> VesperDestination.all.any { it.route == dest.route } }
        ?.route
    val current = VesperDestination.byRoute(currentRoot)

    val navigateTo: (VesperDestination) -> Unit = { dest ->
        if (currentRoot != dest.route) {
            val primaryRoutes = VesperDestination.primary.map { it.route }
            navController.navigate(dest.route) {
                if (dest.route in primaryRoutes) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    val goBack: () -> Unit = {
        if (!navController.popBackStack()) {
            navController.navigate(VesperDestination.Chat.route) {
                launchSingleTop = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(vesperBackdrop())) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                val imeVisible = WindowInsets.isImeVisible
                AnimatedVisibility(
                    visible = !imeVisible,
                    enter = slideInVertically { it } + fadeIn(tween(NAV_ANIM_MS)),
                    exit = slideOutVertically { it } + fadeOut(tween(NAV_EXIT_MS))
                ) {
                    AppBottomBar(
                        current = current,
                        onSelect = navigateTo
                    )
                }
            }
        ) { padding ->
            val imeVisible = WindowInsets.isImeVisible
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (imeVisible) 0.dp else padding.calculateBottomPadding())
                    .imePadding()
            ) {
                if (!blePermissionGranted) {
                    VErrorBanner(
                        message = "Bluetooth access is off, so the Flipper cannot be found.",
                        onRetry = onRequestBlePermissions,
                        visible = true
                    )
                }
                NavHost(
                    navController = navController,
                    startDestination = startRoute,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = {
                        fadeIn(tween(NAV_ANIM_MS)) + slideInHorizontally(
                            tween(NAV_ANIM_MS),
                            initialOffsetX = { it / 24 }
                        )
                    },
                    exitTransition = { fadeOut(tween(NAV_EXIT_MS)) },
                    popEnterTransition = {
                        fadeIn(tween(NAV_ANIM_MS)) + slideInHorizontally(
                            tween(NAV_ANIM_MS),
                            initialOffsetX = { -it / 24 }
                        )
                    },
                    popExitTransition = { fadeOut(tween(NAV_EXIT_MS)) }
                ) {
                    composable(VesperDestination.Chat.route) {
                        ChatScreen(
                            onNavigateToAudit = { navigateTo(VesperDestination.Audit) },
                            onNavigateToSettings = { navigateTo(VesperDestination.Settings) }
                        )
                    }
                    composable(VesperDestination.Device.route) {
                        DeviceScreen(
                            onNavigateToFiles = { navigateTo(VesperDestination.Files) },
                            onNavigateToSettings = { navigateTo(VesperDestination.Settings) }
                        )
                    }
                    composable(VesperDestination.Arsenal.route) {
                        SignalArsenalScreen(
                            onNavigateBack = goBack,
                            onNavigateToDevice = { navigateTo(VesperDestination.Device) }
                        )
                    }
                    composable(VesperDestination.Forge.route) {
                        AlchemyLabScreen(onNavigateToPayloadLab = { navigateTo(VesperDestination.PayloadLab) })
                    }
                    composable(VesperDestination.More.route) {
                        MoreScreen(onSelect = navigateTo)
                    }
                    composable(VesperDestination.Library.route) {
                        FapHubScreen(onNavigateBack = goBack)
                    }
                    composable(VesperDestination.Files.route) {
                        FileBrowserScreen(onNavigateBack = goBack)
                    }
                    composable(VesperDestination.Audit.route) {
                        AuditScreen(onNavigateBack = goBack)
                    }
                    composable(VesperDestination.Oracle.route) {
                        SpectralOracleScreen(
                            onNavigateBack = goBack,
                            onNavigateToDevice = { navigateTo(VesperDestination.Device) },
                            onNavigateToSettings = { navigateTo(VesperDestination.Settings) }
                        )
                    }
                    composable(VesperDestination.PayloadLab.route) {
                        PayloadLabScreen(onNavigateBack = goBack)
                    }
                    composable(VesperDestination.Ops.route) {
                        OpsCenterScreen(onNavigateBack = goBack)
                    }
                    composable(VesperDestination.Settings.route) {
                        SettingsScreen(onNavigateBack = goBack)
                    }
                    composable(VesperDestination.Docs.route) {
                        FieldManualScreen(onNavigateBack = goBack)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppBottomBar(
    current: VesperDestination?,
    onSelect: (VesperDestination) -> Unit
) {
    val colors = Vesper.colors
    NavigationBar(
        containerColor = colors.surface1,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        VesperDestination.primary.forEach { destination ->
            val selected = current?.route == destination.route
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        imageVector = if (selected) destination.selectedIcon else destination.icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                },
                label = {
                    Text(
                        text = destination.label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.gold,
                    selectedTextColor = colors.gold,
                    indicatorColor = colors.surface3,
                    unselectedIconColor = colors.textTertiary,
                    unselectedTextColor = colors.textTertiary
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreScreen(onSelect: (VesperDestination) -> Unit) {
    val colors = Vesper.colors
    VesperScaffold(title = "More", subtitle = "Every tool in the box") { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = VesperSpace.gutter)
                .padding(bottom = VesperSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(VesperSpace.xs)
        ) {
            VSectionHeader(title = "Tools", eyebrow = "VESPER")
            VCard(level = 1, contentPadding = PaddingValues(vertical = VesperSpace.xxs)) {
                VesperDestination.tools.forEach { destination ->
                    VListRow(
                        title = destination.label,
                        subtitle = destination.description,
                        icon = destination.icon,
                        onClick = { onSelect(destination) },
                        trailing = {
                            Icon(
                                Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                tint = colors.textTertiary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }

            VSectionHeader(title = "Preferences")
            VCard(level = 1, contentPadding = PaddingValues(vertical = VesperSpace.xxs)) {
                VesperDestination.preferences.forEach { destination ->
                    VListRow(
                        title = destination.label,
                        subtitle = destination.description,
                        icon = destination.icon,
                        onClick = { onSelect(destination) }
                    )
                }
            }

            VSectionHeader(title = "About")
            VCard(level = 1) {
                Text(
                    text = "VESPER",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.textPrimary
                )
                Text(
                    text = "Version " + BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textTertiary
                )
                VHairline(modifier = Modifier.padding(vertical = VesperSpace.sm))
                Text(
                    text = "An AI command center for the Flipper Zero. Every action is risk-classified, " +
                        "diffed, and logged. Use it only on hardware you own or are authorized to test.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = VesperSpace.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.DevicesOther,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "  Built for Android 8.0 and newer",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldManualScreen(onNavigateBack: () -> Unit) {
    val colors = Vesper.colors
    val uriHandler = LocalUriHandler.current
    val sections = remember {
        listOf(
            "Connect" to "Power on the Flipper, enable Settings > Bluetooth, then open Device and scan. " +
                "Vesper remembers the last device and reconnects when you tap it.",
            "Talk" to "Chat understands plain language. It reads the device, proposes a command, and " +
                "classifies it before anything runs.",
            "Approve" to "LOW actions run immediately. MEDIUM shows a diff. HIGH requires a deliberate " +
                "hold. BLOCKED paths stay locked until you unlock them in Settings.",
            "Undo" to "Every write is diffed first and recorded in the Audit Log with the exact bytes, " +
                "so you can inspect or reverse what changed.",
            "Privacy" to "Your API key is stored encrypted on this device. Only the prompt you send, " +
                "and the device data you ask for, leave the phone."
        )
    }
    VesperScaffold(
        title = "Field Manual",
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = VesperSpace.gutter)
                .padding(bottom = VesperSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(VesperSpace.md)
        ) {
            Text(
                text = "Five things to know",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.textPrimary
            )
            sections.forEach { (title, body) ->
                VCard(level = 1) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.gold
                    )
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = VesperSpace.xxs)
                    )
                }
            }
            VCard(level = 1, onClick = {
                runCatching { uriHandler.openUri("https://github.com/elder-plinius/V3SP3R") }
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Source and issue tracker",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = colors.textSecondary)
                }
            }
            Text(
                text = "Vesper is for education and authorized security research.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
