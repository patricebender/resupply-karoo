package io.resupply.karoo.extension

import android.content.ComponentName
import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import io.resupply.karoo.data.Category
import io.resupply.karoo.data.ConfigStore
import io.resupply.karoo.data.Poi
import io.resupply.karoo.data.ResupplyRepository
import io.resupply.karoo.data.RouteState
import io.resupply.karoo.data.UpcomingPoi
import io.resupply.karoo.data.formatDetour
import io.resupply.karoo.data.formatKm
import io.resupply.karoo.data.elideName
import io.resupply.karoo.data.toRouteState
import io.resupply.karoo.data.upcomingByCategory
import io.resupply.karoo.ui.field.BuildPromptField
import io.resupply.karoo.ui.field.CategoryRow
import io.resupply.karoo.ui.field.FieldMessage
import io.resupply.karoo.ui.field.LoadRoutePromptField
import io.resupply.karoo.ui.field.OffRouteMessage
import io.resupply.karoo.ui.field.PoiCell
import io.resupply.karoo.util.navStateFlow
import io.resupply.karoo.util.streamDataFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Shared machinery for every "upcoming POIs" data field.
 *
 * All the plumbing is here — one connected stream, one progress computation, one set of
 * message states — so the concrete fields differ only in how they lay out the POIs they're
 * handed. Two subclasses today:
 *  - [UpcomingPoisDataType]: rotates/pages through all enabled categories.
 *  - [CategoryPoiDataType]: pinned to a single category.
 *
 * Everything is on-device: POIs and route length come from [ResupplyRepository], live route
 * progress from the `DISTANCE_TO_DESTINATION` stream.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
abstract class UpcomingPoisBaseDataType(
    private val karooSystem: KarooSystemService,
    private val repository: ResupplyRepository,
    private val configStore: ConfigStore,
    extension: String,
    typeId: String,
) : DataTypeImpl(extension, typeId) {

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Timber.d("startView $typeId grid=${config.gridSize} size=${config.viewSize}")

        // In the ride-profile EDITOR the field renders with preview=true. If it stays
        // tap-to-open there, tapping to select/move/delete the field launches Resupply and
        // the field can't be removed. So make the field interactive only in a live ride.
        val interactive = !config.preview

        // Custom graphical field — don't let Karoo overlay a numeric header.
        emitter.onNext(UpdateGraphicConfig(showHeader = false))

        val scope = CoroutineScope(Dispatchers.IO)

        // A rotation tick some fields page/rotate on. render ignores it when there's nothing
        // to rotate, so this never causes pointless motion; the ticker just has to be running.
        val rotation = MutableStateFlow(0L)
        scope.launch {
            var tick = 0L
            while (isActive) {
                kotlinx.coroutines.delay(ROTATE_MS)
                rotation.value = ++tick
            }
        }

        val progressFlow = karooSystem.streamDataFlow(DataType.Type.DISTANCE_TO_DESTINATION)
            .map { it as? StreamState.Streaming }

        // Live route state, so the empty field shows "Tap to build" only when a route is
        // actually loaded and "Load a route" otherwise. Combined with progress into one
        // "live" pair to keep the top-level combine at its 5-arg typed overload. Seeded with
        // Unknown so the field renders before the first nav event arrives.
        val routeFlow = karooSystem.navStateFlow().map { it.toRouteState() }
            .onStart { emit(RouteState.Unknown) }
        val liveFlow = combine(progressFlow, routeFlow) { stream, route -> stream to route }

        // MainActivity, launched when the rider taps the field (open app / "Tap to build").
        val mainActivity = ComponentName(context.packageName, MAIN_ACTIVITY_CLASS)

        val job = scope.launch {
            combine(
                repository.pois,
                repository.routeLengthMeters,
                configStore.config.map { it.enabledCategories },
                liveFlow,
                rotation,
            ) { pois, routeLen, enabled, live, tick ->
                Frame(pois, routeLen, enabled, live.first, live.second, tick)
            }.collect { f ->
                val remoteViews = glance.compose(context, DpSize.Unspecified) {
                    render(f, config, mainActivity, interactive)
                }.remoteViews
                emitter.updateView(remoteViews)
            }
        }

        emitter.setCancellable {
            Timber.d("stopView $typeId")
            job.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    private data class Frame(
        val pois: List<Poi>,
        val routeLenMeters: Double,
        val enabled: Set<Category>,
        val stream: StreamState.Streaming?,
        val routeState: RouteState,
        val rotationTick: Long,
    )

    /**
     * POIs resolved against the rider's live route progress, ready for a subclass to render.
     * The shared message states (no categories / no roadbook / off route) are handled in
     * [render] before this is built, so a subclass only sees the "we have POIs to show" case.
     */
    protected data class ResolvedPois(
        val enabled: Set<Category>,
        val upcoming: Map<Category, List<UpcomingPoi>>,
        val progressMeters: Double,
    )

    /**
     * Lay out [r] for this field. Called only once there's a roadbook and the rider is on
     * route; the shared empty/prompt/off-route states are already handled. [tick] is the
     * rotation counter (fields that don't rotate ignore it).
     */
    @androidx.compose.runtime.Composable
    protected abstract fun renderResolved(
        r: ResolvedPois,
        config: ViewConfig,
        tick: Long,
        activity: ComponentName,
        interactive: Boolean,
    )

    /**
     * The "nothing enabled" state. Default names no category ("Enable a category"); the
     * per-category field overrides it to name its own category. Note this only fires when the
     * *whole* enabled set is empty — a per-category field also handles the case where other
     * categories are enabled but not its own, inside [renderResolved].
     */
    @androidx.compose.runtime.Composable
    protected open fun renderNoCategories(activity: ComponentName, interactive: Boolean) =
        FieldMessage("Enable a category", activity, interactive)

    @androidx.compose.runtime.Composable
    private fun render(f: Frame, config: ViewConfig, mainActivity: ComponentName, interactive: Boolean) {
        if (f.enabled.isEmpty()) return renderNoCategories(mainActivity, interactive)
        // No roadbook yet: offer "Tap to build" only when a route is actually loaded to build
        // along; otherwise a "load a route" prompt that just opens the app (never a surprise
        // nearby search from a stray field tap). Once POIs exist we always show them — even
        // without a live route stream (we just measure from km 0).
        if (f.pois.isEmpty()) {
            return if (f.routeState is RouteState.Loaded) {
                BuildPromptField(mainActivity, interactive)
            } else {
                LoadRoutePromptField(mainActivity, interactive)
            }
        }

        // Live route progress, when available: route length − distance-to-destination.
        // Missing stream / no route length ⇒ progress 0 (show POIs from the start) rather
        // than falling back to the build prompt, which hid an already-built roadbook.
        val values = f.stream?.dataPoint?.values
        val toDest = values?.get(DataType.Field.DISTANCE_TO_DESTINATION)
        val progress = if (f.routeLenMeters > 0.0 && toDest != null) {
            (f.routeLenMeters - toDest).coerceIn(0.0, f.routeLenMeters)
        } else {
            0.0
        }

        // Flag a genuine mid-ride deviation (ON_ROUTE=false after real progress); before
        // the start ON_ROUTE=false just means "not joined yet" and we show POIs anyway.
        val onRoute = values?.get(DataType.Field.ON_ROUTE)?.let { it >= 0.5 } ?: true
        if (!onRoute && progress > START_GRACE_METERS) return OffRouteMessage(mainActivity, interactive)

        val upcoming = upcomingByCategory(f.pois, f.enabled, progress)
        renderResolved(
            ResolvedPois(f.enabled, upcoming, progress),
            config,
            f.rotationTick,
            mainActivity,
            interactive,
        )
    }

    /**
     * Build a [CategoryRow] of up to three [PoiCell]s. All keep the `km` unit; the UI
     * renders the unit in a smaller font on follow-ups so it fits the ~255dp width. Shared
     * by every field so their cards read identically.
     */
    protected fun rowFor(
        category: Category,
        pois: List<UpcomingPoi>,
        large: Boolean,
    ): CategoryRow {
        val cells = pois.mapIndexed { i, p ->
            PoiCell(
                distance = formatKm(p.aheadMeters),
                // Chevrons mark "further along the route": nearest bare, 2nd ›, 3rd ».
                arrow = when (i) {
                    0 -> ""
                    1 -> "›"
                    else -> "»"
                },
                detour = formatDetour(p.detourMeters).removePrefix("·").takeIf { it.isNotEmpty() },
                detourMeters = p.detourMeters,
            )
        }
        val nameMax = if (large) NAME_MAX_LARGE else NAME_MAX_SMALL
        return CategoryRow(
            category = category,
            name = elideName(pois.firstOrNull()?.name, nameMax),
            cells = cells,
        )
    }

    companion object {
        private const val MAIN_ACTIVITY_CLASS = "io.resupply.karoo.MainActivity"

        const val ROTATE_MS = 5_000L
        const val NAME_MAX_LARGE = 18
        const val NAME_MAX_SMALL = 12
        // Below this progress, treat ON_ROUTE=false as "not started yet" (rider is
        // approaching the route start) rather than a mid-ride deviation.
        private const val START_GRACE_METERS = 500.0
    }
}
