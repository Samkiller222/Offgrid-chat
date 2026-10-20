package com.offgrid.chat

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.offgrid.mesh.LoraFragmenter
import com.offgrid.mesh.Meshtastic
import java.util.UUID
import kotlin.random.Random

/**
 * Uses a Meshtastic LoRa board (e.g. Heltec V3, 868 MHz in the EU) as a long-range radio.
 * The phone pairs with the board over Bluetooth; our mesh packets go out over LoRa as
 * Meshtastic PRIVATE_APP broadcasts and any Meshtastic node in range (ours or the public
 * Meshtastic network) floods them onward, several km per hop.
 *
 * To the router this is a single link, [LINK], with no inventory sync because LoRa airtime
 * is scarce (each 220-byte packet is ~1 s on air, and EU868 caps transmit time).
 *
 * Main-thread only. The first connection asks Android to pair: boards with a screen show
 * a PIN on it, boards without one use 123456 unless it was changed in the Meshtastic app.
 */
@SuppressLint("MissingPermission")
class LoraRadio(
    private val context: Context,
    private val events: BleTransport.Events,
) {
    companion object {
        const val LINK = "lora"
        private const val TAG = "LoraRadio"
        private val SERVICE: UUID = UUID.fromString(Meshtastic.SERVICE_UUID)
        private val TORADIO: UUID = UUID.fromString(Meshtastic.TORADIO_UUID)
        private val FROMRADIO: UUID = UUID.fromString(Meshtastic.FROMRADIO_UUID)
        private val FROMNUM: UUID = UUID.fromString(Meshtastic.FROMNUM_UUID)
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val RECONNECT_MS = 10_000L
        private const val MAX_QUEUE = 200
    }

    enum class State { OFF, CONNECTING, CONNECTED }

    /** One GATT operation; Android allows only one in flight per connection. */
    private sealed class Op {
        /** [usesTxSlot]: a mesh packet that takes a slot in the radio's transmit queue. */
        class Write(val value: ByteArray, val usesTxSlot: Boolean) : Op()
        object Read : Op()
        object Subscribe : Op()
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val fragmenter = LoraFragmenter()

    private var address: String? = null
    private var gatt: BluetoothGatt? = null
    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private var fromNum: BluetoothGattCharacteristic? = null
    private val queue = ArrayDeque<Op>()
    private var busy = false
    private var draining = false
    private var pendingNotify = false
    private var configNonce = 0
    /** Free slots in the radio's TX queue (from QueueStatus). Start cautious until it tells us. */
    private var txFree = 2

    var state = State.OFF
        private set
    var deviceName: String? = null
        private set

    /** Connect to the board at [address] and keep reconnecting until [disconnect]. */
    fun connect(address: String) {
        if (this.address == address && state != State.OFF) return
        disconnect()
        this.address = address
        open()
    }

    fun disconnect() {
        address = null
        main.removeCallbacks(reconnect)
        teardown()
    }

    /** Hand a link blob to the radio, split into LoRa-sized pieces. */
    fun send(bytes: ByteArray) {
        if (state != State.CONNECTED) return
        val pieces = try {
            fragmenter.split(bytes)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "packet too big for LoRa, dropped")
            return
        }
        if (queue.size + pieces.size > MAX_QUEUE) {
            Log.w(TAG, "radio queue full, dropping")
            return
        }
        pieces.forEach { queue.addLast(Op.Write(Meshtastic.broadcast(it, Random.nextInt()), usesTxSlot = true)) }
        pump()
    }

    // ------------------------------------------------------------------ connection

    private fun open() {
        val addr = address ?: return
        val device = runCatching { adapter?.getRemoteDevice(addr) }.getOrNull() ?: return
        deviceName = runCatching { device.name }.getOrNull() ?: addr
        state = State.CONNECTING
        Mesh.changed()
        // Meshtastic only talks to bonded phones. Pair first (Android shows the PIN dialog),
        // then connect; closing GATT mid-pairing would cancel the dialog.
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            watchBonding()
            if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond()) scheduleReconnect()
            return
        }
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) scheduleReconnect()
    }

    private fun teardown() {
        val wasUp = state == State.CONNECTED
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        toRadio = null; fromRadio = null; fromNum = null
        queue.clear()
        busy = false
        draining = false
        pendingNotify = false
        txFree = 2
        stopWatchingBonding()
        main.removeCallbacks(txRetry)
        state = State.OFF
        if (wasUp) events.onLinkDown(LINK)
        Mesh.changed()
    }

    private var bondReceiver: BroadcastReceiver? = null

    private fun watchBonding() {
        if (bondReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (d?.address != address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    BluetoothDevice.BOND_BONDED -> { stopWatchingBonding(); state = State.OFF; open() }
                    BluetoothDevice.BOND_NONE -> { stopWatchingBonding(); scheduleReconnect() }
                }
            }
        }
        bondReceiver = r
        context.registerReceiver(r, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    private fun stopWatchingBonding() {
        bondReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        bondReceiver = null
    }

    private val reconnect = Runnable { if (address != null && state == State.OFF) open() }

    private fun scheduleReconnect() {
        teardown()
        if (address != null) main.postDelayed(reconnect, RECONNECT_MS)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (!g.requestMtu(512)) g.discoverServices()
                } else {
                    Log.i(TAG, "radio disconnected ($status)")
                    scheduleReconnect()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post { if (g === gatt) g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt) return@post
                val s = g.getService(SERVICE)
                toRadio = s?.getCharacteristic(TORADIO)
                fromRadio = s?.getCharacteristic(FROMRADIO)
                fromNum = s?.getCharacteristic(FROMNUM)
                if (toRadio == null || fromRadio == null || fromNum == null) {
                    Log.w(TAG, "not a Meshtastic radio")
                    disconnect()
                    return@post
                }
                // Session start: subscribe, ask for config (which unlocks packet delivery), read it all.
                configNonce = Random.nextInt(1, Int.MAX_VALUE)
                queue.addLast(Op.Subscribe)
                queue.addLast(Op.Write(Meshtastic.wantConfig(configNonce), usesTxSlot = false))
                queue.addLast(Op.Read)
                draining = true
                pump()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post { if (g === gatt) opDone(status) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post { if (g === gatt) opDone(status) }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val value = c.value?.copyOf() ?: ByteArray(0)
            main.post { if (g === gatt) onRead(value, status) }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            main.post { if (g === gatt) onRead(value, status) }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            main.post { if (g === gatt) fromNumChanged() }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { if (g === gatt) fromNumChanged() }
        }
    }

    /** The radio has new data for us: read FromRadio until it comes back empty. */
    private fun fromNumChanged() {
        if (draining) {
            // A read is in flight; it might come back empty before this new data. Read once more after.
            pendingNotify = true
            return
        }
        draining = true
        queue.addFirst(Op.Read)
        pump()
    }

    private fun onRead(value: ByteArray, status: Int) {
        busy = false
        if (failed(status)) return
        if (status != BluetoothGatt.GATT_SUCCESS || value.isEmpty()) {
            draining = false
            if (pendingNotify) {
                pendingNotify = false
                fromNumChanged()
                return
            }
        } else {
            handle(Meshtastic.inspect(value))
            queue.addFirst(Op.Read)  // keep draining before sending more
        }
        pump()
    }

    private fun handle(msg: Meshtastic.FromRadio) {
        msg.queueFree?.let { txFree = it }
        msg.received?.let { got ->
            fragmenter.accept(got.fromNode, got.payload)?.let { events.onLinkData(LINK, it) }
        }
        if (msg.configCompleteId == configNonce.toLong() && state == State.CONNECTING) {
            state = State.CONNECTED
            events.onLinkUp(LINK)
            Mesh.changed()
        }
    }

    private fun opDone(status: Int) {
        busy = false
        if (failed(status)) return
        if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "radio op failed: $status")
        pump()
    }

    /** Auth/encryption errors mean the bond is gone or refused: start over (which re-pairs). */
    private fun failed(status: Int): Boolean {
        if (status != 5 && status != 15 && status != 137) return false
        Log.w(TAG, "radio refused us ($status), re-pairing")
        scheduleReconnect()
        return true
    }

    private fun pump() {
        val g = gatt ?: return
        if (busy) return
        val op = queue.firstOrNull() ?: return
        if (op is Op.Write && op.usesTxSlot && txFree <= 0) {
            // Radio's transmit queue is full; it sends QueueStatus as it frees up. Poll as a fallback.
            main.removeCallbacks(txRetry)
            main.postDelayed(txRetry, 3_000)
            return
        }
        queue.removeFirst()
        if (op is Op.Write && op.usesTxSlot) txFree--
        busy = true
        val started = when (op) {
            is Op.Write -> write(g, toRadio!!, op.value)
            Op.Read -> g.readCharacteristic(fromRadio)
            Op.Subscribe -> subscribe(g)
        }
        if (!started) {
            busy = false
            if (op is Op.Write && op.usesTxSlot) txFree++
            queue.addFirst(op)
            main.postDelayed({ pump() }, 100)
        }
    }

    private val txRetry = Runnable {
        if (txFree <= 0) txFree = 1
        pump()
    }

    private fun subscribe(g: BluetoothGatt): Boolean {
        val c = fromNum ?: return false
        val d = c.getDescriptor(CCCD) ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, v) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = v
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, value, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.value = value
            c.writeType = type
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    // ------------------------------------------------------------------ finding radios

    class Found(val name: String, val address: String)

    /** Scan ~10 s for Meshtastic boards nearby. [onFound] is called for each new one, on the main thread. */
    fun scan(onFound: (Found) -> Unit, onDone: () -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: return onDone()
        val seen = HashSet<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                main.post {
                    if (seen.add(d.address)) {
                        val name = result.scanRecord?.deviceName ?: runCatching { d.name }.getOrNull() ?: "Meshtastic"
                        onFound(Found(name, d.address))
                    }
                }
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, cb)
        } catch (e: Exception) {
            Log.w(TAG, "scan", e)
            return onDone()
        }
        main.postDelayed({
            runCatching { scanner.stopScan(cb) }
            onDone()
        }, 10_000)
    }
}
