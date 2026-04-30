package com.vn.cccdreader.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Complete data model for a Vietnamese CCCD (Căn Cước Công Dân) card.
 * Populated from two sources:
 *  - Camera photos (frontPhotoPath, backPhotoPath)
 *  - NFC chip (everything else, read via ICAO 9303 BAC)
 */
@Parcelize
data class IDCardData(
    // ── Identity fields from DG1 (MRZ) ───────────────────────────────────────
    /** 12-digit CCCD number (padded to 9 chars in MRZ) */
    val documentNumber: String = "",
    /** Surname (family name) in UPPERCASE */
    val surname: String = "",
    /** Given names in UPPERCASE */
    val givenNames: String = "",
    /** YYMMDD → displayed as DD/MM/YYYY */
    val dateOfBirth: String = "",
    /** M / F */
    val sex: String = "",
    /** YYMMDD → displayed as DD/MM/YYYY */
    val expiryDate: String = "",
    /** 3-letter ISO country code, typically VNM */
    val nationality: String = "",
    /** Personal number / CCCD number (may repeat documentNumber for CCCD) */
    val personalNumber: String = "",

    // ── Raw MRZ lines (for debugging / display) ───────────────────────────────
    val mrzLine1: String = "",
    val mrzLine2: String = "",
    val mrzLine3: String = "",

    // ── DG2 – biometric face image ────────────────────────────────────────────
    /** Raw JPEG / JPEG2000 bytes of the face image stored on the chip */
    val faceImageBytes: ByteArray? = null,

    // ── Camera photos ─────────────────────────────────────────────────────────
    val frontPhotoPath: String? = null,
    val backPhotoPath: String? = null,

    // ── Status ────────────────────────────────────────────────────────────────
    val nfcReadSuccess: Boolean = false,
    val nfcErrorMessage: String = ""
) : Parcelable {

    /** Full name as "SURNAME Given Names" with proper capitalisation */
    fun fullName(): String {
        val sur = surname.trim().split(" ").joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
        val given = givenNames.trim().split(" ").joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
        return "$sur $given".trim()
    }

    /** Convert YYMMDD → DD/MM/YYYY */
    fun formatDate(yymmdd: String): String {
        if (yymmdd.length != 6) return yymmdd
        val yy = yymmdd.substring(0, 2).toIntOrNull() ?: return yymmdd
        val century = if (yy <= 30) "20" else "19"
        return "${yymmdd.substring(4, 6)}/${yymmdd.substring(2, 4)}/$century${yymmdd.substring(0, 2)}"
    }

    fun displayDOB() = formatDate(dateOfBirth)
    fun displayExpiry() = formatDate(expiryDate)
    fun displaySex() = when (sex.uppercase()) { "M" -> "Nam / Male"; "F" -> "Nữ / Female"; else -> sex }
}
