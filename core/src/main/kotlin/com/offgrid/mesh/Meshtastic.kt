package com.offgrid.mesh

import java.io.ByteArrayOutputStream

/**
 * Just enough of the Meshtastic phone API to use a Meshtastic LoRa board as a dumb radio:
 * we hand it our packets as PRIVATE_APP payloads, it broadcasts them over LoRa, and the
 * Meshtastic network (ours and anyone else's) floods them onward several km per hop.
 *
 * Hand-rolled protobuf for the handful of fields we need, so the app stays dependency-free.
 * Field numbers are from meshtastic/protobufs mesh.proto (ToRadio, FromRadio, MeshPacket, Data).
 */
object Meshtastic {
    const val SERVICE_UUID = "6ba1b218-15a8-461f-9fa8-5dcae273eafd"
    const val TORADIO_UUID = "f75c76d2-129e-4dad-a1dd-7866124401e7"
    const val FROMRADIO_UUID = "2c55e69e-4993-11ed-b878-0242ac120002"
    const val FROMNUM_UUID = "ed9da18c-a800-4f66-a670-aa7547e34453"

    const val PORT_PRIVATE_APP = 256
    const val BROADCAST_NODE = 0xFFFFFFFFL
    /** Data.payload max is 233 bytes (DATA_PAYLOAD_LEN); keep some slack. */
    const val MAX_PAYLOAD = 220

    /** A received PRIVATE_APP payload and the Meshtastic node number that sent it. */
    class Received(val fromNode: Long, val payload: ByteArray)

    /** ToRadio { want_config_id } — starts the session; the radio then streams its config. */
    fun wantConfig(nonce: Int): ByteArray = ProtoWriter().varint(3, nonce.toLong() and 0xFFFFFFFFL).bytes()

    /** ToRadio { packet: MeshPacket { to: broadcast, id, decoded: Data { portnum: PRIVATE_APP, payload } } } */
    fun broadcast(payload: ByteArray, packetId: Int): ByteArray {
        require(payload.size <= 233)
        val data = ProtoWriter().varint(1, PORT_PRIVATE_APP.toLong()).bytes(2, payload).bytes()
        val packet = ProtoWriter()
            .fixed32(2, BROADCAST_NODE)
            .message(4, data)
            .fixed32(6, packetId.toLong() and 0xFFFFFFFFL)
            .bytes()
        return ProtoWriter().message(1, packet).bytes()
    }

    /** Pulls our payload out of a FromRadio message, or null if it's anything else (config, other apps, …). */
    fun parseFromRadio(bytes: ByteArray): Received? {
        val packet = ProtoReader(bytes).fields().firstOrNull { it.number == 2 }?.bytes ?: return null
        var from = 0L
        var data: ByteArray? = null
        for (f in ProtoReader(packet).fields()) {
            when (f.number) {
                1 -> from = f.value
                4 -> data = f.bytes
            }
        }
        data ?: return null
        var port = -1L
        var payload: ByteArray? = null
        for (f in ProtoReader(data).fields()) {
            when (f.number) {
                1 -> port = f.value
                2 -> payload = f.bytes
            }
        }
        if (port != PORT_PRIVATE_APP.toLong() || payload == null) return null
        return Received(from, payload)
    }

    // ------------------------------------------------------------------ protobuf bits

    class ProtoWriter {
        private val out = ByteArrayOutputStream()

        private fun rawVarint(v: Long) {
            var x = v
            while (x and 0x7FL.inv() != 0L) {
                out.write(((x and 0x7F) or 0x80).toInt())
                x = x ushr 7
            }
            out.write(x.toInt())
        }

        private fun tag(field: Int, wire: Int) = rawVarint(((field shl 3) or wire).toLong())

        fun varint(field: Int, v: Long) = apply { tag(field, 0); rawVarint(v) }

        fun fixed32(field: Int, v: Long) = apply {
            tag(field, 5)
            for (i in 0 until 4) out.write(((v ushr (8 * i)) and 0xFF).toInt())
        }

        fun bytes(field: Int, b: ByteArray) = apply { tag(field, 2); rawVarint(b.size.toLong()); out.write(b) }

        fun message(field: Int, b: ByteArray) = bytes(field, b)

        fun bytes(): ByteArray = out.toByteArray()
    }

    class Field(val number: Int, val wire: Int, val value: Long, val bytes: ByteArray?)

    class ProtoReader(private val buf: ByteArray) {
        private var pos = 0

        private fun rawVarint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                if (pos >= buf.size || shift > 63) throw IllegalArgumentException("bad varint")
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        private fun fixed(n: Int): Long {
            if (pos + n > buf.size) throw IllegalArgumentException("truncated")
            var v = 0L
            for (i in 0 until n) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += n
            return v
        }

        /** All top-level fields. Unknown or malformed input yields what parsed cleanly before the problem. */
        fun fields(): List<Field> {
            val result = mutableListOf<Field>()
            try {
                while (pos < buf.size) {
                    val tag = rawVarint()
                    val number = (tag ushr 3).toInt()
                    when (val wire = (tag and 7).toInt()) {
                        0 -> result += Field(number, wire, rawVarint(), null)
                        1 -> result += Field(number, wire, fixed(8), null)
                        5 -> result += Field(number, wire, fixed(4), null)
                        2 -> {
                            val len = rawVarint().toInt()
                            if (len < 0 || pos + len > buf.size) throw IllegalArgumentException("truncated")
                            result += Field(number, wire, 0, buf.copyOfRange(pos, pos + len))
                            pos += len
                        }
                        else -> throw IllegalArgumentException("wire type $wire")
                    }
                }
            } catch (e: IllegalArgumentException) {
                // keep what we have
            }
            return result
        }
    }
}

/**
 * Splits link blobs into pieces that fit one LoRa packet. Unlike BLE, LoRa pieces can be
 * lost, duplicated or reordered, and several senders interleave, so pieces carry a random
 * message id and are reassembled per sender with a timeout.
 *
 * Piece layout: messageId (4 bytes) | index (u8) | count (u8) | data
 */
class LoraFragmenter(private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val HEADER = 6
        const val MAX_PIECES = 32
        const val TIMEOUT_MS = 10 * 60_000L
    }

    private class Partial(val count: Int, val startedAt: Long) {
        val pieces = arrayOfNulls<ByteArray>(count)
        val complete get() = pieces.all { it != null }
    }

    private val partials = LinkedHashMap<String, Partial>()

    fun split(blob: ByteArray, maxPiece: Int = Meshtastic.MAX_PAYLOAD): List<ByteArray> {
        val chunk = maxPiece - HEADER
        val count = maxOf(1, (blob.size + chunk - 1) / chunk)
        require(count <= MAX_PIECES) { "too big for LoRa" }
        val id = Crypto.randomBytes(4)
        return (0 until count).map { i ->
            id + byteArrayOf(i.toByte(), count.toByte()) +
                blob.copyOfRange(i * chunk, minOf(blob.size, (i + 1) * chunk))
        }
    }

    /** Returns the whole blob when its last missing piece arrives. */
    fun accept(sender: Long, piece: ByteArray): ByteArray? {
        if (piece.size < HEADER) return null
        val now = clock()
        partials.entries.removeAll { now - it.value.startedAt > TIMEOUT_MS }
        val index = piece[4].toInt() and 0xFF
        val count = piece[5].toInt() and 0xFF
        if (count == 0 || count > MAX_PIECES || index >= count) return null
        val key = "$sender:" + piece.copyOf(4).toHex()
        val p = partials.getOrPut(key) { Partial(count, now) }
        if (p.count != count) return null
        p.pieces[index] = piece.copyOfRange(HEADER, piece.size)
        if (!p.complete) return null
        partials.remove(key)
        val out = ByteArrayOutputStream()
        p.pieces.forEach { out.write(it!!) }
        while (partials.size > 200) partials.remove(partials.keys.first())
        return out.toByteArray()
    }
}
