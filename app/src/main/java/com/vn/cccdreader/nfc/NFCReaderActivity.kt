package com.vn.cccdreader.nfc

import android.content.Intent
import android.graphics.BitmapFactory
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vn.cccdreader.data.IDCardData
import com.vn.cccdreader.databinding.ActivityNfcReaderBinding
import com.vn.cccdreader.util.parcelable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * NFC reading screen.
 *
 * Uses NFC Reader Mode (not Foreground Dispatch) so the Android NFC stack
 * hands the tag directly to our callback without running NDEF detection first.
 * NDEF detection on an eMRTD chip times out, corrupts the ISO-DEP channel,
 * and causes every subsequent APDU to fail.
 *
 * Expects extras:
 *   KEY_MRZ_INFO  – MRZInfo parcelable (required for BAC)
 *   KEY_CARD_DATA – IDCardData parcelable (carries camera photos forward)
 *
 * Returns via setResult(RESULT_OK):
 *   KEY_CARD_DATA – updated IDCardData with NFC fields filled in
 */
class NFCReaderActivity : AppCompatActivity() {

    companion object {
        const val KEY_MRZ_INFO  = "mrz_info"
        const val KEY_CARD_DATA = "card_data"
        const val REQUEST_CODE  = 3001
    }

    private lateinit var binding: ActivityNfcReaderBinding
    private var nfcAdapter: NfcAdapter? = null
    private var mrzInfo: MRZInfo? = null
    private var existingData: IDCardData = IDCardData()

    @Volatile private var isReading = false

    // Holds the last successfully read result so btnDone can return it
    private var lastResult: IDCardData = IDCardData()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNfcReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mrzInfo      = intent.parcelable(KEY_MRZ_INFO)
        existingData = intent.parcelable(KEY_CARD_DATA) ?: IDCardData()

        if (mrzInfo == null) {
            showError("MRZ data is missing. Please go back and enter MRZ information.")
            return
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            showError("This device does not support NFC.")
            return
        }
        if (!nfcAdapter!!.isEnabled) {
            showError("NFC is disabled. Please enable NFC in Settings.")
            return
        }

        supportActionBar?.title = "Đọc NFC / Read NFC"

        binding.btnCancel.setOnClickListener { finish() }
        binding.btnDone.setOnClickListener {
            val resultIntent = Intent().putExtra(KEY_CARD_DATA, lastResult)
            setResult(RESULT_OK, resultIntent)
            finish()
        }

        showWaiting()
    }

    override fun onResume() {
        super.onResume()
        // Reader mode skips NDEF detection entirely (FLAG_READER_SKIP_NDEF_CHECK).
        // Without this, the NFC stack runs NDEF detection on the eMRTD chip,
        // which times out, corrupts the ISO-DEP channel state, and causes every
        // subsequent APDU to fail before our app even receives the tag.
        //
        // The callback arrives on a binder thread (not the main thread).
        nfcAdapter?.enableReaderMode(
            this,
            { tag -> onTagDiscovered(tag) },
            NfcAdapter.FLAG_READER_NFC_A          or  // ISO 14443-A chips
            NfcAdapter.FLAG_READER_NFC_B          or  // ISO 14443-B chips
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or  // ← key: no NDEF timeout
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, // suppress system beep
            null
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    // Called on the NFC binder thread – must not touch the UI directly
    private fun onTagDiscovered(tag: Tag) {
        if (isReading) return
        processTag(tag)
    }

    private fun processTag(tag: Tag) {
        val isoDep = IsoDep.get(tag) ?: run {
            lifecycleScope.launch(Dispatchers.Main) {
                showError("Tag is not ISO-DEP (not an eMRTD chip).")
            }
            return
        }

        isReading = true
        lifecycleScope.launch(Dispatchers.Main) {
            showReading("Đã phát hiện thẻ – đang xác thực…\nCard detected – authenticating…")
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    // Setting timeout BEFORE connect() ensures the very first
                    // ISO-DEP frame uses our generous 30 s budget rather than the
                    // platform default (~5 s), which by itself frequently surfaces
                    // as "Tag was lost" on slow-responding CCCD chips.
                    runCatching { isoDep.timeout = 30_000 }
                    isoDep.connect()
                    runCatching { isoDep.timeout = 30_000 }
                    val reader = MRTDReader(isoDep)
                    reader.readCard(
                        mrzInfo = mrzInfo!!,
                        existingData = existingData,
                        onPhase = { phase ->
                            lifecycleScope.launch(Dispatchers.Main) { showReading(phase) }
                        }
                    )
                } catch (e: Exception) {
                    existingData.copy(
                        nfcReadSuccess = false,
                        nfcErrorMessage = e.message ?: "Unknown error"
                    )
                } finally {
                    runCatching { isoDep.close() }
                }
            }

            isReading = false
            if (result.nfcReadSuccess) {
                lastResult = result
                showResult(result)
            } else {
                showError(result.nfcErrorMessage.ifBlank { "NFC read failed. Please try again." })
            }
        }
    }

    // ─── UI helpers (all called on the main thread) ───────────────────────────

    private fun showWaiting() {
        binding.apply {
            layoutScanning.visibility = View.VISIBLE
            progressBar.visibility    = View.GONE
            ivNfcIcon.visibility      = View.VISIBLE
            tvStatus.text             = "Đặt thẻ CCCD lên mặt sau điện thoại\n\nPlace the CCCD card on the back of your phone near the NFC antenna"
            tvSubStatus.visibility    = View.GONE
            cardResult.visibility     = View.GONE
            cardError.visibility      = View.GONE
            btnDone.visibility        = View.GONE
            btnCancel.text            = "Huỷ / Cancel"
        }
    }

    private fun showReading(phase: String) {
        binding.apply {
            layoutScanning.visibility = View.VISIBLE
            progressBar.visibility    = View.VISIBLE
            ivNfcIcon.visibility      = View.GONE
            tvStatus.text             = phase
            tvSubStatus.text          = "⚠ Giữ thẻ yên – không di chuyển!\nKeep the card perfectly still!"
            tvSubStatus.visibility    = View.VISIBLE
            cardResult.visibility     = View.GONE
            cardError.visibility      = View.GONE
            btnDone.visibility        = View.GONE
        }
    }

    private fun showResult(data: IDCardData) {
        binding.apply {
            layoutScanning.visibility = View.GONE
            cardError.visibility      = View.GONE
            cardResult.visibility     = View.VISIBLE
            btnDone.visibility        = View.VISIBLE
            btnCancel.text            = "Đọc lại / Re-scan"

            // Face photo from DG2
            val faceBytes = data.faceImageBytes
            if (faceBytes != null && faceBytes.isNotEmpty()) {
                val bmp = runCatching {
                    BitmapFactory.decodeByteArray(faceBytes, 0, faceBytes.size)
                }.getOrNull()
                if (bmp != null) {
                    ivFacePhoto.setImageBitmap(bmp)
                    ivFacePhoto.visibility   = View.VISIBLE
                    tvNoFacePhoto.visibility = View.GONE
                } else {
                    tvNoFacePhoto.text       = "Không giải mã được ảnh / Could not decode photo"
                    tvNoFacePhoto.visibility = View.VISIBLE
                    ivFacePhoto.visibility   = View.GONE
                }
            } else {
                tvNoFacePhoto.visibility = View.VISIBLE
                ivFacePhoto.visibility   = View.GONE
            }

            // Identity fields
            tvFullName.text    = data.fullName().ifBlank { "—" }
            tvDocNumber.text   = data.documentNumber.ifBlank { "—" }
            tvDob.text         = data.displayDOB().ifBlank { "—" }
            tvSex.text         = data.displaySex().ifBlank { "—" }
            tvNationality.text = data.nationality.ifBlank { "—" }
            tvExpiry.text      = data.displayExpiry().ifBlank { "—" }
            tvPersonalNo.text  = data.personalNumber.ifBlank { data.documentNumber }.ifBlank { "—" }

            // Raw MRZ lines
            tvMrzLine1.text       = data.mrzLine1.ifBlank { "(empty)" }
            tvMrzLine2.text       = data.mrzLine2.ifBlank { "(empty)" }
            tvMrzLine3.text       = data.mrzLine3.ifBlank { "" }
            tvMrzLine3.visibility = if (data.mrzLine3.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun showError(msg: String) {
        binding.apply {
            layoutScanning.visibility = View.VISIBLE
            progressBar.visibility    = View.GONE
            ivNfcIcon.visibility      = View.VISIBLE
            tvStatus.text             = "Lỗi / Error"
            tvSubStatus.visibility    = View.GONE
            cardResult.visibility     = View.GONE
            cardError.visibility      = View.VISIBLE
            tvErrorMessage.text       = msg
            btnDone.visibility        = View.GONE
            btnCancel.text            = "Huỷ / Cancel"
            btnRetry.setOnClickListener {
                isReading = false
                showWaiting()
            }
        }
    }
}
