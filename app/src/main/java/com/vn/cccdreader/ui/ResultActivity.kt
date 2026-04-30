package com.vn.cccdreader.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.vn.cccdreader.R
import com.vn.cccdreader.data.IDCardData
import com.vn.cccdreader.databinding.ActivityResultBinding
import com.vn.cccdreader.util.parcelable

/**
 * ResultActivity – Displays all information read from the CCCD card.
 *
 * Extras in:
 *   KEY_CARD_DATA – IDCardData parcelable (required)
 */
class ResultActivity : AppCompatActivity() {

    companion object {
        const val KEY_CARD_DATA = "card_data"
        const val REQUEST_CODE  = 4001
    }

    private lateinit var binding: ActivityResultBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "Thông tin CCCD / ID Card Info"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val cardData: IDCardData = intent.parcelable(KEY_CARD_DATA) ?: run {
            finish()
            return
        }

        bindData(cardData)
    }

    private fun bindData(data: IDCardData) {
        // ── NFC face photo (DG2) ──────────────────────────────────────────────
        if (data.faceImageBytes != null && data.faceImageBytes.isNotEmpty()) {
            try {
                val bmp = BitmapFactory.decodeByteArray(data.faceImageBytes, 0, data.faceImageBytes.size)
                if (bmp != null) {
                    binding.ivFacePhoto.setImageBitmap(bmp)
                    binding.ivFacePhoto.visibility = View.VISIBLE
                    binding.tvNoFacePhoto.visibility = View.GONE
                } else {
                    binding.tvNoFacePhoto.text = "Không giải mã được ảnh chip"
                    binding.tvNoFacePhoto.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                binding.tvNoFacePhoto.visibility = View.VISIBLE
            }
        } else {
            binding.tvNoFacePhoto.visibility = View.VISIBLE
        }

        // ── Camera photos ─────────────────────────────────────────────────────
        if (!data.frontPhotoPath.isNullOrBlank()) {
            Glide.with(this).load(Uri.parse(data.frontPhotoPath)).centerCrop().into(binding.ivFrontPhoto)
            binding.cardFrontPhoto.visibility = View.VISIBLE
        } else {
            binding.cardFrontPhoto.visibility = View.GONE
        }

        if (!data.backPhotoPath.isNullOrBlank()) {
            Glide.with(this).load(Uri.parse(data.backPhotoPath)).centerCrop().into(binding.ivBackPhoto)
            binding.cardBackPhoto.visibility = View.VISIBLE
        } else {
            binding.cardBackPhoto.visibility = View.GONE
        }

        // ── Identity fields ───────────────────────────────────────────────────
        binding.tvFullName.text       = data.fullName().ifBlank { "—" }
        binding.tvDocNumber.text      = data.documentNumber.ifBlank { "—" }
        binding.tvPersonalNo.text     = data.personalNumber.ifBlank { data.documentNumber.ifBlank { "—" } }
        binding.tvDob.text            = data.displayDOB().ifBlank { "—" }
        binding.tvSex.text            = data.displaySex().ifBlank { "—" }
        binding.tvNationality.text    = data.nationality.ifBlank { "—" }
        binding.tvExpiry.text         = data.displayExpiry().ifBlank { "—" }

        // ── NFC status chip ───────────────────────────────────────────────────
        if (data.nfcReadSuccess) {
            binding.chipNfcStatus.text = "✓ NFC đọc thành công"
            binding.chipNfcStatus.setChipBackgroundColorResource(R.color.color_success_text)
            binding.chipNfcStatus.setTextColor(resources.getColor(R.color.white, theme))
        } else {
            binding.chipNfcStatus.text = "✗ Chưa đọc NFC"
            binding.chipNfcStatus.setChipBackgroundColorResource(R.color.color_warning_text)
            binding.chipNfcStatus.setTextColor(resources.getColor(R.color.white, theme))
            if (data.nfcErrorMessage.isNotBlank()) {
                binding.tvNfcError.text       = data.nfcErrorMessage
                binding.tvNfcError.visibility = View.VISIBLE
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
