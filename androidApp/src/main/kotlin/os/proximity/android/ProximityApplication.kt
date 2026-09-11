package os.proximity.android

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import os.proximity.android.data.AppSettings
import os.proximity.android.mesh.BleMeshTransport
import os.proximity.shared.capability.CapabilityRegistry
import os.proximity.shared.crypto.AndroidCryptoPrimitives
import os.proximity.shared.domain.ConversationStore
import os.proximity.shared.domain.FileConversationStore
import os.proximity.shared.files.FileDropStore
import os.proximity.shared.files.FileTransferManager
import os.proximity.shared.guardrail.DefaultGuardrailEngine
import os.proximity.shared.guardrail.FileAuditLog
import os.proximity.shared.identity.FileTrustStore
import os.proximity.shared.identity.JcaSignatureVerifier
import os.proximity.shared.identity.KeystoreDeviceIdentityProvider
import os.proximity.shared.lists.SharedListRepository
import os.proximity.shared.mesh.MeshManager
import os.proximity.shared.storage.AndroidFileStore
import os.proximity.shared.storage.AndroidKeystoreAtRestCipher
import os.proximity.shared.storage.EncryptedFileStore
import os.proximity.shared.storage.FileStore

/**
 * The object graph, written out in one readable place rather than assembled
 * by a DI framework.
 *
 * It lives on the Application rather than the Activity because the mesh has
 * to be able to outlive any single screen: a foreground service keeps it
 * running while the user is elsewhere, and that service and the UI must
 * share one `MeshManager`. Two managers would mean two identities on the
 * air and two conflicting views of the same peers.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Application-scoped: cancelled only when the process goes away. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = AppSettings(appContext)

    // Every persisted file — audit log, trust decisions, lists,
    // capabilities, file drops — passes through this before it ever
    // touches disk. AppSettings (display name, onboarding flag, which
    // policies are on) stays on plain SharedPreferences: none of it is
    // sensitive in the way the mesh's own record of who-did-what is.
    private val fileStore: FileStore = EncryptedFileStore(
        AndroidFileStore(appContext),
        AndroidKeystoreAtRestCipher()
    )

    val auditLog = FileAuditLog(fileStore)
    val trustStore = FileTrustStore(fileStore)
    val conversationStore: ConversationStore = FileConversationStore(fileStore)
    val guardrailEngine = DefaultGuardrailEngine(auditLog)
    val identityProvider = KeystoreDeviceIdentityProvider()

    // One shared instance: it is stateless except for its RNG, and QR
    // verification needs the same SHA-256 the mesh uses so a scanned key
    // hashes to exactly the device ID the handshake would produce.
    val cryptoPrimitives = AndroidCryptoPrimitives()

    val capabilityRegistry = CapabilityRegistry(
        files = fileStore,
        now = { System.currentTimeMillis() }
    )

    val listRepository = SharedListRepository(
        files = fileStore,
        deviceId = { identityProvider.getOrCreateIdentity().deviceId },
        now = { System.currentTimeMillis() }
    )

    val fileTransferManager = FileTransferManager(
        store = FileDropStore(fileStore),
        now = { System.currentTimeMillis() }
    )

    val meshManager = MeshManager(
        transport = BleMeshTransport(appContext),
        primitives = cryptoPrimitives,
        identityProvider = identityProvider,
        verifier = JcaSignatureVerifier(),
        guardrail = guardrailEngine,
        trustStore = trustStore,
        scope = scope,
        displayName = { settings.displayName.value },
        listSync = listRepository,
        capabilities = capabilityRegistry,
        fileTransfer = fileTransferManager
    )

    /**
     * Reads everything persisted. Started eagerly so a peer is not treated
     * as a stranger merely because the load had not finished yet.
     */
    fun restore() {
        scope.launch {
            trustStore.load()
            auditLog.load()
            listRepository.load()
            capabilityRegistry.load()
            fileTransferManager.load()
        }
    }
}

class ProximityApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.restore()
    }
}

/** Convenience accessor for the graph from a Context. */
val Context.appContainer: AppContainer
    get() = (applicationContext as ProximityApplication).container
