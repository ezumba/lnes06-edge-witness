package com.exergynet.myapplication

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.net.wifi.aware.*
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DLTN — Decentralized Local Transceiver Network
 *
 * BLE GATT mesh transceiver. Every node simultaneously:
 *   • ADVERTISES + runs a GATT server  → it can be discovered and written to
 *   • SCANS + acts as a GATT client     → it discovers peers and pushes its outbox
 *
 * Identity handshake: the BLE advertisement only carries a MAC address, never the
 * cryptographic node-id. So on connect the client first READs the peer's node-id
 * from the shared characteristic, then delivers exactly that node's outbox via
 * length-prefixed chunked writes. Both peers do this to each other → bidirectional
 * delivery. Envelopes are self-describing (from/to node-id + ECDSA signature), so
 * the receiver routes & verifies them independently of the BLE MAC.
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

    // ── Transport state ────────────────────────────────────────────────────────
    // Known peers discovered via scan: MAC → last RSSI / device handle.
    private val knownPeers      = ConcurrentHashMap<String, BluetoothDevice>()
    private val knownRssi       = ConcurrentHashMap<String, Int>()
    // MAC → resolved node-id, learned via the identity READ handshake.
    private val peerNodeIds     = ConcurrentHashMap<String, String>()
    // MAC → last GATT-connect attempt timestamp, to throttle reconnects.
    private val lastAttemptMs   = ConcurrentHashMap<String, Long>()
    // Inbound reassembly buffers (GATT server side): MAC → accumulated bytes.
    private val rxBuffers       = ConcurrentHashMap<String, ByteArrayOutputStream>()

    // Only one outbound GATT client delivery in flight at a time (Android limits
    // concurrent client connections and serial GATT ops avoid resource churn).
    private val deliveryInFlight = AtomicBoolean(false)
    @Volatile private var deliveryStartedMs = 0L

    // ── Live diagnostics (surfaced to the UI so failures are observable) ───────
    @Volatile private var advertiseOk   = false
    @Volatile private var advertiseErr  = 0
    @Volatile private var scanStarted   = false
    @Volatile private var scanErr       = 0
    @Volatile private var lastDeliveryNote = "idle"

    private val RECONNECT_THROTTLE_MS = 4_000L
    private val FRAME_HEADER_BYTES    = 4

    // ── Public API ────────────────────────────────────────────────────────────

    fun start() {
        // Retry-safe: do NOT latch running=true until BLE actually initialises.
        // If permissions aren't granted yet, startBleAdvertise() throws
        // SecurityException; we stay not-running so a later ensureMeshStarted()
        // (after the user grants BLUETOOTH_SCAN/CONNECT) can succeed.
        if (running.get()) return
        try {
            Log.i(TAG, "[DLTN] Igniting — advertise + GATT server + continuous scan")
            startBleAdvertise()
            startContinuousScan()
            startWifiAwarePublish()
            running.set(true)
            scope.launch { deliveryWatchdog() }
            Log.i(TAG, "[DLTN] Mesh online")
        } catch (e: SecurityException) {
            Log.w(TAG, "[DLTN] BLE permissions missing — mesh deferred until granted")
        } catch (e: Exception) {
            Log.w(TAG, "[DLTN] start failed: ${e.message}")
        }
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

    // ── BLE Advertise + GATT server ──────────────────────────────────────────

    private fun startBleAdvertise() {
        val aremPayload = arem.buildAdvertisementPayload()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(serviceUuid)
            .addServiceData(serviceUuid, aremPayload)
            .setIncludeDeviceName(false)
            .build()

        try { bleAdvertiser.startAdvertising(settings, data, advertiseCallback) }
        catch (e: Exception) { Log.w(TAG, "[ADV] start failed: ${e.message}") }

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
            advertiseOk = true; advertiseErr = 0
            Log.i(TAG, "[ADV] BLE beacon active")
        }
        override fun onStartFailure(errorCode: Int) {
            advertiseOk = false; advertiseErr = errorCode
            Log.w(TAG, "[ADV] BLE advertise failed: $errorCode")
        }
    }

    // ── Continuous scan ──────────────────────────────────────────────────────

    private fun startContinuousScan() {
        val filter = ScanFilter.Builder().setServiceUuid(serviceUuid).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            bleScanner.startScan(listOf(filter), settings, scanCallback)
            scanStarted = true; scanErr = 0
            Log.i(TAG, "[SCAN] Continuous scan started (BALANCED / ALL_MATCHES)")
        } catch (e: Exception) {
            scanStarted = false
            Log.w(TAG, "[SCAN] start failed: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rssi = result.rssi
            val addr = result.device.address
            if (rssi < DLTNConstants.RSSI_GATE_DBM) return

            knownPeers[addr] = result.device
            knownRssi[addr]  = rssi
            scope.launch { maybeDeliverTo(addr) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanErr = errorCode; scanStarted = false
            Log.w(TAG, "[SCAN] failed: $errorCode")
            // SCAN_FAILED_ALREADY_STARTED (1) is benign; otherwise retry shortly.
            if (errorCode != SCAN_FAILED_ALREADY_STARTED) {
                scope.launch { delay(2000); if (running.get()) startContinuousScan() }
            }
        }
    }

    // Periodic retry: messages composed AFTER discovery still need a push, and
    // some OEMs throttle scan callbacks once a peer is "known". Every few seconds,
    // re-evaluate each known peer for pending delivery.
    private suspend fun deliveryWatchdog() {
        while (running.get()) {
            delay(5_000L)
            if (!running.get()) break
            val hasPending = try { messenger.hasPendingOutbox() } catch (_: Exception) { false }
            if (!hasPending) continue
            for (addr in knownPeers.keys) maybeDeliverTo(addr)
        }
    }

    // ── Outbound delivery (GATT client) ──────────────────────────────────────

    private suspend fun maybeDeliverTo(addr: String) {
        val now = System.currentTimeMillis()
        val last = lastAttemptMs[addr] ?: 0L
        if (now - last < RECONNECT_THROTTLE_MS) return
        if (deliveryInFlight.get()) {
            // Self-heal a stuck lock: a GATT connection that silently died (no
            // callback) must not block the mesh forever. One delivery never
            // legitimately exceeds 20s.
            if (now - deliveryStartedMs > 20_000L) {
                Log.w(TAG, "[CLIENT] stale delivery lock — forcing reset")
                deliveryInFlight.set(false)
            } else return
        }

        // Connect if we don't yet know this peer's identity, or there is mail to push.
        val identityKnown = peerNodeIds.containsKey(addr)
        val hasPending = try { messenger.hasPendingOutbox() } catch (_: Exception) { false }
        if (identityKnown && !hasPending) return

        // If identity IS known, only connect when this specific peer has mail.
        if (identityKnown) {
            val nodeId = peerNodeIds[addr]!!
            val pending = try { messenger.getPendingOutboxEnvelopes(nodeId) } catch (_: Exception) { emptyList() }
            if (pending.isEmpty()) return
        }

        val device = knownPeers[addr] ?: return
        if (!deliveryInFlight.compareAndSet(false, true)) return
        deliveryStartedMs = now
        lastAttemptMs[addr] = now
        Log.i(TAG, "[CLIENT] Connecting to $addr (identityKnown=$identityKnown)")
        try {
            device.connectGatt(context, false, GattClient(addr), BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.w(TAG, "[CLIENT] connectGatt failed: ${e.message}")
            deliveryInFlight.set(false)
        }
    }

    /** Manages one outbound GATT connection: MTU → discover → identity READ → deliver. */
    private inner class GattClient(private val addr: String) : BluetoothGattCallback() {
        private var mtuPayload = 20   // usable bytes per write (ATT MTU - 3)
        private var queue: List<Pair<String, ByteArray>> = emptyList()  // msgId → length-prefixed frame
        private var envIdx = 0
        private var chunkOff = 0
        private var targetNodeId = ""

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "[CLIENT] Connected $addr — requesting MTU")
                if (!gatt.requestMtu(512)) { gatt.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                finish(gatt)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mtuPayload = (mtu - 3).coerceAtLeast(20)
            Log.i(TAG, "[CLIENT] MTU=$mtu (payload=$mtuPayload)")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val char = gatt.getService(UUID.fromString(DLTNConstants.DLTN_BLE_SERVICE_UUID))
                ?.getCharacteristic(charUuid)
            if (char == null) { Log.w(TAG, "[CLIENT] char not found on $addr"); finish(gatt); return }
            // Identity handshake: READ peer's node-id first.
            gatt.readCharacteristic(char)
        }

        @Deprecated("Deprecated in API 33; required for minSdk 29")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int,
        ) {
            val nodeId = try { String(characteristic.value ?: ByteArray(0), Charsets.UTF_8).trim() } catch (_: Exception) { "" }
            if (nodeId.isEmpty()) { Log.w(TAG, "[CLIENT] empty identity from $addr"); finish(gatt); return }

            peerNodeIds[addr] = nodeId
            targetNodeId = nodeId
            lastDeliveryNote = "handshook ${nodeId.take(8)} @ ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}"
            Log.i(TAG, "[CLIENT] $addr identified as ${nodeId.take(8)} — loading outbox")

            scope.launch {
                messenger.upsertDiscoveredContact(nodeId, knownRssi[addr] ?: -60)
                val envelopes = messenger.getPendingOutboxEnvelopes(nodeId)
                queue = envelopes.map { (id, body) -> id to frame(body) }
                if (queue.isEmpty()) {
                    Log.i(TAG, "[CLIENT] no mail for ${nodeId.take(8)} — handshake only")
                    finish(gatt)
                } else {
                    Log.i(TAG, "[CLIENT] delivering ${queue.size} envelope(s) to ${nodeId.take(8)}")
                    envIdx = 0; chunkOff = 0
                    writeNextChunk(gatt, characteristic)
                }
            }
        }

        @Deprecated("Deprecated in API 33; required for minSdk 29")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "[CLIENT] write failed ($status) — aborting delivery to $addr")
                finish(gatt); return
            }
            val frame = queue[envIdx].second
            chunkOff += minOf(mtuPayload, frame.size - chunkOff)
            if (chunkOff >= frame.size) {
                // Envelope fully written → confirm delivered.
                val msgId = queue[envIdx].first
                scope.launch { try { messenger.markDelivered(msgId) } catch (_: Exception) {} }
                lastDeliveryNote = "delivered to ${targetNodeId.take(8)} @ ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}"
                Log.i(TAG, "[CLIENT] envelope ${msgId.take(8)} delivered to ${targetNodeId.take(8)}")
                envIdx += 1; chunkOff = 0
                if (envIdx >= queue.size) { Log.i(TAG, "[CLIENT] all mail delivered"); finish(gatt); return }
            }
            writeNextChunk(gatt, characteristic)
        }

        private fun writeNextChunk(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
            val frame = queue[envIdx].second
            val end   = minOf(chunkOff + mtuPayload, frame.size)
            val chunk = frame.copyOfRange(chunkOff, end)
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION") run { char.value = chunk; gatt.writeCharacteristic(char) }
        }

        private fun finish(gatt: BluetoothGatt) {
            try { gatt.disconnect() } catch (_: Exception) {}
            try { gatt.close() } catch (_: Exception) {}
            deliveryInFlight.set(false)
        }
    }

    /** Prefix an envelope with a 4-byte big-endian length so the receiver can deframe. */
    private fun frame(body: ByteArray): ByteArray {
        val len = body.size
        val out = ByteArray(FRAME_HEADER_BYTES + len)
        out[0] = (len ushr 24 and 0xFF).toByte()
        out[1] = (len ushr 16 and 0xFF).toByte()
        out[2] = (len ushr 8  and 0xFF).toByte()
        out[3] = (len         and 0xFF).toByte()
        System.arraycopy(body, 0, out, FRAME_HEADER_BYTES, len)
        return out
    }

    // ── Inbound (GATT server) ─────────────────────────────────────────────────

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            // Identity handshake: hand back our cryptographic node-id.
            val idBytes = messenger.localNodeId().toByteArray(Charsets.UTF_8)
            val slice = if (offset >= idBytes.size) ByteArray(0)
                        else idBytes.copyOfRange(offset, idBytes.size)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray,
        ) {
            val addr = device.address
            val buf = rxBuffers.getOrPut(addr) { ByteArrayOutputStream() }
            buf.write(value)
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            drainFrames(addr)
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                rxBuffers.remove(device.address)
            }
        }
    }

    /** Extract every complete length-prefixed frame from a peer's inbound buffer. */
    private fun drainFrames(addr: String) {
        val buf = rxBuffers[addr] ?: return
        var bytes = buf.toByteArray()
        var consumed = 0
        while (bytes.size - consumed >= FRAME_HEADER_BYTES) {
            val len = ((bytes[consumed].toInt()     and 0xFF) shl 24) or
                      ((bytes[consumed + 1].toInt() and 0xFF) shl 16) or
                      ((bytes[consumed + 2].toInt() and 0xFF) shl 8)  or
                      ( bytes[consumed + 3].toInt() and 0xFF)
            if (len <= 0 || len > 5_000_000) {           // corrupt → reset this peer's buffer
                Log.w(TAG, "[SERVER] bad frame length $len from $addr — resetting buffer")
                rxBuffers[addr] = ByteArrayOutputStream(); return
            }
            val frameEnd = consumed + FRAME_HEADER_BYTES + len
            if (bytes.size < frameEnd) break             // incomplete — wait for more chunks
            val payload = bytes.copyOfRange(consumed + FRAME_HEADER_BYTES, frameEnd)
            consumed = frameEnd
            handleInboundEnvelope(addr, payload)
        }
        // Persist any unconsumed tail back into the buffer.
        if (consumed > 0) {
            val tail = bytes.copyOfRange(consumed, bytes.size)
            val nb = ByteArrayOutputStream(); nb.write(tail)
            rxBuffers[addr] = nb
        }
    }

    private fun handleInboundEnvelope(addr: String, payload: ByteArray) {
        Log.i(TAG, "[SERVER] envelope received from $addr — ${payload.size} bytes")
        scope.launch {
            messenger.receiveRawPayload(addr, payload)
            val job = DLTNRelayJob(
                jobIdHex         = "gatt_relay_${System.currentTimeMillis()}",
                peerId           = addr,
                vectorLabel      = DLTNConstants.RELAY_VECTOR_LABEL,
                payloadHash      = sha256Hex(payload),
                payloadSizeBytes = payload.size,
            )
            onRelayComplete(job)
        }
    }

    // ── WiFi Aware (kept for capable hardware; BLE is the active path) ─────────

    private fun startWifiAwarePublish() {
        val wam = wifiAwareManager ?: return
        if (wam.isAvailable != true) return
        try {
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
                        ) { Log.i(TAG, "[WiFi Aware] Peer discovered") }
                    }, null)
                }
                override fun onAttachFailed() { Log.w(TAG, "[WiFi Aware] Attach failed") }
            }, null)
        } catch (e: Exception) { Log.w(TAG, "[WiFi Aware] publish error: ${e.message}") }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ── Diagnostics ─────────────────────────────────────────────────────────
    /** Live mesh state as JSON, for the in-app diagnostics panel. */
    fun diagnostics(): String {
        val adapterOn = try { btAdapter?.isEnabled == true } catch (_: Exception) { false }
        val peers = org.json.JSONArray()
        for ((mac, dev) in knownPeers) {
            peers.put(org.json.JSONObject().apply {
                put("mac", mac)
                put("rssi", knownRssi[mac] ?: 0)
                put("nodeId", peerNodeIds[mac] ?: "")
            })
        }
        return org.json.JSONObject().apply {
            put("running", running.get())
            put("adapterOn", adapterOn)
            put("advertising", advertiseOk)
            put("advertiseErr", advertiseErr)
            put("scanning", scanStarted)
            put("scanErr", scanErr)
            put("peerCount", knownPeers.size)
            put("peers", peers)
            put("deliveryInFlight", deliveryInFlight.get())
            put("lastDelivery", lastDeliveryNote)
            put("localNodeId", messenger.localNodeId())
        }.toString()
    }
}
