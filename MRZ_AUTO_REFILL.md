# MRZ Auto-Refill on Verso Capture

## Overview

When the user captures a verso (back side) ID card photo, the MRZ is **automatically extracted and updated every time**, even if MRZ data already exists from a previous capture.

## Behavior

### Flow

```
User captures verso photo
    ↓
MainActivity.extractMrzFromBackPhoto(uri) called
    ↓
MRZExtractor.extractFromUri() processes image
    ↓
Check if extraction successful
    ├─ YES: Update mrzInfo (always, even if it exists)
    │        Show message: "🔄 MRZ đã được cập nhật từ ảnh thẻ!" (updated)
    │        OR "✅ MRZ đã được tự động điền từ ảnh thẻ!" (first time)
    │        Refresh UI to show new MRZ in Step 3
    │
    └─ NO:  Keep existing mrzInfo if available
            Show message: "Không nhận dạng được – vui lòng nhập thủ công"
            Refresh UI

User clicks "Nhập MRZ" → Goes to MRZInputActivity
    ↓
MRZInputActivity.prefillFromIntent() called
    ↓
New extracted MRZ data displayed with color feedback
    ├─ BLUE: Values verified by check digit ✅
    ├─ RED: Values failed check digit validation ❌
    └─ BLACK: User modified the field

User can review, modify, or confirm
    ↓
Returns MRZ to MainActivity
```

## Key Behavior Changes

### Before (Old Code)
```kotlin
if (extracted != null && mrzInfo == null) {  // Only updates if null
    mrzInfo = extracted
    toast("✅ MRZ đã được tự động điền từ ảnh thẻ!")
}
```

**Problem:** If user recaptured verso, MRZ wouldn't update because `mrzInfo != null`

### After (New Code)
```kotlin
if (extracted != null) {  // Always updates
    val wasUpdated = (mrzInfo != null)
    mrzInfo = extracted
    if (wasUpdated) {
        toast("🔄 MRZ đã được cập nhật từ ảnh thẻ!")
    } else {
        toast("✅ MRZ đã được tự động điền từ ảnh thẻ!")
    }
}
```

**Solution:** MRZ is always updated when extraction succeeds, showing appropriate message

## Scenarios

### Scenario 1: First Verso Capture

```
State: No verso captured yet, mrzInfo = null

User captures verso photo
    ↓
Extraction succeeds: "079087001", "900101", "280101"
    ↓
Message: "✅ MRZ đã được tự động điền từ ảnh thẻ!"
    ↓
Step 3 shows: "✓ 079087001 (tự động / auto)"
```

### Scenario 2: Recapture Verso (Better Photo)

```
State: Verso already captured, mrzInfo exists with old data

User recaptures verso (better photo)
    ↓
New extraction succeeds: Better quality data
    ↓
mrzInfo updated with new data
    ↓
Message: "🔄 MRZ đã được cập nhật từ ảnh thẻ!"
    ↓
Step 3 shows: "✓ {new document number} (tự động / auto)"

User clicks "Nhập MRZ"
    ↓
MRZInputActivity opens with NEW extracted data
    ↓
Color feedback applied to NEW values
```

### Scenario 3: Recapture Verso (User Already Modified Data)

```
State: Old MRZ extracted, user has manually entered new values in MRZInputActivity

User recaptures verso
    ↓
New extraction succeeds
    ↓
mrzInfo updated with new extracted data
    ↓
Message: "🔄 MRZ đã được cập nhật từ ảnh thẻ!"
    ↓
User clicks "Nhập MRZ"
    ↓
MRZInputActivity opens with NEW extracted data
    ↓
Previous manual edits are replaced with new extracted data
    ↓
NOTE: This is expected behavior - new verso capture overrides manual edits
```

### Scenario 4: Verso Capture Fails

```
State: Previous verso exists, mrzInfo = "079087001"

User recaptures verso (poor quality, can't read MRZ)
    ↓
Extraction fails: returns null
    ↓
mrzInfo NOT updated (keeps "079087001")
    ↓
Message: "Không nhận dạng được – vui lòng nhập thủ công"
    ↓
User can still proceed with existing MRZ or click "Nhập MRZ" to edit
```

## UI Updates

### Step 3 Status Messages

**First Extraction Success:**
```
tvStep3Status: "✓ 079087001 (tự động / auto)"
```

**After Recapture:**
```
tvStep3Status: "✓ {new_document_number} (tự động / auto)"
```

**Extraction Failure:**
```
tvStep3Status: "Không nhận dạng được – vui lòng nhập thủ công"
             (Cannot recognize - please enter manually)
```

**While Processing:**
```
tvStep3Status: "🔍 Đang nhận dạng MRZ từ ảnh..."
             (Recognizing MRZ from image...)
```

## Color Feedback on Every Refill

When user opens MRZInputActivity after verso capture:

1. **Fresh Extract Data** → Color feedback applied
   - BLUE fields = check digit verified
   - RED fields = check digit failed
   - Gives user immediate confidence assessment

2. **Every Recapture** → Colors reapplied to new data
   - Previous color state doesn't carry over
   - User sees fresh validation for new extract
   - RED fields highlight potential OCR errors in new photo

3. **User Edits** → Colors reset to BLACK
   - User takes responsibility when they modify
   - Can reopen to see original extracted colors

## Technical Details

### MainActivity.extractMrzFromBackPhoto()

```kotlin
private fun extractMrzFromBackPhoto(uriString: String) {
    binding.tvStep3Status.text = "🔍 Đang nhận dạng MRZ từ ảnh..."
    binding.btnEnterMrz.isEnabled = false

    lifecycleScope.launch {
        val extracted = withContext(Dispatchers.IO) {
            MRZExtractor.extractFromUri(this@MainActivity, Uri.parse(uriString))
        }
        if (extracted != null) {
            // KEY CHANGE: Always update, not just if null
            val wasUpdated = (mrzInfo != null)
            mrzInfo = extracted
            if (wasUpdated) {
                toast("🔄 MRZ đã được cập nhật từ ảnh thẻ!")
            } else {
                toast("✅ MRZ đã được tự động điền từ ảnh thẻ!")
            }
        } else {
            binding.tvStep3Status.text = "Không nhận dạng được – vui lòng nhập thủ công"
        }
        binding.btnEnterMrz.isEnabled = true
        refreshStepUI()
    }
}
```

### MRZInputActivity.prefillFromIntent()

```kotlin
private fun prefillFromIntent() {
    val prefilled = intent.parcelable<MRZInfo>(KEY_PREFILLED_MRZ) ?: return
    binding.cardAutoFillBanner.visibility = View.VISIBLE
    
    // Validate check digits and apply color coding
    val validation = prefilled.validateCheckDigits()
    
    // Apply colors based on validation
    // BLUE if verified, RED if failed
    // Colors are fresh for each call, no state carryover
}
```

## Implementation Notes

1. **Always Extract:** MRZ extraction happens on every verso capture, regardless of previous state
2. **Asynchronous:** Extraction uses `lifecycleScope.launch` to not block UI
3. **User Notification:** Different messages for first extraction vs update
4. **Non-blocking:** If extraction fails, previous MRZ is preserved
5. **Fresh Colors:** Every time MRZInputActivity opens, colors are calculated fresh from current data
6. **Override Behavior:** New verso capture completely replaces any manual edits user made

## Testing

### Test Case 1: Basic Auto-Fill
1. Open app
2. Capture front photo
3. Capture verso photo
4. Verify Step 3 shows extracted document number
5. Verify toast says "✅ MRZ đã được tự động điền"

### Test Case 2: Recapture Update
1. Complete Test Case 1
2. Click "Nhập MRZ" and modify a value manually
3. Go back to MainActivity
4. Recapture verso photo (different card)
5. Verify Step 3 updates with new document number
6. Verify toast says "🔄 MRZ đã được cập nhật"
7. Click "Nhập MRZ" again
8. Verify form shows NEW extracted data (manual edits overwritten)

### Test Case 3: Failed Extraction Preserves Data
1. Complete Test Case 1 successfully
2. Recapture verso with poor quality/dark image
3. Verify extraction fails
4. Verify Step 3 still shows previous document number
5. Verify toast says extraction failed
6. Verify MRZ is still usable for NFC reading

### Test Case 4: Color Feedback Consistency
1. Capture verso (MRZ extracted)
2. Click "Nhập MRZ"
3. Note which fields are BLUE (verified)
4. Back to MainActivity
5. Recapture verso
6. Click "Nhập MRZ" again
7. Verify same extraction shows same BLUE/RED colors
8. Edit one field (color resets to BLACK)
9. Go back to MainActivity
10. Recapture verso again
11. Click "Nhập MRZ"
12. Verify new extraction replaces manual edits with fresh colors

## Limitations & Considerations

1. **Manual Edits Not Preserved:** When verso is recaptured, any manual edits are lost
   - User must manually re-enter values after recapture if they want to keep them
   - Recommended: Use "Nhập MRZ" to confirm/modify before recapturing

2. **No Undo:** No way to revert to previous extraction after update
   - Each verso capture is final update
   - User can retake verso photo if they want previous data back

3. **Extraction Errors:** Poor photo quality may fail extraction
   - User can still use previous MRZ or enter manually
   - Recommended: Take clear photo of verso with visible MRZ

## Future Enhancements

1. **Diff on Recapture:** Show what changed between old and new extraction
2. **Keep Previous:** Option to keep previous MRZ if new extraction differs significantly
3. **Confidence Score:** Show OCR confidence percentage
4. **Merge Option:** Allow merging/choosing between old and new values per field
5. **History:** Keep extraction history for comparison
