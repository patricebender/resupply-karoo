package io.resupply.karoo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.resupply.karoo.data.Region.Companion.SEED_REGION_ID
import io.resupply.karoo.data.Region
import io.resupply.karoo.data.RegionManifestEntry

/** Download UI state, owned by the host and passed down. */
sealed interface RegionDownloadState {
    data object Idle : RegionDownloadState
    /** Fetching the manifest before the list is actionable. */
    data object LoadingManifest : RegionDownloadState
    data object ManifestFailed : RegionDownloadState
    /** A download in flight for [regionId], [fraction] of the compressed bytes done. */
    data class Downloading(val regionId: String, val fraction: Float) : RegionDownloadState
    /** Rebuilding the R*Tree after download — indeterminate tail of the install. */
    data class Installing(val regionId: String) : RegionDownloadState
    data class Done(val regionId: String, val poiCount: Int) : RegionDownloadState
    data class Failed(val regionId: String, val reason: String) : RegionDownloadState
}

/**
 * Region picker, a collapsible tree from the bundled `regions.json`: a top section per
 * [Region.group] (just "Europe" today) → the countries in it. Most countries are leaves;
 * Germany is itself an expandable node whose own row is the whole-country ("Complete")
 * download and which expands to its 16 Bundesländer. Per-region download size comes from
 * the live [manifest]; tapping a region's Get starts its download via [onDownload].
 *
 * Only one download runs at a time; while [state] is Downloading/Installing every row is
 * disabled and the active one shows progress. Removing an installed region is confirmed
 * first (see [confirmRemove]).
 */
@Composable
fun RegionsScreen(
    regions: List<Region>,
    manifest: Map<String, RegionManifestEntry>,
    installedRegions: Set<String>,
    state: RegionDownloadState,
    onDownload: (Region) -> Unit,
    onRemove: (Region) -> Unit,
    onBack: () -> Unit,
) {
    val busy = state is RegionDownloadState.Downloading ||
        state is RegionDownloadState.Installing ||
        state is RegionDownloadState.LoadingManifest

    // A region pending removal — set on trash tap, cleared on confirm/cancel. Drives the
    // confirmation dialog so a delete is never a single accidental tap.
    var confirmRemove by remember { mutableStateOf<Region?>(null) }
    confirmRemove?.let { region ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${region.label}?") },
            text = { Text("This deletes its downloaded places from the device. You can download it again later.") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(region)
                    confirmRemove = null
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("Cancel") }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Regions", style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider()

        when (state) {
            is RegionDownloadState.ManifestFailed -> Text(
                "Couldn't reach the region list. Check your connection and try again.",
                color = ClosedRed,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            is RegionDownloadState.LoadingManifest -> Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Loading region list…", style = MaterialTheme.typography.bodyMedium)
            }
            else -> Unit
        }

        // Build the display tree once per catalog/group change: each group → its country
        // nodes (Germany carries its Bundesland children; everyone else is a leaf).
        val tree = remember(regions) { buildRegionTree(regions) }
        // Collapsed by default; expanded state is UI-only (survives recomposition, not nav).
        // Keyed by node id: the group id ("Europe") and the germany parent id.
        val expanded = remember { mutableStateMapOf<String, Boolean>() }

        // Keep the branch holding an in-flight/just-finished download open so its active
        // row stays visible without the rider having to hunt for it: open its group and,
        // if it's a Bundesland, the Germany parent too.
        val activeRegionId = when (state) {
            is RegionDownloadState.Downloading -> state.regionId
            is RegionDownloadState.Installing -> state.regionId
            is RegionDownloadState.Done -> state.regionId
            is RegionDownloadState.Failed -> state.regionId
            else -> null
        }
        LaunchedEffect(activeRegionId) {
            val g = regions.firstOrNull { it.id == activeRegionId }?.group ?: return@LaunchedEffect
            expanded[g] = true
            if (activeRegionId == SEED_REGION_ID ||
                activeRegionId?.startsWith("$SEED_REGION_ID-") == true
            ) {
                expanded[SEED_REGION_ID] = true
            }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            tree.forEach { group ->
                val groupOpen = expanded[group.id] ?: false
                item(key = "grp-${group.id}") {
                    GroupHeader(
                        title = group.title,
                        expanded = groupOpen,
                        installedCount = group.countries.count {
                            Region.covers(installedRegions, it.region.id)
                        },
                        total = group.countries.size,
                        onClick = { expanded[group.id] = !groupOpen },
                    )
                    HorizontalDivider()
                }
                if (!groupOpen) return@forEach

                group.countries.forEach { country ->
                    if (country.children.isEmpty()) {
                        // Leaf country — a plain get/installed row.
                        item(key = country.region.id) {
                            RegionRow(
                                region = country.region,
                                entry = manifest[country.region.id],
                                installed = Region.covers(installedRegions, country.region.id),
                                removable = country.region.id in installedRegions,
                                indent = Indent.COUNTRY,
                                expandable = null,
                                state = state,
                                enabled = !busy && manifest.containsKey(country.region.id),
                                onDownload = { onDownload(country.region) },
                                onRemove = { confirmRemove = country.region },
                            )
                            HorizontalDivider()
                        }
                    } else {
                        // Expandable country (Germany): its own row IS the whole-country
                        // ("Complete") download, plus a chevron that reveals the finer-
                        // grained Bundesländer — there's no separate "Complete" row.
                        val countryOpen = expanded[country.region.id] ?: false
                        item(key = "cty-${country.region.id}") {
                            RegionRow(
                                region = country.region,
                                entry = manifest[country.region.id],
                                installed = Region.covers(installedRegions, country.region.id),
                                removable = country.region.id in installedRegions,
                                indent = Indent.COUNTRY,
                                expandable = countryOpen,
                                state = state,
                                enabled = !busy && manifest.containsKey(country.region.id),
                                onExpandToggle = { expanded[country.region.id] = !countryOpen },
                                onDownload = { onDownload(country.region) },
                                onRemove = { confirmRemove = country.region },
                            )
                            HorizontalDivider()
                        }
                        if (countryOpen) {
                            items(country.children, key = { it.id }) { child ->
                                RegionRow(
                                    region = child,
                                    entry = manifest[child.id],
                                    installed = Region.covers(installedRegions, child.id),
                                    // A Bundesland covered only via "germany" isn't
                                    // removable on its own.
                                    removable = child.id in installedRegions,
                                    indent = Indent.CHILD,
                                    expandable = null,
                                    state = state,
                                    enabled = !busy && manifest.containsKey(child.id),
                                    onDownload = { onDownload(child) },
                                    onRemove = { confirmRemove = child },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Left-padding tiers so the tree depth reads at a glance: a country sits indented under
 * the Europe header (past its chevron), a Bundesland deeper still under Germany.
 */
private enum class Indent(val start: Int) { COUNTRY(32), CHILD(52) }

/** A country in the tree: its own [Region] plus any sub-regions ([children], e.g. Bundesländer). */
private data class CountryNode(val region: Region, val children: List<Region>)

/** A top-level [group] section and its ordered [countries]. */
private data class GroupNode(val id: String, val title: String, val countries: List<CountryNode>)

/**
 * Fold the flat catalog into the display tree. Germany's `germany-*` Bundesländer become
 * children of the `germany` node; every other region is a childless country. Within a
 * group, Germany leads (it's the seed/home country), then the rest in catalog order.
 */
private fun buildRegionTree(regions: List<Region>): List<GroupNode> =
    regions.groupBy { it.group }.map { (group, inGroup) ->
        val children = inGroup.filter { it.id.startsWith("$SEED_REGION_ID-") }
        val countries = inGroup
            .filter { !it.id.startsWith("$SEED_REGION_ID-") }
            .map { CountryNode(it, if (it.id == SEED_REGION_ID) children else emptyList()) }
            .sortedByDescending { it.region.id == SEED_REGION_ID }
        GroupNode(id = group, title = group, countries = countries)
    }

/** Top section header: `>` collapsed / `v` open, name, and an "N/M" installed counter. */
@Composable
private fun GroupHeader(
    title: String,
    expanded: Boolean,
    installedCount: Int,
    total: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (expanded) Icons.Filled.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (expanded) "Collapse $title" else "Expand $title",
        )
        Spacer(Modifier.size(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (installedCount > 0) {
            Text(
                "$installedCount/$total",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * A single region row: its label + size/status on the left, and on the right the
 * download affordance (Get / spinner / ✓ + remove). When [expandable] is non-null the row
 * doubles as an expandable-country header — a leading chevron (`>`/`v`) whose tap fires
 * [onExpandToggle] toggles the children, while the row's own Get/remove still act on the
 * country as a whole (Germany's row IS the whole-country download).
 */
@Composable
private fun RegionRow(
    region: Region,
    entry: RegionManifestEntry?,
    installed: Boolean,
    removable: Boolean,
    // Left-padding tier — a country vs. a nested child row.
    indent: Indent,
    // Non-null ⇒ this row is an expandable country; the value is its open/closed state.
    expandable: Boolean?,
    state: RegionDownloadState,
    enabled: Boolean,
    onExpandToggle: () -> Unit = {},
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    val downloadingThis = state is RegionDownloadState.Downloading && state.regionId == region.id
    val installingThis = state is RegionDownloadState.Installing && state.regionId == region.id
    val doneThis = state is RegionDownloadState.Done && state.regionId == region.id
    val failedThis = state is RegionDownloadState.Failed && state.regionId == region.id

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent.start.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (expandable != null) {
            // A wider hit target than the icon so the chevron is tappable with gloves.
            IconButton(onClick = onExpandToggle, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (expandable) Icons.Filled.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expandable) "Collapse ${region.label}" else "Expand ${region.label}",
                )
            }
            Spacer(Modifier.size(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(region.label, style = MaterialTheme.typography.bodyLarge)
            val subtitle = when {
                installed -> "Installed"
                entry != null -> "${formatMb(entry.bytesGz)} · ${entry.poiCount} places"
                else -> "Unavailable"
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall)

            if (downloadingThis) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (state as RegionDownloadState.Downloading).fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (failedThis) {
                Text(
                    (state as RegionDownloadState.Failed).reason,
                    color = ClosedRed,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (doneThis) {
                Text(
                    "Installed ${(state as RegionDownloadState.Done).poiCount} places",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.size(8.dp))
        when {
            downloadingThis || installingThis ->
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✓", style = MaterialTheme.typography.titleMedium)
                if (removable) {
                    IconButton(onClick = onRemove, enabled = enabled) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove ${region.label}")
                    }
                }
            }
            else -> Button(onClick = onDownload, enabled = enabled) {
                Text(if (failedThis) "Retry" else "Get")
            }
        }
    }
}

/** Bytes → a compact "12.3 MB" / "820 KB" label for download sizes. */
private fun formatMb(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb >= 1.0) "%.1f MB".format(mb) else "%d KB".format(bytes / 1024)
}
