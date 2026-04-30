package com.vn.cccdreader.nfc

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Holds the three MRZ fields needed to derive BAC keys, plus validation helpers.
 *
 * For Vietnamese CCCD (TD1 format, 3 × 30 chars):
 *   Line 1: I<VNM[docNumber9][chk][optional][chk_composite]
 *   Line 2: [YYMMDD][chk][sex][YYMMDD][chk][VNM][optional]
 *   Line 3: [SURNAME]<<[GIVEN_NAMES]
 *
 * BAC key seed = SHA-1(docNum + docChk + dob + dobChk + exp + expChk)[0..15]
 */
@Parcelize
data class MRZInfo(
    /** 9 characters (padded with '<') */
    val documentNumber: String,
    /** YYMMDD */
    val dateOfBirth: String,
    /** YYMMDD */
    val expiryDate: String
) : Parcelable {

    /** The 25-character string used as input to SHA-1 for BAC key derivation */
    fun mrzKeySeed(): String {
        val docNum = documentNumber.padEnd(9, '<').take(9)
        return "$docNum${checkDigit(docNum)}$dateOfBirth${checkDigit(dateOfBirth)}$expiryDate${checkDigit(expiryDate)}"
    }

    fun isValid(): Boolean {
        if (documentNumber.isBlank() || dateOfBirth.length != 6 || expiryDate.length != 6) return false
        if (!dateOfBirth.all { it.isDigit() }) return false
        if (!expiryDate.all { it.isDigit() }) return false
        return true
    }

    companion object {
        private val WEIGHTS = intArrayOf(7, 3, 1)

        private fun charValue(c: Char): Int = when {
            c == '<' -> 0
            c.isDigit() -> c - '0'
            c.isLetter() -> c.uppercaseChar() - 'A' + 10
            else -> 0
        }

        fun checkDigit(input: String): String {
            var sum = 0
            input.forEachIndexed { i, c -> sum += charValue(c) * WEIGHTS[i % 3] }
            return (sum % 10).toString()
        }

        /**
         * Parse MRZ from TD1 (3×30) string.
         * Returns null if format does not match.
         */
        fun fromTD1(line1: String, line2: String, line3: String): MRZInfo? {
            if (line1.length < 30 || line2.length < 30 || line3.length < 30) return null
            // Document number: positions 5-13 of line 1 (0-indexed)
            val docNum = line1.substring(5, 14).trimEnd('<')
            val dob   = line2.substring(0, 6)
            val exp   = line2.substring(8, 14)
            return MRZInfo(docNum, dob, exp)
        }

        /**
         * Parse MRZ from TD3 (2×44) – passport format, rarely used for CCCD but included for completeness.
         */
        fun fromTD3(line1: String, line2: String): MRZInfo? {
            if (line1.length < 44 || line2.length < 44) return null
            val docNum = line2.substring(0, 9).trimEnd('<')
            val dob    = line2.substring(13, 19)
            val exp    = line2.substring(21, 27)
            return MRZInfo(docNum, dob, exp)
        }
    }
}
