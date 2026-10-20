package com.offgrid.mesh

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Standard crypto that ships with every Android version we support (API 26+), so the
 * app needs no third-party libraries: NIST P-256 for identity, signatures and key
 * agreement, AES-256-GCM for message bodies.
 */
object Crypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }

    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"), random)
        generateKeyPair()
    }

    fun publicKey(encoded: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))

    fun privateKey(encoded: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encoded))

    fun sign(key: PrivateKey, data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(key)
        update(data)
        sign()
    }

    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey(publicKey))
            update(data)
            verify(signature)
        }
    } catch (e: Exception) {
        false
    }

    /**
     * ECDH between my private key and their public key, hashed into an AES key.
     * Both sides arrive at the same key, so no key exchange round trip is needed.
     */
    fun sharedKey(mine: PrivateKey, theirs: ByteArray): ByteArray {
        val secret = KeyAgreement.getInstance("ECDH").run {
            init(mine)
            doPhase(publicKey(theirs), true)
            generateSecret()
        }
        return sha256("offgrid-chat/v1".toByteArray(), secret)
    }

    /** Returns iv (12 bytes) followed by ciphertext+tag. [aad] is authenticated but not encrypted. */
    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, data: ByteArray, aad: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
        cipher.updateAAD(aad)
        cipher.doFinal(data, 12, data.size - 12)
    } catch (e: Exception) {
        null
    }
}

/** A person's mesh address: the first 8 bytes of the SHA-256 of their public key, as hex. */
object NodeId {
    const val SIZE = 8

    fun bytesOf(publicKey: ByteArray): ByteArray = Crypto.sha256(publicKey).copyOf(SIZE)

    fun of(publicKey: ByteArray): String = bytesOf(publicKey).toHex()
}

/** This phone's long-lived identity. Persist [privateEncoded] and [publicEncoded] to keep the same address. */
class Identity(private val keyPair: KeyPair) {
    val publicEncoded: ByteArray = keyPair.public.encoded
    val privateEncoded: ByteArray = keyPair.private.encoded
    val id: String = NodeId.of(publicEncoded)
    val idBytes: ByteArray = NodeId.bytesOf(publicEncoded)

    private val sharedKeys = HashMap<String, ByteArray>()

    fun sign(data: ByteArray) = Crypto.sign(keyPair.private, data)

    fun sharedKeyWith(theirPublic: ByteArray): ByteArray =
        sharedKeys.getOrPut(theirPublic.toHex()) { Crypto.sharedKey(keyPair.private, theirPublic) }

    companion object {
        fun generate() = Identity(Crypto.generateKeyPair())

        fun restore(privateEncoded: ByteArray, publicEncoded: ByteArray) = Identity(
            KeyPair(Crypto.publicKey(publicEncoded), Crypto.privateKey(privateEncoded)),
        )
    }
}
