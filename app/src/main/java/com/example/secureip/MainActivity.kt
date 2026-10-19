package com.example.secureip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.secureip.model.LinkDetails
import com.example.secureip.model.NetworkLogEntry
import com.example.secureip.model.NetworkTransportType
import com.example.secureip.model.PublicIpInfo
import com.example.secureip.repository.PublicIpRepository
import com.example.secureip.ui.theme.SecureIPTheme
import com.example.secureip.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SecureIPTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NetworkTrackerScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun NetworkTrackerScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val activeTransport by viewModel.activeTransport.collectAsState()
    val linkDetails by viewModel.linkDetails.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val publicIpInfo by viewModel.publicIpInfo.collectAsState()
    val eventLogs by viewModel.eventLogs.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Compact Status & Active Network Header
        CompactHeaderAndNetworkCard(
            transport = activeTransport,
            linkDetails = linkDetails,
            isListening = isListening,
            onToggleListening = { viewModel.toggleListening() }
        )

        // STUN Public IP & Latency Card
        StunPublicIpCard(
            ipInfo = publicIpInfo,
            onRefresh = { viewModel.fetchPublicIp("Manual User Trigger") }
        )

        // Action Controls
        CompactActionButtonsBar(
            onSimulateHandoff = { viewModel.simulateSoftHandoff() },
            onClearLogs = { viewModel.clearLogs() }
        )

        // Maximized, Auto-Scrolling Realtime Logs Window
        ExpandedLogsSection(
            logs = eventLogs,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun CompactHeaderAndNetworkCard(
    transport: NetworkTransportType,
    linkDetails: LinkDetails,
    isListening: Boolean,
    onToggleListening: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "SecureIP STUN POC",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = Color(0xFF4ADE80).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "UDP STUN",
                            color = Color(0xFF4ADE80),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isListening) "Active" else "Paused",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isListening) Color(0xFF4ADE80) else Color(0xFF94A3B8)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Switch(
                        checked = isListening,
                        onCheckedChange = { onToggleListening() },
                        modifier = Modifier.size(height = 24.dp, width = 38.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = when (transport) {
                        NetworkTransportType.CELLULAR -> Color(0xFFF59E0B)
                        NetworkTransportType.WIFI -> Color(0xFF3B82F6)
                        NetworkTransportType.NONE -> Color(0xFFEF4444)
                        else -> Color(0xFF10B981)
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = transport.name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Text(
                    text = "Iface: ${linkDetails.interfaceName} • ${linkDetails.addresses.firstOrNull() ?: "No IP"}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color(0xFF94A3B8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun StunPublicIpCard(
    ipInfo: PublicIpInfo,
    onRefresh: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (ipInfo.hasChanged) Color(0xFF22C55E) else Color.Transparent,
        label = "BorderAnimation"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "STUN PUBLIC IP:",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF64748B),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = ipInfo.ipAddress,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (ipInfo.ipAddress.contains("Timeout") || ipInfo.ipAddress == "Offline") Color(0xFFEF4444) else Color(0xFF38BDF8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (ipInfo.isFetching) {
                        Spacer(modifier = Modifier.width(6.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = Color(0xFF38BDF8),
                            strokeWidth = 2.dp
                        )
                    }
                }

                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !ipInfo.isFetching,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(text = "STUN Query", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = Color(0xFF22C55E).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "Latency: ${ipInfo.latencyMs} ms",
                            color = Color(0xFF22C55E),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Server: ${ipInfo.stunServerUsed}",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = Color(0xFF94A3B8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = PublicIpRepository.formatTimestamp(ipInfo.lastFetchedTimestamp),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color(0xFF64748B)
                )
            }
        }
    }
}

@Composable
fun CompactActionButtonsBar(
    onSimulateHandoff: () -> Unit,
    onClearLogs: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onSimulateHandoff,
            modifier = Modifier
                .weight(1f)
                .height(34.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "⚡ Simulate Tower Handoff",
                fontSize = 11.sp,
                color = Color.Black,
                fontWeight = FontWeight.Bold
            )
        }

        OutlinedButton(
            onClick = onClearLogs,
            modifier = Modifier.height(34.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(text = "Clear Logs", fontSize = 11.sp, color = Color(0xFF94A3B8))
        }
    }
}

@Composable
fun ExpandedLogsSection(
    logs: List<NetworkLogEntry>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Automatically scroll to index 0 (newest log entry) whenever a new log arrives
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "REALTIME STUN EVENT LOGS (${logs.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Auto-scrolling Active",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = Color(0xFF4ADE80)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F172A), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No network events logged yet.",
                        color = Color(0xFF64748B),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(logs, key = { it.id }) { log ->
                        OptimizedLogItemRow(log)
                    }
                }
            }
        }
    }
}

@Composable
fun OptimizedLogItemRow(log: NetworkLogEntry) {
    val tagColor = when (log.tag) {
        "IP_CHANGE" -> Color(0xFF22C55E)
        "EVENT" -> if (log.isHighlight) Color(0xFFF59E0B) else Color(0xFF38BDF8)
        "STUN_REQ" -> Color(0xFF38BDF8)
        "STUN_RES" -> Color(0xFF10B981)
        "SIMULATION" -> Color(0xFFEC4899)
        "SYSTEM" -> Color(0xFFA855F7)
        else -> Color(0xFF64748B)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (log.isHighlight) tagColor.copy(alpha = 0.12f) else Color(0xFF1E293B).copy(alpha = 0.5f),
                RoundedCornerShape(6.dp)
            )
            .border(
                1.dp,
                if (log.isHighlight) tagColor.copy(alpha = 0.5f) else Color.Transparent,
                RoundedCornerShape(6.dp)
            )
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = tagColor.copy(alpha = 0.2f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = log.tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = tagColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Text(
                text = log.timestamp,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = Color(0xFF94A3B8)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = log.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = if (log.isHighlight) Color.White else Color(0xFFCBD5E1)
        )
    }
}
