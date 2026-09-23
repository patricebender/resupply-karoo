package io.resupply.karoo.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * WiFi awareness for the region picker. Region downloads use a direct (OkHttp) connection,
 * which is dramatically faster than the phone-tethered bridge — but only works on WiFi. So
 * the picker tells the rider when they're on WiFi (fast) vs not (slow), and reacts live when
 * they toggle it.
 *
 * "On WiFi" means the active network is WiFi *and* validated for internet — being associated
 * to an access point with no internet behind it shouldn't read as ready to download.
 */
object Connectivity {

    /** True right now if the active network is internet-capable WiFi. */
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Emits the current WiFi state and again on every change (rider toggles WiFi, walks out
     * of range, …). Distinct so the UI only recomposes on a real transition. Cold: the
     * callback is registered while collected and torn down on cancel.
     */
    fun wifiFlow(context: Context): Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }
        // Re-evaluate from scratch on any change — simpler and less error-prone than tracking
        // per-network deltas, and WiFi transitions are infrequent.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(isOnWifi(context)) }
            override fun onLost(network: Network) { trySend(isOnWifi(context)) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(isOnWifi(context))
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        trySend(isOnWifi(context)) // seed the current value
        cm.registerNetworkCallback(request, callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
