package com.vesper.flipper.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vesper.flipper.domain.model.PendingApproval
import com.vesper.flipper.ui.components.approval.ApprovalSheetContent
import com.vesper.flipper.ui.theme.Vesper

/**
 * Modal approval sheet.
 *
 * Dismissing (scrim, swipe or system back) only hides the sheet: the pending
 * command is untouched and stays reviewable from the banner in chat. A decision
 * is the only thing that calls approve or reject.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalDialog(
    approval: PendingApproval,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit = {}
) {
    val colors = Vesper.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var decisionInFlight by remember(approval.id) { mutableStateOf(false) }

    ModalBottomSheet(
        // Dismissal is always allowed and never rejects; a decision in flight
        // resolves in the ViewModel regardless of sheet visibility.
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface3,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)
    ) {
        ApprovalSheetContent(
            approval = approval,
            decisionInFlight = decisionInFlight,
            onApprove = {
                if (!decisionInFlight) {
                    decisionInFlight = true
                    onApprove()
                }
            },
            onReject = {
                if (!decisionInFlight) {
                    decisionInFlight = true
                    onReject()
                }
            }
        )
    }
}
