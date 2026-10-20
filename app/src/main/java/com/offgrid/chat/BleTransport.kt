package com.offgrid.chat

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
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
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.offgrid.mesh.Crypto
import com.offgrid.mesh.Framing
import com.offgrid.mesh.Transport
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Carries mesh bytes between nearby phones over Bluetooth Low Energy.
 *
 * Every phone advertises our service and scans for it at the same time. For each pair
 * of phones exactly one connection is made (the phone with the lower random token
 * connects), and that connection carries data both ways:
 *  - the connecting phone (GATT client) writes to the other's RX characteristic;
 *  - the other phone (GATT server) sends back through notifications on TX.
 *
 * Each connection shows up to the router as one link: "c:<address>" on the client side,
 * "s:<address>" on the server side. Blobs are chunked with [Framing] to fit the MTU.
 *
 * Everything runs on the main thread; Bluetooth callbacks hop onto it via [main].
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val events: Events,
) : Transport {

    interface Events {
        fun onLinkUp(link: String)
        fun onLinkDown(link: String)
        fun onLinkData(link: String, bytes: ByteArray)
    }

    companion object {
        private const val TAG = "BleTransport"
        val SERVICE_UUID: UUID = UUID.fromString("7a1c0001-5b8e-4f2a-9d3c-0ff6e1d0c4a1")
        /** Client -> server data. */
        val RX_UUID: UUID = UUID.fromString("7a1c0002-5b8e-4f2a-9d3c-0ff6e1d0c4a1")
        /** Server -> client data, via notifications. */
        val TX_UUID: UUID = UUID.fromString("7a1c0003-5b8e-4f2a-9d3c-0ff6e1d0c4a1")
        /** Client writes its token here first, so the server knows who connected. */
        val HELLO_UUID: UUID = UUID.fromString("7a1c0004-5b8e-4f2a-9d3c-0ff6e1d0c4a1")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val MAX_OUTGOING = 5
        private const val TOKEN_SIZE = 4
        /** If the lower-token phone hasn't connected to us by now, connect to it ourselves. */
        private const val FALLBACK_CONNECT_MS = 20_000L
        private const val SCAN_RESTART_MS = 10 * 60_000L
        private const val SEEN_TIMEOUT_MS = 2 * 60_000L
        private const val MAX_BACKOFF_MS = 120_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
    }

    /** Random per run. Decides who connects to whom; not an identity. */
    private val token = Crypto.randomBytes(TOKEN_SIZE)
    private val tokenInt = ByteBuffer.wrap(token).int

    private abstract inner class Link(val name: String) {
        val outbox = ArrayDeque<ByteArray>()
        var busy = false
        var ready = false
        var mtu = 23
        var streamId = 0
        val reassembler = Framing.Reassembler()
        var peerToken: Int? = null

        /** Start sending [frame]; return false if the stack refused it. */
        abstract fun sendFrame(frame: ByteArray): Boolean
        abstract fun close()
    }

    private inner class ClientLink(val gatt: BluetoothGatt, val token: Int) : Link("c:${gatt.device.address}") {
        var rx: BluetoothGattCharacteristic? = null
        val openedAt = SystemClock.elapsedRealtime()

        override fun sendFrame(frame: ByteArray): Boolean {
            val c = rx ?: return false
            return writeCharacteristic(gatt, c, frame)
        }

        override fun close() {
            gatt.disconnect()
            gatt.close()
        }
    }

    private inner class ServerLink(val device: BluetoothDevice) : Link("s:${device.address}") {
        var tx: BluetoothGattCharacteristic? = null

        override fun sendFrame(frame: ByteArray): Boolean {
            val s = server ?: return false
            val c = tx ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                s.notifyCharacteristicChanged(device, c, false, frame) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.value = frame
                @Suppress("DEPRECATION")
                s.notifyCharacteristicChanged(device, c, false)
            }
        }

        override fun close() {
            server?.cancelConnection(device)
        }
    }

    private class Sighting(var device: BluetoothDevice, var lastSeen: Long, val firstSeen: Long)

    private val main = Handler(Looper.getMainLooper())
    private val btManager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = btManager?.adapter
    private var server: BluetoothGattServer? = null
    private var running = false

    private val clientLinks = HashMap<String, ClientLink>()   // by address
    private val serverLinks = HashMap<String, ServerLink>()   // by address
    private val links = HashMap<String, Link>()                // by link name
    private val sightings = HashMap<Int, Sighting>()           // by peer token
    private val connectingTokens = HashMap<Int, Long>()        // token -> started at
    private val backoff = HashMap<Int, Pair<Long, Long>>()     // token -> (retry after, delay)

    val linkCount: Int get() = links.values.count { it.ready }

    fun start() {
        if (running) return
        if (adapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth is off")
            return
        }
        running = true
        openServer()
        startAdvertising()
        startScanning()
        main.postDelayed(housekeeping, 5_000)
        main.postDelayed(restartScan, SCAN_RESTART_MS)
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(housekeeping)
        main.removeCallbacks(restartScan)
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stop", e)
        }
        links.values.toList().forEach { dropLink(it) }
        clientLinks.values.forEach { it.close() }
        clientLinks.clear()
        serverLinks.clear()
        server?.close()
        server = null
        sightings.clear()
        connectingTokens.clear()
    }

    // ------------------------------------------------------------------ Transport

    override fun send(link: String, bytes: ByteArray) {
        val l = links[link] ?: return
        val maxFrame = (l.mtu - 3).coerceAtLeast(20)
        l.streamId = (l.streamId + 1) and 0xFFFF
        Framing.split(bytes, l.streamId, maxFrame).forEach { l.outbox.addLast(it) }
        pump(l)
    }

    private fun pump(l: Link) {
        if (l.busy || !l.ready) return
        val frame = l.outbox.removeFirstOrNull() ?: return
        l.busy = true
        if (!l.sendFrame(frame)) {
            // The stack is busy or the link died. Put it back and retry shortly;
            // a dead link gets cleaned up by its disconnect callback.
            l.outbox.addFirst(frame)
            l.busy = false
            main.postDelayed({ if (links[l.name] === l) pump(l) }, 50)
        }
    }

    private fun frameSent(l: Link?) {
        l ?: return
        l.busy = false
        pump(l)
    }

    private fun linkReady(l: Link) {
        if (l.ready) return
        l.ready = true
        links[l.name] = l
        Log.i(TAG, "link up ${l.name} mtu=${l.mtu}")
        events.onLinkUp(l.name)
        pump(l)
    }

    private fun dropLink(l: Link) {
        if (links.remove(l.name) != null && l.ready) {
            Log.i(TAG, "link down ${l.name}")
            events.onLinkDown(l.name)
        }
        l.ready = false
        l.outbox.clear()
    }

    private fun receiveFrame(l: Link, frame: ByteArray) {
        val blob = l.reassembler.accept(frame) ?: return
        events.onLinkData(l.name, blob)
    }

    // ------------------------------------------------------------------ GATT server (inbound)

    private fun openServer() {
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                RX_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                HELLO_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        val tx = BluetoothGattCharacteristic(
            TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(tx)
        server = btManager?.openGattServer(context, serverCallback)?.also { it.addService(service) }
        if (server == null) Log.e(TAG, "Could not open GATT server")
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    serverLinks.getOrPut(device.address) { ServerLink(device) }
                } else {
                    serverLinks.remove(device.address)?.let { dropLink(it) }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            main.post { serverLinks[device.address]?.mtu = mtu }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            val uuid = characteristic.uuid
            main.post {
                val l = serverLinks.getOrPut(device.address) { ServerLink(device) }
                when (uuid) {
                    HELLO_UUID -> if (value.size == TOKEN_SIZE) {
                        l.peerToken = ByteBuffer.wrap(value).int
                        // If we were also dialing them, our outgoing attempt is redundant.
                        l.peerToken?.let { connectingTokens.remove(it) }
                    }
                    RX_UUID -> receiveFrame(l, value)
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            val tx = descriptor.characteristic
            main.post {
                val l = serverLinks.getOrPut(device.address) { ServerLink(device) }
                if (enable && descriptor.uuid == CCCD_UUID) {
                    l.tx = tx
                    // The client subscribes last, after its MTU and hello are done: link is ready.
                    linkReady(l)
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            main.post { frameSent(serverLinks[device.address]) }
        }
    }

    // ------------------------------------------------------------------ discovery

    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        // A 128-bit UUID fills most of the 31-byte packet, so the token rides in the scan response.
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(SERVICE_UUID), token)
            .setIncludeDeviceName(false)
            .build()
        adapter?.bluetoothLeAdvertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
        }
    }

    private fun startScanning() {
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        try {
            adapter?.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "startScan", e)
        }
    }

    /** Android quietly downgrades scans that run for 30+ minutes, so restart periodically. */
    private val restartScan = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.w(TAG, "stopScan", e)
            }
            startScanning()
            main.postDelayed(this, SCAN_RESTART_MS)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
            if (data.size != TOKEN_SIZE) return
            val peerToken = ByteBuffer.wrap(data).int
            val device = result.device
            main.post { onSighting(peerToken, device) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    private fun onSighting(peerToken: Int, device: BluetoothDevice) {
        if (!running || peerToken == tokenInt) return
        val now = SystemClock.elapsedRealtime()
        val s = sightings.getOrPut(peerToken) { Sighting(device, now, now) }
        s.device = device
        s.lastSeen = now
        maybeConnect(peerToken, s, now)
    }

    private fun isLinked(peerToken: Int): Boolean =
        clientLinks.values.any { it.token == peerToken } ||
            serverLinks.values.any { it.peerToken == peerToken }

    private fun maybeConnect(peerToken: Int, s: Sighting, now: Long) {
        if (isLinked(peerToken) || peerToken in connectingTokens) return
        if (clientLinks.size + connectingTokens.size >= MAX_OUTGOING) return
        backoff[peerToken]?.let { (retryAt, _) -> if (now < retryAt) return }
        // Lower token dials. The higher one only dials if the other side never showed up
        // (e.g. it can't see our adverts, or it's at its connection limit).
        val myTurn = tokenInt < peerToken || now - s.firstSeen > FALLBACK_CONNECT_MS
        if (!myTurn) return
        connectingTokens[peerToken] = now
        Log.i(TAG, "connecting to ${s.device.address}")
        val gatt = s.device.connectGatt(context, false, clientCallback(peerToken), BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) connectFailed(peerToken)
    }

    private fun connectFailed(peerToken: Int) {
        connectingTokens.remove(peerToken)
        val delay = ((backoff[peerToken]?.second ?: 2_500L) * 2).coerceAtMost(MAX_BACKOFF_MS)
        backoff[peerToken] = SystemClock.elapsedRealtime() + delay to delay
    }

    /** Periodic cleanup: stuck connection attempts, stale sightings, and retrying peers we can see. */
    private val housekeeping = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            connectingTokens.filterValues { now - it > CONNECT_TIMEOUT_MS }.keys.forEach { t ->
                Log.w(TAG, "connect timeout")
                clientLinks.values.filter { it.token == t && !it.ready }.forEach { l ->
                    clientLinks.remove(l.gatt.device.address)
                    l.close()
                }
                connectFailed(t)
            }
            sightings.entries.removeAll { now - it.value.lastSeen > SEEN_TIMEOUT_MS }
            sightings.forEach { (t, s) -> maybeConnect(t, s, now) }
            main.postDelayed(this, 5_000)
        }
    }

    // ------------------------------------------------------------------ GATT client (outbound)

    private fun clientCallback(peerToken: Int) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                val address = gatt.device.address
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (!running || isLinked(peerToken)) {
                        gatt.disconnect(); gatt.close(); connectingTokens.remove(peerToken)
                        return@post
                    }
                    clientLinks[address] = ClientLink(gatt, peerToken)
                    if (!gatt.requestMtu(517)) gatt.discoverServices()
                } else {
                    val l = clientLinks.remove(address)
                    if (l != null) dropLink(l)
                    gatt.close()
                    if (l == null || !l.ready) connectFailed(peerToken) else backoff.remove(peerToken)
                    connectingTokens.remove(peerToken)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                clientLinks[gatt.device.address]?.mtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            main.post {
                val l = clientLinks[gatt.device.address] ?: return@post
                val service = gatt.getService(SERVICE_UUID)
                val rx = service?.getCharacteristic(RX_UUID)
                val hello = service?.getCharacteristic(HELLO_UUID)
                if (rx == null || hello == null || service.getCharacteristic(TX_UUID) == null) {
                    gatt.disconnect()
                    return@post
                }
                l.rx = rx
                // Setup steps run one at a time: hello -> subscribe -> ready.
                if (!writeCharacteristic(gatt, hello, token)) gatt.disconnect()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val uuid = characteristic.uuid
            main.post {
                val l = clientLinks[gatt.device.address] ?: return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    gatt.disconnect()
                    return@post
                }
                if (uuid == HELLO_UUID) subscribe(gatt) else frameSent(l)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                val l = clientLinks[gatt.device.address] ?: return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    gatt.disconnect()
                    return@post
                }
                connectingTokens.remove(peerToken)
                linkReady(l)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return  // newer overload handles it
            @Suppress("DEPRECATION")
            val value = characteristic.value?.copyOf() ?: return
            onNotified(gatt, value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onNotified(gatt, value)
        }

        private fun onNotified(gatt: BluetoothGatt, value: ByteArray) {
            main.post { clientLinks[gatt.device.address]?.let { receiveFrame(it, value) } }
        }
    }

    private fun subscribe(gatt: BluetoothGatt) {
        val tx = gatt.getService(SERVICE_UUID)?.getCharacteristic(TX_UUID)
        val cccd = tx?.getDescriptor(CCCD_UUID)
        if (tx == null || cccd == null || !gatt.setCharacteristicNotification(tx, true)) {
            gatt.disconnect()
            return
        }
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
        if (!ok) gatt.disconnect()
    }

    private fun writeCharacteristic(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(c, value, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.value = value
            c.writeType = type
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(c)
        }
    }
}
