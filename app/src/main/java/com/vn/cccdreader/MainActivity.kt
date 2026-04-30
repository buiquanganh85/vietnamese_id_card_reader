package com.vn.cccdreader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vn.cccdreader.camera.CameraActivity
import com.vn.cccdreader.data.IDCardData
import com.vn.cccdreader.databinding.ActivityMainBinding
import com.vn.cccdreader.nfc.MRZInfo
import com.vn.cccdreader.nfc.NFCReaderActivity
import com.vn.cccdreader.ocr.MRZExtractor
import com.vn.cccdreader.ui.MRZInputActivity
import com.vn.cccdreader.ui.ResultActivity
import com.vn.cccdreader.util.parcelable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main entry screen – a step-by-step wizard:
 *
 *  Step 1 – Take photo of FRONT of CCCD card
 *  Step 2 – Take photo of BACK  of CCCD card
 *  Step 3 – Enter MRZ data (document number, DOB, expiry date)
 *  Step 4 – Read NFC chip
 *  Step 5 – View results
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Accumulated card data across all steps
    private var cardData = IDCardData()
    private var mrzInfo: MRZInfo? = null

    // ─── Activity result launchers ────────────────────────────────────────────

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera(pendingCameraMode)
        else toast("Camera permission is required to take photos.")
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data  = result.data ?: return@registerForActivityResult
            val uri  = data.getStringExtra(CameraActivity.KEY_PHOTO_URI) ?: return@registerForActivityResult
            val mode = data.getStringExtra(CameraActivity.KEY_MODE) ?: return@registerForActivityResult

            cardData = if (mode == CameraActivity.MODE_FRONT) {
                cardData.copy(frontPhotoPath = uri)
            } else {
                cardData.copy(backPhotoPath = uri)
            }
            refreshStepUI()

            if (mode == CameraActivity.MODE_BACK) {
                extractMrzFromBackPhoto(uri)
            }
        }
    }

    private val mrzLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            mrzInfo = result.data?.parcelable(MRZInputActivity.KEY_MRZ_INFO)
            refreshStepUI()
        }
    }

    private val nfcLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val updated: IDCardData? = result.data?.parcelable(NFCReaderActivity.KEY_CARD_DATA)
            if (updated != null) {
                cardData = updated
                if (updated.nfcReadSuccess) {
                    refreshStepUI()
                    startActivity(
                        Intent(this, ResultActivity::class.java)
                            .putExtra(ResultActivity.KEY_CARD_DATA, cardData)
                    )
                } else {
                    toast("NFC read failed: ${updated.nfcErrorMessage}")
                    refreshStepUI()
                }
            } else {
                refreshStepUI()
            }
        }
    }

    // ─── Activity lifecycle ───────────────────────────────────────────────────

    private var pendingCameraMode = CameraActivity.MODE_FRONT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "CCCD Reader"

        // Check NFC availability
        val nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            binding.tvNfcWarning.visibility = View.VISIBLE
            binding.tvNfcWarning.text       = "⚠ Thiết bị không hỗ trợ NFC / Device has no NFC"
        } else if (!nfcAdapter.isEnabled) {
            binding.tvNfcWarning.visibility = View.VISIBLE
            binding.tvNfcWarning.text       = "⚠ NFC đang tắt – vui lòng bật NFC / NFC is off – please enable it"
        } else {
            binding.tvNfcWarning.visibility = View.GONE
        }

        setupButtons()
        refreshStepUI()
    }

    private fun setupButtons() {
        binding.btnCaptureFront.setOnClickListener {
            checkCameraAndLaunch(CameraActivity.MODE_FRONT)
        }
        binding.btnCaptureBack.setOnClickListener {
            checkCameraAndLaunch(CameraActivity.MODE_BACK)
        }
        binding.btnEnterMrz.setOnClickListener {
            val intent = Intent(this, MRZInputActivity::class.java).apply {
                mrzInfo?.let { putExtra(MRZInputActivity.KEY_PREFILLED_MRZ, it) }
            }
            mrzLauncher.launch(intent)
        }
        binding.btnReadNfc.setOnClickListener {
            val mrz = mrzInfo
            if (mrz == null) {
                toast("Please enter MRZ data first (Step 3)")
                return@setOnClickListener
            }
            val intent = Intent(this, NFCReaderActivity::class.java).apply {
                putExtra(NFCReaderActivity.KEY_MRZ_INFO,  mrz)
                putExtra(NFCReaderActivity.KEY_CARD_DATA, cardData)
            }
            nfcLauncher.launch(intent)
        }
        binding.btnViewResults.setOnClickListener {
            val intent = Intent(this, ResultActivity::class.java)
                .putExtra(ResultActivity.KEY_CARD_DATA, cardData)
            startActivity(intent)
        }
    }

    private fun refreshStepUI() {
        val hasFront  = !cardData.frontPhotoPath.isNullOrBlank()
        val hasBack   = !cardData.backPhotoPath.isNullOrBlank()
        val hasMRZ    = mrzInfo != null
        val hasNFC    = cardData.nfcReadSuccess

        // Step status icons / text
        binding.ivStep1Check.visibility = if (hasFront) View.VISIBLE else View.INVISIBLE
        binding.ivStep2Check.visibility = if (hasBack)  View.VISIBLE else View.INVISIBLE
        binding.ivStep3Check.visibility = if (hasMRZ)   View.VISIBLE else View.INVISIBLE
        binding.ivStep4Check.visibility = if (hasNFC)   View.VISIBLE else View.INVISIBLE

        binding.tvStep1Status.text = if (hasFront) "✓ Đã chụp mặt trước" else "Chưa chụp"
        binding.tvStep2Status.text = if (hasBack)  "✓ Đã chụp mặt sau"   else "Chưa chụp"
        if (hasMRZ) {
            binding.tvStep3Status.text = "✓ ${mrzInfo!!.documentNumber.trimEnd('<')} (tự động / auto)"
        } else if (binding.tvStep3Status.text.startsWith("🔍") || binding.tvStep3Status.text.startsWith("Không")) {
            // Keep the OCR status message intact
        } else {
            binding.tvStep3Status.text = "Chưa nhập"
        }
        binding.tvStep4Status.text = if (hasNFC)   "✓ Đọc NFC thành công" else "Chưa đọc"

        // Enable/disable NFC button
        binding.btnReadNfc.isEnabled = hasMRZ

        // Show results button only if we have some data
        binding.btnViewResults.isEnabled = hasFront || hasBack || hasNFC
        binding.btnViewResults.alpha     = if (binding.btnViewResults.isEnabled) 1f else 0.5f
    }

    // ─── Camera permission helper ─────────────────────────────────────────────

    private fun checkCameraAndLaunch(mode: String) {
        pendingCameraMode = mode
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                launchCamera(mode)
            }
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera(mode: String) {
        val intent = Intent(this, CameraActivity::class.java)
            .putExtra(CameraActivity.KEY_MODE, mode)
        cameraLauncher.launch(intent)
    }

    private fun extractMrzFromBackPhoto(uriString: String) {
        binding.tvStep3Status.text = "🔍 Đang nhận dạng MRZ từ ảnh..."
        binding.btnEnterMrz.isEnabled = false

        lifecycleScope.launch {
            val extracted = withContext(Dispatchers.IO) {
                MRZExtractor.extractFromUri(this@MainActivity, Uri.parse(uriString))
            }
            if (extracted != null) {
                // Always update MRZ with newly extracted data, even if it already exists
                // This ensures every verso photo capture updates the MRZ info
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
