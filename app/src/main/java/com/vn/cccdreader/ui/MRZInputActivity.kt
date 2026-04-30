package com.vn.cccdreader.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputLayout
import com.vn.cccdreader.databinding.ActivityMrzInputBinding
import com.vn.cccdreader.nfc.MRZInfo
import com.vn.cccdreader.util.parcelable

/**
 * MRZInputActivity – Collects the three fields needed for BAC key derivation:
 *   • Document number (CCCD number, 9–12 digits)
 *   • Date of birth  (DD/MM/YY)
 *   • Expiry date    (DD/MM/YY)
 *
 * Dates are displayed and entered as DD/MM/YY. Internally they are stored
 * and sent to the NFC reader as YYMMDD (the MRZ wire format).
 *
 * Returns via RESULT_OK:
 *   KEY_MRZ_INFO – MRZInfo parcelable (dates in YYMMDD)
 */
class MRZInputActivity : AppCompatActivity() {

    companion object {
        const val KEY_MRZ_INFO      = "mrz_info"
        const val KEY_PREFILLED_MRZ = "prefilled_mrz"
        const val REQUEST_CODE      = 2002
    }

    private lateinit var binding: ActivityMrzInputBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMrzInputBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "Nhập thông tin MRZ / Enter MRZ Data"

        setupInputWatchers()
        setupDateFormatHelpers()
        prefillFromIntent()

        binding.btnConfirm.setOnClickListener { validateAndProceed() }
        binding.btnCancel.setOnClickListener  { finish() }
    }

    // ── Pre-fill from OCR / auto-extract ─────────────────────────────────────

    private fun prefillFromIntent() {
        val prefilled = intent.parcelable<MRZInfo>(KEY_PREFILLED_MRZ) ?: return
        binding.cardAutoFillBanner.visibility = View.VISIBLE
        binding.etDocNumber.setText(prefilled.documentNumber.trimEnd('<'))
        // MRZInfo dates are YYMMDD. Convert to raw DDMMYY digits; the TextWatcher
        // registered in setupDateFormatHelpers will format them as DD/MM/YY.
        binding.etDob.setText(yymmddToDdmmyy(prefilled.dateOfBirth))
        binding.etExpiry.setText(yymmddToDdmmyy(prefilled.expiryDate))
    }

    // ── Input watchers ────────────────────────────────────────────────────────

    private fun setupInputWatchers() {
        binding.etDocNumber.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val text = s.toString().uppercase().replace(" ", "").take(9)
                val chk  = MRZInfo.checkDigit(text.padEnd(9, '<'))
                binding.tilDocNumber.helperText = "Check digit: $chk"
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
    }

    private fun setupDateFormatHelpers() {
        fun autoFormatDate(et: android.widget.EditText, til: TextInputLayout) {
            et.addTextChangedListener(object : TextWatcher {
                private var isFormatting = false
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (isFormatting) return
                    isFormatting = true
                    val digits    = s.toString().filter { it.isDigit() }.take(6)
                    val formatted = formatDdMmYy(digits)
                    // Programmatic replace() only passes through InputFilters (the length
                    // filter set by android:maxLength), NOT through DigitsKeyListener, so
                    // slashes inserted here are accepted even with inputType="number".
                    s?.replace(0, s.length, formatted)
                    val yymmdd = ddmmyyToYymmdd(digits)
                    til.helperText = if (yymmdd != null)
                        "MRZ: $yymmdd  ✓  Check: ${MRZInfo.checkDigit(yymmdd)}"
                    else
                        "Định dạng / Format: DD/MM/YY  (ví dụ: 01/01/90)"
                    isFormatting = false
                }
            })
        }
        autoFormatDate(binding.etDob, binding.tilDob)
        autoFormatDate(binding.etExpiry, binding.tilExpiry)
    }

    // ── Validation & submit ───────────────────────────────────────────────────

    private fun validateAndProceed() {
        var valid = true

        // Document number: Vietnamese CCCD is 12 digits; BAC uses the first 9.
        val docRaw = binding.etDocNumber.text.toString().uppercase().trim().take(9)
        if (docRaw.length < 9) {
            binding.tilDocNumber.error =
                "Nhập 9 chữ số đầu của CCCD / Enter first 9 digits (e.g. 079087001)"
            valid = false
        } else {
            binding.tilDocNumber.error = null
        }

        // Date of birth – field shows DD/MM/YY; strip slashes to get 6 digits
        val dobDigits = binding.etDob.text.toString().filter { it.isDigit() }
        val dobYYMMDD = ddmmyyToYymmdd(dobDigits)
        if (dobYYMMDD == null) {
            binding.tilDob.error = "Ngày không hợp lệ / Invalid date. Use DD/MM/YY"
            valid = false
        } else {
            binding.tilDob.error = null
        }

        // Expiry date
        val expDigits = binding.etExpiry.text.toString().filter { it.isDigit() }
        val expYYMMDD = ddmmyyToYymmdd(expDigits)
        if (expYYMMDD == null) {
            binding.tilExpiry.error = "Ngày không hợp lệ / Invalid date. Use DD/MM/YY"
            valid = false
        } else {
            binding.tilExpiry.error = null
        }

        if (!valid) return

        val docPadded = docRaw.padEnd(9, '<').take(9)
        val mrzInfo   = MRZInfo(docPadded, dobYYMMDD!!, expYYMMDD!!)

        if (!mrzInfo.isValid()) {
            binding.tilDocNumber.error = "Vui lòng kiểm tra lại / Please check all values"
            return
        }

        setResult(RESULT_OK, Intent().putExtra(KEY_MRZ_INFO, mrzInfo))
        finish()
    }

    // ── Date format helpers ───────────────────────────────────────────────────

    /**
     * Formats up to 6 raw digit characters as DD/MM/YY for display.
     * e.g. "01"→"01", "0101"→"01/01", "010190"→"01/01/90"
     */
    private fun formatDdMmYy(digits: String): String = when {
        digits.length <= 2 -> digits
        digits.length <= 4 -> "${digits.substring(0, 2)}/${digits.substring(2)}"
        else               -> "${digits.substring(0, 2)}/${digits.substring(2, 4)}/${digits.substring(4)}"
    }

    /**
     * Converts 6 DDMMYY digits → YYMMDD (the MRZ / BAC wire format).
     * Returns null if the date is invalid.
     */
    private fun ddmmyyToYymmdd(digits: String): String? {
        if (digits.length != 6) return null
        val dd = digits.substring(0, 2).toIntOrNull() ?: return null
        val mm = digits.substring(2, 4).toIntOrNull() ?: return null
        if (dd !in 1..31 || mm !in 1..12) return null
        return "${digits.substring(4, 6)}${digits.substring(2, 4)}${digits.substring(0, 2)}"
    }

    /**
     * Converts a 6-char YYMMDD string (from MRZInfo) → raw DDMMYY digits for display.
     * The date TextWatcher will then format it as DD/MM/YY.
     */
    private fun yymmddToDdmmyy(yymmdd: String): String {
        if (yymmdd.length != 6) return yymmdd
        val yy = yymmdd.substring(0, 2)
        val mm = yymmdd.substring(2, 4)
        val dd = yymmdd.substring(4, 6)
        return "$dd$mm$yy"
    }
}
