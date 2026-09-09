package io.resupply.karoo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.resupply.karoo.data.Category

/** Fixed column count for the icon-only chip grid — a clean 3×3 for the nine categories. */
private const val COLUMNS = 3

/**
 * The category picker as an icon-only grid of tappable tiles, one per [Category], each
 * wearing that category's own color + icon (see [styleForCategory]) so they read as the
 * same amenity as the route dots and list rows — no labels, the icon carries it. An
 * enabled tile is filled with its color; a disabled one is a muted outline struck through
 * corner-to-corner, so "off" reads at a glance. After a build, enabled tiles show a small
 * corner badge with how many places of that category were found ([counts]).
 *
 * A fixed [COLUMNS]-wide grid of Rows (not a LazyVerticalGrid) so it composes inside the
 * settings' verticalScroll; a short trailing row leaves its empty cells as spacers.
 */
@Composable
fun CategoryChipGrid(
    enabled: Set<Category>,
    counts: Map<Category, Int>,
    canToggle: Boolean,
    onToggle: (Category, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = Category.entries.chunked(COLUMNS)
    Column(modifier = modifier.fillMaxWidth()) {
        rows.forEach { cells ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cells.forEach { category ->
                    CategoryChip(
                        category = category,
                        on = category in enabled,
                        count = counts[category],
                        canToggle = canToggle,
                        onToggle = onToggle,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad a short final row so its tiles keep the same width as full rows.
                repeat(COLUMNS - cells.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CategoryChip(
    category: Category,
    on: Boolean,
    count: Int?,
    canToggle: Boolean,
    onToggle: (Category, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = styleForCategory(category)
    val shape = RoundedCornerShape(10.dp)
    var tile = modifier
        .aspectRatio(1.4f)
        .clip(shape)
        .background(if (on) style.color else Color.Transparent)
        .clickable(enabled = canToggle) { onToggle(category, !on) }
    if (!on) {
        // Outlined + a diagonal strike (top-left → bottom-right) in the category color so
        // an off tile reads as "excluded" at a glance.
        tile = tile
            .border(1.dp, style.color, shape)
            .drawWithContent {
                drawContent()
                drawLine(
                    color = style.color,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
    }
    Box(modifier = tile, contentAlignment = Alignment.Center) {
        Icon(
            style.icon,
            // No label, so the icon carries the meaning — name it for accessibility.
            contentDescription = style.label,
            tint = if (on) Color.White else style.color,
            modifier = Modifier.size(26.dp),
        )
        // Post-build count of found places for an enabled category — a small corner badge.
        if (on && count != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.3f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}
