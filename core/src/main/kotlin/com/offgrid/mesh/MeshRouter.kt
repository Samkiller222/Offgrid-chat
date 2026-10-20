package com.offgrid.mesh

import java.io.ByteArrayOutputStream

/** Moves bytes to a neighbouring phone over some radio (Bluetooth today, LoRa later). */
fun interface Transport {
    fun send(link: String, bytes: ByteArray)
}

data class Contact(val id: String, val name: String, val publicKey: ByteArray, val lastSeen: Long)

data class ChatMessage(
    val id: String,
    val fromId: String,
    val fromName: String,
    /** null means a public message to everyone nearby. */
    val toId: String?,
    val text: String,
    val sentAt: Long,
    val hops: Int,
    val outgoing: Boolean,
)

interface MeshListener {
    fun onMessage(message: ChatMessage) {}
    fun onDelivered(messageId: String) {}
    fun onContact(contact: Contact) {}
}

/**
 * The store-and-forward mesh. Transport-agnostic and free of Android code so it can be
 * unit tested on a laptop.
 *
 * How a message gets from A to Z with no internet:
 *  1. A signs the message (and encrypts it for Z) and hands it to every phone in range.
 *  2. Every phone that receives it keeps a copy and passes it to every phone in range.
 *  3. When two phones meet, they swap lists of what they carry and fill each other's gaps.
 *     So a message can ride along in someone's pocket and jump forward when they meet
 *     the next phone, even if there was never a complete chain of phones at one moment.
 *  4. When Z gets it, Z sends back a signed receipt (ACK). Phones that see the receipt
 *     drop their copy of the message, and A marks it delivered.
 *
 * Link messages (the unit handed to [Transport]):
 *   0x01 + packet      a packet
 *   0x02 + n * 8 bytes inventory: short ids of every packet the sender carries
 *
 * Not thread-safe by design: call everything from one thread.
 */
class MeshRouter(
    private val identity: Identity,
    private val store: PacketStore,
    private val listener: MeshListener,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val MESSAGE_LIFETIME = 72 * HOUR
        const val ANNOUNCE_LIFETIME = 24 * HOUR
        const val MAX_LIFETIME = 7 * 24 * HOUR
        const val MAX_CLOCK_SKEW = HOUR
        const val MESSAGE_MAX_HOPS = 64
        const val ANNOUNCE_MAX_HOPS = 16
        /** Public messages are for the people around you, so they don't flood the whole country. */
        const val BROADCAST_LIFETIME = 12 * HOUR
        const val BROADCAST_MAX_HOPS = 10
        const val MAX_TEXT_BYTES = 4000
        private const val LINK_PACKET: Byte = 1
        private const val LINK_INVENTORY: Byte = 2
        private const val SHORT_ID = 8
        private const val MAX_SEEN = 10_000
        private const val MAX_HANDLED = 2000
        const val PACKETS_PER_HOUR = 120
        const val ANNOUNCES_PER_HOUR = 6
        private val PRIORITY = listOf(Packet.Type.ACK, Packet.Type.MESSAGE, Packet.Type.ANNOUNCE)
    }

    var transport: Transport? = null

    /** Per-link filter, e.g. to keep chatty announcements off a LoRa link. */
    var shouldSend: (link: String, packet: Packet) -> Boolean = { _, _ -> true }

    private val links = LinkedHashSet<String>()
    private val seen = LinkedHashSet<String>()
    private val delivered = LinkedHashSet<String>()     // message ids we've seen an ACK for
    private val contacts = HashMap<String, Contact>()
    private val handled = ArrayDeque<ByteArray>()
    private val rateCounts = LinkedHashMap<String, Int>()

    val myId: String get() = identity.id
    val myPublicKey: ByteArray get() = identity.publicEncoded
    val linkCount: Int get() = links.size

    init {
        val now = clock()
        for (packet in store.all()) {
            if (packet.isExpired(now)) {
                store.remove(packet.idHex)
                continue
            }
            markSeen(packet.idHex)
            if (packet.type == Packet.Type.ANNOUNCE) learnContact(packet)
            if (packet.type == Packet.Type.ACK) delivered.add(deliveryKey(packet.payload.toHex(), packet.originId))
        }
    }

    fun contacts(): List<Contact> = contacts.values.sortedByDescending { it.lastSeen }

    fun contact(id: String): Contact? = contacts[id]

    /** Restore a contact saved from an earlier run, so we can still message them after their announcement expires. */
    fun rememberContact(contact: Contact) {
        if (contact.id != NodeId.of(contact.publicKey) || contact.id == myId) return
        val known = contacts[contact.id]
        if (known == null || known.lastSeen < contact.lastSeen) contacts[contact.id] = contact
    }

    /** Packets currently being carried. Exposed for the status screen and tests. */
    fun carriedCount(): Int = store.all().size

    // ------------------------------------------------------------------ links

    /**
     * A neighbour became reachable. [sync] swaps inventories so each side gets what it missed;
     * turn it off for slow broadcast radios (LoRa) where that would eat the airtime budget.
     */
    fun linkUp(link: String, sync: Boolean = true) {
        if (!links.add(link)) return
        if (sync) sendInventory(link)
    }

    fun linkDown(link: String) {
        links.remove(link)
    }

    fun onLinkData(link: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val body = bytes.copyOfRange(1, bytes.size)
        when (bytes[0]) {
            LINK_PACKET -> Packet.decode(body)?.let { receive(it, fromLink = link) }
            LINK_INVENTORY -> onInventory(link, body)
        }
    }

    private fun sendInventory(link: String) {
        pruneExpired()
        val out = ByteArrayOutputStream()
        out.write(LINK_INVENTORY.toInt())
        store.all().forEach { out.write(it.id, 0, SHORT_ID) }
        // Also list what we handled but don't carry (delivered to us, or at its hop limit),
        // so neighbours don't resend it every time we meet.
        handled.forEach { out.write(it, 0, SHORT_ID) }
        transport?.send(link, out.toByteArray())
    }

    /** They told us what they have; send them everything we carry that they're missing. */
    private fun onInventory(link: String, body: ByteArray) {
        val theirs = HashSet<String>()
        for (i in 0 until body.size / SHORT_ID) {
            theirs.add(body.copyOfRange(i * SHORT_ID, (i + 1) * SHORT_ID).toHex())
        }
        val now = clock()
        store.all()
            .filter { !it.isExpired(now) && it.id.copyOf(SHORT_ID).toHex() !in theirs }
            // Receipts first (they free up space everywhere), then messages, then announcements.
            .sortedBy { PRIORITY.indexOf(it.type) }
            .forEach { sendPacket(link, it) }
    }

    private fun sendPacket(link: String, packet: Packet) {
        if (!shouldSend(link, packet)) return
        transport?.send(link, byteArrayOf(LINK_PACKET) + packet.encode())
    }

    // ------------------------------------------------------------------ sending

    /** Tell the mesh who we are, so others can find us by name and message us. */
    fun announce(name: String) {
        val now = clock()
        // Replace our previous announcement rather than piling them up.
        store.all().filter { it.type == Packet.Type.ANNOUNCE && it.originId == myId }
            .forEach { store.remove(it.idHex) }
        originate(Packet.Type.ANNOUNCE, Packet.BROADCAST, name.toByteArray(), now + ANNOUNCE_LIFETIME, ANNOUNCE_MAX_HOPS)
    }

    /**
     * Send [text] to [toId], or to everyone if [toId] is null.
     * Direct messages need the recipient's key, which we learn from their announcement.
     */
    fun sendText(text: String, myName: String, toId: String? = null): ChatMessage {
        val textBytes = text.toByteArray()
        require(textBytes.size <= MAX_TEXT_BYTES) { "message too long" }
        val body = encodeBody(myName, text)
        val now = clock()
        val packet = if (toId == null) {
            originate(Packet.Type.MESSAGE, Packet.BROADCAST, body, now + BROADCAST_LIFETIME, BROADCAST_MAX_HOPS)
        } else {
            val contact = contacts[toId] ?: throw IllegalArgumentException("unknown contact $toId")
            val id = Crypto.randomBytes(Packet.ID_SIZE)
            val sealed = Crypto.encrypt(identity.sharedKeyWith(contact.publicKey), body, id)
            originate(Packet.Type.MESSAGE, toId.hexToBytes(), sealed, now + MESSAGE_LIFETIME, MESSAGE_MAX_HOPS, id)
        }
        return ChatMessage(packet.idHex, myId, myName, toId, text, now, 0, outgoing = true)
    }

    private fun originate(
        type: Packet.Type,
        destination: ByteArray,
        payload: ByteArray,
        expiresAt: Long,
        maxHops: Int,
        id: ByteArray = Crypto.randomBytes(Packet.ID_SIZE),
    ): Packet {
        val unsigned = Packet(
            type, id, identity.publicEncoded, destination, clock(), expiresAt, 0, maxHops, payload, ByteArray(0),
        )
        val packet = Packet(
            type, id, identity.publicEncoded, destination, unsigned.createdAt, expiresAt, 0, maxHops, payload,
            identity.sign(unsigned.signedBytes()),
        )
        markSeen(packet.idHex)
        store.put(packet)
        links.forEach { sendPacket(it, packet) }
        return packet
    }

    // ------------------------------------------------------------------ receiving

    private fun receive(packet: Packet, fromLink: String?) {
        val idHex = packet.idHex
        if (idHex in seen) return
        val now = clock()
        if (packet.isExpired(now)) return
        if (packet.createdAt > now + MAX_CLOCK_SKEW) return
        if (packet.expiresAt - packet.createdAt > MAX_LIFETIME) return
        if (packet.hops > packet.maxHops) return
        if (!packet.verify()) return
        markSeen(idHex)
        if (overRateLimit(packet)) return
        if (!process(packet)) {
            noteHandled(packet)
            return
        }
        if (!relay(packet, fromLink)) noteHandled(packet)
    }

    /**
     * Flood protection: each sender gets a budget per hour of *their* clock (createdAt), so a
     * spammer can't fill everyone's storage, while a burst of honest messages that waited in
     * someone's pocket still gets through. Packets for us are always accepted.
     */
    private fun overRateLimit(packet: Packet): Boolean {
        if (packet.destinationId == myId || packet.originId == myId) return false
        val limit = if (packet.type == Packet.Type.ANNOUNCE) ANNOUNCES_PER_HOUR else PACKETS_PER_HOUR
        val key = "${packet.originId}:${packet.type.code}:${packet.createdAt / HOUR}"
        val count = (rateCounts[key] ?: 0) + 1
        rateCounts[key] = count
        while (rateCounts.size > 20_000) rateCounts.remove(rateCounts.keys.first())
        return count > limit
    }

    private fun noteHandled(packet: Packet) {
        handled.addLast(packet.id.copyOf(SHORT_ID))
        while (handled.size > MAX_HANDLED) handled.removeFirst()
    }

    /** Act on a fresh, valid packet. Returns true if it should travel on to other phones. */
    private fun process(packet: Packet): Boolean {
        val idHex = packet.idHex
        when (packet.type) {
            Packet.Type.ANNOUNCE -> {
                if (packet.originId == myId) return false
                // Only carry each person's newest announcement.
                val older = store.all().filter { it.type == Packet.Type.ANNOUNCE && it.originId == packet.originId }
                if (older.any { it.createdAt > packet.createdAt }) return false
                older.forEach { store.remove(it.idHex) }
                learnContact(packet)
            }

            Packet.Type.MESSAGE -> {
                if (packet.destinationId != null && deliveryKey(idHex, packet.destinationId!!) in delivered) return false
                if (packet.destinationId == myId) {
                    deliverDirect(packet)
                    return false  // Reached its destination: no need to carry it further.
                }
                if (packet.isBroadcast && packet.originId != myId) deliverBroadcast(packet)
            }

            Packet.Type.ACK -> {
                if (packet.payload.size != Packet.ID_SIZE) return false
                val messageId = packet.payload.toHex()
                // Remember who acknowledged, so the message is dropped if it shows up later.
                // Keyed by acknowledger: a stranger's fake receipt can't cancel someone else's message.
                delivered.add(deliveryKey(messageId, packet.originId))
                val original = store.get(messageId)
                if (original != null && original.destinationId == packet.originId) {
                    store.remove(messageId)
                    if (original.originId == myId) listener.onDelivered(messageId)
                }
                if (packet.destinationId == myId) return false
            }
        }
        return true
    }

    /** Store and forward. Returns false if the packet is at its hop limit and stops here. */
    private fun relay(packet: Packet, fromLink: String?): Boolean {
        if (packet.hops + 1 > packet.maxHops) return false
        val next = packet.withHops(packet.hops + 1)
        store.put(next)
        links.filter { it != fromLink }.forEach { sendPacket(it, next) }
        return true
    }

    private fun deliverDirect(packet: Packet) {
        val key = identity.sharedKeyWith(packet.originKey)
        val body = Crypto.decrypt(key, packet.payload, packet.id) ?: return
        val (name, text) = decodeBody(body) ?: return
        listener.onMessage(
            ChatMessage(packet.idHex, packet.originId, name, myId, text, packet.createdAt, packet.hops, false),
        )
        // Signed receipt back to the sender; it travels the mesh like any other packet.
        originate(
            Packet.Type.ACK, NodeId.bytesOf(packet.originKey), packet.id,
            clock() + MESSAGE_LIFETIME, MESSAGE_MAX_HOPS,
        )
    }

    private fun deliverBroadcast(packet: Packet) {
        val (name, text) = decodeBody(packet.payload) ?: return
        listener.onMessage(
            ChatMessage(packet.idHex, packet.originId, name, null, text, packet.createdAt, packet.hops, false),
        )
    }

    private fun learnContact(packet: Packet) {
        val name = String(packet.payload).take(40).ifBlank { "Unknown" }
        val contact = Contact(packet.originId, name, packet.originKey, packet.createdAt)
        val known = contacts[contact.id]
        if (known != null && known.lastSeen > contact.lastSeen) return
        contacts[contact.id] = contact
        listener.onContact(contact)
    }

    private fun deliveryKey(messageId: String, recipientId: String) = "$messageId:$recipientId"

    private fun markSeen(idHex: String) {
        seen.add(idHex)
        if (seen.size > MAX_SEEN) seen.remove(seen.first())
        if (delivered.size > MAX_SEEN) delivered.remove(delivered.first())
    }

    fun pruneExpired() {
        val now = clock()
        store.all().filter { it.isExpired(now) }.forEach { store.remove(it.idHex) }
    }

    private fun encodeBody(name: String, text: String): ByteArray {
        val n = name.take(40).toByteArray()
        return byteArrayOf(n.size.toByte()) + n + text.toByteArray()
    }

    private fun decodeBody(body: ByteArray): Pair<String, String>? {
        if (body.isEmpty()) return null
        val n = body[0].toInt() and 0xFF
        if (body.size < 1 + n) return null
        return String(body, 1, n) to String(body, 1 + n, body.size - 1 - n)
    }
}
