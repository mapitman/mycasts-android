package com.bugzapperlabs.mycasts.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Whether the device's currently active network is cellular (issue #123), checked once at
 * playback-start time by [PlaybackMediaItemFactory.resolve] -- not watched live for the whole
 * session. A stream that's already started is allowed to keep playing through a later network
 * change (e.g. walking out of Wi-Fi range mid-episode); only the initial decision of whether to
 * start it at all is gated on the network type.
 */
fun interface NetworkTypeChecker {
    fun isActiveNetworkCellular(): Boolean
}

class AndroidNetworkTypeChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkTypeChecker {
    override fun isActiveNetworkCellular(): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
}
