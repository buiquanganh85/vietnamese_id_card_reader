# MRZ Verification Implementation

This document describes the implementation of ICAO-compliant MRZ (Machine Readable Zone) verification for Vietnamese ID cards.

## Overview

The MRZ verification system validates three critical fields extracted from ID card photos:
1. **Document Number** - 9-character alphanumeric ID
2. **Date of Birth** - YYMMDD format
3. **Expiry Date** - YYMMDD format

Each field has an associated check digit calculated according to the ICAO standard (ICAO Doc 9303).

## ICAO Check Digit Algorithm

The ICAO standard uses a modulo-10 checksum with repeating weights [7, 3, 1].

### Algorithm Steps:

1. Convert each character to a numeric value:
   - `<` (filler) = 0
   - `0-9` = 0-9
   - `A-Z` = 10-35

2. Multiply each value by its corresponding weight (repeating pattern):
   ```
   sum = Σ(charValue[i] × weight[i % 3])
   ```

3. Calculate check digit:
   ```
   checkDigit = sum % 10
   ```

### Example:

For document number `001234567`:

| Char | Value | Weight | Product |
|------|-------|--------|---------|
| 0    | 0     | 7      | 0       |
| 0    | 0     | 3      | 0       |
| 1    | 1     | 1      | 1       |
| 2    | 2     | 7      | 14      |
| 3    | 3     | 3      | 9       |
| 4    | 4     | 1      | 4       |
| 5    | 5     | 7      | 35      |
| 6    | 6     | 3      | 18      |
| 7    | 7     | 1      | 7       |

Sum = 0 + 0 + 1 + 14 + 9 + 4 + 35 + 18 + 7 = 88
Check Digit = 88 % 10 = **8**

## TD1 Format (Vietnamese CCCD - 3×30)

TD1 is used for Vietnamese Citizen Identity Cards.

```
Line 1: I<VNM[docNum:5-13][chk:14][optional:15-29]
Line 2: [dob:0-5][chk:6][sex:7][exp:8-13][chk:14][nationality:15-17][optional:18-29]
Line 3: [surname]<<[given_names]
```

### Position Reference (0-indexed):

**Line 1:**
- Positions 0-4: `I<VNM` (identifier + country code)
- Positions 5-13: Document number (9 chars)
- Position 14: Document number check digit
- Positions 15-29: Optional data (14 chars)

**Line 2:**
- Positions 0-5: Date of birth (YYMMDD)
- Position 6: DOB check digit
- Position 7: Sex (M/F)
- Positions 8-13: Expiry date (YYMMDD)
- Position 14: Expiry date check digit
- Positions 15-17: Nationality (VNM)
- Positions 18-29: Optional/reserved (12 chars)

**Line 3:**
- Surnames and given names separated by `<<`

## TD3 Format (Passports - 2×44)

TD3 is used for passports and some international travel documents.

```
Line 1: P<[country][name][line1_check]
Line 2: [docNum:0-8][chk:9][dob:13-18][chk:19][exp:21-26][chk:27]...
```

## Usage

### Basic Validation

```kotlin
// After MRZ parsing from OCR
val mrzInfo = MRZInfo(
    documentNumber = "001234567",
    dateOfBirth = "900101",
    expiryDate = "280101",
    documentNumberCheckDigit = "2",
    dateOfBirthCheckDigit = "7",
    expiryDateCheckDigit = "8"
)

// Validate check digits
val result = mrzInfo.validateCheckDigits()

if (result.isValid) {
    // All check digits match - MRZ is valid
    println("MRZ is valid")
} else {
    // Show which fields failed validation
    if (!result.documentNumberValid) {
        println("Document number check digit mismatch")
        println("Expected: ${result.calculatedDocumentNumberCheckDigit}")
        println("Got: ${result.extractedDocumentNumberCheckDigit}")
    }
    if (!result.dateOfBirthValid) {
        println("DOB check digit mismatch")
    }
    if (!result.expiryDateValid) {
        println("Expiry date check digit mismatch")
    }
}
```

### Parsing from MRZ Lines

```kotlin
// TD1 format (Vietnamese CCCD)
val line1 = "I<VNM0012345678<<<<<<<<<<<<<2"
val line2 = "900101<1234567890<VNM0000000000"
val line3 = "NGUYEN<<VAN<<A<<<<<<<<<<<<<<<<"

val mrzInfo = MRZInfo.fromTD1(line1, line2, line3)
mrzInfo?.let { mrz ->
    val validation = mrz.validateCheckDigits()
    if (validation.isValid) {
        // Proceed with NFC data reading using mrzKeySeed()
        val bacKeySeed = mrz.mrzKeySeed()
    }
}
```

### Handling OCR Extraction

When extracting MRZ from OCR:

```kotlin
// After MRZExtractor.extractFromUri()
val mrzInfo = MRZExtractor.extractFromUri(context, imageUri)
mrzInfo?.let { mrz ->
    // Check basic structure validity
    if (mrz.isValid()) {
        // Validate check digits
        val validation = mrz.validateCheckDigits()
        
        if (validation.isValid) {
            // Proceed with trusted data
            val bacKeySeed = mrz.mrzKeySeed()
        } else {
            // Warn user about potential OCR errors
            showValidationError(validation)
        }
    }
}
```

## MRZValidationResult

The `validateCheckDigits()` function returns a `MRZValidationResult` object containing:

```kotlin
data class MRZValidationResult(
    val documentNumberValid: Boolean,         // Check digit matches
    val dateOfBirthValid: Boolean,           // Check digit matches
    val expiryDateValid: Boolean,            // Check digit matches
    val calculatedDocumentNumberCheckDigit: String,  // What we computed
    val calculatedDateOfBirthCheckDigit: String,     // What we computed
    val calculatedExpiryDateCheckDigit: String,      // What we computed
    val extractedDocumentNumberCheckDigit: String?,  // What MRZ contained
    val extractedDateOfBirthCheckDigit: String?,     // What MRZ contained
    val extractedExpiryDateCheckDigit: String?       // What MRZ contained
) {
    val isValid: Boolean  // true if all three fields valid
}
```

## Integration Points

### 1. MRZ Extraction
Update `MRZExtractor.parseMrzFromText()` to validate after parsing:

```kotlin
fun parseMrzFromText(rawText: String): MRZInfo? {
    // ... existing parsing logic ...
    
    // For TD1
    MRZInfo.fromTD1(l1, l2, l3)?.takeIf { 
        it.isValid() && it.validateCheckDigits().isValid
    }?.let { return it }
    
    // For TD3
    MRZInfo.fromTD3(l1, l2)?.takeIf {
        it.isValid() && it.validateCheckDigits().isValid
    }?.let { return it }
}
```

### 2. UI Feedback
In `MRZInputActivity`, show validation warnings:

```kotlin
val validation = mrzInfo.validateCheckDigits()
if (!validation.isValid) {
    showWarningBanner("MRZ validation failed - OCR accuracy may be low")
    // Still allow user to proceed but flag for review
}
```

### 3. BAC Key Derivation
Only use verified MRZ for NFC key generation:

```kotlin
val result = mrzInfo.validateCheckDigits()
if (result.isValid) {
    val bacKeySeed = mrzInfo.mrzKeySeed()
    // Proceed with NFC reading
} else {
    // Retry photo capture
}
```

## Check Digit Padding

Document numbers shorter than 9 characters are padded with `<` (filler) on the right:

```kotlin
val docNum = "001234567"      // 9 chars
val docNum = "00123456"       // 8 chars → padded to "00123456<"
val docNum = "0012345"        // 7 chars → padded to "0012345<<"
```

The check digit is calculated on the **padded** value.

## Testing

Run the MRZ validation tests:

```bash
./gradlew test -k MRZInfoTest
```

The test suite includes:
- Check digit calculation verification
- TD1 format parsing and validation
- TD3 format parsing and validation
- Mismatch detection
- Null check digit handling
- BAC key seed generation

## References

- ICAO Doc 9303 Part 1 (Machine Readable Travel Documents)
- ICAO Doc 9303 Part 2 (Specifications for Travel Documents with Machine Readable Zone - MRZ)
- Vietnamese CCCD Technical Specification (TD1 format)

## Security Considerations

1. **OCR Reliability**: MRZ check digits help detect OCR errors but don't guarantee correctness
2. **User Verification**: Always show users what MRZ data was extracted for verification
3. **NFC Validation**: Use check digit validation to prevent wasting NFC reads on corrupted data
4. **Error Messages**: Provide clear feedback on which field(s) failed validation
