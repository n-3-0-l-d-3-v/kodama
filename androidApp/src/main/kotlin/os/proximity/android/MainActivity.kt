package os.proximity.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import os.proximity.android.ui.ProximityApp
import os.proximity.android.ui.ProximityViewModel

/**
 * Hosts the UI.
 *
 * The object graph itself lives on [ProximityApplication] rather than here,
 * because the mesh has to be able to outlive this screen: when the user has
 * asked it to keep running in the background, the foreground service and the
 * UI must share one MeshManager. Two would mean two identities on the air
 * and two conflicting views of the same peers.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = appContainer

        val viewModel = ViewModelProvider(
            this,
            ProximityViewModel.Factory(
                settings = container.settings,
                engine = container.guardrailEngine,
                auditLog = container.auditLog,
                identityProvider = container.identityProvider,
                listRepository = container.listRepository,
                conversationStore = container.conversationStore,
                capabilities = container.capabilityRegistry,
                cryptoPrimitives = container.cryptoPrimitives,
                mesh = container.meshManager
            )
        )[ProximityViewModel::class.java]

        setContent {
            ProximityApp(
                viewModel = viewModel,
                requiredPermissions = requiredBlePermissions()
            )
        }
    }
}

/**
 * API 31 split Bluetooth into purpose-specific permissions. Below that,
 * scanning was gated behind location — which is exactly the conflation this
 * project dislikes, but it is what those OS versions require.
 */
private fun requiredBlePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
