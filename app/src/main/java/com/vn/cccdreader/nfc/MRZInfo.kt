package com.vn.cccdreader.nfc

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

data class MRZValidationResult(
    val documentNumberValid: Boolean,
    val dateOfBirthValid: Boolean,
    val expiryDateValid: Boolean,
    val calculatedDocumentNumberCheckDigit: String,
    val calculatedDateOfBirthCheckDigit: String,
    val calculatedExpiryDateCheckDigit: String,
    val extractedDocumentNumberCheckDigit: String? = null,
    val extractedDateOfBirthCheckDigit: String? = null,
    val extractedExpiryDateCheckDigit: String? = null
) {
    val isValid: Boolean
        get() = documentNumberValid && dateOfBirthValid && expiryDateValid
}

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
    val expiryDate: String,
    val documentNumberCheckDigit: String? = null,
    val dateOfBirthCheckDigit: String? = null,
    val expiryDateCheckDigit: String? = null
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

    fun validateCheckDigits(): MRZValidationResult {
        val docNumPadded = documentNumber.padEnd(9, '<').take(9)
        val docNumChk = checkDigit(docNumPadded)
        val dobChk = checkDigit(dateOfBirth)
        val expChk = checkDigit(expiryDate)

        val docNumValid = documentNumberCheckDigit == null || documentNumberCheckDigit == docNumChk
        val dobValid = dateOfBirthCheckDigit == null || dateOfBirthCheckDigit == dobChk
        val expValid = expiryDateCheckDigit == null || expiryDateCheckDigit == expChk

        return MRZValidationResult(
            documentNumberValid = docNumValid,
            dateOfBirthValid = dobValid,
            expiryDateValid = expValid,
            calculatedDocumentNumberCheckDigit = docNumChk,
            calculatedDateOfBirthCheckDigit = dobChk,
            calculatedExpiryDateCheckDigit = expChk,
            extractedDocumentNumberCheckDigit = documentNumberCheckDigit,
            extractedDateOfBirthCheckDigit = dateOfBirthCheckDigit,
            extractedExpiryDateCheckDigit = expiryDateCheckDigit
        )
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
         * Parse MRZ from TD1 (3×30) string with check digit extraction.
         * Returns null if format does not match.
         *
         * TD1 Format:
         *   Line 1: I<VNM[docNum: 5-13][chk: 14][optional: 15-29]
         *   Line 2: [dob: 0-5][chk: 6][sex: 7][exp: 8-13][chk: 14][nationality: 15-17][optional: 18-29]
         *   Line 3: Names
         */
        fun fromTD1(line1: String, line2: String, line3: String): MRZInfo? {
            if (line1.length < 30 || line2.length < 30 || line3.length < 30) return null
            val docNum = line1.substring(5, 14).trimEnd('<')
            val docNumChk = if (line1.length > 14) line1[14].toString() else null
            val dob   = line2.substring(0, 6)
            val dobChk = if (line2.length > 6) line2[6].toString() else null
            val exp   = line2.substring(8, 14)
            val expChk = if (line2.length > 14) line2[14].toString() else null
            return MRZInfo(docNum, dob, exp, docNumChk, dobChk, expChk)
        }

        /**
         * Parse MRZ from TD3 (2×44) – passport format with check digit extraction.
         *
         * TD3 Format:
         *   Line 1: P<[country][names]
         *   Line 2: [docNum: 0-8][chk: 9][dob: 13-18][chk: 19][exp: 21-26][chk: 27]...
         */
        fun fromTD3(line1: String, line2: String): MRZInfo? {
            if (line1.length < 44 || line2.length < 44) return null
            val docNum = line2.substring(0, 9).trimEnd('<')
            val docNumChk = if (line2.length > 9) line2[9].toString() else null
            val dob    = line2.substring(13, 19)
            val dobChk = if (line2.length > 19) line2[19].toString() else null
            val exp    = line2.substring(21, 27)
            val expChk = if (line2.length > 27) line2[27].toString() else null
            return MRZInfo(docNum, dob, exp, docNumChk, dobChk, expChk)
        }
    }
}
