package os.proximity.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import os.proximity.android.service.MeshForegroundService
import os.proximity.android.ui.components.Banner
import os.proximity.android.ui.screens.AuditScreen
import os.proximity.android.ui.screens.ChatScreen
import os.proximity.android.ui.screens.ConversationListScreen
import os.proximity.android.ui.screens.ListDetailScreen
import os.proximity.android.ui.screens.ListsScreen
import os.proximity.android.ui.screens.NearbyScreen
import os.proximity.android.ui.screens.OnboardingScreen
import os.proximity.android.ui.screens.PoliciesScreen
import os.proximity.android.ui.theme.ProximityTheme

private enum class Tab(val title: String, val glyph: String) {
    NEARBY("Nearby", "◎"),
    LISTS("Lists", "✓"),
    CHATS("Chats", "✉"),
    ACTIVITY("Activity", "☰"),
    RULES("Rules", "⚙")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProximityApp(
    viewModel: ProximityViewModel,
    requiredPermissions: Array<String>
) {
    ProximityTheme {
        val hasOnboarded by viewModel.hasOnboarded.collectAsState()
        val displayName by viewModel.displayName.collectAsState()

        if (!hasOnboarded) {
            OnboardingScreen(
                initialName = displayName,
                onFinish = { name ->
                    viewModel.setDisplayName(name)
                    viewModel.completeOnboarding()
                }
            )
            return@ProximityTheme
        }

        val context = LocalContext.current
        var permissionsGranted by remember {
            mutableStateOf(
                requiredPermissions.all {
                    ContextCompat.checkSelfPermission(context, it) ==
                        PackageManager.PERMISSION_GRANTED
                }
            )
        }
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results -> permissionsGranted = results.values.all { it } }

        if (!permissionsGranted) {
            PermissionScreen(onRequest = { permissionLauncher.launch(requiredPermissions) })
            return@ProximityTheme
        }

        MainScaffold(viewModel, displayName)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(viewModel: ProximityViewModel, displayName: String) {
    var tab by remember { mutableStateOf(Tab.NEARBY) }
    var openChatDeviceId by remember { mutableStateOf<String?>(null) }
    var openListId by remember { mutableStateOf<String?>(null) }

    val peers by viewModel.peers.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val auditEntries by viewModel.auditEntries.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val enabledPolicies by viewModel.enabledPolicyIds.collectAsState()
    val lists by viewModel.lists.collectAsState()
    val enabledCapabilities by viewModel.enabledCapabilities.collectAsState()
    val runInBackground by viewModel.runInBackground.collectAsState()

    // The setting is the single source of truth; the service follows it.
    // Doing it the other way round would let the service outlive the
    // preference that authorised it.
    val serviceContext = LocalContext.current

    // On Android 13+ the ongoing notification needs permission. That
    // notification *is* the disclosure that the radio is running, so it is
    // requested when the user opts in rather than treated as cosmetic.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(runInBackground) {
        if (runInBackground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    serviceContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            MeshForegroundService.start(serviceContext)
        } else {
            MeshForegroundService.stop(serviceContext)
        }
    }

    val openConversation = openChatDeviceId?.let { conversations[it] }
    val openList = openListId?.let { lists[it] }
    val isDrilledIn = openConversation != null || openList != null

    BackHandler(enabled = isDrilledIn) {
        openChatDeviceId = null
        openListId = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = openConversation?.peerLabel ?: openList?.name ?: "Kodama",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    if (isDrilledIn) {
                        TextButton(onClick = {
                            openChatDeviceId = null
                            openListId = null
                        }) { Text("Back") }
                    }
                }
            )
        },
        bottomBar = {
            if (!isDrilledIn) {
                NavigationBar {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = { Text(entry.glyph) },
                            label = { Text(entry.title) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            viewModel.banner?.let { message ->
                Banner(text = message, onDismiss = viewModel::dismissBanner)
            }

            when {
                openConversation != null -> ChatScreen(
                    conversation = openConversation,
                    onSend = { viewModel.sendMessage(openConversation.peerDeviceId, it) },
                    onVerify = { viewModel.markVerified(openConversation.peerDeviceId) }
                )

                tab == Tab.NEARBY -> NearbyScreen(
                    peers = peers,
                    isScanning = isScanning,
                    myFingerprint = viewModel.myFingerprint,
                    myDisplayName = displayName,
                    onToggleScan = viewModel::toggleScanning,
                    onConnect = viewModel::connectTo,
                    onDisconnect = viewModel::disconnect,
                    onOpenChat = { openChatDeviceId = it },
                    onVerify = viewModel::markVerified
                )

                openList != null -> ListDetailScreen(
                    list = openList,
                    onToggleItem = { itemId, done ->
                        viewModel.setListItemDone(openList.id, itemId, done)
                    },
                    onAddItem = { viewModel.addListItem(openList.id, it) },
                    onRemoveItem = { viewModel.removeListItem(openList.id, it) }
                )

                tab == Tab.LISTS -> ListsScreen(
                    lists = lists.values.sortedBy { it.name },
                    connectedPeerCount = peers.count { it.isSecured },
                    onOpen = { openListId = it },
                    onCreate = viewModel::createList
                )

                tab == Tab.CHATS -> ConversationListScreen(
                    conversations = conversations.values.toList(),
                    onOpen = { openChatDeviceId = it }
                )

                tab == Tab.ACTIVITY -> AuditScreen(entries = auditEntries)

                tab == Tab.RULES -> PoliciesScreen(
                    enabledIds = enabledPolicies,
                    enabledCapabilities = enabledCapabilities,
                    runInBackground = runInBackground,
                    displayName = displayName,
                    onToggle = viewModel::setPolicyEnabled,
                    onToggleCapability = viewModel::setCapabilityEnabled,
                    onRunInBackgroundChange = viewModel::setRunInBackground,
                    onDisplayNameChange = viewModel::setDisplayName
                )
            }
        }
    }

    viewModel.pendingDecision?.let { decision ->
        AlertDialog(
            onDismissRequest = { viewModel.resolvePendingDecision(false) },
            title = { Text("Allow this?") },
            text = {
                Column {
                    Text(decision.reason)
                    if (decision.peerFingerprint != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Their code: ${decision.peerFingerprint}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "If you weren't expecting this, say no. You can always allow it later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resolvePendingDecision(true) }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolvePendingDecision(false) }) { Text("Deny") }
            }
        )
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Bluetooth permission needed",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Kodama uses Bluetooth to find phones near you. It does not use this " +
                "for location, and nothing is shared until you approve a connection.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
            Text("Grant permission")
        }
    }
}
