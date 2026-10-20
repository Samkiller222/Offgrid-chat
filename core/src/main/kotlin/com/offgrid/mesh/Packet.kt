package com.offgrid.mesh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Everything that travels across the mesh is a [Packet].
 *
 * Packets are signed by whoever created them ([originKey]), so relays can't forge or
 * alter them. Only [hops] changes as a packet moves from phone to phone, so it is
 * left out of the signature.
 */
class Packet(
    val type: Type,
    /** Random id, unique per packet. Used to drop duplicates. */
    val id: ByteArray,
    /** X.509-encoded public key of the phone that created the packet. */
    val originKey: ByteArray,
    /** [NodeId] of the recipient, or [BROADCAST] for everyone. */
    val destination: ByteArray,
    val createdAt: Long,
    val expiresAt: Long,
    val hops: Int,
    val maxHops: Int,
    val payload: ByteArray,
    val signature: ByteArray,
) {
    enum class Type(val code: Int) {
        /** "I exist, this is my name and key." Lets people find each other. */
        ANNOUNCE(1),
        /** A chat message. Encrypted when sent to one person, plain when broadcast. */
        MESSAGE(2),
        /** Delivery receipt. Payload is the id of the delivered MESSAGE. */
        ACK(3);

        companion object {
            fun of(code: Int) = entries.firstOrNull { it.code == code }
        }
    }

    /**
     * The packet's identity everywhere (dedup, storage, receipts): its random [id] bound to the
     * author's key. Anyone can copy someone else's [id], but not with their key, so a forged
     * packet can't shadow a real one.
     */
    val uid: ByteArray by lazy { Crypto.sha256(originKey, id).copyOf(ID_SIZE) }
    val idHex: String get() = uid.toHex()
    val originId: String by lazy { NodeId.of(originKey) }
    val destinationId: String? get() = if (isBroadcast) null else destination.toHex()
    val isBroadcast: Boolean get() = destination.contentEquals(BROADCAST)

    fun isExpired(now: Long) = now >= expiresAt

    fun withHops(newHops: Int) = Packet(
        type, id, originKey, destination, createdAt, expiresAt, newHops, maxHops, payload, signature,
    )

    /** The bytes the origin signs: everything except [hops] and the signature itself. */
    fun signedBytes(): ByteArray = ByteArrayOutputStream().also { buf ->
        DataOutputStream(buf).run {
            writeByte(VERSION)
            writeByte(type.code)
            write(id)
            writeBlob(originKey)
            write(destination)
            writeLong(createdAt)
            writeLong(expiresAt)
            writeByte(maxHops)
            writeBlob(payload)
        }
    }.toByteArray()

    fun encode(): ByteArray = ByteArrayOutputStream().also { buf ->
        DataOutputStream(buf).run {
            write(signedBytes())
            writeByte(hops)
            writeBlob(signature)
        }
    }.toByteArray()

    fun verify(): Boolean = Crypto.verify(originKey, signedBytes(), signature)

    companion object {
        const val VERSION = 1
        const val ID_SIZE = 16
        val BROADCAST = ByteArray(NodeId.SIZE)

        /** Returns null for anything malformed, truncated or from a newer protocol version. */
        fun decode(bytes: ByteArray): Packet? = try {
            DataInputStream(ByteArrayInputStream(bytes)).run {
                if (readUnsignedByte() != VERSION) return null
                val type = Type.of(readUnsignedByte()) ?: return null
                val id = readExactly(ID_SIZE)
                val originKey = readBlob()
                val destination = readExactly(NodeId.SIZE)
                val createdAt = readLong()
                val expiresAt = readLong()
                val maxHops = readUnsignedByte()
                val payload = readBlob()
                val hops = readUnsignedByte()
                val signature = readBlob()
                if (available() != 0) return null
                Packet(type, id, originKey, destination, createdAt, expiresAt, hops, maxHops, payload, signature)
            }
        } catch (e: Exception) {
            null
        }

        private fun DataOutputStream.writeBlob(b: ByteArray) {
            require(b.size <= 0xFFFF) { "blob too large" }
            writeShort(b.size)
            write(b)
        }

        private fun DataInputStream.readBlob(): ByteArray = readExactly(readUnsignedShort())

        private fun DataInputStream.readExactly(n: Int) = ByteArray(n).also { readFully(it) }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
