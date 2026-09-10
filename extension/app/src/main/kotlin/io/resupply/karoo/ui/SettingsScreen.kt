package io.resupply.karoo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.resupply.karoo.build.BuildState
import io.resupply.karoo.data.Category
import io.resupply.karoo.data.ResupplyConfig
import kotlin.math.roundToInt

/**
 * Settings, reached from the Waybook header gear. One scrollable screen in sections —
 * Categories (color chip grid, showing the last build's per-category counts), Detour
 * radius, then Data (the region picker entry). The top bar carries a trashcan that clears
 * the current places; Build itself lives on the overview, not here.
 */
@Composable
fun SettingsScreen(
    config: ResupplyConfig,
    buildState: BuildState,
    hasPins: Boolean,
    installedSummary: String,
    onDetourChange: (Int) -> Unit,
    onCategoryToggle: (Category, Boolean) -> Unit,
    onBuild: () -> Unit,
    onClear: () -> Unit,
    onOpenRegions: () -> Unit,
    onBack: () -> Unit,
) {
    val building = buildState is BuildState.Building

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar: back + title + rebuild + trashcan. Build feedback lives on the rebuild
        // icon itself (it turns into a spinner in place), so there's no separate status
        // banner. The trashcan stays put but greys out when there's nothing to clear or a
        // build is running.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onBuild, enabled = !building) {
                if (building) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Rebuild",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onClear, enabled = hasPins && !building) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Clear places",
                    // Full error red when usable; disabled tint reads as "nothing to clear".
                    tint = if (hasPins && !building) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }
        }
        // No progress banner — the rebuild icon carries the in-flight state. Only a build
        // error surfaces here, since the icon can't show a message.
        BuildErrorLine(
            buildState,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        HorizontalDivider()

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SectionHeader("Categories")
            CategoryChipGrid(
                enabled = config.enabledCategories,
                counts = (buildState as? BuildState.Success)?.byCategory ?: emptyMap(),
                canToggle = !building,
                onToggle = onCategoryToggle,
            )

            Spacer(Modifier.height(20.dp))
            SectionHeader("Detour radius")
            Text(
                formatDistance(config.detourMeters),
                style = MaterialTheme.typography.titleMedium,
            )
            val options = ResupplyConfig.DETOUR_OPTIONS_METERS
            val currentIndex = options.indexOfFirst { it >= config.detourMeters }
                .let { if (it < 0) options.lastIndex else it }
            Slider(
                value = currentIndex.toFloat(),
                onValueChange = { raw ->
                    onDetourChange(options[raw.roundToInt().coerceIn(0, options.lastIndex)])
                },
                valueRange = 0f..options.lastIndex.toFloat(),
                steps = options.size - 2,
                enabled = !building,
            )

            Spacer(Modifier.height(20.dp))
            SectionHeader("Data")
            RegionsRow(summary = installedSummary, enabled = !building, onClick = onOpenRegions)
        }
    }
}

/** Uppercase section label shared by the settings sections. */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * The Data-section entry into the region picker, styled as a settings list row (globe +
 * "Regions" + the current install summary + a drill-in chevron), not a button.
 */
@Composable
private fun RegionsRow(summary: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Public,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Regions", style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A failed build's message — the one thing the rebuild icon can't convey on its own. */
@Composable
private fun BuildErrorLine(state: BuildState, modifier: Modifier = Modifier) {
    if (state is BuildState.Error) {
        Text(
            state.message,
            color = ClosedRed,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.padding(bottom = 8.dp),
        )
    }
}
