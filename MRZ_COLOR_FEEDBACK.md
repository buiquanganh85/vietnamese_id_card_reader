# MRZ Color-Coded Validation Feedback

## Overview

MRZInputActivity now displays color-coded feedback for auto-filled values based on ICAO check digit validation. This provides visual confirmation of data accuracy when MRZ is extracted from ID card photos via OCR.

## Color Meanings

### 🔵 BLUE (#2196F3) - Verified by Check Digit
- **Meaning:** The value passed ICAO check digit validation
- **Confidence:** High - data matches the check digit in the MRZ
- **Action:** User can proceed with confidence
- **Example:** OCR extracted "079087001" with correct check digit

### 🔴 RED (#F44336) - Failed Check Digit Validation
- **Meaning:** The value did NOT pass ICAO check digit validation
- **Confidence:** Low - potential OCR error detected
- **Action:** User should review and correct the value
- **Example:** OCR extracted "079087002" but expected check digit indicates "079087001"

### ⚫ BLACK (#000000) - User Modified
- **Meaning:** User has manually edited this field
- **Confidence:** Unknown - depends on user input accuracy
- **Action:** User takes full responsibility for the value
- **Behavior:** Color resets to black as soon as user types

## Workflow

### 1. OCR Extraction & Auto-Fill
```
User captures ID card photo
    ↓
MRZExtractor parses MRZ from image
    ↓
Check digits extracted from MRZ positions
    ↓
MRZInputActivity.prefillFromIntent() called
    ↓
validateCheckDigits() compares extracted vs calculated
    ↓
Text color applied based on validation result
```

### 2. Field-by-Field Validation

**Document Number (CCCD):**
- Extracted from MRZ Line 1, position 14
- Calculated from positions 5-13
- Colored BLUE if match, RED if mismatch

**Date of Birth:**
- Extracted from MRZ Line 2, position 6
- Calculated from positions 0-5 (YYMMDD)
- Colored BLUE if match, RED if mismatch

**Expiry Date:**
- Extracted from MRZ Line 2, position 14
- Calculated from positions 8-13 (YYMMDD)
- Colored BLUE if match, RED if mismatch

### 3. User Interaction

When user starts typing in any field:
1. Validation flag is checked
2. If field was colored (BLUE or RED), color resets to BLACK
3. User takes responsibility for the value
4. Live check digit calculation shown in helper text

Example flow:
```
AUTO-FILLED VALUE: 079087001 (BLUE - verified)
    ↓
User types first digit: "0" → Color changes to BLACK
    ↓
Field now shows: 0_________ (BLACK text)
    ↓
Helper text: "Check digit: 5" (calculated on-the-fly)
    ↓
User confirms submission → Uses manually entered value
```

## Implementation Details

### State Tracking

Three boolean flags track verification status:

```kotlin
private var docNumberVerified = false    // Document number validated
private var dobVerified = false          // Date of birth validated  
private var expiryVerified = false       // Expiry date validated
```

### Color Constants

```kotlin
private val COLOR_VERIFIED = 0xFF2196F3.toInt()     // Blue #2196F3
private val COLOR_UNVERIFIED = 0xFFF44336.toInt()   // Red #F44336
private val COLOR_NEUTRAL = 0xFF000000.toInt()      // Black #000000
```

### Color Application

```kotlin
private fun setTextColor(editText: android.widget.EditText, color: Int) {
    editText.setTextColor(color)
}
```

## Visual Examples

### Example 1: All Values Verified ✅
```
OCR extracted from verso:
  Document: 079087001
  DOB: 01/01/90
  Expiry: 01/12/35

All check digits match:
  ✓ Document check digit: 2 matches
  ✓ DOB check digit: 1 matches
  ✓ Expiry check digit: 5 matches

Result:
  [079087001] ← BLUE text (verified)
  [01/01/90]  ← BLUE text (verified)
  [01/12/35]  ← BLUE text (verified)
```

### Example 2: Mixed Validation
```
OCR extracted:
  Document: 079087002 (OCR error - should be 001)
  DOB: 01/01/90 ✓
  Expiry: 01/12/35 ✓

Check digit validation:
  ✗ Document: expected 1, got 2 ✗
  ✓ DOB: expected 1, got 1 ✓
  ✓ Expiry: expected 5, got 5 ✓

Result:
  [079087002] ← RED text (unverified - OCR error detected!)
  [01/01/90]  ← BLUE text (verified)
  [01/12/35]  ← BLUE text (verified)
  
User immediately sees RED field and can correct it
```

### Example 3: User Editing
```
Initial state (auto-filled):
  [079087001] ← BLUE text

User presses backspace:
  [07908700]  ← BLACK text (color reset immediately)

User types new digit:
  [079087003] ← BLACK text (user responsible)

Helper text shows:
  "Check digit: 8" (calculated on-the-fly)
```

## User Experience Benefits

1. **Immediate Visual Feedback**
   - Green banner appears when OCR data is loaded
   - Color-coded fields show at a glance which values are trusted

2. **OCR Error Detection**
   - RED fields immediately highlight potential OCR mistakes
   - User can verify against physical ID card

3. **Clear Responsibility**
   - BLUE = system verified the value
   - BLACK = user is now responsible
   - No ambiguity about data source

4. **Trust Building**
   - BLUE values give user confidence to proceed
   - RED values trigger verification without being blocking
   - User always has final say

5. **Bilingual Support**
   - Visual feedback works regardless of language
   - Color is universal indicator
   - Complements Vietnamese/English text

## Technical Flow in Code

### Prefill Phase
```kotlin
private fun prefillFromIntent() {
    val prefilled = intent.parcelable<MRZInfo>(KEY_PREFILLED_MRZ) ?: return
    
    // Validate check digits
    val validation = prefilled.validateCheckDigits()
    
    // Apply color based on validation
    if (validation.documentNumberValid) {
        setTextColor(binding.etDocNumber, COLOR_VERIFIED)  // BLUE
        docNumberVerified = true
    } else {
        setTextColor(binding.etDocNumber, COLOR_UNVERIFIED) // RED
        docNumberVerified = false
    }
    
    // Same for DOB and Expiry...
}
```

### Edit Phase - Document Number
```kotlin
private fun setupInputWatchers() {
    binding.etDocNumber.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            // Reset color if field was previously verified
            if (docNumberVerified) {
                setTextColor(binding.etDocNumber, COLOR_NEUTRAL)  // BLACK
                docNumberVerified = false
            }
            
            // Calculate new check digit for display
            val text = s.toString().uppercase().take(9)
            val chk = MRZInfo.checkDigit(text.padEnd(9, '<'))
            binding.tilDocNumber.helperText = "Check digit: $chk"
        }
    })
}
```

### Edit Phase - Date Fields
```kotlin
private fun setupDateFormatHelpers() {
    fun autoFormatDate(et: EditText, til: TextInputLayout, isDoB: Boolean) {
        et.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                // Reset color when user edits
                if (isDoB && dobVerified) {
                    setTextColor(et, COLOR_NEUTRAL)  // BLACK
                    dobVerified = false
                }
                
                // Continue with date formatting and check digit calculation
                // ...
            }
        })
    }
}
```

## Testing the Color Feedback

### Test Case 1: All Verified
1. Capture a valid verso image with clear MRZ
2. All three fields should appear in BLUE
3. Verification: All check digits match

### Test Case 2: Unverified Document Number
1. Manually modify verso image or OCR result
2. Document number field should appear in RED
3. Verification: Check digit mismatch detected

### Test Case 3: Color Reset on Edit
1. Fields appear in BLUE/RED initially
2. Click on any field and type
3. Verification: Color immediately resets to BLACK

### Test Case 4: Mixed Validation States
1. Some fields BLUE, some RED
2. Edit a RED field
3. Verification: Color resets to BLACK only for edited field

## Accessibility Considerations

While color is the primary feedback mechanism:
- Helper text still shows "Check digit: X" for all fields
- Error messages display if validation fails on confirm
- Large text size (18sp) aids visibility
- Monospace font makes characters distinct

Consider adding:
- Icons (✓ for blue, ⚠ for red, ✎ for black)
- Tooltip text on long-press
- Screen reader announcements

## Color Specifications

For design/theme customization:

| State | Color | Hex | RGB | Usage |
|-------|-------|-----|-----|-------|
| Verified | Blue | #2196F3 | (33, 150, 243) | BLUE text |
| Unverified | Red | #F44336 | (244, 67, 54) | RED text |
| Neutral | Black | #000000 | (0, 0, 0) | BLACK text |

Adjust hex values in `MRZInputActivity.kt`:
```kotlin
private val COLOR_VERIFIED = 0xFF2196F3.toInt()
private val COLOR_UNVERIFIED = 0xFFF44336.toInt()
private val COLOR_NEUTRAL = 0xFF000000.toInt()
```

## Future Enhancements

1. **Animated Transitions**
   - Fade between colors instead of instant change
   - Subtle animation when color resets

2. **Confidence Score**
   - Show percentage confidence if OCR library provides it
   - Warn user of borderline cases

3. **Field History**
   - Remember original auto-filled values
   - Allow reverting to verified values

4. **Comparison Mode**
   - Side-by-side display of OCR vs check digit calculation
   - Show exactly what didn't match

5. **Validation Summary**
   - Banner showing which fields passed/failed validation
   - Dismissible with user action
