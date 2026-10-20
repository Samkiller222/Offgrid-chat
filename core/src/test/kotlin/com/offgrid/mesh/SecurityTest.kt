package com.offgrid.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Attacks found in a security review; each must now fail. */
class SecurityTest {
    private fun forge(
        who: Identity, type: Packet.Type, dest: ByteArray, payload: ByteArray,
        createdAt: Long, expiresAt: Long, maxHops: Int = 10, id: ByteArray = Crypto.randomBytes(16),
    ): Packet {
        val u = Packet(type, id, who.publicEncoded, dest, createdAt, expiresAt, 0, maxHops, payload, ByteArray(0))
        return Packet(type, id, who.publicEncoded, dest, createdAt, expiresAt, 0, maxHops, payload, who.sign(u.signedBytes()))
    }
    private fun wire(p: Packet) = byteArrayOf(1) + p.encode()
    private fun body(name: String, text: String) = byteArrayOf(name.length.toByte()) + name.toByteArray() + text.toByteArray()

    @Test fun immortalPacketViaOverflow() {
        val net = SimNetwork(); val b = net.node("b")
        val evil = Identity.generate()
        val p = forge(evil, Packet.Type.MESSAGE, Packet.BROADCAST, body("x", "forever"), Long.MIN_VALUE, Long.MAX_VALUE)
        b.router.onLinkData("m", wire(p))
        net.now += 365L * 24 * MeshRouter.HOUR
        b.router.pruneExpired()
        assertEquals(0, b.store.all().size, "a packet claiming an absurd lifetime must be rejected")
    }

    @Test fun idSquattingCensorsRealMessage() {
        val net = SimNetwork()
        val a = net.node("a"); val c = net.node("c")
        val ann = forge(c.identity, Packet.Type.ANNOUNCE, Packet.BROADCAST, "C".toByteArray(), net.now, net.now + 1000000)
        a.router.onLinkData("c", wire(ann))
        val msg = a.router.sendText("hi", "A", c.id)
        val evil = Identity.generate()
        val fake = forge(evil, Packet.Type.MESSAGE, Packet.BROADCAST, body("m", "spam"), net.now, net.now + 100000, id = msg.id.hexToBytes())
        c.router.onLinkData("m", wire(fake))
        net.connect(a, c)
        assertEquals(1, c.inbox.count { it.text == "hi" }, "a copied id must not block the real message")
    }

    @Test fun replayAfterSeenEvictionDuplicatesDelivery() {
        val net = SimNetwork()
        val a = net.node("a"); val c = net.node("c")
        val ann = forge(c.identity, Packet.Type.ANNOUNCE, Packet.BROADCAST, "C".toByteArray(), net.now, net.now + 1000000)
        a.router.onLinkData("c", wire(ann))
        a.router.sendText("hi", "A", c.id)
        val orig = a.store.all().first { it.type == Packet.Type.MESSAGE }
        c.router.onLinkData("a", wire(orig))
        assertEquals(1, c.inbox.size)
        val evil = Identity.generate()
        repeat(10_001) {
            c.router.onLinkData("m", wire(forge(evil, Packet.Type.MESSAGE, Packet.BROADCAST, ByteArray(0), net.now, net.now + 100000)))
        }
        c.router.onLinkData("m", wire(orig))
        assertEquals(1, c.inbox.size, "a replayed message must not be shown twice")
    }

    @Test fun rateLimitBypassedByBackdatedBuckets() {
        val net = SimNetwork(); val b = net.node("b")
        val honest = forge(Identity.generate(), Packet.Type.MESSAGE, Packet.BROADCAST, body("h", "hi"), net.now, net.now + 10 * MeshRouter.HOUR)
        b.router.onLinkData("h", wire(honest))
        val evil = Identity.generate()
        for (i in 0 until 1000) {
            val created = net.now - (i / 120) * MeshRouter.HOUR
            b.router.onLinkData("m", wire(forge(evil, Packet.Type.MESSAGE, Packet.BROADCAST, ByteArray(0), created, created + 7 * 24 * MeshRouter.HOUR)))
        }
        assertTrue(b.store.all().count { it.originId == evil.id } <= MeshRouter.MAX_STORED_PER_ORIGIN)
        assertTrue(b.store.get(honest.idHex) != null, "one sender must not be able to evict others")
    }

    @Test fun dmFloodExhaustsVictimAckBudget() {
        val net = SimNetwork()
        val a = net.node("a"); val b = net.node("b"); val z = net.node("z")
        net.connect(a, b); net.connect(b, z)
        z.router.announce("Z"); net.run()
        val evil = Identity.generate()
        val key = evil.sharedKeyWith(z.identity.publicEncoded)
        repeat(125) {
            val id = Crypto.randomBytes(16)
            val sealed = Crypto.encrypt(key, body("m", "x"), id + evil.idBytes + z.identity.idBytes)
            z.router.onLinkData("m", wire(forge(evil, Packet.Type.MESSAGE, z.identity.idBytes, sealed, net.now, net.now + 100000, id = id)))
        }
        net.run()
        val msg = a.router.sendText("real", "A", z.id); net.run()
        assertEquals(1, z.inbox.count { it.text == "real" })
        assertTrue(msg.id in a.deliveredIds, "spam sent to Z must not stop Z's real receipts")
    }
}
