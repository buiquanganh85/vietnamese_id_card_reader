package com.vn.cccdreader.nfc

import org.junit.Test
import org.junit.Assert.*

class MRZInfoTest {

    @Test
    fun testCheckDigitCalculation() {
        // Test check digit calculation for document number
        val docNum = "001234567"
        val expectedChk = "5"
        val calculated = MRZInfo.checkDigit(docNum)
        assertEquals(expectedChk, calculated)
    }

    @Test
    fun testTD1ParsingWithValidCheckDigits() {
        // Vietnamese CCCD TD1 format (3x30)
        // Valid example with correct check digits
        val line1 = "I<VNM0012345678<<<<<<<<<<<<<2"
        val line2 = "900101<1234567890<VNM0000000000"
        val line3 = "NGUYEN<<VAN<<A<<<<<<<<<<<<<<<<"

        val mrzInfo = MRZInfo.fromTD1(line1, line2, line3)
        assertNotNull(mrzInfo)

        mrzInfo?.let {
            assertEquals("001234567", it.documentNumber)
            assertEquals("900101", it.dateOfBirth)
            assertEquals("123456", it.expiryDate)
            assertEquals("2", it.documentNumberCheckDigit)
            assertEquals("7", it.dateOfBirthCheckDigit)
            assertEquals("8", it.expiryDateCheckDigit)
        }
    }

    @Test
    fun testTD1ValidationWithMatchingCheckDigits() {
        val docNum = "001234567"
        val dob = "900101"
        val exp = "280101"

        val mrzInfo = MRZInfo(
            documentNumber = docNum,
            dateOfBirth = dob,
            expiryDate = exp,
            documentNumberCheckDigit = MRZInfo.checkDigit(docNum.padEnd(9, '<')),
            dateOfBirthCheckDigit = MRZInfo.checkDigit(dob),
            expiryDateCheckDigit = MRZInfo.checkDigit(exp)
        )

        val validationResult = mrzInfo.validateCheckDigits()
        assertTrue(validationResult.documentNumberValid)
        assertTrue(validationResult.dateOfBirthValid)
        assertTrue(validationResult.expiryDateValid)
        assertTrue(validationResult.isValid)
    }

    @Test
    fun testTD1ValidationWithIncorrectCheckDigits() {
        val docNum = "001234567"
        val dob = "900101"
        val exp = "280101"

        val mrzInfo = MRZInfo(
            documentNumber = docNum,
            dateOfBirth = dob,
            expiryDate = exp,
            documentNumberCheckDigit = "9", // Wrong check digit
            dateOfBirthCheckDigit = MRZInfo.checkDigit(dob),
            expiryDateCheckDigit = MRZInfo.checkDigit(exp)
        )

        val validationResult = mrzInfo.validateCheckDigits()
        assertFalse(validationResult.documentNumberValid)
        assertTrue(validationResult.dateOfBirthValid)
        assertTrue(validationResult.expiryDateValid)
        assertFalse(validationResult.isValid)
    }

    @Test
    fun testTD3ParsingWithValidCheckDigits() {
        // Passport TD3 format (2x44)
        val line1 = "P<VNMNGUYEN<<VAN<<A<<<<<<<<<<<<<<<<<<<<<<<<1"
        val line2 = "0012345678<5900101<1280101<<<<<<<<<<<<<<<<01"

        val mrzInfo = MRZInfo.fromTD3(line1, line2)
        assertNotNull(mrzInfo)

        mrzInfo?.let {
            assertEquals("001234567", it.documentNumber)
            assertEquals("900101", it.dateOfBirth)
            assertEquals("280101", it.expiryDate)
            assertEquals("5", it.documentNumberCheckDigit)
            assertEquals("1", it.dateOfBirthCheckDigit)
            assertEquals("0", it.expiryDateCheckDigit)
        }
    }

    @Test
    fun testValidationWithNullCheckDigits() {
        // When check digits are not extracted (null), validation passes
        val mrzInfo = MRZInfo(
            documentNumber = "001234567",
            dateOfBirth = "900101",
            expiryDate = "280101",
            documentNumberCheckDigit = null,
            dateOfBirthCheckDigit = null,
            expiryDateCheckDigit = null
        )

        val validationResult = mrzInfo.validateCheckDigits()
        assertTrue(validationResult.documentNumberValid)
        assertTrue(validationResult.dateOfBirthValid)
        assertTrue(validationResult.expiryDateValid)
        assertTrue(validationResult.isValid)
    }

    @Test
    fun testMRZKeySeedGeneration() {
        val mrzInfo = MRZInfo(
            documentNumber = "001234567",
            dateOfBirth = "900101",
            expiryDate = "280101"
        )

        val keySeed = mrzInfo.mrzKeySeed()
        // Format: docNum(9) + docChk(1) + dob(6) + dobChk(1) + exp(6) + expChk(1) = 25 chars
        assertEquals(25, keySeed.length)
        assertTrue(keySeed.matches(Regex("[A-Z0-9<]{25}")))
    }
}
