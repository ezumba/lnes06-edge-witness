package com.exergynet.myapplication

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.net.wifi.aware.*
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DLTN — Decentralized Local Transceiver Network
 *
 * Three-phase physical logic controller:
 *   Phase A: Passive BLE beacon (low-energy dormant state)
 *   Phase B: AREM-gated BLE scan (temporal + spatial constraint)
 *   Phase C: WiFi Aware bridge (Android) or BLE transfer (iOS fallback)
 */
@SuppressLint("MissingPermission")
class DLTNManager(
    private val context:         Context,
    private val minerId:         String,
    private val messenger:       DLTNMessenger,
    private val arem:            AREMSynchronizer,
    private val assa:            ASSAScavenger,
    private val onRelayComplete: (DLTNRelayJob) -> Unit,
) {
    private val TAG = "DLTNManager"

    private val running = AtomicBoolean(false)
    private val scope   = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val btManager    by lazy { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val btAdapter    by lazy { btManager.adapter }
    private val bleScanner   by lazy { btAdapter.bluetoothLeScanner }
    private val bleAdvertiser by lazy { btAdapter.bluetoothLeAdvertiser }
    private var gattServer: BluetoothGattServer? = null

    private val wifiAwareManager by lazy {
        context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    }
    private var wifiAwareSession: WifiAwareSession? = null

    private val serviceUuid = ParcelUuid(UUID.fromString(DLTNConstants.DLTN_BLE_SERVICE_UUID))
    private val charUuid    = UUID.fromString(DLTNConstants.DLTN_BLE_CHAR_UUID)

    // ── Public API ────────────────────────────────────────────────────────────

    fun start() {
        if (running.getAndSet(true)) return
        Log.i(TAG, "[DLTN] Phase A — BLE beacon igniting")
        startBleAdvertise()
        scope.launch { aremGatedScanLoop() }
        startWifiAwarePublish()
    }

    fun stop() {
        running.set(false)
        scope.cancel()
        try { bleScanner.stopScan(scanCallback) } catch (_: Exception) {}
        try { bleAdvertiser.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        gattServer?.close()
        wifiAwareSession?.close()
        Log.i(TAG, "[DLTN] Substrate torn down — MLE restored")
    }

    // ── Phase A — BLE Advertise ───────────────────────────────────────────────

    private fun startBleAdvertise() {
        val aremPayload = arem.buildAdvertisementPayload()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(serviceUuid)
            .addServiceData(serviceUuid, aremPayload)
            .setIncludeDeviceName(false)
            .build()

        bleAdvertiser.startAdvertising(settings, data, advertiseCallback)

        gattServer = btManager.openGattServer(context, gattServerCallback).also { server ->
            val service = BluetoothGattService(
                UUID.fromString(DLTNConstants.DLTN_BLE_SERVICE_UUID),
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
            val characteristic = BluetoothGattCharacteristic(
                charUuid,
                BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(characteristic)
            server.addService(service)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "[Phase A] BLE beacon active — duty cycle LOW_POWER")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "[Phase A] BLE advertise failed: $errorCode")
        }
    }

    // ── Phase B — AREM-gated BLE scan loop ───────────────────────────────────

    private suspend fun aremGatedScanLoop() = withContext(Dispatchers.IO) {
        val filter = ScanFilter.Builder().setServiceUuid(serviceUuid).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
            .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
            .build()

        while (running.get() && isActive) {
            val wait = arem.msUntilNextWindow()
            if (wait > 0) {
                Log.v(TAG, "[Phase B] AREM: ${wait}ms until next window — dormant")
                delay(wait)
            }

            if (!running.get()) break

            Log.d(TAG, "[Phase B] AREM window open — scanning")
            bleScanner.startScan(listOf(filter), settings, scanCallback)
            delay(DLTNConstants.AREM_WINDOW_MS + DLTNConstants.AREM_JITTER_TOLERANCE)
            try { bleScanner.stopScan(scanCallback) } catch (_: Exception) {}

            // Sleep remainder of cycle before next window
            delay((DLTNConstants.AREM_CYCLE_MS - DLTNConstants.AREM_WINDOW_MS).coerceAtLeast(0L))
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rssi       = result.rssi
            val deviceAddr = result.device.address

            if (rssi < DLTNConstants.RSSI_GATE_DBM) {
                Log.v(TAG, "[Phase B] Gate closed — $deviceAddr RSSI $rssi dBm")
                return
            }

            // AREM spatial gate — parse peer's advertisement payload
            val serviceData = result.scanRecord?.getServiceData(serviceUuid)
            if (serviceData != null) {
                val peerPayload = arem.parseAdvertisementPayload(serviceData)
                if (peerPayload != null && !arem.passesSpatialGate(peerPayload, rssi)) {
                    Log.d(TAG, "[Phase B] AREM spatial gate rejected $deviceAddr")
                    return
                }
            }

            Log.i(TAG, "[Phase B] Gate OPEN — $deviceAddr RSSI $rssi dBm → Phase C")
            scope.launch {
                messenger.upsertDiscoveredContact(deviceAddr, rssi)
                // Deliver any pending outbox messages
                val pending = messenger.getPendingOutboxForNode(deviceAddr)
                if (pending.isNotEmpty()) {
                    Log.i(TAG, "[Phase B] Outbox: ${pending.size} msgs queued for $deviceAddr")
                }
            }
            onPeerProximityConfirmed(deviceAddr, result.device)
        }
    }

    // ── Phase C — Bridge selection ────────────────────────────────────────────

    private fun onPeerProximityConfirmed(peerAddr: String, device: BluetoothDevice) {
        if (wifiAwareManager?.isAvailable == true) {
            Log.i(TAG, "[Phase C] WiFi Aware available — negotiating direct bridge")
            initiateWifiAwareBridge(peerAddr)
        } else {
            Log.i(TAG, "[Phase C] WiFi Aware unavailable — BLE fallback engaged")
            initiateBleTransfer(device, peerAddr)
        }
    }

    // ── WiFi Aware bridge ─────────────────────────────────────────────────────

    private fun startWifiAwarePublish() {
        val wam = wifiAwareManager ?: return
        wam.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                wifiAwareSession = session
                val config = PublishConfig.Builder()
                    .setServiceName(DLTNConstants.WIFI_AWARE_SERVICE_NAME)
                    .build()
                session.publish(config, object : DiscoverySessionCallback() {
                    override fun onServiceDiscovered(
                        peerHandle: PeerHandle,
                        serviceSpecificInfo: ByteArray?,
                        matchFilter: MutableList<ByteArray>
                    ) {
                        Log.i(TAG, "[WiFi Aware] Peer discovered via service subscription")
                    }
                }, null)
                Log.d(TAG, "[WiFi Aware] Published: ${DLTNConstants.WIFI_AWARE_SERVICE_NAME}")
            }
            override fun onAttachFailed() {
                Log.w(TAG, "[WiFi Aware] Attach failed — BLE fallback will cover iOS peers")
            }
        }, null)
    }

    private fun initiateWifiAwareBridge(peerAddr: String) {
        val wam = wifiAwareManager ?: return
        wam.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                val config = SubscribeConfig.Builder()
                    .setServiceName(DLTNConstants.WIFI_AWARE_SERVICE_NAME)
                    .build()
                session.subscribe(config, object : DiscoverySessionCallback() {
                    override fun onServiceDiscovered(
                        peerHandle: PeerHandle,
                        serviceSpecificInfo: ByteArray?,
                        matchFilter: MutableList<ByteArray>
                    ) {
                        scope.launch { openWifiAwareSocket(session, peerHandle, peerAddr) }
                    }
                }, null)
            }
            override fun onAttachFailed() {
                Log.w(TAG, "[WiFi Aware] Subscribe attach failed")
            }
        }, null)
    }

    private suspend fun openWifiAwareSocket(
        session: WifiAwareSession,
        peerHandle: PeerHandle,
        peerAddr: String,
    ) = withContext(Dispatchers.IO) {
        try {
            val serverSocket = ServerSocket(DLTNConstants.WIFI_AWARE_PORT)
            serverSocket.soTimeout = DLTNConstants.WIFI_AWARE_TIMEOUT_MS.toInt()
            Log.d(TAG, "[WiFi Aware] Listening on port ${DLTNConstants.WIFI_AWARE_PORT}")
            val socket = serverSocket.accept()

            // Deliver outbox first
            val outbox = messenger.getPendingOutboxForNode(peerAddr)
            if (outbox.isNotEmpty()) {
                val out = DataOutputStream(socket.getOutputStream())
                for (msgBytes in outbox) {
                    out.writeInt(msgBytes.size)
                    out.write(msgBytes)
                }
                out.flush()
            }

            receiveAndSettlePayload(socket, peerAddr, bridge = "WIFI_AWARE")
            socket.close()
            serverSocket.close()
        } catch (e: Exception) {
            Log.w(TAG, "[WiFi Aware] Socket error: ${e.message}")
        }
        session.close()
        Log.i(TAG, "[Phase C] WiFi Aware bridge torn down — Phase A restored")
    }

    // ── BLE fallback (iOS peers) ──────────────────────────────────────────────

    private fun initiateBleTransfer(device: BluetoothDevice, peerAddr: String) {
        device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "[BLE Fallback] Connected to $peerAddr")
                    gatt.discoverServices()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val char = gatt.getService(UUID.fromString(DLTNConstants.DLTN_BLE_SERVICE_UUID))
                    ?.getCharacteristic(charUuid) ?: return
                gatt.readCharacteristic(char)
            }

            @Deprecated("Deprecated in API 33 but needed for API 29 minSdk")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) return
                val payloadBytes = characteristic.value ?: return
                Log.i(TAG, "[BLE Fallback] Payload received — ${payloadBytes.size} bytes")

                scope.launch {
                    messenger.receiveRawPayload(peerAddr, payloadBytes)
                    val hash = sha256Hex(payloadBytes)
                    val job  = DLTNRelayJob(
                        jobIdHex         = "ble_relay_${System.currentTimeMillis()}",
                        peerId           = peerAddr,
                        vectorLabel      = DLTNConstants.RELAY_VECTOR_LABEL,
                        payloadHash      = hash,
                        payloadSizeBytes = payloadBytes.size,
                    )
                    onRelayComplete(job)
                }
                gatt.disconnect()
                gatt.close()
                Log.i(TAG, "[Phase C] BLE bridge torn down — Phase A restored")
            }
        })
    }

    // ── GATT server (inbound iOS writes) ─────────────────────────────────────

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        private val pendingWrites = mutableMapOf<String, ByteArray>()

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray,
        ) {
            val addr = device.address
            pendingWrites[addr] = (pendingWrites[addr] ?: ByteArray(0)) + value
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }

            val accumulated = pendingWrites[addr] ?: return
            if (accumulated.size >= value.size) {
                scope.launch {
                    messenger.receiveRawPayload(addr, accumulated)
                    val hash = sha256Hex(accumulated)
                    val job  = DLTNRelayJob(
                        jobIdHex         = "gatt_relay_${System.currentTimeMillis()}",
                        peerId           = addr,
                        vectorLabel      = DLTNConstants.RELAY_VECTOR_LABEL,
                        payloadHash      = hash,
                        payloadSizeBytes = accumulated.size,
                    )
                    onRelayComplete(job)
                    pendingWrites.remove(addr)
                }
            }
        }
    }

    // ── WiFi Aware payload reception ──────────────────────────────────────────

    private fun receiveAndSettlePayload(socket: Socket, peerAddr: String, bridge: String) {
        try {
            val input        = DataInputStream(socket.getInputStream())
            val length       = input.readInt()
            val payloadBytes = ByteArray(length)
            input.readFully(payloadBytes)
            Log.i(TAG, "[$bridge] Received $length bytes from $peerAddr")

            scope.launch {
                messenger.receiveRawPayload(peerAddr, payloadBytes)
                val hash = sha256Hex(payloadBytes)
                val job  = DLTNRelayJob(
                    jobIdHex         = "wifi_relay_${System.currentTimeMillis()}",
                    peerId           = peerAddr,
                    vectorLabel      = DLTNConstants.RELAY_VECTOR_LABEL,
                    payloadHash      = hash,
                    payloadSizeBytes = length,
                )
                onRelayComplete(job)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$bridge] Receive error: ${e.message}")
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
