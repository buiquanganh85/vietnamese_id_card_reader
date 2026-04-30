package com.vn.cccdreader.nfc

import com.vn.cccdreader.data.IDCardData

/**
 * Logical Data Structure (LDS) parser for ICAO 9303 data groups.
 *
 * Supported data groups:
 *  DG1  (tag 0x61) – MRZ data
 *  DG2  (tag 0x75) – Face image (JPEG / JPEG2000)
 *  DG11 (tag 0x6B) – Additional personal data (name in native script, etc.)
 *  DG13 (tag 0x6D) – Optional details (Vietnamese-specific)
 */
object LDSParser {

    // ─── DG1 – MRZ ───────────────────────────────────────────────────────────

    /**
     * Parse DG1 bytes and fill [IDCardData] with MRZ-derived fields.
     *
     * TD1 = 3 × 30 chars, TD3 = 2 × 44 chars
     */
    fun parseDG1(data: ByteArray, builder: IDCardData.() -> IDCardData = { this }): ParsedMRZ? {
        val mrzBytes = extractTag(data, 0x5F1F) ?: return null

        // Strip ALL control / whitespace characters (handles \r, \n, \r\n, spaces).
        // Some chips separate the three TD1 lines with CRLF, making the raw string
        // 92 chars instead of 90, which previously caused an unrecognised-length return.
        val mrz = String(mrzBytes, Charsets.UTF_8)
            .filter { it.isLetterOrDigit() || it == '<' }

        return when {
            mrz.length == 90 -> parseTD1(mrz)    // TD1: 3 × 30 chars (CCCD, travel docs)
            mrz.length == 88 -> parseTD3(mrz)    // TD3: 2 × 44 chars (passport)
            mrz.length >= 90 -> parseTD1(mrz.substring(0, 90))  // truncate excess padding
            mrz.length >= 88 -> parseTD3(mrz.substring(0, 88))
            else -> null
        }
    }

    data class ParsedMRZ(
        val documentNumber: String,
        val surname: String,
        val givenNames: String,
        val dateOfBirth: String,
        val sex: String,
        val expiryDate: String,
        val nationality: String,
        val personalNumber: String,
        val line1: String = "",
        val line2: String = "",
        val line3: String = ""
    )

    private fun parseTD1(mrz: String): ParsedMRZ {
        val l1 = mrz.substring(0, 30)
        val l2 = mrz.substring(30, 60)
        val l3 = mrz.substring(60, 90)

        val docNum = l1.substring(5, 14).replace("<", "")
        val optional1 = l1.substring(15, 29).replace("<", "")
        val dob       = l2.substring(0, 6)
        val sex       = l2.substring(7, 8)
        val exp       = l2.substring(8, 14)
        val nat       = l2.substring(15, 18)
        val optional2 = l2.substring(18, 29).replace("<", "")
        val namePart  = l3.split("<<")
        val surname   = (namePart.getOrNull(0) ?: "").replace("<", " ").trim()
        val given     = (namePart.getOrNull(1) ?: "").replace("<", " ").trim()

        // personal number = optional data on line 2 or line 1
        val personalNumber = optional1.ifEmpty { optional2 }.ifEmpty { docNum }

        return ParsedMRZ(docNum, surname, given, dob, sex, exp, nat, personalNumber, l1, l2, l3)
    }

    private fun parseTD3(mrz: String): ParsedMRZ {
        val l1 = mrz.substring(0, 44)
        val l2 = mrz.substring(44, 88)

        val namePart = l1.substring(5).split("<<")
        val surname  = (namePart.getOrNull(0) ?: "").replace("<", " ").trim()
        val given    = (namePart.getOrNull(1) ?: "").replace("<", " ").trim()

        val docNum   = l2.substring(0, 9).replace("<", "")
        val nat      = l2.substring(10, 13)
        val dob      = l2.substring(13, 19)
        val sex      = l2.substring(20, 21)
        val exp      = l2.substring(21, 27)
        val personal = l2.substring(28, 42).replace("<", "")

        return ParsedMRZ(docNum, surname, given, dob, sex, exp, nat, personal, l1, l2)
    }

    // ─── DG2 – face image ─────────────────────────────────────────────────────

    /**
     * Extract raw image bytes from DG2.
     * DG2 follows ICAO 9303-10 / ISO 19794-5 (CBEFF structure).
     *
     * Structure:
     *  75 [len]            – DG2 tag
     *   7F61 [len]         – Biometric information group
     *    02 [len] count    – Number of biometric data blocks
     *    7F60 [len]        – Biometric data block
     *     A1 [len]         – Biometric information template
     *     5F2E or 7F2E     – Biometric data (contains JPEG/JP2000)
     */
    fun parseDG2(data: ByteArray): ByteArray? {
        // Try 5F2E first (biometric data object), then 7F2E
        val biometricData = extractTag(data, 0x5F2E) ?: extractTag(data, 0x7F2E)
            ?: return null

        // Skip the CBEFF / Facial Record Header to reach the raw JPEG
        // The header is a fixed-size prefix before the image data
        return extractImageFromBiometricData(biometricData)
    }

    private fun extractImageFromBiometricData(data: ByteArray): ByteArray? {
        // ICAO facial image record header is 14 bytes ("FAC\0" + version + length + nFaces)
        // followed by facial record data block header (20 bytes), then image data
        // Look for JPEG SOI marker (FF D8) or JPEG2000 magic (00 00 00 0C 6A 50 20 20)
        for (i in data.indices) {
            if (i + 1 < data.size) {
                // JPEG
                if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte()) {
                    return data.copyOfRange(i, data.size)
                }
                // JPEG2000 magic: 00 00 00 0C 6A 50 20 20
                if (i + 7 < data.size &&
                    data[i]     == 0x00.toByte() &&
                    data[i + 1] == 0x00.toByte() &&
                    data[i + 2] == 0x00.toByte() &&
                    data[i + 3] == 0x0C.toByte() &&
                    data[i + 4] == 0x6A.toByte() &&
                    data[i + 5] == 0x50.toByte()
                ) {
                    return data.copyOfRange(i, data.size)
                }
            }
        }
        return data   // Return raw if no magic found
    }

    // ─── Generic BER-TLV helpers ──────────────────────────────────────────────

    /**
     * Recursively search for a tag in BER-TLV encoded data.
     * Handles 1- and 2-byte tags and multibyte lengths.
     */
    fun extractTag(data: ByteArray, targetTag: Int): ByteArray? {
        var i = 0
        while (i < data.size) {
            if (i >= data.size) break

            val tagByte = data[i].toInt() and 0xFF
            val tag: Int
            val tagLen: Int

            // Check for 2-byte tag (first byte ends in 1F)
            if (tagByte and 0x1F == 0x1F) {
                if (i + 1 >= data.size) break
                tag    = (tagByte shl 8) or (data[i + 1].toInt() and 0xFF)
                tagLen = 2
            } else {
                tag    = tagByte
                tagLen = 1
            }
            i += tagLen

            // Parse length
            if (i >= data.size) break
            val (len, lenBytes) = decodeBerLength(data, i)
            i += lenBytes

            if (i + len > data.size) break
            val value = data.copyOfRange(i, i + len)

            if (tag == targetTag) return value

            // If constructed (bit 6 of first tag byte set), recurse into value
            if (tagByte and 0x20 != 0) {
                val found = extractTag(value, targetTag)
                if (found != null) return found
            }

            i += len
        }
        return null
    }

    private fun decodeBerLength(data: ByteArray, offset: Int): Pair<Int, Int> {
        if (offset >= data.size) return Pair(0, 1)
        val first = data[offset].toInt() and 0xFF
        return when {
            first < 0x80   -> Pair(first, 1)
            first == 0x81  -> Pair(data[offset + 1].toInt() and 0xFF, 2)
            first == 0x82  -> Pair(
                ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset + 2].toInt() and 0xFF), 3
            )
            first == 0x83  -> Pair(
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8)  or
                 (data[offset + 3].toInt() and 0xFF), 4
            )
            else           -> Pair(0, 1)
        }
    }

    /** Parse EF.COM to get list of available DG tags */
    fun parseEFCOM(data: ByteArray): List<Int> {
        val tags = mutableListOf<Int>()
        // EF.COM: 60 [len] 5F01 [len] version 5F36 [len] unicode 5C [len] [dg-tags...]
        val dgList = extractTag(data, 0x5C) ?: return tags
        dgList.forEach { tags.add(it.toInt() and 0xFF) }
        return tags
    }
}
