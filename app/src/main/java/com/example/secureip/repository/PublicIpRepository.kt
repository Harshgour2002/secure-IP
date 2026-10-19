package com.example.secureip.repository

import com.example.secureip.model.PublicIpInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.experimental.xor

/**
 * Lightweight repository using STUN protocol (UDP over port 19302/3478)
 * to resolve the device's external Public IP and measure resolution latency.
 */
class PublicIpRepository {

    private val cachedInfo = AtomicReference(PublicIpInfo())

    private data class StunServerHost(val host: String, val port: Int)

    // Standard public STUN servers (RFC 5389 / RFC 3489)
    private val stunServers = listOf(
        StunServerHost("stun.l.google.com", 19302),
        StunServerHost("stun1.l.google.com", 19302),
        StunServerHost("stun.cloudflare.com", 3478),
        StunServerHost("stun.gmx.net", 3478)
    )

    fun getCachedInfo(): PublicIpInfo = cachedInfo.get()

    /**
     * Resolves public IP via STUN UDP request and calculates latency.
     */
    suspend fun fetchPublicIp(triggerReason: String): PublicIpInfo = withContext(Dispatchers.IO) {
        val currentCache = cachedInfo.get()
        cachedInfo.set(currentCache.copy(isFetching = true))

        val overallStartTime = System.currentTimeMillis()
        var resolvedIp: String? = null
        var usedServer = "None"
        var latencyMs = 0L

        for (server in stunServers) {
            val serverStartTime = System.currentTimeMillis()
            try {
                val ip = queryStunServer(server.host, server.port, timeoutMs = 2000)
                if (ip != null && isValidIpString(ip)) {
                    latencyMs = System.currentTimeMillis() - serverStartTime
                    resolvedIp = ip
                    usedServer = "${server.host}:${server.port}"
                    break
                }
            } catch (_: Exception) {
                continue
            }
        }

        val totalTimeTaken = System.currentTimeMillis() - overallStartTime
        val finalLatency = if (latencyMs > 0) latencyMs else totalTimeTaken
        val ipResult = resolvedIp ?: "STUN Timeout / Offline"
        val previousIp = currentCache.ipAddress

        val hasIpChanged = previousIp != "Unavailable" &&
                ipResult != "STUN Timeout / Offline" &&
                previousIp != ipResult

        val updatedInfo = PublicIpInfo(
            ipAddress = ipResult,
            lastFetchedTimestamp = System.currentTimeMillis(),
            triggerReason = triggerReason,
            latencyMs = finalLatency,
            stunServerUsed = usedServer,
            isFetching = false,
            hasChanged = hasIpChanged
        )

        cachedInfo.set(updatedInfo)
        updatedInfo
    }

    /**
     * Constructs and sends a STUN Binding Request (20-byte UDP packet)
     * and parses MAPPED-ADDRESS / XOR-MAPPED-ADDRESS attributes.
     */
    private fun queryStunServer(host: String, port: Int, timeoutMs: Int): String? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = timeoutMs

            val inetAddress = InetAddress.getByName(host)

            // 1. Build STUN Binding Request Header (20 bytes)
            val request = ByteArray(20)
            request[0] = 0x00 // STUN Message Type: 0x0001 (Binding Request)
            request[1] = 0x01
            request[2] = 0x00 // Message Length: 0x0000
            request[3] = 0x00

            // Magic Cookie (0x2112A442)
            request[4] = 0x21.toByte()
            request[5] = 0x12.toByte()
            request[6] = 0xA4.toByte()
            request[7] = 0x42.toByte()

            // 12-byte Transaction ID
            val transactionId = ByteArray(12)
            SecureRandom().nextBytes(transactionId)
            System.arraycopy(transactionId, 0, request, 8, 12)

            val sendPacket = DatagramPacket(request, request.size, inetAddress, port)
            socket.send(sendPacket)

            // 2. Receive Response
            val buffer = ByteArray(1024)
            val receivePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(receivePacket)

            val length = receivePacket.length
            if (length < 20) return null

            // Verify STUN Binding Response (0x0101)
            val msgType0 = buffer[0].toInt() and 0xFF
            val msgType1 = buffer[1].toInt() and 0xFF
            if (msgType0 != 0x01 || (msgType1 != 0x01 && msgType1 != 0x02)) return null

            val msgLength = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)

            // 3. Parse STUN Attributes starting at offset 20
            var offset = 20
            val maxOffset = minOf(20 + msgLength, length)

            while (offset + 4 <= maxOffset) {
                val attrType = ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
                val attrLength = ((buffer[offset + 2].toInt() and 0xFF) shl 8) or (buffer[offset + 3].toInt() and 0xFF)

                if (offset + 4 + attrLength > length) break

                // Check for XOR-MAPPED-ADDRESS (0x0020)
                if (attrType == 0x0020 && attrLength >= 8) {
                    val family = buffer[offset + 5].toInt() and 0xFF
                    if (family == 0x01) { // IPv4
                        val b0 = (buffer[offset + 8] xor 0x21.toByte()).toInt() and 0xFF
                        val b1 = (buffer[offset + 9] xor 0x12.toByte()).toInt() and 0xFF
                        val b2 = (buffer[offset + 10] xor 0xA4.toByte()).toInt() and 0xFF
                        val b3 = (buffer[offset + 11] xor 0x42.toByte()).toInt() and 0xFF
                        return "$b0.$b1.$b2.$b3"
                    }
                }

                // Check for MAPPED-ADDRESS (0x0001) as fallback
                if (attrType == 0x0001 && attrLength >= 8) {
                    val family = buffer[offset + 5].toInt() and 0xFF
                    if (family == 0x01) { // IPv4
                        val b0 = buffer[offset + 8].toInt() and 0xFF
                        val b1 = buffer[offset + 9].toInt() and 0xFF
                        val b2 = buffer[offset + 10].toInt() and 0xFF
                        val b3 = buffer[offset + 11].toInt() and 0xFF
                        return "$b0.$b1.$b2.$b3"
                    }
                }

                // Advance offset (STUN attributes are 32-bit aligned)
                val padding = (4 - (attrLength % 4)) % 4
                offset += 4 + attrLength + padding
            }

            return null
        } catch (_: Exception) {
            return null
        } finally {
            socket?.close()
        }
    }

    private fun isValidIpString(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        return ip.contains(".") || ip.contains(":")
    }

    companion object {
        fun formatTimestamp(timestampMs: Long): String {
            if (timestampMs <= 0) return "Never"
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            return sdf.format(Date(timestampMs))
        }
    }
}
