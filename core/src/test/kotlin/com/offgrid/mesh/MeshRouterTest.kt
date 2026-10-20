package com.offgrid.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phones on a table: links are added and removed by hand, bytes are delivered via a queue. */
class SimNetwork {
    var now = 1_700_000_000_000L
    private val queue = ArrayDeque<Triple<Node, String, ByteArray>>()
    val nodes = mutableListOf<Node>()
    var bytesSent = 0L

    inner class Node(val name: String) : MeshListener {
        val identity = Identity.generate()
        val store = MemoryPacketStore()
        val inbox = mutableListOf<ChatMessage>()
        val deliveredIds = mutableListOf<String>()
        val router = MeshRouter(identity, store, this) { now }
        val id get() = identity.id

        init {
            router.transport = Transport { link, bytes ->
                bytesSent += bytes.size
                val target = nodes.first { it.name == link }
                queue.addLast(Triple(target, name, bytes))
            }
        }

        override fun onMessage(message: ChatMessage) { inbox += message }
        override fun onDelivered(messageId: String) { deliveredIds += messageId }
    }

    fun node(name: String) = Node(name).also { nodes += it }

    fun connect(a: Node, b: Node) {
        a.router.linkUp(b.name)
        b.router.linkUp(a.name)
        run()
    }

    fun disconnect(a: Node, b: Node) {
        a.router.linkDown(b.name)
        b.router.linkDown(a.name)
    }

    fun run() {
        while (queue.isNotEmpty()) {
            val (target, from, bytes) = queue.removeFirst()
            // Only deliver if the link still exists on the receiving side.
            target.router.onLinkData(from, bytes)
        }
    }
}

class MeshRouterTest {

    @Test
    fun packetRoundTripsAndVerifies() {
        val net = SimNetwork()
        val a = net.node("a")
        a.router.announce("Alice")
        val packet = a.store.all().single()
        val decoded = Packet.decode(packet.encode())!!
        assertTrue(decoded.verify())
        assertEquals(a.id, decoded.originId)
        // Tampering breaks the signature.
        val bytes = packet.encode()
        bytes[bytes.size - 80] = (bytes[bytes.size - 80] + 1).toByte()
        assertTrue(Packet.decode(bytes)?.verify() != true)
    }

    @Test
    fun broadcastHopsDownAChain() {
        val net = SimNetwork()
        val (a, b, c, d) = listOf("a", "b", "c", "d").map { net.node(it) }
        net.connect(a, b); net.connect(b, c); net.connect(c, d)

        a.router.sendText("hello all", "Alice")
        net.run()

        listOf(b, c, d).forEach { assertEquals("hello all", it.inbox.single().text) }
        assertEquals(2, d.inbox.single().hops, "relayed by B and C")
        assertTrue(a.inbox.isEmpty())
    }

    @Test
    fun directMessageIsEncryptedAndAcked() {
        val net = SimNetwork()
        val (a, b, c) = listOf("a", "b", "c").map { net.node(it) }
        net.connect(a, b); net.connect(b, c)
        c.router.announce("Carol")
        net.run()

        val sent = a.router.sendText("secret", "Alice", toId = c.id)
        net.run()

        assertEquals("secret", c.inbox.single().text)
        assertEquals("Alice", c.inbox.single().fromName)
        assertTrue(b.inbox.isEmpty(), "relay must not see the message")
        assertEquals(listOf(sent.id), a.deliveredIds)
        // The relay saw the receipt and stopped carrying the message.
        assertNull(b.store.get(sent.id))
        // Ciphertext must not contain the plaintext.
        val carried = a.store.all().none { "secret" in String(it.payload) }
        assertTrue(carried)
    }

    @Test
    fun storeAndCarryAcrossDisconnectedGroups() {
        // A and C are never in range at the same time, and neither are A and B with C.
        // B walks from A's town to C's town carrying the message.
        val net = SimNetwork()
        val (a, b, c) = listOf("a", "b", "c").map { net.node(it) }

        net.connect(b, c)
        c.router.announce("Carol")
        net.run()
        net.disconnect(b, c)

        net.connect(a, b)             // A learns Carol's key from B's carried announcement
        val sent = a.router.sendText("see you tomorrow", "Alice", toId = c.id)
        net.run()
        net.disconnect(a, b)
        assertTrue(c.inbox.isEmpty())

        net.now += 2 * MeshRouter.HOUR
        net.connect(b, c)             // B arrives in C's town
        assertEquals("see you tomorrow", c.inbox.single().text)

        net.disconnect(b, c)
        net.connect(a, b)             // B comes back with the receipt
        assertEquals(listOf(sent.id), a.deliveredIds)
    }

    @Test
    fun noDuplicatesInALoop() {
        val net = SimNetwork()
        val nodes = (0 until 6).map { net.node("n$it") }
        // Fully connected: lots of paths, each phone should still see the message once.
        for (i in nodes.indices) for (j in i + 1 until nodes.size) net.connect(nodes[i], nodes[j])
        nodes[0].router.sendText("ping", "Zero")
        net.run()
        nodes.drop(1).forEach { assertEquals(1, it.inbox.size) }
    }

    @Test
    fun expiredMessagesAreDropped() {
        val net = SimNetwork()
        val (a, b) = listOf("a", "b").map { net.node(it) }
        a.router.sendText("old news", "Alice")
        net.now += MeshRouter.MESSAGE_LIFETIME + 1
        net.connect(a, b)
        assertTrue(b.inbox.isEmpty())
        assertEquals(0, a.router.carriedCount())
    }

    @Test
    fun reconnectingDoesNotResendWhatThePeerHas() {
        val net = SimNetwork()
        val (a, b) = listOf("a", "b").map { net.node(it) }
        net.connect(a, b)
        repeat(20) { a.router.sendText("msg $it", "Alice") }
        net.run()
        net.disconnect(a, b)
        val before = net.bytesSent
        net.connect(a, b)
        // Only the two inventories cross, roughly 1 + 20*8 bytes each.
        assertTrue(net.bytesSent - before < 400, "sent ${net.bytesSent - before}")
        assertEquals(20, b.inbox.size)
    }

    @Test
    fun framingSplitsAndReassembles() {
        val blob = Crypto.randomBytes(1000)
        val frames = Framing.split(blob, streamId = 7, maxFrameSize = 20)
        val r = Framing.Reassembler()
        val results = frames.mapNotNull { r.accept(it) }
        assertEquals(1, results.size)
        assertTrue(blob.contentEquals(results[0]))

        // A dropped frame discards the stream instead of producing garbage.
        val r2 = Framing.Reassembler()
        assertTrue(frames.filterIndexed { i, _ -> i != 3 }.mapNotNull { r2.accept(it) }.isEmpty())
        // ...and the next stream still works.
        assertTrue(Framing.split(byteArrayOf(1, 2, 3), 8, 20).mapNotNull { r2.accept(it) }.single()
            .contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun directMessageToNeighbour() {
        val net = SimNetwork()
        val (a, b) = listOf("a", "b").map { net.node(it) }
        net.connect(a, b)
        b.router.announce("Bob")
        net.run()
        a.router.sendText("hi", "Alice", toId = b.id)
        net.run()
        assertEquals(1, b.inbox.size)
        assertEquals(1, a.deliveredIds.size)
    }

    @Test
    fun fakeReceiptFromStrangerDoesNotCancelMessage() {
        val net = SimNetwork()
        val (a, b, c, m) = listOf("a", "b", "c", "mallory").map { net.node(it) }
        net.connect(b, c)
        c.router.announce("Carol")
        net.run()
        net.disconnect(b, c)
        net.connect(a, b)
        val sent = a.router.sendText("hi carol", "Alice", toId = c.id)
        net.run()

        // Mallory forges a receipt for Alice's message id and pushes it to B.
        val forgedAck = forge(m.identity, Packet.Type.ACK, a.identity.idBytes, sent.id.hexToBytes(), net.now)
        net.connect(m, b)
        b.router.onLinkData("mallory", byteArrayOf(1) + forgedAck.encode())
        net.run()

        assertTrue(a.deliveredIds.isEmpty(), "forged receipt must not mark delivered")
        assertTrue(b.store.get(sent.id) != null, "relay must keep carrying the message")
        net.connect(b, c)
        assertEquals("hi carol", c.inbox.single().text)
    }

    @Test
    fun tamperedRelayCopyIsRejected() {
        val net = SimNetwork()
        val (a, b) = listOf("a", "b").map { net.node(it) }
        a.router.sendText("original", "Alice")
        val p = a.store.all().single()
        val evil = Packet(p.type, p.id, p.originKey, p.destination, p.createdAt, p.expiresAt, p.hops, p.maxHops,
            byteArrayOf(5) + "Alice".toByteArray() + "changed".toByteArray(), p.signature)
        b.router.linkUp("a")
        b.router.onLinkData("a", byteArrayOf(1) + evil.encode())
        assertTrue(b.inbox.isEmpty())
    }

    @Test
    fun storeEvictsAnnouncementsBeforeMessages() {
        val net = SimNetwork()
        val a = net.node("a")
        val store = MemoryPacketStore(capacity = 3)
        val id = a.identity
        val msg = forge(id, Packet.Type.MESSAGE, Packet.BROADCAST, byteArrayOf(0), net.now)
        store.put(msg)
        repeat(4) { store.put(forge(id, Packet.Type.ANNOUNCE, Packet.BROADCAST, "x".toByteArray(), net.now)) }
        assertEquals(3, store.all().size)
        assertTrue(store.get(msg.idHex) != null)
    }

    @Test
    fun fileStoreSurvivesRestart() {
        val dir = kotlin.io.path.createTempDirectory("packets").toFile()
        val id = Identity.generate()
        val p = forge(id, Packet.Type.MESSAGE, Packet.BROADCAST, byteArrayOf(0), System.currentTimeMillis())
        FilePacketStore(dir).put(p)
        val reloaded = FilePacketStore(dir)
        assertTrue(reloaded.get(p.idHex)!!.verify())
        reloaded.remove(p.idHex)
        assertTrue(FilePacketStore(dir).all().isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun identityRestoresToSameAddressAndCanDecrypt() {
        val net = SimNetwork()
        val (a, b) = listOf("a", "b").map { net.node(it) }
        val restored = Identity.restore(b.identity.privateEncoded, b.identity.publicEncoded)
        assertEquals(b.id, restored.id)
        assertTrue(a.identity.sharedKeyWith(b.identity.publicEncoded)
            .contentEquals(restored.sharedKeyWith(a.identity.publicEncoded)))
    }

    @Test
    fun deliveredMessagesAreNotResentOnReconnect() {
        val net = SimNetwork()
        val (a, b, c) = listOf("a", "b", "c").map { net.node(it) }
        net.connect(b, c)
        c.router.announce("Carol")
        net.run()
        net.connect(a, b)
        a.router.sendText("hello", "Alice", toId = c.id)
        net.run()
        assertEquals(1, c.inbox.size)
        // B still carries C's receipt; C must not get the message pushed back at every meeting.
        net.disconnect(b, c)
        val before = net.bytesSent
        net.connect(b, c)
        assertTrue(net.bytesSent - before < 300, "sent ${net.bytesSent - before}")
        assertEquals(1, c.inbox.size)
    }

    @Test
    fun meshtasticEncodingMatchesProtobuf() {
        // ToRadio { want_config_id: 1 } is tag 0x18, value 1.
        assertTrue(Meshtastic.wantConfig(1).contentEquals(byteArrayOf(0x18, 0x01)))
        val toRadio = Meshtastic.broadcast(byteArrayOf(9, 8, 7), packetId = 0x01020304)
        // ToRadio.packet (1) -> MeshPacket: to=0xFFFFFFFF (fixed32 #2), decoded (#4), id (#6)
        val packet = Meshtastic.ProtoReader(toRadio).fields().single { it.number == 1 }.bytes!!
        val fields = Meshtastic.ProtoReader(packet).fields()
        assertEquals(0xFFFFFFFFL, fields.single { it.number == 2 }.value)
        assertEquals(0x01020304L, fields.single { it.number == 6 }.value)
        assertEquals(Meshtastic.HOP_LIMIT.toLong(), fields.single { it.number == 9 }.value, "must set hop_limit")
        assertTrue(fields.none { it.number == 10 }, "no want_ack on broadcasts")
        // Session/queue messages are recognised.
        assertEquals(77L, Meshtastic.inspect(Meshtastic.ProtoWriter().varint(7, 77).bytes()).configCompleteId)
        val queue = Meshtastic.ProtoWriter().varint(2, 5).varint(3, 16).bytes()
        assertEquals(5, Meshtastic.inspect(Meshtastic.ProtoWriter().message(11, queue).bytes()).queueFree)
        // The radio echoes packets back inside FromRadio.packet (#2) with a from field.
        val meshPacket = Meshtastic.ProtoWriter().fixed32(1, 0xDEADBEEFL)
            .message(4, fields.single { it.number == 4 }.bytes!!).bytes()
        val fromRadio = Meshtastic.ProtoWriter().varint(1, 5).message(2, meshPacket).bytes()
        val got = Meshtastic.parseFromRadio(fromRadio)!!
        assertEquals(0xDEADBEEFL, got.fromNode)
        assertTrue(got.payload.contentEquals(byteArrayOf(9, 8, 7)))
        // Config and other traffic is ignored, garbage doesn't crash.
        assertNull(Meshtastic.parseFromRadio(Meshtastic.ProtoWriter().varint(7, 42).bytes()))
        assertNull(Meshtastic.parseFromRadio(byteArrayOf(0x12, 0x7F, 1)))
    }

    @Test
    fun loraFragmentsReassembleOutOfOrderAcrossSenders() {
        val f = LoraFragmenter()
        val a = Crypto.randomBytes(700)
        val b = Crypto.randomBytes(300)
        val pa = f.split(a)
        val pb = f.split(b)
        assertTrue(pa.all { it.size <= Meshtastic.MAX_PAYLOAD })
        val r = LoraFragmenter()
        val out = mutableListOf<ByteArray>()
        (pa.reversed().map { 1L to it } + pb.map { 2L to it } + (1L to pa[0]))
            .shuffled(kotlin.random.Random(1))
            .forEach { (sender, piece) -> r.accept(sender, piece)?.let { out += it } }
        assertEquals(2, out.size)
        assertTrue(out.any { it.contentEquals(a) } && out.any { it.contentEquals(b) })
        // Other apps' PRIVATE_APP traffic (no magic byte) is ignored.
        assertNull(r.accept(3L, byteArrayOf(1, 2, 3, 4, 5, 0, 1, 9)))
    }

    @Test
    fun loraLinkSkipsInventoryAndAnnouncementsFromOthers() {
        val net = SimNetwork()
        val (a, b) = listOf("a", "b").map { net.node(it) }
        b.router.announce("Bob")
        a.router.shouldSend = { link, p -> link != "b" || p.type != Packet.Type.ANNOUNCE || p.originId == a.id }
        net.connect(b, a)       // b syncs to a normally, a learns Bob
        net.disconnect(a, b)
        val before = net.bytesSent
        a.router.linkUp("b", sync = false)
        assertEquals(before, net.bytesSent, "no inventory on a no-sync link")
        a.router.sendText("over lora", "Alice")
        net.run()
        assertEquals("over lora", b.inbox.single().text)
        // a never forwards Bob's announcement back over the LoRa link
        assertTrue(a.store.all().any { it.type == Packet.Type.ANNOUNCE })
    }

    @Test
    fun spammerIsRateLimitedButQueuedHonestMessagesPass() {
        val net = SimNetwork()
        val (spammer, relay, victim) = listOf("s", "r", "v").map { net.node(it) }
        net.connect(spammer, relay); net.connect(relay, victim)
        repeat(MeshRouter.PACKETS_PER_HOUR + 50) { spammer.router.sendText("spam $it", "S") }
        net.run()
        assertEquals(MeshRouter.PACKETS_PER_HOUR, victim.inbox.size)
        assertTrue(relay.router.carriedCount() <= MeshRouter.PACKETS_PER_HOUR + 1)

        // Messages written over several hours and delivered in one burst are fine.
        val (a, b) = listOf("a", "b").map { net.node(it) }
        repeat(5) {
            repeat(100) { a.router.sendText("note $it", "A") }
            net.now += MeshRouter.HOUR
        }
        net.connect(a, b)
        assertEquals(500, b.inbox.size)
    }

    @Test
    fun walkersCarryMessagesAcrossATown() {
        // 40 phones wander a 2 km x 2 km town; Bluetooth reaches 50 m. No path exists at any
        // one moment, yet messages should get through by being carried around.
        val rnd = kotlin.random.Random(42)
        val net = SimNetwork()
        val phones = (0 until 40).map { net.node("p$it") }
        val pos = phones.associateWith { doubleArrayOf(rnd.nextDouble() * 2000, rnd.nextDouble() * 2000) }
        val linked = HashSet<Pair<Int, Int>>()
        fun step() {
            net.now += MeshRouter.MINUTE
            for (p in phones) {
                val xy = pos.getValue(p)
                xy[0] = (xy[0] + rnd.nextDouble(-80.0, 80.0)).coerceIn(0.0, 2000.0)
                xy[1] = (xy[1] + rnd.nextDouble(-80.0, 80.0)).coerceIn(0.0, 2000.0)
            }
            for (i in phones.indices) for (j in i + 1 until phones.size) {
                val a = pos.getValue(phones[i]); val b = pos.getValue(phones[j])
                val near = Math.hypot(a[0] - b[0], a[1] - b[1]) < 50
                val key = i to j
                if (near && linked.add(key)) net.connect(phones[i], phones[j])
                if (!near && linked.remove(key)) net.disconnect(phones[i], phones[j])
            }
        }
        phones.forEach { it.router.announce(it.name) }
        repeat(6 * 60) { step() }   // 6 hours for announcements to spread
        val pairs = (0 until 10).map { phones[2 * it] to phones[2 * it + 1] }
        val sent = pairs.mapNotNull { (from, to) ->
            if (from.router.contact(to.id) == null) null else from.router.sendText("hi ${to.name}", from.name, to.id)
        }
        repeat(12 * 60) { step() }  // 12 more hours of walking
        val delivered = pairs.count { (_, to) -> to.inbox.any { it.text == "hi ${to.name}" } }
        println("walkers: ${sent.size} sent after discovery, $delivered delivered")
        assertTrue(sent.size >= 8, "most people should have discovered each other, got ${sent.size}")
        assertTrue(delivered >= sent.size - 1, "delivered $delivered of ${sent.size}")
    }

    private fun forge(who: Identity, type: Packet.Type, dest: ByteArray, payload: ByteArray, now: Long): Packet {
        val id = Crypto.randomBytes(Packet.ID_SIZE)
        val unsigned = Packet(type, id, who.publicEncoded, dest, now, now + MeshRouter.HOUR, 0, 10, payload, ByteArray(0))
        return Packet(type, id, who.publicEncoded, dest, now, now + MeshRouter.HOUR, 0, 10, payload,
            who.sign(unsigned.signedBytes()))
    }
}
