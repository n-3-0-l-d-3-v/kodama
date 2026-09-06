package os.proximity.android.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import os.proximity.shared.mesh.DiscoveredPeer
import os.proximity.shared.mesh.IncomingMessage
import os.proximity.shared.mesh.MeshTransport
import os.proximity.shared.util.currentTimeMillis

/**
 * Bluetooth LE implementation of [MeshTransport].
 *
 * Discovery is symmetric: this device advertises [SERVICE_UUID] so peers can
 * find it, and scans for the same UUID so it can find them. Once connected,
 * each device acts as a GATT *client* to write to the peer's *server*, and
 * runs its own server so the peer can write back — every device is both,
 * matching the peer-to-peer shape of the mesh.
 *
 * Two BLE realities this class exists to absorb:
 *
 * - **One write may be outstanding at a time.** Issuing another before
 *   `onCharacteristicWrite` fires silently discards it, which would corrupt
 *   any chunked message. [send] therefore serialises per peer and suspends
 *   until the stack confirms the write.
 * - **The default MTU is 23 bytes** (20 usable). We request the maximum on
 *   connect and report the result through [maxPayloadSize] so the layer
 *   above chunks correctly rather than assuming.
 *
 * Callers must hold BLUETOOTH_SCAN / ADVERTISE / CONNECT (API 31+) or
 * ACCESS_FINE_LOCATION (below) before calling [startDiscovery]; permission
 * requests belong to the UI layer.
 */
@SuppressLint("MissingPermission")
class BleMeshTransport(context: Context) : MeshTransport {

    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private val discoveredPeersState = MutableStateFlow<Map<String, DiscoveredPeer>>(emptyMap())
    override val discoveredPeers: Flow<List<DiscoveredPeer>> =
        discoveredPeersState.map { peers ->
            peers.values.sortedByDescending { it.lastSeenEpochMillis }
        }

    private val incomingChannel = Channel<IncomingMessage>(Channel.BUFFERED)
    override val incomingMessages: Flow<IncomingMessage> = incomingChannel.receiveAsFlow()

    private val links = ConcurrentHashMap<String, Link>()

    private var gattServer: BluetoothGattServer? = null
    private var scanCallback: ScanCallback? = null
    private var advertiseCallback: AdvertiseCallback? = null

    /** Per-connection state, including the write serialisation described above. */
    private class Link(val gatt: BluetoothGatt) {
        val writeMutex = Mutex()

        @Volatile
        var mtu: Int = DEFAULT_ATT_MTU

        @Volatile
        var pendingWrite: CancellableContinuation<Boolean>? = null

        @Volatile
        var characteristic: BluetoothGattCharacteristic? = null
    }

    override fun startDiscovery() {
        val scanner = adapter?.bluetoothLeScanner
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (scanner == null || advertiser == null) {
            Log.w(TAG, "Bluetooth LE unavailable; cannot start discovery")
            return
        }

        // Deferred until here rather than construction: opening a GATT server
        // requires BLUETOOTH_CONNECT, which may not be granted at startup.
        if (gattServer == null) startGattServer()

        if (scanCallback == null) {
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device
                    val peer = DiscoveredPeer(
                        transportAddress = device.address,
                        displayName = runCatching { device.name }.getOrNull(),
                        rssi = result.rssi,
                        lastSeenEpochMillis = currentTimeMillis()
                    )
                    discoveredPeersState.value =
                        discoveredPeersState.value + (peer.transportAddress to peer)
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "BLE scan failed: $errorCode")
                }
            }
            scanCallback = callback
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(),
                callback
            )
        }

        if (advertiseCallback == null) {
            val callback = object : AdvertiseCallback() {
                override fun onStartFailure(errorCode: Int) {
                    Log.w(TAG, "BLE advertise failed: $errorCode")
                }
            }
            advertiseCallback = callback
            advertiser.startAdvertising(
                AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setConnectable(true)
                    .build(),
                AdvertiseData.Builder()
                    // The device name is not included: a stable, human-chosen
                    // name in a broadcast is a tracking beacon
                    // (docs/THREAT_MODEL.md #4).
                    .setIncludeDeviceName(false)
                    .addServiceUuid(ParcelUuid(SERVICE_UUID))
                    .build(),
                callback
            )
        }
    }

    override fun stopDiscovery() {
        scanCallback?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        advertiseCallback?.let { runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        scanCallback = null
        advertiseCallback = null
    }

    override fun maxPayloadSize(peerAddress: String): Int {
        val mtu = links[peerAddress]?.mtu ?: DEFAULT_ATT_MTU
        // ATT write overhead is 3 bytes (opcode + handle).
        return (mtu - ATT_WRITE_OVERHEAD).coerceAtLeast(MIN_PAYLOAD)
    }

    override suspend fun connect(peerAddress: String): Boolean {
        links[peerAddress]?.let { return true }
        val device = adapter?.getRemoteDevice(peerAddress) ?: return false

        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : BluetoothGattCallback() {

                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                // Ask for the largest MTU before discovering
                                // services, so chunking uses the real size.
                                if (!gatt.requestMtu(PREFERRED_MTU)) gatt.discoverServices()
                            }

                            BluetoothProfile.STATE_DISCONNECTED -> {
                                cleanUp(peerAddress, gatt)
                                if (continuation.isActive) continuation.resume(false)
                            }
                        }
                    }

                    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                        links[peerAddress]?.mtu = mtu
                        pendingMtu[peerAddress] = mtu
                        gatt.discoverServices()
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            if (continuation.isActive) continuation.resume(false)
                            return
                        }
                        val characteristic = gatt.getService(SERVICE_UUID)
                            ?.getCharacteristic(INBOX_CHARACTERISTIC_UUID)
                        if (characteristic == null) {
                            // Advertised our service UUID but doesn't implement
                            // it — not a Kodama peer we can talk to.
                            if (continuation.isActive) continuation.resume(false)
                            return
                        }
                        val link = Link(gatt).apply {
                            this.characteristic = characteristic
                            this.mtu = pendingMtu[peerAddress] ?: DEFAULT_ATT_MTU
                        }
                        links[peerAddress] = link
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCharacteristicWrite(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int
                    ) {
                        // Hand the result back to whichever send() is waiting.
                        val link = links[peerAddress] ?: return
                        val waiting = link.pendingWrite
                        link.pendingWrite = null
                        waiting?.takeIf { it.isActive }
                            ?.resume(status == BluetoothGatt.GATT_SUCCESS)
                    }
                }
                val gatt = device.connectGatt(appContext, false, callback)
                continuation.invokeOnCancellation { runCatching { gatt?.close() } }
            }
        }

        if (connected != true) {
            disconnect(peerAddress)
            return false
        }
        return true
    }

    override fun disconnect(peerAddress: String) {
        links.remove(peerAddress)?.let { link ->
            link.pendingWrite?.takeIf { it.isActive }?.resume(false)
            runCatching { link.gatt.disconnect() }
            runCatching { link.gatt.close() }
        }
        pendingMtu.remove(peerAddress)
    }

    override suspend fun send(peerAddress: String, payload: ByteArray): Boolean {
        val link = links[peerAddress] ?: return false
        val characteristic = link.characteristic ?: return false

        // One outstanding write per connection; the mutex enforces it.
        return link.writeMutex.withLock {
            withTimeoutOrNull(WRITE_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine { continuation ->
                    link.pendingWrite = continuation

                    val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        link.gatt.writeCharacteristic(
                            characteristic,
                            payload,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        ) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            characteristic.writeType =
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            characteristic.value = payload
                            link.gatt.writeCharacteristic(characteristic)
                        }
                    }

                    if (!started) {
                        link.pendingWrite = null
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            } ?: false
        }
    }

    private fun cleanUp(peerAddress: String, gatt: BluetoothGatt) {
        links.remove(peerAddress)
        runCatching { gatt.close() }
    }

    private fun startGattServer() {
        val server = bluetoothManager.openGattServer(
            appContext,
            object : BluetoothGattServerCallback() {
                override fun onCharacteristicWriteRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    characteristic: BluetoothGattCharacteristic,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray
                ) {
                    if (characteristic.uuid == INBOX_CHARACTERISTIC_UUID) {
                        incomingChannel.trySend(IncomingMessage(device.address, value))
                    }
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            offset,
                            null
                        )
                    }
                }
            }
        )

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                INBOX_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
        )
        server?.addService(service)
        gattServer = server
    }

    /** MTU is reported before the Link exists, so it is parked here briefly. */
    private val pendingMtu = ConcurrentHashMap<String, Int>()

    companion object {
        private const val TAG = "BleMeshTransport"

        private const val DEFAULT_ATT_MTU = 23
        private const val PREFERRED_MTU = 517
        private const val ATT_WRITE_OVERHEAD = 3
        private const val MIN_PAYLOAD = 20
        private const val CONNECT_TIMEOUT_MILLIS = 20_000L
        private const val WRITE_TIMEOUT_MILLIS = 10_000L

        /** Identifies Kodama peers during scanning and advertising. */
        val SERVICE_UUID: UUID = UUID.fromString("7a4f9b1e-7e0e-4f2a-9b0e-1e2d3c4b5a69")

        /** Peers write framed payloads to this characteristic. */
        val INBOX_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("7a4f9b1e-7e0e-4f2a-9b0e-1e2d3c4b5a6a")
    }
}
