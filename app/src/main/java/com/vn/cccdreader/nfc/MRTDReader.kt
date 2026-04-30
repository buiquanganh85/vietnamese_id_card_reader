package com.vn.cccdreader.nfc

import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import com.vn.cccdreader.data.IDCardData

/**
 * High-level MRTD reader that orchestrates:
 *  1. Select eMRTD application (AID = A0 00 00 02 47 10 01)
 *  2. BAC authentication
 *  3. Reading DG1 (MRZ) and DG2 (face image) via Secure Messaging
 */
class MRTDReader(private val isoDep: IsoDep) {

    companion object {
        // eMRTD AID
        private val AID = byteArrayOf(
            0xA0.toByte(), 0x00, 0x00, 0x02, 0x47, 0x10, 0x01
        )

        // EF file IDs
        private const val FID_EF_COM  = 0x011E
        private const val FID_EF_SOD  = 0x011D
        private const val FID_DG1     = 0x0101
        private const val FID_DG2     = 0x0102

        // APDU constants
        private const val CLA_NO_SM         = 0x00
        private const val INS_SELECT        = 0xA4
        private const val INS_GET_CHALLENGE = 0x84
        private const val INS_MUTUAL_AUTH   = 0x82
        private const val INS_READ_BINARY   = 0xB0

        /**
         * Safe chunk size for SM-wrapped READ BINARY responses when ONLY short
         * APDUs are supported. Each plaintext byte is wrapped with ~30 bytes of SM
         * overhead (DO'87' header + padding + DO'99' + DO'8E'); the whole SM
         * response must fit in 256 bytes, so the safe ceiling is ~224. We use 192
         * to leave headroom and keep round-trips brisk on slow chips.
         */
        private const val MAX_READ_SIZE_SHORT = 192

        /**
         * When the chip supports Extended-Length APDUs (most Vietnamese CCCDs from
         * Infineon / NXP do), each chunk can be much larger – fewer round-trips
         * means a much smaller window for a TagLost.  Cap at 0xDF0 to stay below
         * the chip's maxTransceiveLength on most devices.
         */
        private const val MAX_READ_SIZE_EXTENDED = 0x0DF0   // 3568

        /** IsoDep transceive timeout in ms.  30 s covers slow chips and large DG2. */
        private const val ISODEP_TIMEOUT_MS = 30_000
    }

    /**
     * Read the card and return an [IDCardData] populated from the chip.
     * Must be called from a background thread (does I/O).
     *
     * @param mrzInfo      MRZ key fields needed for BAC
     * @param existingData Any data already collected (camera photos etc.)
     * @param onPhase      Optional UI callback with a human-readable phase description.
     *                     Called on the IO thread – post to Main if updating UI.
     */
    fun readCard(
        mrzInfo: MRZInfo,
        existingData: IDCardData = IDCardData(),
        onPhase: ((String) -> Unit)? = null
    ): IDCardData {
        try {
            // Generous timeout – DG2 (face photo) can be 10-50 KB requiring
            // many READ BINARY round-trips that each need time to complete.
            // Also detect extended-length APDU support so we can read DG2 in
            // a handful of large chunks instead of dozens of small ones –
            // dramatically shrinks the window where a TagLost can happen.
            isoDep.timeout = ISODEP_TIMEOUT_MS
            val extendedApduSupported = runCatching { isoDep.isExtendedLengthApduSupported }.getOrDefault(false)
            // Clamp the per-chunk read size to what the chip's transport actually
            // accepts (maxTransceiveLength is the largest APDU this device/chip pair
            // is willing to exchange). On chips that under-report we fall back to
            // the SM short-APDU ceiling.
            val transportLimit = runCatching { isoDep.maxTransceiveLength }.getOrDefault(0)
            val maxRead = when {
                extendedApduSupported && transportLimit > 256 ->
                    minOf(MAX_READ_SIZE_EXTENDED, transportLimit - 64) // 64-byte SM overhead headroom
                else -> MAX_READ_SIZE_SHORT
            }

            // 1. Select eMRTD application
            onPhase?.invoke("Kết nối chip…\nConnecting to chip…")
            selectApplication()

            // 2. Derive static keys from MRZ
            val (kEnc, kMac) = BACProtocol.deriveKeys(mrzInfo)

            // 3-6. BAC handshake with retry on transient TagLost during
            //      challenge/mutual-auth (chip can briefly drop while computing).
            onPhase?.invoke("Xác thực BAC…\nBAC authentication…")
            val sm = bacHandshakeWithRetry(kEnc, kMac, maxAttempts = 3)

            // 7. Read DG1 (MRZ) – small file, fast
            onPhase?.invoke("Đọc dữ liệu MRZ (DG1)…\nReading MRZ data… ⚠ Giữ thẻ yên!")
            val dg1Bytes = readEF(sm, FID_DG1, maxRead)
            val parsedMRZ = LDSParser.parseDG1(dg1Bytes)

            // 8. Read DG2 (face image) – large file, can take several seconds
            onPhase?.invoke("Đọc ảnh khuôn mặt (DG2)…\nReading face photo… ⚠ Giữ thẻ yên!")
            val dg2Bytes = try {
                readEF(sm, FID_DG2, maxRead) { read, total ->
                    val pct = if (total > 0) (read * 100 / total) else 0
                    onPhase?.invoke("Đọc ảnh khuôn mặt… $pct%\nReading face photo… $pct% ⚠ Giữ thẻ yên!")
                }
            } catch (e: TagLostException) {
                throw e          // re-throw so outer catch gives user the right message
            } catch (e: Exception) {
                null             // DG2 optional – continue without face photo
            }
            val faceImage = dg2Bytes?.let { LDSParser.parseDG2(it) }

            return existingData.copy(
                documentNumber = parsedMRZ?.documentNumber ?: mrzInfo.documentNumber,
                surname        = parsedMRZ?.surname ?: "",
                givenNames     = parsedMRZ?.givenNames ?: "",
                dateOfBirth    = parsedMRZ?.dateOfBirth ?: mrzInfo.dateOfBirth,
                sex            = parsedMRZ?.sex ?: "",
                expiryDate     = parsedMRZ?.expiryDate ?: mrzInfo.expiryDate,
                nationality    = parsedMRZ?.nationality ?: "",
                personalNumber = parsedMRZ?.personalNumber ?: "",
                mrzLine1       = parsedMRZ?.line1 ?: "",
                mrzLine2       = parsedMRZ?.line2 ?: "",
                mrzLine3       = parsedMRZ?.line3 ?: "",
                faceImageBytes = faceImage,
                nfcReadSuccess = true
            )

        } catch (e: TagLostException) {
            return existingData.copy(
                nfcReadSuccess = false,
                nfcErrorMessage = "Thẻ mất kết nối / Tag lost – giữ thẻ yên và thử lại.\n\n" +
                    "Tips:\n" +
                    "• Đặt thẻ thẳng lên vị trí anten NFC (thường ở góc trên hoặc giữa lưng điện thoại)\n" +
                    "• Giữ nguyên trong 5-10 giây, không di chuyển\n" +
                    "• Tháo bao da / ốp lưng dày nếu có\n\n" +
                    "Place the card flat on the NFC antenna and hold it perfectly still for 5–10 s. " +
                    "Remove thick cases if present."
            )
        } catch (e: BACException) {
            return existingData.copy(
                nfcReadSuccess = false,
                nfcErrorMessage = "BAC failed: ${e.message}"
            )
        } catch (e: SecureMessagingException) {
            return existingData.copy(
                nfcReadSuccess = false,
                nfcErrorMessage = "Secure messaging error: ${e.message}"
            )
        } catch (e: Exception) {
            return existingData.copy(
                nfcReadSuccess = false,
                nfcErrorMessage = e.message ?: "Unknown error"
            )
        }
    }

    // ─── APDU commands ────────────────────────────────────────────────────────

    private fun selectApplication() {
        // Case 4 APDU with Le=0x00 – tolerated by every chip we have tested,
        // and required by some Vietnamese CCCD chips that reject pure case-3.
        val apdu = byteArrayOf(
            CLA_NO_SM.toByte(), INS_SELECT.toByte(), 0x04, 0x0C,
            AID.size.toByte()
        ) + AID + byteArrayOf(0x00)
        val response = transmit(apdu)
        checkSW(response, "SELECT APPLICATION")
    }

    private fun getChallenge(): ByteArray {
        val apdu = byteArrayOf(
            CLA_NO_SM.toByte(), INS_GET_CHALLENGE.toByte(), 0x00, 0x00, 0x08
        )
        val response = transmit(apdu)
        checkSW(response, "GET CHALLENGE")
        return response.copyOf(response.size - 2)   // strip SW1 SW2
    }

    private fun mutualAuthenticate(payload: ByteArray): ByteArray {
        val apdu = byteArrayOf(
            CLA_NO_SM.toByte(), INS_MUTUAL_AUTH.toByte(), 0x00, 0x00,
            payload.size.toByte()
        ) + payload + byteArrayOf(0x28)   // Le = 0x28 = 40
        val response = transmit(apdu)
        checkSW(response, "MUTUAL AUTHENTICATE")
        return response.copyOf(response.size - 2)
    }

    /**
     * Run the four-step BAC handshake (GET CHALLENGE → build response → MUTUAL
     * AUTHENTICATE → derive session keys) with a small retry loop.  A genuinely
     * wrong MRZ throws BACException immediately; a transient TagLostException is
     * retried up to [maxAttempts] times because the chip can briefly drop
     * communication while it computes the 3DES MAC.
     */
    private fun bacHandshakeWithRetry(
        kEnc: ByteArray,
        kMac: ByteArray,
        maxAttempts: Int
    ): SecureMessaging {
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                val rndIC = getChallenge()
                val authChallenge = BACProtocol.buildAuthChallenge(rndIC, kEnc, kMac)
                val authResponse = mutualAuthenticate(authChallenge.apduBody)
                val session = BACProtocol.deriveSessionKeys(
                    rndIC, authResponse, kEnc, kMac, authChallenge.rndIFD, authChallenge.kIFD
                )
                return SecureMessaging(session.ksEnc, session.ksMac, session.ssc)
            } catch (e: BACException) {
                throw e   // wrong MRZ – retrying will not help
            } catch (e: TagLostException) {
                lastError = e
                if (attempt < maxAttempts - 1) Thread.sleep(150)
            } catch (e: Exception) {
                // SW=6300 / 6982 etc. propagate immediately – not transient.
                throw e
            }
        }
        throw lastError ?: Exception("BAC handshake failed")
    }

    /**
     * Map a standard ICAO EF File Identifier to its Short File Identifier (SFI).
     *
     * SFI encoding (P1 = 0x80 | SFI) combines EF selection and data read into
     * one SM-wrapped READ BINARY command, avoiding SM-wrapped SELECT EF entirely.
     *
     * This chip's SM implementation does NOT support SM SELECT EF (INS=0xA4
     * with CLA=0x0C).  Every variant tried returned either a ~15-second hang
     * (P2=0x0C, no response data) or SW=6D00 (P2=0x00, return FCI).
     * SFI-based READ BINARY is the only supported path for file access under SM.
     */
    private fun fidToSfi(fid: Int): Int = when (fid) {
        FID_DG1    -> 0x01
        FID_DG2    -> 0x02
        FID_EF_COM -> 0x1E
        FID_EF_SOD -> 0x1D
        else       -> throw Exception("No SFI mapping for FID 0x${fid.toString(16).uppercase()}")
    }

    /**
     * SM-wrapped READ BINARY using Short File Identifier (P1 = 0x80 | SFI).
     *
     * Per ISO/IEC 7816-4 §7.2.3, this implicitly selects the EF identified by
     * SFI as the current EF.  All subsequent regular READ BINARY commands
     * (P1 = high-offset byte, P2 = low-offset byte) will continue from that EF.
     *
     * P2 carries the start offset (must be 0 for the first/selection read).
     */
    private fun readBinaryWithSFI(sm: SecureMessaging, sfi: Int, length: Int): ByteArray {
        val smApdu = sm.wrap(
            CLA_NO_SM, INS_READ_BINARY,
            0x80 or (sfi and 0x1F), 0x00,   // P1 = 0x80|SFI, P2 = offset 0
            ByteArray(0), length
        )
        val response = transmit(smApdu)
        return sm.unwrap(response)
    }

    /**
     * Read a complete EF without ever issuing SM SELECT EF.
     *
     * The first READ BINARY uses SFI encoding (P1 = 0x80 | SFI) to implicitly
     * select the EF and read the first bytes in one round-trip.  Subsequent
     * chunks use ordinary P1/P2 offset encoding against the now-selected EF.
     *
     * @param maxRead    largest plaintext byte count we may request per chunk
     * @param onProgress optional callback: (bytesRead, totalBytes)
     */
    private fun readEF(
        sm: SecureMessaging,
        fid: Int,
        maxRead: Int,
        onProgress: ((Int, Int) -> Unit)? = null
    ): ByteArray {
        val sfi = fidToSfi(fid)

        // First read: SFI implicitly selects the EF and reads the TLV header.
        val header = readBinaryWithSFI(sm, sfi, 4)
        val (totalLen, headerSize) = parseTotalLength(header)
        val fullSize = headerSize + totalLen

        // Sanity-check parsed length: caps DG2 around 64 KB which is generous.
        if (fullSize <= 0 || fullSize > 0x10000) {
            throw Exception("EF length is implausible (parsed $fullSize bytes)")
        }

        val result = ByteArray(fullSize)
        header.copyInto(result)

        var offset = 4
        while (offset < fullSize) {
            val chunkSize = minOf(maxRead, fullSize - offset)
            val chunk = readBinaryChunkWithRetry(sm, offset, chunkSize)
            if (chunk.isEmpty()) break   // chip signalled end-of-file early
            chunk.copyInto(result, offset)
            offset += chunk.size
            onProgress?.invoke(offset, fullSize)
        }
        return if (offset < fullSize) result.copyOf(offset) else result
    }

    /**
     * Read one chunk with up to [maxRetries] retries on transient I/O errors.
     * A [TagLostException] is NOT retried – it propagates immediately so the
     * caller can give the user a "hold still" prompt.
     */
    private fun readBinaryChunkWithRetry(
        sm: SecureMessaging,
        offset: Int,
        length: Int,
        maxRetries: Int = 2
    ): ByteArray {
        var lastError: Exception? = null
        repeat(maxRetries + 1) { attempt ->
            try {
                return readBinaryChunk(sm, offset, length)
            } catch (e: TagLostException) {
                throw e   // physical loss – do not retry, propagate immediately
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries) Thread.sleep(80)   // brief pause before retry
            }
        }
        throw lastError!!
    }

    private fun readBinaryChunk(sm: SecureMessaging, offset: Int, length: Int): ByteArray {
        val smApdu = sm.wrap(
            CLA_NO_SM, INS_READ_BINARY,
            (offset shr 8) and 0xFF, offset and 0xFF,
            ByteArray(0), length
        )
        val response = transmit(smApdu)
        return sm.unwrap(response)
    }

    /** Parse the outer BER-TLV total length from the first bytes of an EF */
    private fun parseTotalLength(header: ByteArray): Pair<Int, Int> {
        if (header.size < 2) return Pair(0, 1)
        var i = 1   // skip outer tag byte(s)
        // Skip 2-byte tags (first byte & 0x1F == 0x1F)
        if (header[0].toInt() and 0x1F == 0x1F) i = 2

        if (i >= header.size) return Pair(0, i + 1)
        val first = header[i].toInt() and 0xFF
        return when {
            first < 0x80  -> Pair(first, i + 1)
            first == 0x81 -> Pair(header[i + 1].toInt() and 0xFF, i + 2)
            first == 0x82 -> Pair(
                ((header[i + 1].toInt() and 0xFF) shl 8) or (header[i + 2].toInt() and 0xFF), i + 3
            )
            else          -> Pair(first and 0x7F, i + 1)
        }
    }

    // ─── Low-level I/O ────────────────────────────────────────────────────────

    private fun transmit(apdu: ByteArray): ByteArray {
        return isoDep.transceive(apdu)
    }

    private fun checkSW(response: ByteArray, context: String) {
        if (response.size < 2) throw Exception("$context: empty response")
        val sw1 = response[response.size - 2].toInt() and 0xFF
        val sw2 = response[response.size - 1].toInt() and 0xFF
        if (sw1 != 0x90 || sw2 != 0x00) {
            val swHex = "%02X%02X".format(sw1, sw2)
            val hint = when (swHex) {
                "6300" -> " (BAC failed – check MRZ: document number, date of birth, expiry date)"
                "6700" -> " (Wrong length – APDU framing rejected by chip)"
                "6982" -> " (Security status not satisfied – chip may require PACE)"
                "6983" -> " (Authentication method blocked – BAC counter exhausted)"
                "6A80" -> " (Incorrect parameters in command data)"
                "6A82" -> " (File not found – chip does not expose this DG)"
                "6A86" -> " (Incorrect P1-P2 parameters)"
                "6A88" -> " (Referenced data not found)"
                "6B00" -> " (Wrong P1-P2 / offset out of range)"
                "6CXX" -> " (Wrong Le – retry with the indicated length)"
                "6D00" -> " (Instruction not supported)"
                "6E00" -> " (Class not supported)"
                else   -> ""
            }
            throw Exception("$context failed: SW=$swHex$hint")
        }
    }
}
