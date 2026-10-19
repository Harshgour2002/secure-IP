package com.example.secureip.model

/**
 * Represents the current active network transport type.
 */
enum class NetworkTransportType {
    CELLULAR,
    WIFI,
    ETHERNET,
    VPN,
    OTHER,
    NONE
}

/**
 * Details about the current network link properties.
 */
data class LinkDetails(
    val interfaceName: String = "Unknown",
    val addresses: List<String> = emptyList(),
    val dnsServers: List<String> = emptyList(),
    val mtu: Int = 0
)

/**
 * Sealed class representing distinct network handoff and change events.
 */
sealed class NetworkChangeEvent {
    data class HardHandoff(
        val previousTransport: NetworkTransportType,
        val newTransport: NetworkTransportType,
        val interfaceName: String
    ) : NetworkChangeEvent()

    data class SoftHandoff(
        val transport: NetworkTransportType,
        val interfaceName: String,
        val changedProperties: String
    ) : NetworkChangeEvent()

    data class DefaultNetworkAvailable(
        val transport: NetworkTransportType,
        val interfaceName: String
    ) : NetworkChangeEvent()

    data object NetworkLost : NetworkChangeEvent()
}

/**
 * Model representing cached public IP info fetched via STUN protocol.
 */
data class PublicIpInfo(
    val ipAddress: String = "Unavailable",
    val lastFetchedTimestamp: Long = 0L,
    val triggerReason: String = "Initial state",
    val latencyMs: Long = 0L,
    val stunServerUsed: String = "None",
    val isFetching: Boolean = false,
    val hasChanged: Boolean = false
)

/**
 * Event log entry for displaying live updates in the UI.
 */
data class NetworkLogEntry(
    val id: Long = System.currentTimeMillis() + (0..999).random(),
    val timestamp: String,
    val tag: String,
    val message: String,
    val isHighlight: Boolean = false
)
