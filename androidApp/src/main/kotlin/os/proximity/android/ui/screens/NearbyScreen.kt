package os.proximity.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import os.proximity.android.ui.components.EmptyState
import os.proximity.android.ui.components.FingerprintText
import os.proximity.android.ui.components.SectionHeader
import os.proximity.android.ui.components.StatusDot
import os.proximity.android.ui.components.TrustBadge
import os.proximity.android.ui.theme.LocalDecisionColors
import os.proximity.shared.domain.LinkState
import os.proximity.shared.domain.Peer
import os.proximity.shared.guardrail.TrustState

@Composable
fun NearbyScreen(
    peers: List<Peer>,
    isScanning: Boolean,
    myFingerprint: String?,
    myDisplayName: String,
    onToggleScan: () -> Unit,
    onConnect: (Peer) -> Unit,
    onDisconnect: (Peer) -> Unit,
    onOpenChat: (String) -> Unit,
    onVerify: (String) -> Unit,
    onShowMyCode: () -> Unit,
    onScanCode: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            IdentityCard(
                displayName = myDisplayName,
                fingerprint = myFingerprint,
                isScanning = isScanning,
                onToggleScan = onToggleScan,
                onShowMyCode = onShowMyCode,
                onScanCode = onScanCode
            )
        }

        if (peers.isEmpty()) {
            item {
                EmptyState(
                    title = if (isScanning) "Looking for devices nearby" else "Nothing nearby yet",
                    body = if (isScanning) {
                        "Other phones running Kodama will appear here as they come into range. " +
                            "This is normal — most of the time, there won't be anyone."
                    } else {
                        "Start scanning to find other phones running Kodama within Bluetooth range."
                    },
                    actionLabel = if (isScanning) null else "Start scanning",
                    onAction = if (isScanning) null else onToggleScan
                )
            }
        } else {
            item { SectionHeader("${peers.size} device${if (peers.size == 1) "" else "s"} nearby") }
            items(peers, key = { it.transportAddress }) { peer ->
                PeerRow(
                    peer = peer,
                    onConnect = { onConnect(peer) },
                    onDisconnect = { onDisconnect(peer) },
                    onOpenChat = { peer.deviceId?.let(onOpenChat) },
                    onVerify = { peer.deviceId?.let(onVerify) }
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun IdentityCard(
    displayName: String,
    fingerprint: String?,
    isScanning: Boolean,
    onToggleScan: () -> Unit,
    onShowMyCode: () -> Unit,
    onScanCode: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "You appear as",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                displayName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            if (fingerprint != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Your verification code",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                FingerprintText(fingerprint)
                Text(
                    "Read this aloud, or let someone scan it, to prove it's really you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onShowMyCode) { Text("Show my code") }
                    OutlinedButton(onClick = onScanCode) { Text("Scan a code") }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onToggleScan) {
                    Text(if (isScanning) "Stop scanning" else "Start scanning")
                }
                if (isScanning) {
                    Spacer(Modifier.padding(horizontal = 8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun PeerRow(
    peer: Peer,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenChat: () -> Unit,
    onVerify: () -> Unit
) {
    val colors = LocalDecisionColors.current
    val statusColor = when (peer.linkState) {
        LinkState.SECURED -> colors.allow
        LinkState.CONNECTING, LinkState.HANDSHAKING -> colors.ask
        LinkState.FAILED -> colors.deny
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clickable(enabled = peer.isSecured, onClick = onOpenChat),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(statusColor)
                    Spacer(Modifier.padding(horizontal = 5.dp))
                    Text(
                        peer.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (peer.isSecured) TrustBadge(peer.trustState)
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = statusLine(peer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Only show a fingerprint once the handshake actually proved an
            // identity. Before that we have a radio address, not a person.
            if (peer.isSecured && peer.fingerprint != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Their code: ${peer.fingerprint}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    peer.isSecured -> {
                        OutlinedButton(onClick = onOpenChat) { Text("Message") }
                        if (peer.trustState != TrustState.VERIFIED) {
                            OutlinedButton(onClick = onVerify) { Text("Verify") }
                        }
                        OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                    }

                    peer.linkState == LinkState.CONNECTING ||
                        peer.linkState == LinkState.HANDSHAKING -> {
                        Text(
                            "Working…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    else -> OutlinedButton(onClick = onConnect) { Text("Connect") }
                }
            }
        }
    }
}

private fun statusLine(peer: Peer): String = when (peer.linkState) {
    LinkState.DISCOVERED -> "In range${peer.rssi?.let { " · signal $it dBm" } ?: ""}"
    LinkState.CONNECTING -> "Connecting…"
    LinkState.HANDSHAKING -> "Setting up encryption…"
    LinkState.SECURED -> "Encrypted channel open"
    LinkState.DISCONNECTED -> "Disconnected"
    LinkState.FAILED -> peer.statusDetail ?: "Couldn't connect"
}
