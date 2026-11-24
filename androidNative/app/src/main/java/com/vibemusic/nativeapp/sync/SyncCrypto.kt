package com.vibemusic.nativeapp.sync

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SyncIdentity(
    val privateKey: PrivateKey,
    val publicKey: PublicKey,
    val pubSpkiB64: String,
    val deviceId: String
)

data class SyncSessionKeys(
    val sessionKey: ByteArray,
    val authKey: ByteArray
)

object SyncCrypto {
    private val rng = SecureRandom()

    fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = hexChars[v ushr 4]
            out[i * 2 + 1] = hexChars[v and 0x0F]
        }
        return String(out)
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "odd hex length" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "invalid hex char" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun b64Encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun b64Decode(str: String): ByteArray = Base64.getDecoder().decode(str)
    fun b64UrlEncode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun b64UrlDecode(str: String): ByteArray = Base64.getUrlDecoder().decode(str)

    fun deviceIdFromSpki(spki: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(spki)
        return bytesToHex(digest.copyOf(16))
    }

    fun generateEcKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), rng)
        return kpg.generateKeyPair()
    }

    fun createIdentity(): Pair<SyncIdentity, String> {
        val kp = generateEcKeyPair()
        val spki = kp.public.encoded
        val pkcs8 = kp.private.encoded
        val identity = SyncIdentity(
            privateKey = kp.private,
            publicKey = kp.public,
            pubSpkiB64 = b64Encode(spki),
            deviceId = deviceIdFromSpki(spki)
        )
        val combined = b64Encode(pkcs8) + "|" + b64Encode(spki)
        return identity to combined
    }

    fun parseStoredIdentity(stored: String): SyncIdentity? {
        if (stored.isBlank()) return null
        return try {
            val parts = stored.split("|", limit = 2)
            if (parts.size != 2) return null
            val kf = KeyFactory.getInstance("EC")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(b64Decode(parts[0])))
            val spki = b64Decode(parts[1])
            val pub = kf.generatePublic(X509EncodedKeySpec(spki))
            SyncIdentity(
                privateKey = priv,
                publicKey = pub,
                pubSpkiB64 = parts[1],
                deviceId = deviceIdFromSpki(spki)
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseP256Spki(spkiB64: String): Pair<PublicKey, ByteArray> {
        val der = b64Decode(spkiB64)
        val kf = KeyFactory.getInstance("EC")
        val pub = kf.generatePublic(X509EncodedKeySpec(der))
        require(pub is ECPublicKey && pub.params.curve.field.fieldSize == 256) {
            "expected P-256 ECDSA public key"
        }
        return pub to der
    }

    fun verifyDeviceIdAndKey(deviceId: String, spkiB64: String): PublicKey {
        val (pub, der) = parseP256Spki(spkiB64)
        require(deviceIdFromSpki(der) == deviceId) { "deviceId does not match public key" }
        return pub
    }

    fun signMessage(priv: PrivateKey, msg: ByteArray): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(priv, rng)
        sig.update(msg)
        return b64Encode(sig.sign())
    }

    fun verifySignature(pub: PublicKey, msg: ByteArray, sigB64: String): Boolean {
        return try {
            val sigBytes = b64Decode(sigB64)
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(pub)
            sig.update(msg)
            sig.verify(sigBytes)
        } catch (_: Exception) {
            false
        }
    }

    fun generateEphemeralKey(): Pair<PrivateKey, String> {
        val kp = generateEcKeyPair()
        return kp.private to b64Encode(kp.public.encoded)
    }

    fun computeEcdhSharedSecret(priv: PrivateKey, peerEphSpkiB64: String): ByteArray {
        val (peerPub, _) = parseP256Spki(peerEphSpkiB64)
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(peerPub, true)
        return ka.generateSecret()
    }

    fun randomHex(numBytes: Int): String {
        val b = ByteArray(numBytes)
        rng.nextBytes(b)
        return bytesToHex(b)
    }

    private fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    fun deriveSessionKeys(sharedSecret: ByteArray, initNonceHex: String, respNonceHex: String): SyncSessionKeys {
        val n1 = hexToBytes(initNonceHex)
        val n2 = hexToBytes(respNonceHex)
        val salt = n1 + n2
        val prk = hmacSha256(salt, sharedSecret)
        val sessionKey = hmacSha256(
            prk,
            "vibemusic-sync-enc-v1".toByteArray(Charsets.UTF_8),
            byteArrayOf(0x01)
        )
        val authKey = hmacSha256(
            prk,
            sessionKey,
            "vibemusic-sync-mac-v1".toByteArray(Charsets.UTF_8),
            byteArrayOf(0x02)
        )
        return SyncSessionKeys(sessionKey = sessionKey, authKey = authKey)
    }

    fun buildTranscript(
        initId: String,
        respId: String,
        initPub: String,
        respPub: String,
        initEph: String,
        respEph: String,
        initNonce: String,
        respNonce: String
    ): String {
        return "vibemusic-pair-v1|$initId|$respId|$initPub|$respPub|$initEph|$respEph|$initNonce|$respNonce"
    }

    fun computeSasPin(transcript: String): String {
        val sum = MessageDigest.getInstance("SHA-256")
            .digest(("vibemusic-sas-v1|$transcript").toByteArray(Charsets.UTF_8))
        val v = ((sum[0].toInt() and 0x7F) shl 24) or
            ((sum[1].toInt() and 0xFF) shl 16) or
            ((sum[2].toInt() and 0xFF) shl 8) or
            (sum[3].toInt() and 0xFF)
        return "%06d".format(v % 1_000_000)
    }

    fun computeRequestMAC(
        authKey: ByteArray,
        method: String,
        pathWithQuery: String,
        sessionId: String,
        nonceHeader: String
    ): String {
        val msg = "$method|$pathWithQuery|$sessionId|$nonceHeader".toByteArray(Charsets.UTF_8)
        return bytesToHex(hmacSha256(authKey, msg))
    }

    fun verifyRequestMAC(
        authKey: ByteArray,
        method: String,
        pathWithQuery: String,
        sessionId: String,
        nonceHeader: String,
        providedHex: String
    ): Boolean {
        val expected = computeRequestMAC(authKey, method, pathWithQuery, sessionId, nonceHeader)
        return MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), providedHex.toByteArray(Charsets.US_ASCII))
    }

    fun encryptSingleBuffer(sessionKey: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plaintext)
        return iv + ct
    }

    fun decryptSingleBuffer(sessionKey: ByteArray, sealed: ByteArray): ByteArray {
        require(sealed.size >= 12 + 16) { "ciphertext too short" }
        val iv = sealed.copyOfRange(0, 12)
        val ct = sealed.copyOfRange(12, sealed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    fun encryptedStreamSize(plainSize: Long): Long {
        if (plainSize < 0L) return -1L
        val chunkSize = SyncConstants.CHUNK_SIZE.toLong()
        val fullChunks = plainSize / chunkSize
        val rem = plainSize % chunkSize
        var total = 12L + fullChunks * (4L + chunkSize + 16L)
        if (rem > 0L) {
            total += 4L + rem + 16L
        }
        total += 4L + 16L
        return total
    }

    private fun deriveChunkNonce(baseIV: ByteArray, idx: Long): ByteArray {
        val nonce = baseIV.copyOf(12)
        val bb = ByteBuffer.wrap(nonce).order(ByteOrder.BIG_ENDIAN)
        val tail = bb.getLong(4) xor idx
        bb.putLong(4, tail)
        return nonce
    }

    private fun makeChunkAAD(idx: Long, eof: Byte): ByteArray {
        return ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
            .putLong(idx)
            .put(eof)
            .array()
    }

    private fun readFully(input: InputStream, buf: ByteArray, off: Int = 0, len: Int = buf.size): Int {
        var total = 0
        while (total < len) {
            val n = input.read(buf, off + total, len - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun readExact(input: InputStream, buf: ByteArray, len: Int = buf.size) {
        val n = readFully(input, buf, 0, len)
        if (n != len) throw EOFException("unexpected EOF (got $n, expected $len)")
    }

    fun writeEncryptedStream(
        out: OutputStream,
        sessionKey: ByteArray,
        input: InputStream,
        onBytes: ((Long) -> Unit)? = null
    ) {
        val keySpec = SecretKeySpec(sessionKey, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val baseIV = ByteArray(12).also { rng.nextBytes(it) }
        out.write(baseIV)

        val buf = ByteArray(SyncConstants.CHUNK_SIZE)
        val lenBuf = ByteArray(4)
        var idx = 0L

        while (true) {
            val n = readFully(input, buf, 0, buf.size)
            if (n > 0) {
                val nonce = deriveChunkNonce(baseIV, idx)
                val aad = makeChunkAAD(idx, 0x00)
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
                cipher.updateAAD(aad)
                val ct = cipher.doFinal(buf, 0, n)
                ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).putInt(n)
                out.write(lenBuf)
                out.write(ct)
                idx++
                onBytes?.invoke(n.toLong())
            }
            if (n < buf.size) break
        }

        val eofNonce = deriveChunkNonce(baseIV, idx)
        val eofAad = makeChunkAAD(idx, 0x01)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, eofNonce))
        cipher.updateAAD(eofAad)
        val eofTag = cipher.doFinal(ByteArray(0))
        ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).putInt(0)
        out.write(lenBuf)
        out.write(eofTag)
        out.flush()
    }

    fun readEncryptedStream(
        input: InputStream,
        sessionKey: ByteArray,
        out: OutputStream,
        expectedPlainSize: Long = -1L,
        onBytes: ((Long) -> Unit)? = null
    ): Long {
        val keySpec = SecretKeySpec(sessionKey, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val baseIV = ByteArray(12)
        readExact(input, baseIV)

        val lenBuf = ByteArray(4)
        val ctBuf = ByteArray(SyncConstants.CHUNK_SIZE + 16)
        var idx = 0L
        var total = 0L

        while (true) {
            readExact(input, lenBuf)
            val plainLen = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).int
            if (plainLen == 0) {
                val eofTag = ByteArray(16)
                readExact(input, eofTag)
                val eofNonce = deriveChunkNonce(baseIV, idx)
                val eofAad = makeChunkAAD(idx, 0x01)
                cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, eofNonce))
                cipher.updateAAD(eofAad)
                cipher.doFinal(eofTag)
                if (expectedPlainSize >= 0L && total != expectedPlainSize) {
                    throw IllegalStateException("size mismatch: got $total want $expectedPlainSize")
                }
                return total
            }
            if (plainLen < 0 || plainLen > SyncConstants.CHUNK_SIZE) {
                throw IllegalStateException("invalid chunk len: $plainLen")
            }
            val need = plainLen + 16
            readExact(input, ctBuf, need)
            val nonce = deriveChunkNonce(baseIV, idx)
            val aad = makeChunkAAD(idx, 0x00)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(ctBuf, 0, need)
            out.write(plain)
            idx++
            total += plain.size.toLong()
            onBytes?.invoke(plain.size.toLong())
        }
    }
}
