package com.strataspent.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Tiny synchronous "is there a usable internet connection right now" check.
 * Used to route AI capture: when clearly offline we skip the slow cloud
 * timeout and go straight to the on-device Gemma model (if the user enabled
 * and downloaded it).
 */
object Connectivity {
    fun isOnline(context: Context): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
