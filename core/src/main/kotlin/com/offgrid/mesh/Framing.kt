package com.offgrid.mesh

import java.io.ByteArrayOutputStream

/**
 * Splits a byte blob into frames small enough for one radio write, and puts them back
 * together on the other side. BLE writes are capped at the negotiated MTU (as low as 20
 * bytes), so anything bigger has to be chunked.
 *
 * Frame layout: streamId (u16) | index (u16) | count (u16) | data
 */
object Framing {
    const val HEADER = 6
    const val MAX_BLOB = 64 * 1024

    fun split(blob: ByteArray, streamId: Int, maxFrameSize: Int): List<ByteArray> {
        val chunk = maxFrameSize - HEADER
        require(chunk > 0) { "frame size too small" }
        val count = maxOf(1, (blob.size + chunk - 1) / chunk)
        require(count <= 0xFFFF)
        return (0 until count).map { i ->
            val data = blob.copyOfRange(i * chunk, minOf(blob.size, (i + 1) * chunk))
            byteArrayOf(
                (streamId shr 8).toByte(), streamId.toByte(),
                (i shr 8).toByte(), i.toByte(),
                (count shr 8).toByte(), count.toByte(),
            ) + data
        }
    }

    /** One per link. Frames on a link arrive in order, but streams may be cut off by a disconnect. */
    class Reassembler {
        private var streamId = -1
        private var expectedIndex = 0
        private var count = 0
        private val buffer = ByteArrayOutputStream()

        /** Returns the whole blob once its last frame arrives, otherwise null. */
        fun accept(frame: ByteArray): ByteArray? {
            if (frame.size < HEADER) return null
            val id = u16(frame, 0)
            val index = u16(frame, 2)
            val total = u16(frame, 4)
            if (index == 0) {
                streamId = id
                count = total
                expectedIndex = 0
                buffer.reset()
            }
            if (id != streamId || index != expectedIndex || total != count) {
                // Lost or out-of-order frame: drop this stream, wait for the next one to start.
                streamId = -1
                return null
            }
            buffer.write(frame, HEADER, frame.size - HEADER)
            if (buffer.size() > MAX_BLOB) {
                streamId = -1
                return null
            }
            expectedIndex++
            if (expectedIndex < count) return null
            streamId = -1
            return buffer.toByteArray()
        }

        private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    }
}
