# MRZ Verification Implementation Summary

## Overview

This implementation adds ICAO-compliant check digit validation for Vietnamese ID cards (CCCD). After verso (back side) image capture and MRZ extraction, the system validates the extracted document_number, date_of_birth, and expiry_date against ICAO Doc 9303 check digit standards.

## Files Modified/Created

### Core Implementation

1. **`app/src/main/java/com/vn/cccdreader/nfc/MRZInfo.kt`** (Modified)
   - Added `MRZValidationResult` data class
   - Enhanced `MRZInfo` to store check digits from MRZ
   - Implemented `validateCheckDigits()` function
   - Updated `fromTD1()` and `fromTD3()` parsers to extract check digits
   - Added field position documentation

2. **`app/src/test/java/com/vn/cccdreader/nfc/MRZInfoTest.kt`** (Created)
   - Comprehensive test suite with 8 test cases
   - Tests for check digit calculation
   - TD1 and TD3 format validation
   - Matching and mismatching check digit detection
   - BAC key seed generation verification

### Documentation

3. **`MRZ_VERIFICATION.md`** (Created)
   - ICAO check digit algorithm explanation with examples
   - TD1 format specification (Vietnamese CCCD)
   - TD3 format specification (Passports)
   - Detailed usage examples
   - Check digit padding rules
   - Security considerations

4. **`MRZ_INTEGRATION_EXAMPLE.md`** (Created)
   - Three integration strategies:
     1. Non-blocking warnings (allow user override)
     2. Strict validation (require all checks pass)
     3. Visual feedback (show calculated values)
   - Integration with MRZInputActivity
   - Integration with MRZExtractor
   - Integration with NFC reading
   - Bilingual message examples
   - Testing guidelines

## ICAO Check Digit Validation

### Algorithm

The ICAO standard uses modulo-10 checksum with repeating weights [7, 3, 1]:

```
Sum = Σ(charValue[i] × weight[i % 3])
CheckDigit = sum % 10
```

**Character values:**
- `<` (filler) = 0
- `0-9` = numeric value
- `A-Z` = 10-35 (A=10, B=11, ..., Z=35)

### TD1 Format (Vietnamese CCCD - 3×30 chars)

**Line 1:** `I<VNM[docNum:5-13][chk:14][optional:15-29]`
- Positions 5-13: Document number (9 chars)
- Position 14: Document number check digit

**Line 2:** `[dob:0-5][chk:6][sex:7][exp:8-13][chk:14][nat:15-17][optional:18-29]`
- Positions 0-5: Date of birth (YYMMDD)
- Position 6: DOB check digit
- Positions 8-13: Expiry date (YYMMDD)
- Position 14: Expiry date check digit

**Line 3:** Name field

### TD3 Format (Passports - 2×44 chars)

**Line 2:** `[docNum:0-8][chk:9][...][dob:13-18][chk:19][exp:21-26][chk:27]...`
- Positions 0-8: Document number (9 chars)
- Position 9: Document number check digit
- Positions 13-18: DOB (YYMMDD)
- Position 19: DOB check digit
- Positions 21-26: Expiry (YYMMDD)
- Position 27: Expiry check digit

## Data Structures

### MRZValidationResult

```kotlin
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
    val isValid: Boolean  // true if all three fields match
}
```

### Enhanced MRZInfo

```kotlin
@Parcelize
data class MRZInfo(
    val documentNumber: String,
    val dateOfBirth: String,
    val expiryDate: String,
    val documentNumberCheckDigit: String? = null,  // NEW
    val dateOfBirthCheckDigit: String? = null,    // NEW
    val expiryDateCheckDigit: String? = null      // NEW
) : Parcelable
```

## Usage Examples

### Basic Validation

```kotlin
val mrzInfo = MRZInfo(
    documentNumber = "001234567",
    dateOfBirth = "900101",
    expiryDate = "280101",
    documentNumberCheckDigit = "2",
    dateOfBirthCheckDigit = "7",
    expiryDateCheckDigit = "8"
)

val result = mrzInfo.validateCheckDigits()
if (result.isValid) {
    // All checks passed
    val bacKeySeed = mrzInfo.mrzKeySeed()
} else {
    // Show which field failed
    println("Failed: ${result.extractedDocumentNumberCheckDigit}")
}
```

### Parsing from MRZ Lines

```kotlin
val line1 = "I<VNM0012345678<<<<<<<<<<<<<2"
val line2 = "900101<1234567890<VNM0000000000"
val line3 = "NGUYEN<<VAN<<A<<<<<<<<<<<<<<<<"

val mrzInfo = MRZInfo.fromTD1(line1, line2, line3)
val validation = mrzInfo?.validateCheckDigits()
```

### With OCR Extraction

```kotlin
val mrzInfo = MRZExtractor.extractFromUri(context, photoUri)
mrzInfo?.let { mrz ->
    if (mrz.isValid()) {
        val validation = mrz.validateCheckDigits()
        if (validation.isValid) {
            // Proceed with NFC reading
        } else {
            // Warn about potential OCR errors
            showWarning("Check digit validation failed")
        }
    }
}
```

## Integration Strategy

### For Manual Input (MRZInputActivity)

1. **Show calculated check digits** as helper text in real-time
2. **Allow override** - User can manually enter data even if check digit doesn't match
3. **Validate on submit** - Show warnings but don't block

### For OCR Extraction

1. **Extract check digits** from MRZ during parsing (already implemented in fromTD1/fromTD3)
2. **Validate immediately** after extraction
3. **Warn user** if validation fails (indicates OCR errors)
4. **Allow user choice** - proceed with unverified data or retake photo

### For NFC Reading

1. **Require strict validation** - Cannot proceed without passing check digits
2. **Uses mrzKeySeed()** which depends on validated data
3. **Prevents wasted NFC reads** with invalid data

## Benefits

1. **Error Detection**: Catches OCR mistakes in critical ID fields
2. **User Confidence**: Shows when data was read correctly
3. **NFC Efficiency**: Validates before expensive NFC operations
4. **Standards Compliance**: Follows ICAO Doc 9303
5. **Graceful Degradation**: Allows override when needed
6. **Detailed Feedback**: Shows exactly which field failed and why

## Testing

The implementation includes comprehensive test coverage:

```bash
./gradlew test -k MRZInfoTest
```

Tests include:
- Check digit calculation verification
- TD1 format parsing with check digits
- TD3 format parsing with check digits
- Valid check digit matching
- Invalid check digit detection
- Null check digit handling
- BAC key seed generation

## Security Notes

1. **OCR Limitations**: Check digits help detect errors but OCR may still have false positives
2. **Manual Verification**: Always show users what MRZ was extracted for visual confirmation
3. **No Forgery Prevention**: Check digits validate format, not authenticity
4. **NFC as Final Check**: NFC reading validates against actual chip data

## References

- ICAO Doc 9303 Part 1: Machine Readable Travel Documents
- ICAO Doc 9303 Part 2: Specifications for Travel Documents with MRZ
- Vietnamese CCCD Technical Specification (TD1 format)

## Next Steps

1. **Integrate into UI** - Use one of the three strategies from MRZ_INTEGRATION_EXAMPLE.md
2. **Test with real data** - Validate with actual Vietnamese ID cards
3. **Monitor OCR accuracy** - Track validation failure rates to assess OCR quality
4. **User feedback** - Gather feedback on validation warnings
