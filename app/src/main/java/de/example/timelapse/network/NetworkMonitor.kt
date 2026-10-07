package de.example.timelapse.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Monitors active network connectivity status using ConnectivityManager.NetworkCallback.
 */
class NetworkMonitor private constructor(private val context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isOnline = MutableStateFlow(checkInitialConnectivity())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    init {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d("NetworkMonitor", "Network available: $network")
                    _isOnline.value = true
                }

                override fun onLost(network: Network) {
                    Log.d("NetworkMonitor", "Network lost: $network")
                    _isOnline.value = checkInitialConnectivity()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) {
                    val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    _isOnline.value = hasInternet
                }
            })
        } catch (t: Throwable) {
            Log.w("NetworkMonitor", "Failed to register network callback", t)
        }
    }

    fun isCurrentlyOnline(): Boolean {
        return checkInitialConnectivity()
    }

    /**
     * If currently offline, attempts to trigger a Wi-Fi reconnect on the system
     * and waits up to [timeoutMs] for the network connection to establish.
     * Returns true if online (or reconnected), false otherwise.
     */
    suspend fun ensureOnlineOrTryReconnect(timeoutMs: Long = 4000L): Boolean = withContext(Dispatchers.IO) {
        if (isCurrentlyOnline()) return@withContext true

        Log.i("NetworkMonitor", "Network offline: triggering Wi-Fi reconnect attempt...")
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                @Suppress("DEPRECATION")
                wifiManager.reconnect()
                @Suppress("DEPRECATION")
                wifiManager.reassociate()
            }
        } catch (t: Throwable) {
            Log.w("NetworkMonitor", "Failed to trigger wifiManager.reconnect()", t)
        }

        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isCurrentlyOnline()) {
                Log.i("NetworkMonitor", "Wi-Fi reconnected successfully after trigger!")
                return@withContext true
            }
            delay(500L)
        }

        return@withContext isCurrentlyOnline()
    }

    private fun checkInitialConnectivity(): Boolean {
        return try {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        @Volatile
        private var instance: NetworkMonitor? = null

        fun getInstance(context: Context): NetworkMonitor {
            return instance ?: synchronized(this) {
                instance ?: NetworkMonitor(context).also { instance = it }
            }
        }
    }
}
