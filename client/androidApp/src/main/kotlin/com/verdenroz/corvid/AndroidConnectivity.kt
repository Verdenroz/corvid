package com.verdenroz.corvid

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.verdenroz.corvid.core.common.Connectivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal fun isUsable(
    hasInternet: Boolean,
    validated: Boolean,
) = hasInternet && validated

private fun NetworkCapabilities?.isUsable() =
    this != null &&
        isUsable(hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))

class AndroidConnectivity(context: Context) : Connectivity {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(manager.getNetworkCapabilities(manager.activeNetwork).isUsable())
    override val online: StateFlow<Boolean> = _online
    override val eventDriven = true

    init {
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    _online.value = capabilities.isUsable()
                }

                override fun onLost(network: Network) {
                    _online.value = false
                }
            },
        )
    }
}
