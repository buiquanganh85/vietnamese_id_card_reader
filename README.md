# Vietnamese ID Card Reader

Vietnamese ID Card Reader is an Android application designed to read and verify Vietnamese Citizen Identity Cards (CCCD) using NFC and Camera. It follows the ICAO 9303 standard for Machine Readable Travel Documents (MRTD).

## 🚀 Overview

The application implements a step-by-step wizard to collect card data:
1. **Front Photo:** Capture the recto side of the card.
2. **Back Photo:** Capture the verso side of the card.
3. **MRZ Data:** Input document number, date of birth, and expiry date (required for NFC authentication).
4. **NFC Read:** Access the chip using Basic Access Control (BAC) to extract personal information and the biometric face photo.
5. **Results:** Display all collected and verified data.

## ✨ Key Features

- **ICAO 9303 NFC Reading:** Securely reads the embedded chip using Basic Access Control (BAC).
- **Automatic MRZ Extraction:** Uses Google ML Kit Text Recognition to automatically extract document number, date of birth, and expiry date from the back of the card.
- **Color-Coded Validation:** MRZ fields are highlighted to show confidence:
  - 🔵 **Blue:** Verified by ICAO check digits (high confidence).
  - 🔴 **Red:** Failed check digit validation (OCR error detected).
  - ⚫ **Black:** Manually edited or neutral state.
- **Biometric Face Photo:** Extracts and displays the high-quality JPEG/JPEG2000 face photo from the chip (DG2).
- **Modern Camera Interface:** Uses CameraX for a smooth card capture experience.

## 🛠 Tech Stack

- **Language:** Kotlin 1.9.22
- **Platform:** Android (Min SDK 26, Target SDK 34)
- **Build System:** Gradle
- **Key Libraries:**
  - **CameraX:** For high-quality card image capture.
  - **BouncyCastle:** For ICAO 9303 BAC cryptography (3DES, SHA-1).
  - **Glide:** For efficient image loading and processing.
  - **Coroutines:** For asynchronous I/O and NFC transceive operations.
  - **Google ML Kit:** For Text Recognition (OCR) and MRZ parsing.

## 📋 Requirements

- Android device with NFC support (NFC-A / ISO 14443-4).
- Android 8.0 (API 26) or higher.
- Camera permission for card scanning.

## ⚙️ Setup & Run

1. **Clone the repository:**
   ```bash
   git clone https://github.com/[your-username]/test_idcard_reader_android.git
   ```
2. **Open in Android Studio:**
   Import the project as a Gradle project.
3. **Build & Run:**
   - Select the `app` module.
   - Click **Run** or use the terminal:
     ```bash
     ./gradlew assembleDebug
     ```

## 📂 Project Structure

```text
app/src/main/java/com/vn/cccdreader/
├── camera/
│   └── CameraActivity.kt      # CameraX implementation for card capture
├── data/
│   └── IDCardData.kt          # Data models for card information
├── nfc/
│   ├── BACProtocol.kt         # Basic Access Control implementation
│   ├── LDSParser.kt           # Parser for Logical Data Structure (DG1, DG2)
│   ├── MRZInfo.kt             # MRZ data model and key derivation helpers
│   ├── MRTDReader.kt          # Orchestrator for NFC chip reading
│   ├── NFCReaderActivity.kt   # UI and lifecycle for NFC scanning
│   └── SecureMessaging.kt     # Session encryption (3DES/MAC)
├── ocr/
│   └── MRZExtractor.kt        # Google ML Kit OCR for MRZ parsing
├── ui/
│   ├── MRZInputActivity.kt    # Manual entry and color-coded validation
│   └── ResultActivity.kt      # Display of final results and face photo
├── util/
│   └── IntentExt.kt           # Helper for Intent data passing (Parcelable)
├── MainActivity.kt            # Main entry point and wizard orchestrator
└── MainApplication.kt         # Application class
```

## 🧪 Tests

The project includes unit and instrumentation tests:
- **Unit Tests:** Found in `app/src/test`. Run via `./gradlew test`.
- **Instrumentation Tests:** Found in `app/src/androidTest`. Run via `./gradlew connectedAndroidTest`.

## 📋 TODO List

- [x] **Automatic MRZ Extraction:** Extract MRZ data from the verso card image to auto-fill Step 3. (Implemented using Google ML Kit)
- [x] **Color-Coded MRZ Validation:** Provide visual feedback based on ICAO check digits.
- [ ] **QR Code Extraction:** Extract QR code data from the verso card image.
- [ ] **ID Number Extraction:** Extract the ID number from the recto card image.
- [ ] **Data Verification:** Implement a verification layer to check coherence between image OCR, QR code, and NFC chip data.

## 📄 License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE.md](LICENSE.md) file for details.
