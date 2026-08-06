package com.bugzapperlabs.myfeeds.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.bugzapperlabs.myfeeds.R

/**
 * Confirmation gate for an otherwise-instant, unrecoverable delete (issue #288), matching the
 * existing unsubscribe-confirm pattern (`FeedPropertiesScreen`) -- shared here since the Item
 * List, Feed River, and Downloads screens all need the identical "are you sure" step, whether
 * deleting one item (Downloads, per row) or a multi-select bulk of them (Item List/Feed River).
 */
@Composable
fun ConfirmDeleteDialog(
    itemCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.delete_confirm_title, itemCount, itemCount)) },
        text = { Text(stringResource(R.string.delete_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
