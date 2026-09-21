package io.resupply.karoo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The app's one confirmation dialog: a colored icon disc leading a centered title and a
 * supporting line, over a matched pair of actions — an accent-filled confirm and a quiet
 * text Cancel. One composable so every "are you sure?" across the app reads as the same
 * control, on-brand with the POI hero (colored disc + centered name) rather than a stock
 * Material box.
 *
 * [accent] tints the icon disc and the confirm button, so the dialog inherits the color of
 * whatever it's about — the category color for a place, [ClosedRed] for a destructive delete.
 * Destructive calls should pass a red [accent]; the disc + button then read as a warning
 * without any extra flag.
 */
@Composable
fun ConfirmDialog(
    icon: ImageVector,
    accent: Color,
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        // A filled accent disc, echoing the POI detail hero, so the dialog announces its
        // subject with color and glyph before a word is read.
        icon = {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
        },
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        },
        // Material's AlertDialog end-aligns the confirm/dismiss row; render both actions
        // ourselves in a centered row (in the confirmButton slot, dismissButton left null)
        // so they sit under the centered title/text rather than shoved to the trailing edge.
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(dismissLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = Color.White,
                    ),
                ) { Text(confirmLabel) }
            }
        },
    )
}
