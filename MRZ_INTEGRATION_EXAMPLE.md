# MRZ Validation Integration Example

This document shows how to integrate the ICAO check digit validation into your MRZ input activity.

## Integration with MRZInputActivity

### Option 1: Show Validation Warnings (Non-blocking)

Update the `validateAndProceed()` method to show validation warnings when check digits don't match:

```kotlin
private fun validateAndProceed() {
    var valid = true

    // ... existing validation code ...

    if (!valid) return

    val docPadded = docRaw.padEnd(9, '<').take(9)
    val mrzInfo   = MRZInfo(docPadded, dobYYMMDD!!, expYYMMDD!!)

    if (!mrzInfo.isValid()) {
        binding.tilDocNumber.error = "Vui lòng kiểm tra lại / Please check all values"
        return
    }

    // NEW: Validate check digits
    val validation = mrzInfo.validateCheckDigits()
    if (!validation.isValid) {
        // Show warning but allow user to proceed
        showValidationWarning(validation)
        // Optionally: return false to require correction
        // return
    }

    setResult(RESULT_OK, Intent().putExtra(KEY_MRZ_INFO, mrzInfo))
    finish()
}

private fun showValidationWarning(result: MRZValidationResult) {
    val issues = mutableListOf<String>()
    
    if (!result.documentNumberValid) {
        issues.add("Document #: expected ${result.calculatedDocumentNumberCheckDigit}, got ${result.extractedDocumentNumberCheckDigit}")
    }
    if (!result.dateOfBirthValid) {
        issues.add("DOB: expected ${result.calculatedDateOfBirthCheckDigit}, got ${result.extractedDateOfBirthCheckDigit}")
    }
    if (!result.expiryDateValid) {
        issues.add("Expiry: expected ${result.calculatedExpiryDateCheckDigit}, got ${result.extractedExpiryDateCheckDigit}")
    }

    val message = "Check digit mismatch (OCR may have errors):\n" + issues.joinToString("\n")
    android.app.AlertDialog.Builder(this)
        .setTitle("MRZ Verification Warning")
        .setMessage(message)
        .setPositiveButton("Continue Anyway") { _, _ ->
            // User confirms - proceed
            val docPadded = binding.etDocNumber.text.toString().uppercase().take(9).padEnd(9, '<')
            val dobYYMMDD = ddmmyyToYymmdd(binding.etDob.text.toString().filter { it.isDigit() })
            val expYYMMDD = ddmmyyToYymmdd(binding.etExpiry.text.toString().filter { it.isDigit() })
            
            val mrzInfo = MRZInfo(docPadded, dobYYMMDD!!, expYYMMDD!!)
            setResult(RESULT_OK, Intent().putExtra(KEY_MRZ_INFO, mrzInfo))
            finish()
        }
        .setNegativeButton("Correct Data") { _, _ ->
            // User chooses to correct - stay on screen
        }
        .show()
}
```

### Option 2: Strict Validation (Blocking)

For strict validation, require check digits to match:

```kotlin
private fun validateAndProceed() {
    // ... existing validation ...
    
    if (!valid) return

    val docPadded = docRaw.padEnd(9, '<').take(9)
    val mrzInfo   = MRZInfo(docPadded, dobYYMMDD!!, expYYMMDD!!)

    if (!mrzInfo.isValid()) {
        binding.tilDocNumber.error = "Vui lòng kiểm tra lại / Please check all values"
        return
    }

    // NEW: Require valid check digits
    val validation = mrzInfo.validateCheckDigits()
    if (!validation.isValid) {
        val message = buildString {
            if (!validation.documentNumberValid) {
                append("Document #: expected ${validation.calculatedDocumentNumberCheckDigit}, got ${validation.extractedDocumentNumberCheckDigit}\n")
            }
            if (!validation.dateOfBirthValid) {
                append("DOB: expected ${validation.calculatedDateOfBirthCheckDigit}, got ${validation.extractedDateOfBirthCheckDigit}\n")
            }
            if (!validation.expiryDateValid) {
                append("Expiry: expected ${validation.calculatedExpiryDateCheckDigit}, got ${validation.extractedExpiryDateCheckDigit}\n")
            }
        }
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Invalid MRZ Data")
            .setMessage("Check digit validation failed. Please correct:\n\n$message")
            .setPositiveButton("OK") { _, _ -> }
            .show()
        return
    }

    setResult(RESULT_OK, Intent().putExtra(KEY_MRZ_INFO, mrzInfo))
    finish()
}
```

### Option 3: Visual Feedback with Colored Indicators

Add visual feedback during input using helper text and colors:

```kotlin
private fun setupInputWatchers() {
    binding.etDocNumber.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            val text = s.toString().uppercase().replace(" ", "").take(9)
            val chk  = MRZInfo.checkDigit(text.padEnd(9, '<'))
            binding.tilDocNumber.helperText = "Check digit: $chk"
            
            // Visual feedback: none if empty, else show status
            if (text.isNotEmpty()) {
                binding.tilDocNumber.helperText = "✓ Check digit: $chk"
            }
        }
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
    })

    // For dates, validate while user types
    fun validateDateCheckDigit(et: android.widget.EditText, til: TextInputLayout) {
        et.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isFormatting) return
                isFormatting = true
                val digits    = s.toString().filter { it.isDigit() }.take(6)
                val formatted = formatDdMmYy(digits)
                s?.replace(0, s.length, formatted)
                
                val yymmdd = ddmmyyToYymmdd(digits)
                if (yymmdd != null) {
                    val chk = MRZInfo.checkDigit(yymmdd)
                    til.helperText = "MRZ: $yymmdd  Check: $chk ✓"
                } else {
                    til.helperText = "Format: DD/MM/YY"
                }
                isFormatting = false
            }
        })
    }

    validateDateCheckDigit(binding.etDob, binding.tilDob)
    validateDateCheckDigit(binding.etExpiry, binding.tilExpiry)
}
```

## Integration with MRZExtractor

After OCR extraction, validate before prefilling:

```kotlin
private suspend fun handleOcrResult(imageUri: Uri) {
    try {
        val mrzInfo = MRZExtractor.extractFromUri(context, imageUri)
        
        if (mrzInfo != null) {
            // Check basic structure
            if (!mrzInfo.isValid()) {
                showError("Failed to extract valid MRZ from image")
                return
            }
            
            // Validate check digits
            val validation = mrzInfo.validateCheckDigits()
            
            if (!validation.isValid) {
                // Warn about potential OCR errors
                android.app.AlertDialog.Builder(this)
                    .setTitle("OCR Confidence Warning")
                    .setMessage("Check digit validation failed. Please verify the extracted data:\n\n" +
                        buildString {
                            if (!validation.documentNumberValid) {
                                append("- Document number may be incorrect\n")
                            }
                            if (!validation.dateOfBirthValid) {
                                append("- DOB may be incorrect\n")
                            }
                            if (!validation.expiryDateValid) {
                                append("- Expiry date may be incorrect\n")
                            }
                        })
                    .setPositiveButton("Use Anyway") { _, _ ->
                        prefillMrzData(mrzInfo)
                    }
                    .setNegativeButton("Retake Photo") { _, _ ->
                        // Retry photo capture
                    }
                    .show()
            } else {
                // All checks passed - confidently prefill
                prefillMrzData(mrzInfo)
            }
        } else {
            showError("Could not extract MRZ from image")
        }
    } catch (e: Exception) {
        showError("Error during OCR: ${e.message}")
    }
}

private fun prefillMrzData(mrzInfo: MRZInfo) {
    binding.cardAutoFillBanner.visibility = View.VISIBLE
    binding.etDocNumber.setText(mrzInfo.documentNumber.trimEnd('<'))
    binding.etDob.setText(yymmddToDdmmyy(mrzInfo.dateOfBirth))
    binding.etExpiry.setText(yymmddToDdmmyy(mrzInfo.expiryDate))
}
```

## NFC Reading with Validated MRZ

Only use validated MRZ data for NFC key derivation:

```kotlin
// In NFC reading activity
private suspend fun performNfcReading(mrzInfo: MRZInfo) {
    // Validate before using for BAC key
    val validation = mrzInfo.validateCheckDigits()
    
    if (!validation.isValid) {
        showError("Cannot proceed: MRZ validation failed")
        return
    }
    
    // Safe to use for BAC key derivation
    val bacKeySeed = mrzInfo.mrzKeySeed()
    try {
        val nfcData = nfcReader.readWithBac(bacKeySeed, context)
        // Process NFC data
    } catch (e: Exception) {
        showError("NFC read failed: ${e.message}")
    }
}
```

## Bilingual Messages Example

```kotlin
object ValidationMessages {
    fun checkDigitMismatch(field: String, expected: String, actual: String?): String {
        return "VN: Sai kiểm tra chữ số $field (kỳ vọng: $expected, nhận: $actual)\n" +
               "EN: $field check digit mismatch (expected: $expected, got: $actual)"
    }
    
    fun validationFailed(): String {
        return "VN: Kiểm tra MRZ thất bại. Vui lòng kiểm tra lại dữ liệu.\n" +
               "EN: MRZ validation failed. Please check the data."
    }
    
    fun ocrWarning(): String {
        return "VN: Độ chính xác OCR thấp. Vui lòng xác minh dữ liệu được trích xuất.\n" +
               "EN: OCR accuracy may be low. Please verify the extracted data."
    }
}
```

## Testing the Integration

Use the test cases in `MRZInfoTest.kt`:

```bash
# Run all MRZ tests
./gradlew test -k MRZInfoTest

# Run specific test
./gradlew test -k MRZInfoTest::testTD1ValidationWithIncorrectCheckDigits
```

## Recommended Approach

1. **For User Input** (MRZInputActivity):
   - Show calculated check digits as helper text
   - If user manually enters data, validate but allow override (Option 1)

2. **For OCR Extraction**:
   - Always validate check digits
   - Warn user if validation fails (high OCR error probability)
   - Allow user to proceed but flag for manual review

3. **For NFC Reading**:
   - Require strict validation (Option 2)
   - Cannot proceed with invalid MRZ to BAC key derivation

4. **Error Recovery**:
   - Suggest specific fields that failed
   - Prompt user to retake photo or correct manually
   - Show before/after comparison if user retakes
