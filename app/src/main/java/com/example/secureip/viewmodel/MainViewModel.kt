package com.example.secureip.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.secureip.model.LinkDetails
import com.example.secureip.model.NetworkChangeEvent
import com.example.secureip.model.NetworkLogEntry
import com.example.secureip.model.NetworkTransportType
import com.example.secureip.model.PublicIpInfo
import com.example.secureip.repository.PublicIpRepository
import com.example.secureip.tracker.NetworkChangeTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val tracker = NetworkChangeTracker(application)
    private val repository = PublicIpRepository()

    val activeTransport: StateFlow<NetworkTransportType> = tracker.activeTransport
    val linkDetails: StateFlow<LinkDetails> = tracker.currentLinkDetails
    val isListening: StateFlow<Boolean> = tracker.isListening

    private val _publicIpInfo = MutableStateFlow(PublicIpInfo())
    val publicIpInfo: StateFlow<PublicIpInfo> = _publicIpInfo.asStateFlow()

    private val _eventLogs = MutableStateFlow<List<NetworkLogEntry>>(emptyList())
    val eventLogs: StateFlow<List<NetworkLogEntry>> = _eventLogs.asStateFlow()

    init {
        // Automatically start listening on initialization
        tracker.startListening()
        addLog("SYSTEM", "Realtime STUN Network change listener active (Zero runtime permissions).", isHighlight = true)

        // Initial fetch of public IP via STUN
        fetchPublicIp("App Initialization")

        // Observe realtime network events without debounce delay
        tracker.networkEvents
            .onEach { event ->
                handleNetworkEvent(event)
            }
            .launchIn(viewModelScope)
    }

    private fun handleNetworkEvent(event: NetworkChangeEvent) {
        val triggerReason: String
        val logMessage: String

        when (event) {
            is NetworkChangeEvent.SoftHandoff -> {
                triggerReason = "Soft Handoff (${event.transport})"
                logMessage = "Soft handoff detected on ${event.transport} [Interface: ${event.interfaceName}]. ${event.changedProperties}"
            }
            is NetworkChangeEvent.HardHandoff -> {
                triggerReason = "Hard Handoff (${event.previousTransport} -> ${event.newTransport})"
                logMessage = "Hard handoff detected: Transport changed from ${event.previousTransport} to ${event.newTransport} [Interface: ${event.interfaceName}]"
            }
            is NetworkChangeEvent.DefaultNetworkAvailable -> {
                triggerReason = "Network Available (${event.transport})"
                logMessage = "Default network connected: ${event.transport} [Interface: ${event.interfaceName}]"
            }
            is NetworkChangeEvent.NetworkLost -> {
                triggerReason = "Network Lost"
                logMessage = "Default network lost. Device offline."
            }
        }

        addLog("EVENT", logMessage, isHighlight = event is NetworkChangeEvent.SoftHandoff || event is NetworkChangeEvent.HardHandoff)

        if (event !is NetworkChangeEvent.NetworkLost) {
            fetchPublicIp(triggerReason)
        } else {
            _publicIpInfo.value = PublicIpInfo(
                ipAddress = "Offline",
                lastFetchedTimestamp = System.currentTimeMillis(),
                triggerReason = triggerReason,
                isFetching = false,
                hasChanged = false
            )
        }
    }

    fun fetchPublicIp(reason: String) {
        viewModelScope.launch {
            _publicIpInfo.value = _publicIpInfo.value.copy(isFetching = true)
            addLog("STUN_REQ", "Querying STUN server over UDP... Reason: $reason")

            val result = repository.fetchPublicIp(reason)
            _publicIpInfo.value = result

            if (result.hasChanged) {
                addLog(
                    "IP_CHANGE",
                    ">>> PUBLIC IP CHANGED! IP: ${result.ipAddress} (Latency: ${result.latencyMs}ms, Server: ${result.stunServerUsed}) <<<",
                    isHighlight = true
                )
            } else {
                addLog(
                    "STUN_RES",
                    "STUN IP: ${result.ipAddress} | Latency: ${result.latencyMs}ms | Server: ${result.stunServerUsed}"
                )
            }
        }
    }

    fun toggleListening() {
        if (tracker.isListening.value) {
            tracker.stopListening()
            addLog("SYSTEM", "Network change listener paused.")
        } else {
            tracker.startListening()
            addLog("SYSTEM", "Network change listener resumed.")
        }
    }

    fun simulateSoftHandoff() {
        viewModelScope.launch {
            addLog("SIMULATION", "Simulating Soft Cellular Tower Handoff event...", isHighlight = true)
            val currentTransport = activeTransport.value
            val iface = linkDetails.value.interfaceName
            handleNetworkEvent(
                NetworkChangeEvent.SoftHandoff(
                    transport = if (currentTransport == NetworkTransportType.NONE) NetworkTransportType.CELLULAR else currentTransport,
                    interfaceName = iface,
                    changedProperties = "Simulated radio tower handover rebind"
                )
            )
        }
    }

    fun clearLogs() {
        _eventLogs.value = emptyList()
    }

    private fun addLog(tag: String, message: String, isHighlight: Boolean = false) {
        val now = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val newEntry = NetworkLogEntry(
            timestamp = now,
            tag = tag,
            message = message,
            isHighlight = isHighlight
        )
        _eventLogs.value = listOf(newEntry) + _eventLogs.value
    }
}
