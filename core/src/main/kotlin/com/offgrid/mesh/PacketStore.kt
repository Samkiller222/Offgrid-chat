package com.offgrid.mesh

import java.io.File

/**
 * Packets this phone is carrying for others (and its own, until delivered).
 * This is the "carry" half of store-and-forward: whatever is in here gets handed to
 * every new phone we meet, so messages keep moving even when no path exists right now.
 */
interface PacketStore {
    fun all(): List<Packet>
    fun get(idHex: String): Packet?
    fun put(packet: Packet)
    fun remove(idHex: String)
}

open class MemoryPacketStore(private val capacity: Int = 1000) : PacketStore {
    // Insertion order = age, so the eldest entry is the one to evict when full.
    protected val packets = LinkedHashMap<String, Packet>()

    override fun all(): List<Packet> = packets.values.toList()

    override fun get(idHex: String) = packets[idHex]

    override fun put(packet: Packet) {
        packets[packet.idHex] = packet
        while (packets.size > capacity) remove(packets.keys.first())
    }

    override fun remove(idHex: String) {
        packets.remove(idHex)
    }
}

/** Keeps one file per packet so carried messages survive the app or phone restarting. */
class FilePacketStore(private val dir: File, capacity: Int = 1000) : MemoryPacketStore(capacity) {
    init {
        dir.mkdirs()
        dir.listFiles()
            ?.sortedBy { it.lastModified() }
            ?.forEach { file ->
                val packet = Packet.decode(file.readBytes())
                if (packet == null) file.delete() else packets[packet.idHex] = packet
            }
    }

    override fun put(packet: Packet) {
        File(dir, packet.idHex).writeBytes(packet.encode())
        super.put(packet)
    }

    override fun remove(idHex: String) {
        File(dir, idHex).delete()
        super.remove(idHex)
    }
}
