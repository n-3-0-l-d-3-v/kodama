package os.proximity.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import os.proximity.android.ui.components.EmptyState
import os.proximity.android.ui.components.SectionHeader
import os.proximity.android.ui.components.TrustBadge
import os.proximity.shared.domain.ChatMessage
import os.proximity.shared.domain.Conversation
import os.proximity.shared.domain.DeliveryState
import os.proximity.shared.domain.MessageDirection
import os.proximity.shared.guardrail.TrustState

@Composable
fun ConversationListScreen(
    conversations: List<Conversation>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (conversations.isEmpty()) {
        EmptyState(
            title = "No conversations yet",
            body = "Connect to someone from the Nearby tab and your conversation will appear here.",
            modifier = modifier
        )
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item { SectionHeader("Conversations") }
        items(conversations, key = { it.peerDeviceId }) { conversation ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { onOpen(conversation.peerDeviceId) },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            conversation.peerLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        TrustBadge(conversation.trustState)
                    }
                    conversation.lastMessage?.let { last ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            last.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    if (!conversation.isOnline) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Out of range",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatScreen(
    conversation: Conversation,
    onSend: (String) -> Unit,
    onVerify: () -> Unit,
    onScanToVerify: () -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(conversation.messages.size) {
        if (conversation.messages.isNotEmpty()) {
            listState.animateScrollToItem(conversation.messages.lastIndex)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (conversation.trustState != TrustState.VERIFIED) {
            UnverifiedNotice(
                fingerprint = conversation.peerFingerprint,
                peerLabel = conversation.peerLabel,
                onVerify = onVerify,
                onScanToVerify = onScanToVerify
            )
        }

        if (conversation.messages.isEmpty()) {
            Box(Modifier.weight(1f)) {
                EmptyState(
                    title = "No messages yet",
                    body = "Anything you send is encrypted end to end and travels " +
                        "directly between your phones — it never touches the internet."
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
            ) {
                items(conversation.messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
            }
        }

        Composer(
            value = draft,
            onValueChange = { draft = it },
            onSend = {
                onSend(draft)
                draft = ""
            }
        )
    }
}

@Composable
private fun UnverifiedNotice(
    fingerprint: String?,
    peerLabel: String,
    onVerify: () -> Unit,
    onScanToVerify: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "You haven't verified $peerLabel",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Messages are encrypted, but encryption alone can't prove who is holding " +
                    "the other phone. Compare the code below with them in person.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (fingerprint != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    fingerprint,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScanToVerify) { Text("Scan their code") }
                OutlinedButton(onClick = onVerify) { Text("It matches — verify") }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isSent = message.direction == MessageDirection.SENT
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.dp),
        horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (isSent) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSent) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append(formatTime(message.sentAtEpochMillis))
                        if (isSent) append(" · ${deliveryLabel(message.deliveryState)}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Composer(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("Message") },
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            Spacer(Modifier.padding(horizontal = 4.dp))
            Button(onClick = onSend, enabled = value.isNotBlank()) { Text("Send") }
        }
    }
}

private fun deliveryLabel(state: DeliveryState): String = when (state) {
    DeliveryState.PENDING -> "sending"
    DeliveryState.SENT -> "sent"
    DeliveryState.DELIVERED -> "delivered"
    DeliveryState.FAILED -> "failed"
}

private fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))
