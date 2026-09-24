package io.resupply.karoo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.resupply.karoo.data.Region
import io.resupply.karoo.data.RegionManifestEntry
import io.resupply.karoo.service.RegionDownloadService.LiveDownload
import io.resupply.karoo.service.RegionDownloadService.LivePhase
import io.resupply.karoo.service.RegionDownloadService
import kotlinx.coroutines.delay

/** A region file bigger than this warrants a "this may be slow off Wi‑Fi" confirm. */
private const val LARGE_REGION_BYTES = 5L * 1024 * 1024

/**
 * Region picker, a collapsible tree from the bundled `regions.json`: a top section per
 * [Region.group] → the countries in it. A country whose id prefixes others (germany/usa/
 * canada) is an expandable node whose row is the whole-country download and which expands to
 * its states; other countries are leaves. Per-region size/place count comes from [manifest].
 *
 * Downloads run in a foreground service (direct WiFi transport), so this screen only reflects
 * state: [live] is the in-flight/just-finished download (with a **measured** rate + ETA);
 * [failedRegionIds] carries persisted failures for a Retry affordance. A [onWifi] banner tells
 * the rider that Wi‑Fi makes downloads far faster, and a large region opens a confirm.
 */
@Composable
fun RegionsScreen(
    regions: List<Region>,
    manifest: Map<String, RegionManifestEntry>,
    installedRegions: Set<String>,
    /** Installed data version per region id; an update is offered when the manifest outranks it. */
    installedVersions: Map<String, Int>,
    onWifi: Boolean,
    manifestLoading: Boolean,
    manifestFailed: Boolean,
    /** The in-flight (or just-finished) download, or null when idle. */
    live: LiveDownload?,
    /** Region ids with a persisted failed download (→ show Retry). */
    failedRegionIds: Map<String, String>,
    /** Region id currently being removed (in-DB, fast), or null. */
    removingRegionId: String?,
    onDownload: (Region) -> Unit,
    onUpdate: (Region) -> Unit,
    onRemove: (Region) -> Unit,
    onBack: () -> Unit,
) {
    val downloadingId = live?.takeIf {
        it.phase == LivePhase.DOWNLOADING || it.phase == LivePhase.INSTALLING
    }?.regionId
    val busy = downloadingId != null || removingRegionId != null

    // A region pending removal or a large-download confirm — each drives a dialog.
    var confirmRemove by remember { mutableStateOf<Region?>(null) }
    var confirmLarge by remember { mutableStateOf<Region?>(null) }

    confirmRemove?.let { region ->
        ConfirmDialog(
            icon = Icons.Filled.Delete,
            accent = MaterialTheme.colorScheme.error,
            title = "Remove ${region.label}?",
            message = "This deletes its downloaded places from the device. You can download it again later.",
            confirmLabel = "Remove",
            onConfirm = { onRemove(region); confirmRemove = null },
            onDismiss = { confirmRemove = null },
        )
    }
    confirmLarge?.let { region ->
        val mb = manifest[region.id]?.let { formatMb(it.bytesGz) } ?: "a large file"
        ConfirmDialog(
            icon = Icons.Filled.Download,
            accent = MaterialTheme.colorScheme.primary,
            title = "Download ${region.label}?",
            // This dialog only opens off Wi‑Fi (on Wi‑Fi a large region downloads straight away).
            message = "This is $mb. Without Wi‑Fi it can take a long while — connecting to Wi‑Fi first is much faster.",
            confirmLabel = "Download",
            onConfirm = { onDownload(region); confirmLarge = null },
            onDismiss = { confirmLarge = null },
        )
    }

    // Decide download vs. confirm. On Wi‑Fi even a large region is quick, so just go —
    // the confirm exists only to warn about a slow off‑Wi‑Fi download. Off Wi‑Fi, a large
    // region still confirms; small ones always go.
    val startDownload: (Region) -> Unit = { region ->
        val big = (manifest[region.id]?.bytesGz ?: 0L) >= LARGE_REGION_BYTES
        if (big && !onWifi) confirmLarge = region else onDownload(region)
    }

    // An update is offered only for a directly-installed region (one in [installedRegions],
    // i.e. `removable`) whose manifest data version outranks the installed one — not for a
    // state merely covered by an installed country.
    val hasUpdate: (String) -> Boolean = { id ->
        id in installedRegions &&
            (manifest[id]?.dataVersion ?: 0) > (installedVersions[id] ?: 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Regions", style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider()

        // Wi‑Fi guidance banner — the single biggest lever on download speed.
        WifiBanner(onWifi)

        if (manifestLoading) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Loading region list…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (manifestFailed) {
            Text(
                "Couldn't reach the region list. Check your connection and try again.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        val tree = remember(regions) { buildRegionTree(regions) }
        val expanded = remember { mutableStateMapOf<String, Boolean>() }

        // Keep the branch of the active/just-finished download open so its row stays visible.
        val activeRegionId = live?.regionId
        LaunchedEffect(activeRegionId) {
            val g = regions.firstOrNull { it.id == activeRegionId }?.group ?: return@LaunchedEffect
            expanded[g] = true
            activeRegionId?.substringBefore('-')?.let { parent ->
                if (parent != activeRegionId && regions.any { it.id == parent }) expanded[parent] = true
            }
        }

        // Installed, directly-removable regions surfaced to the top so a rider manages what they
        // have without hunting the tree. Derived purely from installedRegions — the same regions
        // still appear (installed) in Browse below, so the two never desync.
        val yourRegions = remember(regions, installedRegions) {
            regions.filter { it.id in installedRegions }.sortedBy { it.label }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            if (yourRegions.isNotEmpty()) {
                item(key = "sec-yours") { SectionLabel("Your regions") }
                items(yourRegions, key = { "yours-${it.id}" }) { region ->
                    RegionRow(
                        region = region,
                        entry = manifest[region.id],
                        installed = true,
                        removable = true,
                        indent = Indent.COUNTRY,
                        expandable = null,
                        live = live?.takeIf { it.regionId == region.id },
                        failedReason = failedRegionIds[region.id],
                        removing = removingRegionId == region.id,
                        updateAvailable = hasUpdate(region.id),
                        enabled = !busy && manifest.containsKey(region.id),
                        onDownload = { startDownload(region) },
                        onUpdate = { onUpdate(region) },
                        onRemove = { confirmRemove = region },
                    )
                    HorizontalDivider()
                }
                item(key = "sec-browse") { SectionLabel("Browse all") }
            }

            tree.forEach { group ->
                val groupOpen = expanded[group.id] ?: false
                item(key = "grp-${group.id}") {
                    GroupHeader(
                        title = group.title,
                        expanded = groupOpen,
                        installedCount = group.countries.count { Region.covers(installedRegions, it.region.id) },
                        total = group.countries.size,
                        onClick = { expanded[group.id] = !groupOpen },
                    )
                    HorizontalDivider()
                }
                if (!groupOpen) return@forEach

                group.countries.forEach { country ->
                    if (country.children.isEmpty()) {
                        item(key = country.region.id) {
                            RegionRow(
                                region = country.region,
                                entry = manifest[country.region.id],
                                installed = Region.covers(installedRegions, country.region.id),
                                removable = country.region.id in installedRegions,
                                indent = Indent.COUNTRY,
                                expandable = null,
                                live = live?.takeIf { it.regionId == country.region.id },
                                failedReason = failedRegionIds[country.region.id],
                                removing = removingRegionId == country.region.id,
                                updateAvailable = hasUpdate(country.region.id),
                                enabled = !busy && manifest.containsKey(country.region.id),
                                onDownload = { startDownload(country.region) },
                                onUpdate = { onUpdate(country.region) },
                                onRemove = { confirmRemove = country.region },
                            )
                            HorizontalDivider()
                        }
                    } else {
                        val countryOpen = expanded[country.region.id] ?: false
                        item(key = "cty-${country.region.id}") {
                            RegionRow(
                                region = country.region,
                                entry = manifest[country.region.id],
                                installed = Region.covers(installedRegions, country.region.id),
                                removable = country.region.id in installedRegions,
                                indent = Indent.COUNTRY,
                                expandable = countryOpen,
                                live = live?.takeIf { it.regionId == country.region.id },
                                failedReason = failedRegionIds[country.region.id],
                                removing = removingRegionId == country.region.id,
                                updateAvailable = hasUpdate(country.region.id),
                                enabled = !busy && manifest.containsKey(country.region.id),
                                onExpandToggle = { expanded[country.region.id] = !countryOpen },
                                onDownload = { startDownload(country.region) },
                                onUpdate = { onUpdate(country.region) },
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
                                    removable = child.id in installedRegions,
                                    indent = Indent.CHILD,
                                    expandable = null,
                                    live = live?.takeIf { it.regionId == child.id },
                                    failedReason = failedRegionIds[child.id],
                                    removing = removingRegionId == child.id,
                                    updateAvailable = hasUpdate(child.id),
                                    enabled = !busy && manifest.containsKey(child.id),
                                    onDownload = { startDownload(child) },
                                    onUpdate = { onUpdate(child) },
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
 * Reactive Wi‑Fi advice — the biggest lever on download speed. Off Wi‑Fi it shows a
 * persistent red banner nudging the rider to connect. The moment Wi‑Fi comes on it flips
 * to a brief green "connected" confirmation, then slides away — no permanent chrome once
 * downloads are fast. Stays hidden while connected.
 */
@Composable
private fun WifiBanner(onWifi: Boolean) {
    // Show the green confirmation only for a short beat after a transition to Wi‑Fi.
    var showConnected by remember { mutableStateOf(false) }
    LaunchedEffect(onWifi) {
        if (onWifi) {
            showConnected = true
            delay(2_000)
            showConnected = false
        } else {
            showConnected = false
        }
    }

    // Off Wi‑Fi → red banner; just-connected → green banner; connected+settled → nothing.
    val visible = !onWifi || showConnected
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut() + shrinkVertically()) {
        val green = Color(0xFF2E7D32)
        val onGreen = Color.White
        val bg = if (onWifi) green else MaterialTheme.colorScheme.errorContainer
        val fg = if (onWifi) onGreen else MaterialTheme.colorScheme.onErrorContainer
        val icon = if (onWifi) Icons.Filled.CheckCircle else Icons.Filled.WifiOff
        val text = if (onWifi) {
            "Wi‑Fi connected — downloads are fast."
        } else {
            "Turn on Wi‑Fi to download much faster. Without it, a large region can take a long time."
        }
        Row(
            modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = fg)
        }
    }
}

private enum class Indent(val start: Int) { COUNTRY(32), CHILD(52) }

private data class CountryNode(val region: Region, val children: List<Region>)
private data class GroupNode(val id: String, val title: String, val countries: List<CountryNode>)

/**
 * Fold the flat catalog into the display tree. A country whose id is the prefix of other ids
 * (germany → `germany-*`, usa → `usa-*`) becomes an expandable parent carrying those children;
 * every other region is a childless country. Parents lead their group.
 */
private fun buildRegionTree(regions: List<Region>): List<GroupNode> {
    val parentIds = regions
        .filter { p -> regions.any { it.id != p.id && it.id.startsWith("${p.id}-") } }
        .map { it.id }
        .toSet()
    fun parentOf(id: String): String? =
        id.substringBefore('-').takeIf { it != id && it in parentIds }

    return regions.groupBy { it.group }.map { (group, inGroup) ->
        val countries = inGroup
            .filter { parentOf(it.id) == null }
            .map { country ->
                val children = inGroup.filter { it.id.startsWith("${country.id}-") }
                CountryNode(country, children)
            }
            .sortedByDescending { it.region.id in parentIds }
        GroupNode(id = group, title = group, countries = countries)
    }
}

/** A small all-caps section label ("YOUR REGIONS" / "BROWSE ALL") separating the screen's parts. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun GroupHeader(
    title: String,
    expanded: Boolean,
    installedCount: Int,
    total: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (expanded) Icons.Filled.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (expanded) "Collapse $title" else "Expand $title",
        )
        Spacer(Modifier.size(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
 * A single region row. Left: label + size/status (or, while downloading, a **live** progress
 * bar with measured % · ETA · rate). Right: Get / spinner / ✓ + remove. A non-null [expandable]
 * makes the row an expandable-country header (leading chevron); its own Get still acts on the
 * whole country.
 */
@Composable
private fun RegionRow(
    region: Region,
    entry: RegionManifestEntry?,
    installed: Boolean,
    removable: Boolean,
    indent: Indent,
    expandable: Boolean?,
    live: LiveDownload?,
    failedReason: String?,
    removing: Boolean,
    updateAvailable: Boolean,
    enabled: Boolean,
    onExpandToggle: () -> Unit = {},
    onDownload: () -> Unit,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
) {
    val downloading = live?.phase == LivePhase.DOWNLOADING
    val installing = live?.phase == LivePhase.INSTALLING || removing
    val done = live?.phase == LivePhase.DONE
    val failed = live?.phase == LivePhase.FAILED || failedReason != null
    val busyRow = downloading || installing

    // An expandable row's chevron already provides the visual indent, so don't also pad it in —
    // that double-cost is what squeezed "Germany" onto two lines. Non-expandable rows keep their
    // catalog indent for the hierarchy. The whole row taps to expand a country.
    val startPad = if (expandable != null) 4 else indent.start
    val rowClick = if (expandable != null) Modifier.clickable(onClick = onExpandToggle) else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(rowClick)
            .defaultMinSize(minHeight = 56.dp)
            .padding(start = startPad.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expandable != null) {
            Icon(
                if (expandable) Icons.Filled.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(32.dp).padding(4.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                region.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            when {
                // Active work owns the second line: a determinate bar + phase, no clipped spinner.
                busyRow -> {
                    val fraction = if (installing && live == null) null else live?.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                    }
                    Spacer(Modifier.height(3.dp))
                    val phase = when {
                        installing -> if (removing) "Removing…" else "Installing…"
                        else -> "Downloading… ${downloadLine(live!!)}"
                    }
                    Text(phase, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                failed -> StatusChip(
                    dot = MaterialTheme.colorScheme.error,
                    text = live?.reason ?: failedReason ?: "Download failed",
                    color = MaterialTheme.colorScheme.error,
                )
                installed && updateAvailable -> StatusChip(
                    dot = MaterialTheme.colorScheme.primary,
                    text = "Update available",
                    color = MaterialTheme.colorScheme.primary,
                )
                installed -> StatusChip(
                    dot = StatusGreen,
                    text = entry?.let { "Installed · ${formatPlaces(it.poiCount)} places" } ?: "Installed",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry != null -> StatusChip(
                    dot = null,
                    text = "${formatMb(entry.bytesGz)} · ${formatPlaces(entry.poiCount)} places",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> StatusChip(dot = null, text = "Unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.size(8.dp))
        // Trailing action: nothing while busy (progress owns the row), an overflow menu for an
        // installed region (Update / Remove), or a "Get" CTA to download.
        when {
            busyRow -> {}
            installed && removable -> RegionOverflowMenu(
                label = region.label,
                canUpdate = updateAvailable,
                enabled = enabled,
                onUpdate = onUpdate,
                onRemove = onRemove,
            )
            installed -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Installed",
                tint = StatusGreen,
                modifier = Modifier.size(20.dp),
            )
            else -> CompactActionButton(if (failed) "Retry" else "Get", onClick = onDownload, enabled = enabled)
        }
    }
}

/** Warm status-green (matches the WifiBanner "connected" green) for an installed region. */
private val StatusGreen = Color(0xFF2E7D32)

/** A tiny colored dot + label line — the row's status at a glance. [dot] null → no dot. */
@Composable
private fun StatusChip(dot: Color?, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) {
            Box(modifier = Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.size(6.dp))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The ⋮ overflow for an installed region: Update (when newer data exists) + Remove. */
@Composable
private fun RegionOverflowMenu(
    label: String,
    canUpdate: Boolean,
    enabled: Boolean,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "$label options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canUpdate) {
                DropdownMenuItem(
                    text = { Text("Update") },
                    leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                    onClick = { open = false; onUpdate() },
                )
            }
            DropdownMenuItem(
                text = { Text("Remove") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = { open = false; onRemove() },
            )
        }
    }
}

/** Group thousands so "502805 places" reads as "502,805". */
private fun formatPlaces(n: Int): String = "%,d".format(n)

/** A slim pill button sized for the narrow Karoo region rows — trims the default Material
 *  Button's min width + padding so the row's text label keeps its space. */
@Composable
private fun CompactActionButton(label: String, onClick: () -> Unit, enabled: Boolean) {
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 32.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Live "46% · ~2 min left · 1.4 MB/s" line from measured throughput. */
private fun downloadLine(live: LiveDownload): String {
    val pct = (live.fraction * 100).toInt()
    val eta = live.etaSeconds?.let { " · ${RegionDownloadService.formatEta(it)} left" } ?: ""
    val rate = if (live.bytesPerSec > 0) " · ${formatRate(live.bytesPerSec)}" else ""
    return "$pct%$eta$rate"
}

/** Bytes → a compact "12.3 MB" / "820 KB" label for download sizes. */
private fun formatMb(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb >= 1.0) "%.1f MB".format(mb) else "%d KB".format(bytes / 1024)
}

/** Bytes/sec → "1.4 MB/s" / "820 KB/s". */
private fun formatRate(bytesPerSec: Long): String {
    val mb = bytesPerSec / 1_048_576.0
    return if (mb >= 1.0) "%.1f MB/s".format(mb) else "%d KB/s".format(bytesPerSec / 1024)
}
