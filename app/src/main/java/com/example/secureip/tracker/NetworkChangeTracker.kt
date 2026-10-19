package com.example.secureip.tracker

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.example.secureip.model.LinkDetails
import com.example.secureip.model.NetworkChangeEvent
import com.example.secureip.model.NetworkTransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Realtime Network Change Tracker for SDK & App consumption.
 *
 * Runs efficiently on Android 10+ (API 29+) with:
 * 1. Zero dangerous runtime permissions required (uses only ACCESS_NETWORK_STATE).
 * 2. Realtime tracking of both Hard Handoffs and Soft Handoffs (Cellular tower/link rebinds).
 * 3. Zero debounce delay for instant STUN public IP resolution on network changes.
 */
class NetworkChangeTracker(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val trackerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Realtime SharedFlow for instant actionable network change events
    private val _networkEvents = MutableSharedFlow<NetworkChangeEvent>(extraBufferCapacity = 128)
    val networkEvents: SharedFlow<NetworkChangeEvent> = _networkEvents.asSharedFlow()

    // StateFlows for current active state
    private val _activeTransport = MutableStateFlow(NetworkTransportType.NONE)
    val activeTransport: StateFlow<NetworkTransportType> = _activeTransport.asStateFlow()

    private val _currentLinkDetails = MutableStateFlow(LinkDetails())
    val currentLinkDetails: StateFlow<LinkDetails> = _currentLinkDetails.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    // Track state across callbacks
    private var lastObservedTransport = NetworkTransportType.NONE
    private var lastLinkPropertiesFingerprint: String = ""
    private var activeNetworkId: Long? = null

    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var cellularNetworkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Start monitoring network state and handoff events in real time.
     */
    fun startListening() {
        if (_isListening.value) return

        val initialTransport = getCurrentActiveTransport()
        _activeTransport.value = initialTransport
        lastObservedTransport = initialTransport

        // 1. Register callback for Default Active Network (Catches Hard Handoffs & Default changes)
        val defaultCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activeNetworkId = network.networkHandle
                val newTransport = getTransportType(network)
                val linkProps = connectivityManager.getLinkProperties(network)
                val interfaceName = linkProps?.interfaceName ?: "Unknown"

                updateLinkDetails(linkProps)

                if (lastObservedTransport != NetworkTransportType.NONE && lastObservedTransport != newTransport) {
                    val event = NetworkChangeEvent.HardHandoff(
                        previousTransport = lastObservedTransport,
                        newTransport = newTransport,
                        interfaceName = interfaceName
                    )
                    emitRealtimeEvent(event)
                } else {
                    val event = NetworkChangeEvent.DefaultNetworkAvailable(
                        transport = newTransport,
                        interfaceName = interfaceName
                    )
                    emitRealtimeEvent(event)
                }

                _activeTransport.value = newTransport
                lastObservedTransport = newTransport
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (network.networkHandle == activeNetworkId) {
                    val currentFingerprint = generateFingerprint(linkProperties)
                    if (currentFingerprint != lastLinkPropertiesFingerprint && lastLinkPropertiesFingerprint.isNotEmpty()) {
                        val transport = getTransportType(network)
                        val event = NetworkChangeEvent.SoftHandoff(
                            transport = transport,
                            interfaceName = linkProperties.interfaceName ?: "Unknown",
                            changedProperties = "LinkProperties updated (IP/Route rebind)"
                        )
                        emitRealtimeEvent(event)
                    }
                    lastLinkPropertiesFingerprint = currentFingerprint
                    updateLinkDetails(linkProperties)
                }
            }

            override fun onLost(network: Network) {
                if (network.networkHandle == activeNetworkId) {
                    _activeTransport.value = NetworkTransportType.NONE
                    lastObservedTransport = NetworkTransportType.NONE
                    _currentLinkDetails.value = LinkDetails()
                    emitRealtimeEvent(NetworkChangeEvent.NetworkLost)
                }
            }
        }

        // 2. Register specific callback for Cellular transport (Catches soft cellular tower handoffs)
        val cellularRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        val cellularCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val fingerprint = generateFingerprint(linkProperties)
                if (fingerprint != lastLinkPropertiesFingerprint && lastLinkPropertiesFingerprint.isNotEmpty()) {
                    val event = NetworkChangeEvent.SoftHandoff(
                        transport = NetworkTransportType.CELLULAR,
                        interfaceName = linkProperties.interfaceName ?: "Cellular",
                        changedProperties = "Cellular tower/link rebind detected"
                    )
                    emitRealtimeEvent(event)
                    lastLinkPropertiesFingerprint = fingerprint
                    updateLinkDetails(linkProperties)
                }
            }
        }

        try {
            connectivityManager.registerDefaultNetworkCallback(defaultCallback)
            connectivityManager.registerNetworkCallback(cellularRequest, cellularCallback)

            defaultNetworkCallback = defaultCallback
            this.cellularNetworkCallback = cellularCallback
            _isListening.value = true
        } catch (_: Exception) {
            _isListening.value = false
        }
    }

    /**
     * Stop listening and release callbacks.
     */
    fun stopListening() {
        if (!_isListening.value) return

        try {
            defaultNetworkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
            cellularNetworkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        } catch (_: Exception) {
            // Ignore unregister exceptions if already unregistered
        } finally {
            defaultNetworkCallback = null
            cellularNetworkCallback = null
            _isListening.value = false
        }
    }

    private fun emitRealtimeEvent(event: NetworkChangeEvent) {
        trackerScope.launch {
            _networkEvents.emit(event)
        }
    }

    private fun updateLinkDetails(linkProperties: LinkProperties?) {
        if (linkProperties == null) return
        val addresses = linkProperties.linkAddresses.map { it.address.hostAddress ?: "" }
            .filter { it.isNotEmpty() }
        val dns = linkProperties.dnsServers.map { it.hostAddress ?: "" }
            .filter { it.isNotEmpty() }

        val mtuValue = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            linkProperties.mtu
        } else {
            0
        }

        _currentLinkDetails.value = LinkDetails(
            interfaceName = linkProperties.interfaceName ?: "Unknown",
            addresses = addresses,
            dnsServers = dns,
            mtu = mtuValue
        )
    }

    private fun getTransportType(network: Network): NetworkTransportType {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return NetworkTransportType.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransportType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransportType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransportType.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransportType.VPN
            else -> NetworkTransportType.OTHER
        }
    }

    private fun getCurrentActiveTransport(): NetworkTransportType {
        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkTransportType.NONE
        return getTransportType(activeNetwork)
    }

    private fun generateFingerprint(lp: LinkProperties): String {
        val iface = lp.interfaceName ?: ""
        val addresses = lp.linkAddresses.joinToString(",") { it.address.hostAddress ?: "" }
        val routes = lp.routes.joinToString(",") { it.toString() }
        return "$iface|$addresses|$routes"
    }
}
