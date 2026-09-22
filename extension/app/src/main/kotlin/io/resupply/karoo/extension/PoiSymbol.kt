package io.resupply.karoo.extension

import io.hammerhead.karooext.models.Symbol
import io.resupply.karoo.data.Poi

/**
 * Convert a [Poi] to the karoo-ext [Symbol.POI] used both for drawing the map pin
 * ([ResupplyExtension.startMap]) and for launching navigation to it (LaunchPinDrop from
 * the app). Shared so a dropped pin is byte-for-byte the same symbol as the map pin.
 */
fun Poi.toSymbol(): Symbol.POI = Symbol.POI(
    id = id,
    lat = lat,
    lng = lng,
    type = symbolType(type),
    name = name,
    distancesAlongRoute = distancesAlongRoute,
)

/**
 * Map our POI type strings to karoo-ext [Symbol.POI.Types] constants (which
 * are lowercase). A mismatch renders the generic pin icon, so this must stay
 * aligned with the DB's `type` values.
 */
private fun symbolType(type: String): String = when (type) {
    "COFFEE" -> Symbol.POI.Types.COFFEE
    "FOOD" -> Symbol.POI.Types.FOOD
    "BAR" -> Symbol.POI.Types.BAR
    "CONVENIENCE_STORE" -> Symbol.POI.Types.CONVENIENCE_STORE
    "REST_STOP" -> Symbol.POI.Types.WATER      // drinking water → water icon
    "RESTROOM" -> Symbol.POI.Types.RESTROOM
    "BIKE_SHOP" -> Symbol.POI.Types.BIKE_SHOP
    "GAS_STATION" -> Symbol.POI.Types.GAS_STATION
    "ICE_CREAM" -> Symbol.POI.Types.FOOD       // no ice-cream pin in karoo-ext → food icon
    "LODGING" -> Symbol.POI.Types.LODGING
    "PHARMACY" -> Symbol.POI.Types.FIRST_AID   // no pharmacy pin → first-aid (medical) icon
    "ATM" -> Symbol.POI.Types.ATM
    "CAMPING" -> Symbol.POI.Types.CAMPING
    else -> Symbol.POI.Types.GENERIC
}
