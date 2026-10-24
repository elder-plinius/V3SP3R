package com.vesper.flipper.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.ble.FlipperFileSystem
import com.vesper.flipper.domain.model.SignalType
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VEmptyState
import com.vesper.flipper.ui.components.VErrorBanner
import com.vesper.flipper.ui.components.VInlineLoading
import com.vesper.flipper.ui.components.VListRow
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.components.VSecondaryButton
import com.vesper.flipper.ui.components.VSectionHeader
import com.vesper.flipper.ui.components.VesperScaffold
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperTheme
import com.vesper.flipper.ui.theme.vesperBackdrop
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Configuration screen for [SignalWidget].
 *
 * The result starts as RESULT_CANCELED. It becomes RESULT_OK only when the
 * user saves at least one slot, so backing out never leaves a half-configured
 * widget behind.
 */
@AndroidEntryPoint
class SignalWidgetConfigActivity : ComponentActivity() {

    @Inject
    lateinit var fileSystem: FlipperFileSystem

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            VesperTheme(darkTheme = true) {
                var slots by remember {
                    mutableStateOf(SignalWidget.loadWidgetSignals(this@SignalWidgetConfigActivity, appWidgetId))
                }
                var selectedSlot by remember { mutableStateOf<Int?>(null) }
                var catalog by remember { mutableStateOf<SignalCatalog>(SignalCatalog.Loading) }
                var reloadKey by remember { mutableStateOf(0) }

                LaunchedEffect(reloadKey) {
                    catalog = SignalCatalog.Loading
                    catalog = try {
                        SignalCatalog.Ready(loadSignalCatalog())
                    } catch (e: Exception) {
                        SignalCatalog.Failed(e.message ?: "Could not load signals")
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(vesperBackdrop())
                ) {
                    WidgetConfigScreen(
                        slots = slots,
                        selectedSlot = selectedSlot,
                        catalog = catalog,
                        onSelectSlot = { index ->
                            selectedSlot = if (selectedSlot == index) null else index
                        },
                        onAssign = { index, signal ->
                            slots = slots.toMutableList().also { it[index] = signal }
                            selectedSlot = null
                        },
                        onClearSlot = { index ->
                            slots = slots.toMutableList().also { it[index] = null }
                        },
                        onRetry = { reloadKey += 1 },
                        onSave = { saveAndFinish(slots) },
                        onCancel = { cancelAndFinish() }
                    )
                }
            }
        }
    }

    private suspend fun loadSignalCatalog(): List<SignalGroup> {
        return withContext(Dispatchers.IO) {
            val groups = mutableListOf<SignalGroup>()
            var loadedTypes = 0
            var failedTypes = 0

            SignalType.entries.forEach { type ->
                val result = fileSystem.listDirectory(type.directory)
                if (result.isFailure) {
                    failedTypes += 1
                    return@forEach
                }

                loadedTypes += 1
                val signals = result.getOrThrow()
                    .asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(type.extension, ignoreCase = true) }
                    .map { entry ->
                        WidgetSignal(
                            id = entry.path,
                            name = entry.name.removeSuffix(type.extension).ifBlank { entry.name },
                            type = type.name.lowercase(Locale.US)
                        )
                    }
                    .sortedBy { it.name.lowercase(Locale.US) }
                    .toList()

                if (signals.isNotEmpty()) {
                    groups.add(
                        SignalGroup(
                            name = type.displayName,
                            type = type.name.lowercase(Locale.US),
                            signals = signals
                        )
                    )
                }
            }

            if (loadedTypes == 0 && failedTypes > 0) {
                throw IllegalStateException("Connect to your Flipper, then try again")
            }
            groups
        }
    }

    private fun saveAndFinish(slots: List<WidgetSignal?>) {
        if (slots.none { it != null }) return

        SignalWidget.saveWidgetSignals(this, appWidgetId, slots)
        val appWidgetManager = AppWidgetManager.getInstance(this)
        SignalWidget.updateAppWidget(this, appWidgetManager, appWidgetId)

        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultValue)
        finish()
    }

    private fun cancelAndFinish() {
        setResult(RESULT_CANCELED)
        finish()
    }
}

@Composable
private fun WidgetConfigScreen(
    slots: List<WidgetSignal?>,
    selectedSlot: Int?,
    catalog: SignalCatalog,
    onSelectSlot: (Int) -> Unit,
    onAssign: (Int, WidgetSignal) -> Unit,
    onClearSlot: (Int) -> Unit,
    onRetry: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val colors = Vesper.colors
    val space = Vesper.space

    VesperScaffold(
        title = "Configure widget",
        subtitle = "Choose up to four signals",
        navigationIcon = {
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Cancel",
                    tint = colors.textSecondary
                )
            }
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = space.gutter, vertical = space.md),
                horizontalArrangement = Arrangement.spacedBy(space.sm)
            ) {
                VSecondaryButton(
                    text = "Cancel",
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                )
                VPrimaryButton(
                    text = "Save",
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    enabled = slots.any { it != null }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = space.gutter,
                top = space.md,
                end = space.gutter,
                bottom = space.lg
            ),
            verticalArrangement = Arrangement.spacedBy(space.sm)
        ) {
            item(key = "preview") {
                WidgetPreview(
                    slots = slots,
                    selectedSlot = selectedSlot,
                    onSelectSlot = onSelectSlot,
                    onClearSlot = onClearSlot
                )
            }

            item(key = "hint") {
                Text(
                    text = if (selectedSlot == null) {
                        "Tap a slot in the preview, then choose a signal."
                    } else {
                        "Choosing a signal for slot " + (selectedSlot + 1) + "."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
            }

            when (catalog) {
                is SignalCatalog.Loading -> {
                    item(key = "loading") {
                        VInlineLoading("Loading signals")
                    }
                }

                is SignalCatalog.Failed -> {
                    item(key = "error") {
                        VErrorBanner(
                            message = catalog.message,
                            onRetry = onRetry
                        )
                    }
                }

                is SignalCatalog.Ready -> {
                    if (catalog.groups.isEmpty()) {
                        item(key = "empty") {
                            VEmptyState(
                                title = "No signals found",
                                message = "Connect to your Flipper and capture a signal, then try again.",
                                actionLabel = "Retry",
                                onAction = onRetry
                            )
                        }
                    } else {
                        catalog.groups.forEach { group ->
                            item(key = "group-" + group.type) {
                                VSectionHeader(
                                    title = group.name,
                                    eyebrow = group.signals.size.toString() + " signals"
                                )
                            }
                            items(
                                items = group.signals,
                                key = { signal -> signal.id }
                            ) { signal ->
                                val slot = selectedSlot
                                val isAssigned = slot?.let { slots.getOrNull(it)?.id } == signal.id
                                VListRow(
                                    title = signal.name,
                                    subtitle = if (slot == null) "Tap a slot first" else group.name,
                                    trailing = if (isAssigned) {
                                        {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = "Assigned",
                                                tint = colors.success
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    enabled = slot != null,
                                    onClick = if (slot == null) null else {
                                        {
                                            onAssign(slot, signal)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetPreview(
    slots: List<WidgetSignal?>,
    selectedSlot: Int?,
    onSelectSlot: (Int) -> Unit,
    onClearSlot: (Int) -> Unit
) {
    val colors = Vesper.colors
    val space = Vesper.space

    Column(modifier = Modifier.fillMaxWidth()) {
        VSectionHeader(title = "Preview", eyebrow = "Home screen")

        VCard(
            level = 2,
            contentPadding = PaddingValues(space.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Vesper signals",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Arsenal",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary
                )
            }

            Spacer(modifier = Modifier.height(space.xs))

            for (row in 0 until 2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(space.xs)
                ) {
                    for (column in 0 until 2) {
                        val index = row * 2 + column
                        WidgetSlot(
                            signal = slots.getOrNull(index),
                            slotNumber = index + 1,
                            selected = selectedSlot == index,
                            onClick = { onSelectSlot(index) },
                            onClear = { onClearSlot(index) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (row == 0) {
                    Spacer(modifier = Modifier.height(space.xs))
                }
            }
        }
    }
}

@Composable
private fun WidgetSlot(
    signal: WidgetSignal?,
    slotNumber: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val space = Vesper.space

    VCard(
        level = if (selected) 3 else 1,
        onClick = onClick,
        modifier = modifier.heightIn(min = 72.dp),
        contentPadding = PaddingValues(horizontal = space.sm, vertical = space.xs)
    ) {
        Text(
            text = signal?.name ?: ("Slot " + slotNumber),
            style = MaterialTheme.typography.titleSmall,
            color = if (signal == null) colors.textTertiary else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (signal == null) "Empty" else "Assigned",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (selected) "Editing" else "Tap to edit",
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) colors.gold else colors.textTertiary,
                modifier = Modifier.weight(1f)
            )
            if (signal != null) {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Clear slot " + slotNumber,
                        tint = colors.textTertiary
                    )
                }
            }
        }
    }
}

private sealed interface SignalCatalog {
    data object Loading : SignalCatalog
    data class Ready(val groups: List<SignalGroup>) : SignalCatalog
    data class Failed(val message: String) : SignalCatalog
}

data class SignalGroup(
    val name: String,
    val type: String,
    val signals: List<WidgetSignal>
)
