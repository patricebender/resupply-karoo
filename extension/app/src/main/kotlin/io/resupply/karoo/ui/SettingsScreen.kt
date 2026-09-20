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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.resupply.karoo.build.BuildState
import io.resupply.karoo.data.Category
import io.resupply.karoo.data.Poi
import io.resupply.karoo.data.ResupplyConfig
import kotlin.math.roundToInt

/**
 * Settings, reached from the Waybook header gear. One scrollable screen in sections:
 * Categories (color chip grid, showing the per-category counts of what's currently *shown*),
 * the smart search radius toggle (with a manual detour radius when it's off), then Data (the
 * region picker entry). The top bar carries a trashcan that clears the current places; Build
 * itself lives on the overview, not here.
 */
@Composable
fun SettingsScreen(
    config: ResupplyConfig,
    buildState: BuildState,
    // The whole built set (every category). The chip badges count the *visible* subset of
    // this through [ResupplyConfig.showsPoi], so a category toggle or the safe-water switch
    // updates the counts live, in step with the map/overview and with no rebuild.
    pois: List<Poi>,
    installedSummary: String,
    onDetourChange: (Int) -> Unit,
    onCategoryToggle: (Category, Boolean) -> Unit,
    onSafeWaterToggle: (Boolean) -> Unit,
    onSmartDistanceToggle: (Boolean) -> Unit,
    onBuild: () -> Unit,
    onClear: () -> Unit,
    onOpenRegions: () -> Unit,
    onBack: () -> Unit,
) {
    val building = buildState is BuildState.Building
    val hasPins = pois.isNotEmpty()
    // Per-category badge counts of the *visible* POIs (same gate as the map/overview), so the
    // water badge reflects the safe subset and every badge tracks its toggle without a rebuild.
    // Once a build exists, seed every enabled category to 0 so one that resolves to nothing under
    // the current filter (e.g. water hidden by safe-water, or a category simply absent along the
    // route) shows an explicit "0" rather than a blank badge — the count would otherwise be absent
    // (→ no badge), reading as "not built" when it's really "built, none here". Before any build
    // there's nothing to count, so no seeding (no badges at all).
    val visibleCounts = remember(pois, config) {
        val counted = pois.asSequence()
            .filter { config.showsPoi(it) }
            .mapNotNull { Category.ofType(it.type) }
            .groupingBy { it }
            .eachCount()
        if (pois.isNotEmpty()) {
            config.enabledCategories.associateWith { counted[it] ?: 0 }
        } else {
            counted
        }
    }

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
                counts = visibleCounts,
                canToggle = !building,
                onToggle = onCategoryToggle,
            )
            SafeWaterRow(
                checked = config.safeWaterOnly,
                // Inactive while water is off (nothing to narrow) or a build is running.
                enabled = Category.WATER in config.enabledCategories && !building,
                onToggle = onSafeWaterToggle,
            )

            Spacer(Modifier.height(20.dp))
            SmartDistanceRow(
                checked = config.smartDistance,
                enabled = !building,
                onToggle = onSmartDistanceToggle,
            )
            // Manual radius only when smart is off, otherwise the distance is derived per area.
            if (!config.smartDistance) {
                Spacer(Modifier.height(12.dp))
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
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader("Data")
            RegionsRow(summary = installedSummary, enabled = !building, onClick = onOpenRegions)
        }
    }
}

/**
 * The "safe water sources" toggle, sitting just under the category grid since it narrows the
 * water category. The label + switch fit one row; the explanation of what counts as safe sits
 * permanently on the line below, led by an info icon. Greys out when water is disabled —
 * there's nothing to narrow, so the control has no effect.
 */
@Composable
private fun SafeWaterRow(checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val labelColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Safe water sources",
                style = MaterialTheme.typography.bodyLarge,
                color = labelColor,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled)
        }
        Row(modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).padding(top = 1.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                "Taps, water tagged as drinkable, and graveyards (which usually have a tap). " +
                    "Sources of unconfirmed quality are hidden.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The "Smart search radius" toggle. Same layout as [SafeWaterRow]: label + switch on one line,
 * an info-led explanation below. When on, the caller hides the manual radius slider and the
 * distance is derived per area from local POI density (see selectAlongRoute's band model).
 */
@Composable
private fun SmartDistanceRow(checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val labelColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Smart search radius",
                style = MaterialTheme.typography.bodyLarge,
                color = labelColor,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled)
        }
        Row(modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).padding(top = 1.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                "Searches close to your route in busy areas and extends further where places are " +
                    "few. Turn off to set a fixed radius yourself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
