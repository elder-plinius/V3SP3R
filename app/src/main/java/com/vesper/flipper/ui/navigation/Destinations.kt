package com.vesper.flipper.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Science
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Every screen the app has, modelled once.
 *
 * [primary] destinations get a bottom-bar slot. Everything else is
 * reachable from the More screen — nothing is orphaned.
 */
sealed class VesperDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val description: String = ""
) {
    data object Chat : VesperDestination(
        route = "chat",
        label = "Chat",
        icon = Icons.Outlined.Chat,
        selectedIcon = Icons.Filled.Chat,
        description = "Talk to your Flipper"
    )

    data object Device : VesperDestination(
        route = "device",
        label = "Device",
        icon = Icons.Outlined.Bluetooth,
        selectedIcon = Icons.Filled.Bluetooth,
        description = "Connection, battery, remote control"
    )

    data object Arsenal : VesperDestination(
        route = "arsenal",
        label = "Signals",
        icon = Icons.Outlined.Sensors,
        selectedIcon = Icons.Filled.Sensors,
        description = "Captured SubGHz and IR signals"
    )

    data object Forge : VesperDestination(
        route = "forge",
        label = "Forge",
        icon = Icons.Rounded.AutoAwesome,
        selectedIcon = Icons.Rounded.AutoAwesome,
        description = "Synthesize and deploy payloads"
    )

    data object More : VesperDestination(
        route = "more",
        label = "More",
        icon = Icons.Outlined.MoreHoriz,
        selectedIcon = Icons.Filled.MoreHoriz,
        description = "Every tool and setting"
    )

    // ── Secondary destinations (reachable from More) ──────
    data object Library : VesperDestination(
        route = "library",
        label = "App Library",
        icon = Icons.Filled.Widgets,
        selectedIcon = Icons.Filled.Widgets,
        description = "Browse and install Flipper apps"
    )

    data object Files : VesperDestination(
        route = "files",
        label = "Files",
        icon = Icons.Outlined.Folder,
        selectedIcon = Icons.Filled.Folder,
        description = "Browse the SD card"
    )

    data object Audit : VesperDestination(
        route = "audit",
        label = "Audit Log",
        icon = Icons.Outlined.History,
        selectedIcon = Icons.Filled.History,
        description = "Every action, every approval"
    )

    data object Oracle : VesperDestination(
        route = "oracle",
        label = "Spectral Oracle",
        icon = Icons.Rounded.Explore,
        selectedIcon = Icons.Rounded.Explore,
        description = "Analyze captures with AI"
    )

    data object PayloadLab : VesperDestination(
        route = "payload_lab",
        label = "Payload Lab",
        icon = Icons.Rounded.Science,
        selectedIcon = Icons.Rounded.Science,
        description = "BadUSB and Evil Portal builders"
    )

    data object Ops : VesperDestination(
        route = "ops",
        label = "Ops Center",
        icon = Icons.Rounded.MonitorHeart,
        selectedIcon = Icons.Rounded.MonitorHeart,
        description = "Pipeline health and runbooks"
    )

    data object Settings : VesperDestination(
        route = "settings",
        label = "Settings",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Filled.Settings,
        description = "Keys, model, theme, permissions"
    )

    data object Docs : VesperDestination(
        route = "docs",
        label = "Field Manual",
        icon = Icons.Rounded.Description,
        selectedIcon = Icons.Rounded.Description,
        description = "How Vesper works"
    )

    companion object {
        // IMPORTANT: these lists are lazy on purpose.
        //
        // Building them in the class initializer creates a class-init cycle:
        // touching VesperDestination.Chat.route initializes Chat first, which
        // initializes this (its superclass) while Chat.INSTANCE is still null.
        // The initializer then stores a null Chat in the list and the router
        // crashes on the first lookup. Lazy lists are created after the class
        // hierarchy is initialized, so every INSTANCE is non-null.
        val primary: List<VesperDestination> by lazy {
            listOf(Chat, Device, Arsenal, Forge, More)
        }

        val tools: List<VesperDestination> by lazy {
            listOf(Library, Files, Audit, Oracle, PayloadLab, Ops)
        }

        val preferences: List<VesperDestination> by lazy {
            listOf(Settings, Docs)
        }

        val all: List<VesperDestination> by lazy {
            primary + tools + preferences
        }

        fun byRoute(route: String?): VesperDestination? =
            all.firstOrNull { it.route == route }
    }
}
