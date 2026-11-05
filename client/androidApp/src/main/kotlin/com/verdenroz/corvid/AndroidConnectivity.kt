package com.verdenroz.corvid

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.verdenroz.corvid.core.common.Connectivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AndroidConnectivity(context: Context) : Connectivity {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(manager.activeNetwork != null)
    override val online: StateFlow<Boolean> = _online

    init {
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _online.value = true
                }

                override fun onLost(network: Network) {
                    _online.value = false
                }
            },
        )
    }
}
