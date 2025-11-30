package com.pokkzdev.pastillapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Utility class for checking network connectivity and Firebase availability
 */
object NetworkUtils {
    private const val TAG = "NetworkUtils"

    /**
     * Check if device has internet connectivity
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo?.isConnected == true
        }
    }

    /**
     * Check if device has WiFi or mobile data connection
     */
    fun hasActiveConnection(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo?.isConnected == true
        }
    }

    /**
     * Check if Firebase is likely available (has internet connection)
     * This is a simple check - actual Firebase availability would require a network request
     */
    fun isFirebaseLikelyAvailable(context: Context): Boolean {
        return hasActiveConnection(context)
    }

    /**
     * Suspend function to wait for network availability
     */
    suspend fun waitForNetwork(context: Context, timeoutMs: Long = 10000): Boolean {
        if (isNetworkAvailable(context)) {
            return true
        }

        return suspendCancellableCoroutine { continuation ->
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (isNetworkAvailable(context)) {
                        connectivityManager.unregisterNetworkCallback(this)
                        continuation.resume(true)
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    ) {
                        connectivityManager.unregisterNetworkCallback(this)
                        continuation.resume(true)
                    }
                }

                override fun onLost(network: Network) {
                    // Network lost, but we'll wait for another one
                }
            }

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()

            try {
                connectivityManager.registerNetworkCallback(request, networkCallback)
                
                // Set timeout
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        connectivityManager.unregisterNetworkCallback(networkCallback)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error unregistering network callback", e)
                    }
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }, timeoutMs)
            } catch (e: Exception) {
                Log.e(TAG, "Error registering network callback", e)
                continuation.resume(false)
            }

            continuation.invokeOnCancellation {
                try {
                    connectivityManager.unregisterNetworkCallback(networkCallback)
                } catch (e: Exception) {
                    Log.e(TAG, "Error unregistering network callback on cancellation", e)
                }
            }
        }
    }
}


