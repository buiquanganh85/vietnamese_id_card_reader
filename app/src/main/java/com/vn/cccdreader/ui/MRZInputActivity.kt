package com.vn.cccdreader.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputLayout
import com.vn.cccdreader.databinding.ActivityMrzInputBinding
import com.vn.cccdreader.nfc.MRZInfo

/**
 * MRZInputActivity – Collects the three fields needed for BAC key derivation:
 *   • Document number (CCCD number, 9–12 digits)
 *   • Date of birth  (YYMMDD or DD/MM/YYYY)
 *   • Expiry date    (YYMMDD or DD/MM/YYYY)
 *
 * Returns via RESULT_OK:
 *   KEY_MRZ_INFO – MRZInfo parcelable
 */
class MRZInputActivity : AppCompatActivity() {

    companion object {
        const val KEY_MRZ_INFO = "mrz_info"
        const val REQUEST_CODE = 2002
    }

    private lateinit var binding: ActivityMrzInputBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMrzInputBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "Nhập thông tin MRZ / Enter MRZ Data"

        setupInputWatchers()
        setupDateFormatHelpers()

        binding.btnConfirm.setOnClickListener { validateAndProceed() }
        binding.btnCancel.setOnClickListener  { finish() }
    }

    private fun setupInputWatchers() {
        // Real-time check-digit display
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
        // Auto-format DOB as DD/MM/YY when typing
        fun autoFormatDate(et: android.widget.EditText, til: TextInputLayout) {
            et.addTextChangedListener(object : TextWatcher {
                private var isFormatting = false
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (isFormatting) return
                    isFormatting = true
                    val digits = s.toString().filter { it.isDigit() }.take(6)
                    val yymmdd = convertToYYMMDD(digits)
                    if (yymmdd != null) til.helperText = "YYMMDD: $yymmdd  Check: ${MRZInfo.checkDigit(yymmdd)}"
                    else til.helperText = "Format: DDMMYY or YYMMDD"
                    isFormatting = false
                }
            })
        }
        autoFormatDate(binding.etDob, binding.tilDob)
        autoFormatDate(binding.etExpiry, binding.tilExpiry)
    }

    private fun validateAndProceed() {
        var valid = true

        // Document number
        // Vietnamese CCCD has 12 digits, but BAC only uses the first 9 for the key seed.
        // The chip's MRZ document number field is always 9 chars (padded with '<').
        val docRaw = binding.etDocNumber.text.toString().uppercase().trim().take(9)
        if (docRaw.length < 9) {
            binding.tilDocNumber.error = "Enter first 9 digits of your CCCD number (e.g. 079087001 from 079087001234)"
            valid = false
        } else {
            binding.tilDocNumber.error = null
        }

        // Date of birth
        val dobRaw    = binding.etDob.text.toString().filter { it.isDigit() }
        val dobYYMMDD = convertToYYMMDD(dobRaw)
        if (dobYYMMDD == null) {
            binding.tilDob.error = "Invalid date. Use DDMMYY or YYMMDD (6 digits)"
            valid = false
        } else {
            binding.tilDob.error = null
        }

        // Expiry date
        val expRaw    = binding.etExpiry.text.toString().filter { it.isDigit() }
        val expYYMMDD = convertToYYMMDD(expRaw)
        if (expYYMMDD == null) {
            binding.tilExpiry.error = "Invalid date. Use DDMMYY or YYMMDD (6 digits)"
            valid = false
        } else {
            binding.tilExpiry.error = null
        }

        if (!valid) return

        // Build MRZInfo
        val docPadded = docRaw.padEnd(9, '<').take(9)
        val mrzInfo   = MRZInfo(docPadded, dobYYMMDD!!, expYYMMDD!!)

        if (!mrzInfo.isValid()) {
            binding.tilDocNumber.error = "Please check all entered values"
            return
        }

        val resultIntent = Intent().putExtra(KEY_MRZ_INFO, mrzInfo)
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    /**
     * Accepts either DDMMYY (Vietnamese common format) or YYMMDD (MRZ format).
     * Returns null if not a valid 6-digit date.
     */
    private fun convertToYYMMDD(digits: String): String? {
        if (digits.length != 6) return null

        // Try interpreting as DDMMYY (day first)
        val dd = digits.substring(0, 2).toIntOrNull() ?: return null
        val mm = digits.substring(2, 4).toIntOrNull() ?: return null
        val yy = digits.substring(4, 6).toIntOrNull() ?: return null

        if (dd in 1..31 && mm in 1..12) {
            // Looks like DDMMYY → convert to YYMMDD
            return "${digits.substring(4, 6)}${digits.substring(2, 4)}${digits.substring(0, 2)}"
        }

        // Try interpreting as YYMMDD (already correct format)
        val yy2 = digits.substring(0, 2).toIntOrNull() ?: return null
        val mm2 = digits.substring(2, 4).toIntOrNull() ?: return null
        val dd2 = digits.substring(4, 6).toIntOrNull() ?: return null

        if (mm2 in 1..12 && dd2 in 1..31) return digits

        return null
    }
}
