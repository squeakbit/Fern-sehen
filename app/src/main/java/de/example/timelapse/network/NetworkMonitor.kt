package de.example.timelapse.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors active network connectivity status using ConnectivityManager.NetworkCallback.
 */
class NetworkMonitor private constructor(context: Context) {

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
