package com.vn.cccdreader.nfc

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ICAO 9303 – Basic Access Control (BAC) implementation.
 *
 * Flow:
 *  1. Derive K_enc and K_mac from MRZ key seed
 *  2. GET CHALLENGE → RND.IC
 *  3. Generate RND.IFD + K.IFD
 *  4. Build E_IFD = 3DES_enc(K_enc, RND.IFD || RND.IC || K.IFD)
 *  5. Build M_IFD = retail_mac(K_mac, E_IFD)
 *  6. MUTUAL AUTHENTICATE with E_IFD || M_IFD
 *  7. Receive + verify E_IC || M_IC
 *  8. Derive session keys KS_enc, KS_mac and SSC
 */
object BACProtocol {

    // ─── Key derivation ──────────────────────────────────────────────────────

    fun deriveKeys(mrzInfo: MRZInfo): Pair<ByteArray, ByteArray> {
        val seed = sha1(mrzInfo.mrzKeySeed().toByteArray(Charsets.UTF_8)).copyOf(16)
        return Pair(deriveKey(seed, 1), deriveKey(seed, 2))
    }

    private fun deriveKey(seed: ByteArray, counter: Int): ByteArray {
        val input = seed + byteArrayOf(0, 0, 0, counter.toByte())
        val hash = sha1(input).copyOf(16)
        adjustDESParity(hash)
        return hash
    }

    /** Adjust DES parity bits so each byte has odd parity */
    private fun adjustDESParity(key: ByteArray) {
        for (i in key.indices) {
            var b = key[i].toInt() and 0xFF
            var parity = 0
            for (j in 1..7) { parity += (b shr j) and 1 }
            b = if (parity % 2 == 0) b or 1 else b and 0xFE
            key[i] = b.toByte()
        }
    }

    // ─── BAC challenge/response ──────────────────────────────────────────────

    /**
     * Build the 40-byte payload for MUTUAL AUTHENTICATE.
     * Returns E_IFD (32 bytes) + M_IFD (8 bytes).
     */
    fun buildAuthPayload(rndIC: ByteArray, kEnc: ByteArray, kMac: ByteArray): ByteArray {
        val rndIFD = randomBytes(8)
        val kIFD   = randomBytes(16)

        // S = RND.IFD || RND.IC || K.IFD
        val s = rndIFD + rndIC + kIFD

        // E_IFD = 3DES_enc(K_enc, S)  IV = 0
        val eIFD = tripleDESEncrypt(kEnc, ByteArray(8), s)

        // M_IFD = retail_mac(K_mac, E_IFD)
        val mIFD = retailMAC(kMac, eIFD)

        return eIFD + mIFD          // 32 + 8 = 40 bytes
    }

    /**
     * Verify and parse the 40-byte MUTUAL AUTHENTICATE response.
     * Returns (KS_enc, KS_mac, SSC) on success or throws [BACException].
     */
    fun parseAuthResponse(
        responseData: ByteArray,
        rndIFD: ByteArray,
        kEnc: ByteArray,
        kMac: ByteArray
    ): Triple<ByteArray, ByteArray, ByteArray> {
        if (responseData.size < 40) throw BACException("Auth response too short: ${responseData.size}")

        val eIC = responseData.copyOf(32)
        val mIC = responseData.copyOfRange(32, 40)

        // Verify MAC
        val computedMAC = retailMAC(kMac, eIC)
        if (!computedMAC.contentEquals(mIC)) throw BACException("MAC mismatch in authentication response")

        // Decrypt
        val dIC = tripleDESDecrypt(kEnc, ByteArray(8), eIC)

        // dIC = RND.IC || RND.IFD || K.IC
        val kIC = dIC.copyOfRange(16, 32)

        // Session key seed = K.IFD XOR K.IC
        // We stored K.IFD in the first call; caller must pass it back in rndIFD actually
        // but for simplicity the caller reconstructs it from the stored state.
        // Here we just return kIC for the caller to compute XOR externally.

        // SSC = RND.IC[4..7] || RND.IFD[4..7]
        val rndIC = dIC.copyOf(8)
        val ssc   = rndIC.copyOfRange(4, 8) + rndIFD.copyOfRange(4, 8)

        return Triple(kIC, ByteArray(0), ssc)   // kIC returned to caller for XOR step
    }

    // ─── BAC full exchange (stateful helper used by MRTDReader) ─────────────

    data class BACSession(
        val ksEnc: ByteArray,
        val ksMac: ByteArray,
        val ssc: ByteArray
    )

    /**
     * One-shot BAC: takes the raw GET_CHALLENGE response and the full
     * MUTUAL_AUTHENTICATE response and produces session keys.
     *
     * @param rndIC        8-byte challenge from card
     * @param authResponse 40-byte response from card (eIC + mIC)
     * @param kEnc         static encryption key derived from MRZ
     * @param kMac         static MAC key derived from MRZ
     * @param rndIFD       the 8-byte nonce we generated (stored by caller)
     * @param kIFD         the 16-byte key material we generated (stored by caller)
     */
    fun deriveSessionKeys(
        rndIC: ByteArray,
        authResponse: ByteArray,
        kEnc: ByteArray,
        kMac: ByteArray,
        rndIFD: ByteArray,
        kIFD: ByteArray
    ): BACSession {
        if (authResponse.size < 40) throw BACException("Auth response too short")

        val eIC = authResponse.copyOf(32)
        val mIC = authResponse.copyOfRange(32, 40)

        // Verify MAC
        if (!retailMAC(kMac, eIC).contentEquals(mIC))
            throw BACException("MAC verification failed – wrong MRZ data?")

        // Decrypt
        val dIC = tripleDESDecrypt(kEnc, ByteArray(8), eIC)

        // Check dIC starts with RND.IC
        if (!dIC.copyOf(8).contentEquals(rndIC))
            throw BACException("RND.IC mismatch in decrypted response")

        val kIC = dIC.copyOfRange(16, 32)

        // Session key seed
        val kSeed = ByteArray(16) { i -> (kIFD[i].toInt() xor kIC[i].toInt()).toByte() }

        val ksEnc = deriveKey(kSeed, 1)
        val ksMac = deriveKey(kSeed, 2)

        // SSC = RND.IC[4..7] || RND.IFD[4..7]
        val ssc = rndIC.copyOfRange(4, 8) + rndIFD.copyOfRange(4, 8)

        return BACSession(ksEnc, ksMac, ssc)
    }

    // ─── Crypto primitives ───────────────────────────────────────────────────

    fun tripleDESEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        tripleDES(Cipher.ENCRYPT_MODE, key, iv, data)

    fun tripleDESDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        tripleDES(Cipher.DECRYPT_MODE, key, iv, data)

    private fun tripleDES(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val fullKey = if (key.size == 16) key + key.copyOf(8) else key   // 2-key → 3-key 3DES
        val secretKey: SecretKey = SecretKeySpec(fullKey, "DESede")
        val cipher = Cipher.getInstance("DESede/CBC/NoPadding")
        cipher.init(mode, secretKey, IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /**
     * ISO 9797-1 Retail MAC (Algorithm 3):
     *  1. Pad with method 2 (80 00...) to 8-byte boundary
     *  2. DES-CBC all blocks but last using key[0..7]
     *  3. 3DES last block using full key
     *  4. Return last 8 bytes
     */
    fun retailMAC(key: ByteArray, data: ByteArray): ByteArray {
        val padded = iso9797Pad(data)
        val desKey = SecretKeySpec(key.copyOf(8), "DES")
        val desCipher = Cipher.getInstance("DES/CBC/NoPadding")

        var iv = ByteArray(8)
        val n = padded.size / 8

        // DES-CBC all blocks except last
        for (i in 0 until n - 1) {
            desCipher.init(Cipher.ENCRYPT_MODE, desKey, IvParameterSpec(iv))
            iv = desCipher.doFinal(padded, i * 8, 8)
        }

        // 3DES on last block
        val fullKey = if (key.size == 16) key + key.copyOf(8) else key
        val tdesKey = SecretKeySpec(fullKey, "DESede")
        val tdesCipher = Cipher.getInstance("DESede/CBC/NoPadding")
        tdesCipher.init(Cipher.ENCRYPT_MODE, tdesKey, IvParameterSpec(iv))
        return tdesCipher.doFinal(padded, (n - 1) * 8, 8)
    }

    /**
     * ISO/IEC 7816-4 / ISO 9797-1 Method 2 padding:
     * Append 0x80 then zero bytes until the total length is a multiple of 8.
     *
     * Examples:
     *   []      → [80 00 00 00 00 00 00 00]   (8 bytes)
     *   [AA]×7  → [AA×7 80]                   (8 bytes)
     *   [AA]×8  → [AA×8 80 00 00 00 00 00 00] (16 bytes)
     */
    fun iso9797Pad(data: ByteArray): ByteArray {
        val lenWithMarker = data.size + 1           // +1 for the mandatory 0x80 byte
        val remainder     = lenWithMarker % 8
        val paddedLen     = if (remainder == 0) lenWithMarker else lenWithMarker + (8 - remainder)
        val out = ByteArray(paddedLen)              // zero-filled by default
        data.copyInto(out)
        out[data.size] = 0x80.toByte()
        return out
    }

    fun sha1(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(data)

    fun randomBytes(n: Int): ByteArray =
        ByteArray(n).also { SecureRandom().nextBytes(it) }

    // ─── Build APDU payloads for BAC ─────────────────────────────────────────

    /**
     * Build the full 40-byte body for MUTUAL AUTHENTICATE and return it
     * together with the ephemeral values so the caller can pass them to
     * [deriveSessionKeys] after the card responds.
     */
    data class AuthChallenge(
        val apduBody: ByteArray,   // 40 bytes to send
        val rndIFD: ByteArray,     // 8 bytes
        val kIFD: ByteArray        // 16 bytes
    )

    fun buildAuthChallenge(rndIC: ByteArray, kEnc: ByteArray, kMac: ByteArray): AuthChallenge {
        val rndIFD = randomBytes(8)
        val kIFD   = randomBytes(16)

        val s    = rndIFD + rndIC + kIFD
        val eIFD = tripleDESEncrypt(kEnc, ByteArray(8), s)
        val mIFD = retailMAC(kMac, eIFD)

        return AuthChallenge(eIFD + mIFD, rndIFD, kIFD)
    }
}

class BACException(msg: String) : Exception(msg)
