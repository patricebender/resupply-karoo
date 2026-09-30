package io.resupply.karoo.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.resupply.karoo.build.BuildState
import io.resupply.karoo.data.Category
import io.resupply.karoo.data.Poi
import io.resupply.karoo.data.ResupplyConfig
import io.resupply.karoo.data.ThemeMode
import io.resupply.karoo.data.UnitMode
import io.resupply.karoo.service.AppUpdateService
import io.resupply.karoo.service.AppUpdateService.UpdatePhase
import io.resupply.karoo.service.RegionDownloadService
import kotlin.math.roundToInt

/** A newer release the rider can install, surfaced in the Update section of Settings. */
data class AppUpdateInfo(
    /** e.g. "1.5.0" — the version being offered. */
    val versionName: String,
    /** First line of the release notes ("what's new"), or empty. */
    val whatsNew: String,
    /** All release-note bullets (plain prose), for the "Show changelog" dialog. */
    val notes: List<String> = emptyList(),
)

/** The state of the app-update check, driving the always-present Update section. */
sealed interface AppUpdateCheck {
    /** The manifest fetch is in flight and there's no prior result to show. */
    data object Checking : AppUpdateCheck
    /** Checked: the running build is the latest. */
    data object UpToDate : AppUpdateCheck
    /** Checked: a newer release is available. */
    data class Available(val info: AppUpdateInfo) : AppUpdateCheck
    /** The check couldn't complete (offline / no phone link) and there's nothing to show. */
    data object Failed : AppUpdateCheck
}

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
    // Whether a route is loaded. No route = live (nearby) mode, which auto-refreshes as the rider
    // moves and on a periodic tick, so a manual rebuild is redundant — the rebuild arrow (and the
    // trashcan) are route-mode only. In live mode the top bar carries a "Stop live search" control.
    routeLoaded: Boolean,
    // Live (nearby) search on/off — the rider's intent (on unless they've paused it). Drives the
    // live-mode toggle's on (pulsing) vs off (crossed-out) look.
    liveOn: Boolean,
    // A GPS fix is available. The live toggle follows the overview Find button's rule: with no fix
    // there's nothing to run, so the toggle is disabled (inert) regardless of on/off.
    hasFix: Boolean,
    // The whole built set (every category). The chip badges count the *visible* subset of
    // this through [ResupplyConfig.showsPoi], so a category toggle or the safe-water switch
    // updates the counts live, in step with the map/overview and with no rebuild.
    pois: List<Poi>,
    installedSummary: String,
    onDetourChange: (Int) -> Unit,
    onCategoryToggle: (Category, Boolean) -> Unit,
    onSafeWaterToggle: (Boolean) -> Unit,
    onSmartDistanceToggle: (Boolean) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    unitMode: UnitMode,
    onUnitModeChange: (UnitMode) -> Unit,
    // Resolved units for the detour-radius label (Auto folds in the Karoo's preference).
    useImperial: Boolean,
    // The app-update check result (checking / up-to-date / available / failed). Drives the
    // always-present Update section. The check runs on entering Settings.
    updateCheck: AppUpdateCheck,
    // The running build's version name, shown on the up-to-date / failed lines.
    currentVersion: String,
    // The in-flight (or just-finished) update, mirrored from AppUpdateService; null when idle.
    updateProgress: io.resupply.karoo.service.AppUpdateService.UpdateProgress?,
    // Whether the OS "install unknown apps" permission is granted for us. When false the
    // "Install now" button becomes "Allow installs", routing the rider to the system setting.
    canInstallApp: Boolean,
    onInstallUpdate: () -> Unit,
    onAllowInstalls: () -> Unit,
    onRetryCheck: () -> Unit,
    onBuild: () -> Unit,
    onClear: () -> Unit,
    // Toggle route-less live search. true = resume (a fresh search repopulates + tracking takes
    // over); false = pause → pre-search state (overview shows its invitation).
    onToggleLive: (Boolean) -> Unit,
    onOpenRegions: () -> Unit,
    onBack: () -> Unit,
) {
    val building = buildState is BuildState.Building
    val hasPins = pois.isNotEmpty()
    // The trashcan asks before wiping — clearing also forgets this route's saved favorites.
    var showClearDialog by remember { mutableStateOf(false) }
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
            if (routeLoaded) {
                // Route mode: rebuild the roadbook (a deliberate one-shot) + trashcan to clear it.
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
                IconButton(onClick = { showClearDialog = true }, enabled = hasPins && !building) {
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
            } else {
                // Live (nearby) mode: neither rebuild nor trashcan make sense (the set refreshes
                // itself and there's no route to clear). Instead a live search on/off toggle.
                LiveToggle(
                    on = liveOn,
                    // Turning on runs a build → show the "coming online" spinner state until it
                    // lands, so the ~second of loading reads as progress, not a dead tap.
                    loading = building,
                    // No fix → nothing to run against; the toggle is inert. (Loading is handled
                    // separately so the ON transition still shows the spinner rather than greying.)
                    enabled = hasFix && !building,
                    onToggle = { onToggleLive(!liveOn) },
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
                val options = ResupplyConfig.DETOUR_OPTIONS_METERS
                val currentIndex = options.indexOfFirst { it >= config.detourMeters }
                    .let { if (it < 0) options.lastIndex else it }
                // Track the drag locally so the label + thumb follow the finger, but only COMMIT to
                // config on release (onValueChangeFinished). Persisting every intermediate step
                // would emit config changes mid-drag → the extension would rebuild the roadbook
                // repeatedly as the finger moves. Committing once on release means one rebuild for
                // the value the rider actually chose. `dragIndex == null` → not dragging, show the
                // persisted value; keyed on currentIndex so an external change (or a fresh screen)
                // resets cleanly.
                var dragIndex by remember(currentIndex) { mutableStateOf<Float?>(null) }
                val shownIndex = dragIndex ?: currentIndex.toFloat()
                Text(
                    formatDistance(options[shownIndex.roundToInt().coerceIn(0, options.lastIndex)], useImperial),
                    style = MaterialTheme.typography.titleMedium,
                )
                Slider(
                    value = shownIndex,
                    onValueChange = { raw -> dragIndex = raw },
                    onValueChangeFinished = {
                        dragIndex?.let { raw ->
                            onDetourChange(options[raw.roundToInt().coerceIn(0, options.lastIndex)])
                        }
                        dragIndex = null
                    },
                    valueRange = 0f..options.lastIndex.toFloat(),
                    steps = options.size - 2,
                    enabled = !building,
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader("Appearance")
            AppearanceSection(mode = themeMode, onModeChange = onThemeModeChange)

            Spacer(Modifier.height(20.dp))
            SectionHeader("Units")
            UnitsSection(mode = unitMode, onModeChange = onUnitModeChange)

            // Always present, so the rider can always see "am I current?" — a stable anchor that
            // distinguishes up-to-date from a check that hasn't landed or failed.
            Spacer(Modifier.height(20.dp))
            SectionHeader("Update")
            UpdatesSection(
                check = updateCheck,
                currentVersion = currentVersion,
                progress = updateProgress,
                canInstall = canInstallApp,
                onInstall = onInstallUpdate,
                onAllowInstalls = onAllowInstalls,
                onRetryCheck = onRetryCheck,
            )

            Spacer(Modifier.height(20.dp))
            SectionHeader("Data")
            RegionsRow(summary = installedSummary, enabled = !building, onClick = onOpenRegions)
        }
    }

    if (showClearDialog) {
        ConfirmDialog(
            icon = Icons.Filled.Delete,
            accent = MaterialTheme.colorScheme.error,
            title = "Clear this roadbook?",
            message = "Removes the places found for the current route and the favorites you saved " +
                "for it. Building the same route again will start fresh.",
            confirmLabel = "Clear",
            onConfirm = {
                showClearDialog = false
                onClear()
            },
            onDismiss = { showClearDialog = false },
        )
    }
}

/**
 * The live-mode top-bar toggle: one "● Live" pill with clear on/off semantics.
 *  - ON  → a tinted-green pill, a slow-pulsing green dot + "Live". Tap to pause.
 *  - OFF → the SAME pill, muted, with a single diagonal slash struck across the whole control
 *          (the universal "off" mark) — the label stays "Live" so it reads as "Live: off", not a
 *          different mode. Tap to resume (a fresh search repopulates and tracking takes back over).
 *  - disabled ([enabled] false, i.e. no GPS fix) → the off look, greyed and inert. Matches the
 *    overview Find button's "no fix → nothing to run" rule.
 * The pulse only breathes in the ON state; every other state is static.
 */
@Composable
private fun LiveToggle(on: Boolean, loading: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val active = on && enabled
    // Coming online: the rider tapped ON and the build is running. Wear the green "live" look
    // already (accent + tinted fill, no slash) so the transition reads as "turning on", with a
    // spinner in the dot slot until the POIs land.
    val accent = when {
        active || loading -> openGreen
        enabled -> MaterialTheme.colorScheme.onSurfaceVariant // off but toggleable
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) // no fix → inert
    }
    val container = if (active || loading) {
        openGreen.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val pulse = rememberInfiniteTransition(label = "liveTogglePulse")
    val breath by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "liveToggleDotAlpha",
    )
    val dotAlpha = if (active) breath else 1f
    // The slash marks "off"; it must NOT show while loading (that's a turning-on state, not off).
    val slashed = !active && !loading

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(container)
            .clickable(enabled = enabled, onClick = onToggle)
            // Strike a diagonal slash across the ENTIRE pill when off/unavailable (never while
            // loading), drawn after the content so it sits on top. Round cap + a slight overshoot
            // so it spans corner-to-corner cleanly.
            .drawWithContent {
                drawContent()
                if (slashed) {
                    val pad = size.height * 0.18f
                    drawLine(
                        color = accent,
                        start = Offset(pad, size.height - pad),
                        end = Offset(size.width - pad, pad),
                        strokeWidth = size.height * 0.09f,
                        cap = StrokeCap.Round,
                    )
                }
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        // Dot slot: a spinner while coming online, otherwise a plain dot (the slash, when off,
        // lives on the pill — not the dot). Same 9dp footprint so the label doesn't shift.
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(9.dp),
                strokeWidth = 1.5.dp,
                color = accent,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .alpha(dotAlpha)
                    .clip(CircleShape)
                    .background(accent),
            )
        }
        Spacer(Modifier.size(7.dp))
        Text(
            "Live",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = accent,
            maxLines = 1,
        )
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

/**
 * The Appearance section: a single three-way theme toggle — Light / Auto / Dark — mapping directly
 * to the three [ThemeMode]s. Auto ([ThemeMode.SYSTEM]) follows the Karoo's day/night mode and is
 * the default; Light/Dark are explicit rider overrides. One control, three explicit states — no
 * hidden coupling, the selected segment is always what you get.
 */
@Composable
private fun AppearanceSection(mode: ThemeMode, onModeChange: (ThemeMode) -> Unit) {
    SegmentedModeToggle(
        segments = THEME_SEGMENTS,
        selected = mode,
        onSelect = onModeChange,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The Units section: a single three-way distance toggle — km / Auto / mi — mapping directly to the
 * three [UnitMode]s. Auto ([UnitMode.SYSTEM]) follows the Karoo's own unit preference and is the
 * default; km/mi are explicit rider overrides. Same segmented control as Appearance.
 */
@Composable
private fun UnitsSection(mode: UnitMode, onModeChange: (UnitMode) -> Unit) {
    SegmentedModeToggle(
        segments = UNIT_SEGMENTS,
        selected = mode,
        onSelect = onModeChange,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The theme segments, in display order (left→right), each with its icon + label. */
private val THEME_SEGMENTS = listOf(
    Triple(ThemeMode.LIGHT, Icons.Filled.WbSunny, "Light"),
    Triple(ThemeMode.SYSTEM, Icons.Filled.BrightnessAuto, "Auto"),
    Triple(ThemeMode.DARK, Icons.Filled.DarkMode, "Dark"),
)

/** The unit segments, in display order (left→right). Auto reuses the Appearance "Auto" icon. */
private val UNIT_SEGMENTS = listOf(
    Triple(UnitMode.METRIC, Icons.Filled.Straighten, "km"),
    Triple(UnitMode.SYSTEM, Icons.Filled.BrightnessAuto, "Auto"),
    Triple(UnitMode.IMPERIAL, Icons.Filled.Straighten, "mi"),
)

/**
 * A generic three-(or more)-way segmented control: a rounded track holding equal cells, with a
 * highlighted pill thumb that slides to the [selected] cell (animated offset + color, the same
 * motion vocabulary as the header star toggle). Thin dividers between the resting cells sell the
 * "segments" read; the active cell's content flips to the primary's on-color, the others stay
 * muted. Tapping a cell selects its value. Width is measured so the thumb lands on exact fractions.
 * Shared by the Appearance and Units sections.
 */
@Composable
private fun <T> SegmentedModeToggle(
    segments: List<Triple<T, ImageVector, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = segments.size
    val selectedIndex = segments.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val thumbIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "segmentedmode-thumb",
    )
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val thumbColor = MaterialTheme.colorScheme.primary
    val onThumb = MaterialTheme.colorScheme.onPrimary
    val onTrack = MaterialTheme.colorScheme.onSurfaceVariant
    val divider = MaterialTheme.colorScheme.outlineVariant

    val trackHeight = 46.dp
    val inset = 3.dp // uniform gap between the thumb and the track edge on all sides

    BoxWithConstraints(
        modifier = modifier
            .height(trackHeight)
            .clip(RoundedCornerShape(percent = 50))
            .background(trackColor),
        contentAlignment = Alignment.CenterStart,
    ) {
        val segmentWidth = maxWidth / count

        // Resting dividers between cells — fade out as the thumb approaches so it never overlaps
        // the accent pill. One fewer divider than cells.
        for (i in 1 until count) {
            // Distance (in cells) from the thumb centre to this divider; near → hide it.
            val near = (kotlin.math.abs(thumbIndex + 0.5f - i)).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .offset(x = segmentWidth * i)
                    .padding(vertical = 12.dp)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(divider.copy(alpha = 0.6f * near)),
            )
        }

        // The sliding accent pill, exactly one cell wide minus the uniform inset, centred in its
        // cell and offset to the (fractional) selected index.
        Box(
            modifier = Modifier
                .offset(x = segmentWidth * thumbIndex + inset)
                .padding(vertical = inset)
                .width(segmentWidth - inset * 2)
                .fillMaxHeight()
                .clip(RoundedCornerShape(percent = 50))
                .background(thumbColor),
        )

        // The cells on top: each exactly one Nth wide, content centred within it.
        Row(modifier = Modifier.fillMaxSize()) {
            segments.forEachIndexed { index, (value, icon, label) ->
                val active = index == selectedIndex
                val content by animateColorAsState(
                    targetValue = if (active) onThumb else onTrack,
                    animationSpec = tween(durationMillis = 300),
                    label = "segmentedmode-content",
                )
                Row(
                    modifier = Modifier
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable { onSelect(value) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = content,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = content,
                        maxLines = 1,
                    )
                }
            }
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

private val UpdateStatusGreen = Color(0xFF2E7D32)

/**
 * The Update section: surfaces a newer release and drives the in-app install. Four faces,
 * mutually exclusive. The live install [progress] takes precedence once a download is under
 * way / just finished; otherwise the [check] result drives it:
 *  - **Downloading / Installing** — a determinate bar + "46% · ~1 min left" then "Almost done…".
 *  - **Install failed** — an error chip with the reason, and a Retry pill.
 *  - **Install ready (DONE)** — a green chip ("confirm the install to finish"), Install-again pill.
 *  - **Available** — version + what's-new line, and an "Install now" (or "Allow installs") pill.
 *  - **Up to date** — a green check + "Up to date · vX.Y.Z", no action.
 *  - **Checking** — a spinner + "Checking for updates…", no action.
 *  - **Check failed** — a "Couldn't check for updates" line + "On vX.Y.Z", and a Retry pill.
 */
@Composable
private fun UpdatesSection(
    check: AppUpdateCheck,
    currentVersion: String,
    progress: AppUpdateService.UpdateProgress?,
    canInstall: Boolean,
    onInstall: () -> Unit,
    onAllowInstalls: () -> Unit,
    onRetryCheck: () -> Unit,
) {
    val running = progress?.phase == UpdatePhase.DOWNLOADING || progress?.phase == UpdatePhase.INSTALLING
    val available = check as? AppUpdateCheck.Available
    var showChangelog by remember { mutableStateOf(false) }

    // Two visual registers: a quiet inline line for the no-action states (up-to-date / checking),
    // and a lifted "release card" for everything actionable (available / downloading / installing /
    // terminal / failed check) — so the section only draws attention when there's something to do.
    val quiet = !running && progress == null &&
        (check is AppUpdateCheck.UpToDate || check is AppUpdateCheck.Checking)

    if (quiet) {
        QuietUpdateLine(check, currentVersion)
    } else {
        UpdateCard(
            check = check,
            available = available,
            currentVersion = currentVersion,
            progress = progress,
            running = running,
            canInstall = canInstall,
            onInstall = onInstall,
            onAllowInstalls = onAllowInstalls,
            onRetryCheck = onRetryCheck,
            onShowChangelog = { showChangelog = true },
        )
    }

    if (showChangelog && available != null) {
        ChangelogDialog(
            version = available.info.versionName,
            notes = available.info.notes,
            onDismiss = { showChangelog = false },
        )
    }
}

/** The minimal, no-action states: a single icon + line. No card — nothing to act on. */
@Composable
private fun QuietUpdateLine(check: AppUpdateCheck, currentVersion: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (check is AppUpdateCheck.Checking) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = UpdateStatusGreen)
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (check is AppUpdateCheck.Checking) {
                Text("Checking for updates…", style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            } else {
                Text("You're up to date", style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                UpdateSubtle("Version $currentVersion")
            }
        }
    }
}

/**
 * The lifted "release card" for actionable update states. A tinted panel (primaryContainer at low
 * alpha, hairline border) with a small state overline, a hero version line that owns its own row
 * (so it's never truncated), and a full-width action row. The version — the thing that must be
 * readable — is the visual anchor.
 */
@Composable
private fun UpdateCard(
    check: AppUpdateCheck,
    available: AppUpdateCheck.Available?,
    currentVersion: String,
    progress: AppUpdateService.UpdateProgress?,
    running: Boolean,
    canInstall: Boolean,
    onInstall: () -> Unit,
    onAllowInstalls: () -> Unit,
    onRetryCheck: () -> Unit,
    onShowChangelog: () -> Unit,
) {
    val accent = when {
        progress?.phase == UpdatePhase.FAILED || check is AppUpdateCheck.Failed ->
            MaterialTheme.colorScheme.error
        progress?.phase == UpdatePhase.DONE -> UpdateStatusGreen
        else -> MaterialTheme.colorScheme.primary
    }
    // Overline (small accent label) + hero (large headline) + optional detail (small subtitle),
    // per state. Raw installer error strings go in the detail line, never the hero.
    val installing = progress?.phase == UpdatePhase.INSTALLING
    data class CardCopy(val overline: String, val hero: String, val detail: String? = null)
    val copy = when {
        running && installing -> CardCopy("INSTALLING", "Installing…")
        running -> CardCopy("DOWNLOADING", "Downloading update")
        progress?.phase == UpdatePhase.FAILED ->
            CardCopy("UPDATE FAILED", "Couldn't install", humanInstallError(progress.reason))
        progress?.phase == UpdatePhase.DONE -> CardCopy("READY", "Confirm to finish", "Approve the install prompt to update.")
        available != null -> CardCopy("UPDATE AVAILABLE", "Version ${available.info.versionName}")
        check is AppUpdateCheck.Failed -> CardCopy("COULDN'T CHECK", "You're on $currentVersion", "No connection to check for updates.")
        else -> CardCopy("", "")
    }
    val overline = copy.overline
    val hero = copy.hero

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        // Overline: a small icon + accent label.
        Row(verticalAlignment = Alignment.CenterVertically) {
            val icon = when {
                progress?.phase == UpdatePhase.FAILED || check is AppUpdateCheck.Failed -> Icons.Filled.ErrorOutline
                progress?.phase == UpdatePhase.DONE -> Icons.Filled.CheckCircle
                else -> Icons.Filled.SystemUpdateAlt
            }
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(
                overline,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = accent,
            )
        }

        Spacer(Modifier.height(6.dp))
        // Hero line: the version (or state headline). Own row, full width — the thing that was
        // getting truncated, so it gets the whole width and wraps rather than ellipsizing.
        Text(
            hero,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // Optional detail line (e.g. a plain-English install error) under the hero.
        copy.detail?.let {
            Spacer(Modifier.height(4.dp))
            UpdateSubtle(it, maxLines = 3)
        }

        // Live progress bar while downloading/installing.
        if (running && progress != null) {
            Spacer(Modifier.height(12.dp))
            if (installing || progress.fraction <= 0f) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                )
            } else {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                )
            }
            Spacer(Modifier.height(6.dp))
            UpdateSubtle(if (installing) "Finishing up" else downloadLine(progress))
        }

        // Actions: the primary pill gets its OWN full-width row (so its label never clips), with
        // a quiet "What's new" text link above it when there are notes. No action while running.
        val pill: Pair<String, () -> Unit>? = when {
            running -> null
            progress?.phase == UpdatePhase.FAILED -> "Retry" to onInstall
            available != null || progress?.phase == UpdatePhase.DONE ->
                if (canInstall) "Install now" to onInstall else "Allow installs" to onAllowInstalls
            check is AppUpdateCheck.Failed -> "Retry" to onRetryCheck
            else -> null
        }
        // Only in the idle "available" state — not mid-download or after a terminal result, where
        // an orphaned link would float with no install button beside it.
        val showChangelogAction = available != null && available.info.notes.isNotEmpty() &&
            progress == null
        if (showChangelogAction) {
            Spacer(Modifier.height(10.dp))
            Text(
                "What's new",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onShowChangelog)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (pill != null) {
            Spacer(Modifier.height(12.dp))
            UpdatePrimaryButton(
                label = pill.first,
                accent = accent,
                onClick = pill.second,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * A scrollable "what's new" dialog for the offered release: the accent disc + title vocabulary of
 * [ConfirmDialog], but a left-aligned bulleted body (the notes can run long) and a single Close.
 */
@Composable
private fun ChangelogDialog(version: String, notes: List<String>, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        icon = {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.SystemUpdateAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(26.dp),
                )
            }
        },
        title = {
            Text(
                "What's new in v$version",
                style = MaterialTheme.typography.titleLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            // Cap the height so a long changelog scrolls inside the dialog rather than
            // overflowing it; each note is a hanging-indent bullet.
            Column(
                modifier = Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                notes.forEach { note ->
                    Row(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            "•  ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun UpdateSubtle(text: String, maxLines: Int = 1) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Turn a raw PackageInstaller failure string into one plain-English sentence. The installer
 * returns developer-facing codes like `INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package … signatures
 * do not match …`; riders should never see those.
 */
private fun humanInstallError(reason: String?): String = when {
    reason == null -> "Something went wrong. Try again."
    reason.contains("SIGNATURE", true) || reason.contains("signatures do not match", true) ->
        "This update is signed differently from the installed app. Uninstall the current app, then install the update."
    reason.contains("INSUFFICIENT_STORAGE", true) || reason.contains("storage", true) ->
        "Not enough storage to install the update. Free up some space and try again."
    reason.contains("ABORTED", true) || reason.contains("USER", true) ->
        "Install was cancelled."
    reason.contains("INVALID", true) || reason.contains("checksum", true) ->
        "The downloaded update looked corrupted. Try again."
    else -> "Couldn't install the update. Try again."
}

/** "46% · ~1 min left · 1.4 MB/s" from the live update progress (mirrors the region picker). */
private fun downloadLine(p: AppUpdateService.UpdateProgress): String {
    val pct = (p.fraction * 100).toInt()
    val eta = p.etaSeconds?.let { " · ${RegionDownloadService.formatEta(it)} left" } ?: ""
    val rate = if (p.bytesPerSec > 0) " · ${formatUpdateRate(p.bytesPerSec)}" else ""
    return "$pct%$eta$rate"
}

private fun formatUpdateRate(bytesPerSec: Long): String {
    val mb = bytesPerSec / 1_000_000.0
    return if (mb >= 1.0) "%.1f MB/s".format(mb) else "${bytesPerSec / 1000} KB/s"
}

/** The primary CTA inside the update card: an accent-filled pill with a centered label. */
@Composable
private fun UpdatePrimaryButton(
    label: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The primary accent (orange) pairs with the theme's onPrimary; the error/green accents pair
    // with white. Choose by whether this is the brand primary.
    val onAccent = if (accent == MaterialTheme.colorScheme.primary) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        Color.White
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = onAccent,
            maxLines = 1,
            softWrap = false, // never clip the CTA mid-word — the pill sizes to fit the label
        )
    }
}

/** A failed build's message — the one thing the rebuild icon can't convey on its own. */
@Composable
private fun BuildErrorLine(state: BuildState, modifier: Modifier = Modifier) {
    if (state is BuildState.Error) {
        Text(
            state.message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier.padding(bottom = 8.dp),
        )
    }
}
