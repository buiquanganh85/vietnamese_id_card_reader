package com.vn.cccdreader.nfc

import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Cipher

/**
 * ICAO 9303 Secure Messaging (SM) – wraps / unwraps APDUs after BAC.
 *
 * Each APDU is processed as follows:
 *  WRAP (command):
 *   1. Increment SSC
 *   2. Encrypt command data with KS_enc (3DES-CBC, IV=0)
 *   3. Build DO'87 (encrypted data) and DO'97 (expected length)
 *   4. Compute MAC over SSC || DO'87 || DO'97 → DO'8E
 *   5. Assemble secure APDU
 *
 *  UNWRAP (response):
 *   1. Increment SSC
 *   2. Verify MAC (DO'8E) over SSC || response DOs
 *   3. Decrypt DO'87 to get plain response data
 */
class SecureMessaging(
    ksEnc: ByteArray,
    ksMac: ByteArray,
    initialSSC: ByteArray
) {
    private val ksEnc: ByteArray = ksEnc.copyOf()
    private val ksMac: ByteArray = ksMac.copyOf()
    private val ssc: ByteArray   = initialSSC.copyOf()   // 8 bytes, big-endian counter

    // ─── SSC management ──────────────────────────────────────────────────────

    private fun incrementSSC() {
        for (i in ssc.indices.reversed()) {
            if (++ssc[i] != 0.toByte()) break
        }
    }

    // ─── Wrap a plain command APDU with SM ───────────────────────────────────

    /**
     * @param cla  Class byte (will be OR'd with 0x0C for SM)
     * @param ins  Instruction byte
     * @param p1   Parameter 1
     * @param p2   Parameter 2
     * @param data Plain command data (may be empty)
     * @param le   Expected length (-1 = no Le)
     */
    fun wrap(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray, le: Int): ByteArray {
        incrementSSC()

        val do87 = if (data.isNotEmpty()) buildDO87(data) else ByteArray(0)
        val do97 = if (le >= 0) buildDO97(le) else ByteArray(0)

        // MAC input: SSC || (CLA|0x0C) INS P1 P2 80 [00..] || DO87 || DO97 80 [00..]
        val header = byteArrayOf(
            (cla or 0x0C).toByte(), ins.toByte(), p1.toByte(), p2.toByte()
        )
        val macInput = ssc + BACProtocol.iso9797Pad(header) + do87 + do97
        val do8e = buildDO8E(BACProtocol.retailMAC(ksMac, macInput))

        val body = do87 + do97 + do8e

        // Choose short or extended-length APDU encoding so we can safely
        // request more than 256 bytes from chips that support it.
        val needsExtended = body.size > 255 || le > 256
        return if (!needsExtended) {
            // Short APDU: header || Lc (1 byte) || body || Le (1 byte – 0x00 = 256)
            header + byteArrayOf(body.size.toByte()) + body + byteArrayOf(0x00)
        } else {
            // Extended APDU: header || Lc (00 hi lo) || body || Le (hi lo).
            // Le = 0x0000 means 65 536 (max) – the chip will return up to that many.
            val lcExt = byteArrayOf(0x00, ((body.size shr 8) and 0xFF).toByte(), (body.size and 0xFF).toByte())
            val leExt = byteArrayOf(0x00, 0x00)
            header + lcExt + body + leExt
        }
    }

    // ─── Unwrap a SM-protected response APDU ─────────────────────────────────

    /**
     * @param response Full raw response including SW1 SW2
     * @return Plain decrypted response data (without SW bytes)
     */
    fun unwrap(response: ByteArray): ByteArray {
        if (response.size < 2) throw SecureMessagingException("Response too short")

        incrementSSC()

        val sw1 = response[response.size - 2].toInt() and 0xFF
        val sw2 = response[response.size - 1].toInt() and 0xFF
        // Accept 9000 (success) AND 6282 (end-of-file warning – partial data still valid)
        // so that an over-read on the very last chunk does not abort the whole session.
        val isSuccess    = (sw1 == 0x90 && sw2 == 0x00)
        val isEofWarning = (sw1 == 0x62 && sw2 == 0x82)
        if (!isSuccess && !isEofWarning) {
            throw SecureMessagingException(
                "Card returned error: ${"%02X".format(sw1)}${"%02X".format(sw2)}"
            )
        }

        val tlvData = response.copyOf(response.size - 2)
        val parsed  = parseTLVs(tlvData)

        // BAC SM puts encrypted data in DO'87' (with padding indicator).
        // A few chips occasionally return DO'85' (no padding) – accept both.
        val do87 = parsed[0x87]
        val do85 = parsed[0x85]
        val do8e = parsed[0x8E] ?: throw SecureMessagingException("Missing MAC (DO'8E) in response")
        val do99 = parsed[0x99]   // status word in SM response (mandatory per ICAO; tolerated if absent)

        // Verify MAC over: SSC || [DO'87' or DO'85'] || DO'99'
        val do87Or85Raw = when {
            do87 != null -> buildDO87raw(do87)
            do85 != null -> buildDO85raw(do85)
            else         -> ByteArray(0)
        }
        val macInput = ssc + do87Or85Raw + (do99?.let { buildDO99raw(it) } ?: ByteArray(0))
        val expectedMAC = BACProtocol.retailMAC(ksMac, macInput)
        if (!expectedMAC.contentEquals(do8e)) throw SecureMessagingException("MAC verification failed on response")

        // ── Decrypt DO'87' (or return DO'85' as plaintext)
        if (do85 != null) return do85
        if (do87 == null) return ByteArray(0)

        // DO'87' content starts with padding indicator 0x01
        val encData = if (do87.isNotEmpty() && do87[0] == 0x01.toByte()) do87.copyOfRange(1, do87.size) else do87

        // ICAO 9303 Part 11 §9.8.6.1 – BAC Secure Messaging uses IV = 00 00 00 00 00 00 00 00.
        // (PACE-3DES uses IV = E_KSEnc(SSC); BAC does NOT.  Using the PACE rule with BAC keys
        //  corrupts the first 8 plaintext bytes of every response chunk, which made the file-
        //  length parsing return garbage and subsequent reads land at invalid offsets – ultimately
        //  surfacing to the user as "Tag was lost / timeout".)
        val iv = ByteArray(8)
        val plain = tripleDESDecrypt(ksEnc, iv, encData)
        return removePaddingBytes(plain)
    }

    // ─── DO builders ─────────────────────────────────────────────────────────

    private fun buildDO87(plainData: ByteArray): ByteArray {
        // ICAO 9303 §9.8.6.1 – BAC SM uses IV = 0 (NOT E_KSEnc(SSC) – that rule is for PACE).
        val iv    = ByteArray(8)
        val enc   = tripleDESEncrypt(ksEnc, iv, BACProtocol.iso9797Pad(plainData))
        val value = byteArrayOf(0x01) + enc      // padding indicator
        return tlv(0x87, value)
    }

    private fun buildDO87raw(value: ByteArray): ByteArray = tlv(0x87, value)
    private fun buildDO85raw(value: ByteArray): ByteArray = tlv(0x85, value)

    /**
     * Build DO'97' (expected response length).
     *  - Le ≤ 256  → 1-byte encoding (0 means 256).
     *  - Le > 256  → 2-byte big-endian encoding required for extended-length APDUs.
     */
    private fun buildDO97(le: Int): ByteArray {
        val leValue = when {
            le == 256        -> byteArrayOf(0x00)
            le < 256         -> byteArrayOf(le.toByte())
            le == 0x10000    -> byteArrayOf(0x00, 0x00)
            else             -> byteArrayOf(((le shr 8) and 0xFF).toByte(), (le and 0xFF).toByte())
        }
        return tlv(0x97, leValue)
    }
    private fun buildDO99raw(value: ByteArray): ByteArray = tlv(0x99, value)
    private fun buildDO8E(mac: ByteArray): ByteArray = tlv(0x8E, mac)

    // ─── Simple TLV parser ────────────────────────────────────────────────────

    private fun parseTLVs(data: ByteArray): Map<Int, ByteArray> {
        val result = mutableMapOf<Int, ByteArray>()
        var i = 0
        while (i < data.size) {
            val tag = data[i++].toInt() and 0xFF
            if (tag == 0) continue
            val len = parseLength(data, i)
            i += len.second
            result[tag] = data.copyOfRange(i, i + len.first)
            i += len.first
        }
        return result
    }

    private fun parseLength(data: ByteArray, offset: Int): Pair<Int, Int> {
        val first = data[offset].toInt() and 0xFF
        return when {
            first < 0x80 -> Pair(first, 1)
            first == 0x81 -> Pair(data[offset + 1].toInt() and 0xFF, 2)
            first == 0x82 -> Pair(
                ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset + 2].toInt() and 0xFF), 3
            )
            else -> Pair(first and 0x7F, 1)
        }
    }

    // ─── Crypto helpers ───────────────────────────────────────────────────────

    private fun tripleDESEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val fullKey = if (key.size == 16) key + key.copyOf(8) else key
        val cipher  = Cipher.getInstance("DESede/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fullKey, "DESede"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun tripleDESDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val fullKey = if (key.size == 16) key + key.copyOf(8) else key
        val cipher  = Cipher.getInstance("DESede/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(fullKey, "DESede"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /**
     * Strip ISO/IEC 9797-1 Method 2 padding (single 0x80, then zero bytes to block boundary).
     * Tolerant of inputs that have already been stripped (returns them unchanged).
     */
    private fun removePaddingBytes(data: ByteArray): ByteArray {
        var end = data.size
        while (end > 0 && data[end - 1] == 0x00.toByte()) end--
        if (end > 0 && data[end - 1] == 0x80.toByte()) end--
        return data.copyOf(end)
    }

    // ─── TLV encoding ─────────────────────────────────────────────────────────

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val lenBytes = encodeLength(value.size)
        return byteArrayOf(tag.toByte()) + lenBytes + value
    }

    private fun encodeLength(len: Int): ByteArray = when {
        len < 0x80   -> byteArrayOf(len.toByte())
        len < 0x100  -> byteArrayOf(0x81.toByte(), len.toByte())
        else         -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())
    }
}

class SecureMessagingException(msg: String) : Exception(msg)
