package com.vn.cccdreader.camera

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.vn.cccdreader.databinding.ActivityCameraBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraActivity – Full-screen camera viewfinder for capturing ID card photos.
 *
 * Extras in:
 *   KEY_MODE  – "FRONT" | "BACK"  (which side of the card to capture)
 *
 * Extras out (RESULT_OK):
 *   KEY_PHOTO_URI  – content URI string of the captured photo
 *   KEY_MODE       – echoed back
 */
class CameraActivity : AppCompatActivity() {

    companion object {
        const val KEY_MODE      = "capture_mode"
        const val KEY_PHOTO_URI = "photo_uri"
        const val MODE_FRONT    = "FRONT"
        const val MODE_BACK     = "BACK"
        const val REQUEST_CODE  = 2001
        private const val TAG   = "CameraActivity"
    }

    private lateinit var binding: ActivityCameraBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var mode: String = MODE_FRONT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra(KEY_MODE) ?: MODE_FRONT

        val titleRes = if (mode == MODE_FRONT) "Chụp mặt trước / Front" else "Chụp mặt sau / Back"
        supportActionBar?.title = titleRes
        binding.tvInstruction.text = if (mode == MODE_FRONT)
            "Đặt mặt trước thẻ CCCD vào khung\nPlace the FRONT of the CCCD card in the frame"
        else
            "Đặt mặt sau thẻ CCCD vào khung\nPlace the BACK of the CCCD card in the frame"

        cameraExecutor = Executors.newSingleThreadExecutor()

        startCamera()

        binding.btnCapture.setOnClickListener { takePhoto() }
        binding.btnCancel.setOnClickListener  { finish() }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        binding.btnCapture.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE

        val photoFile = File(
            externalCacheDir ?: cacheDir,
            "CCCD_${mode}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        )
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(outputOptions, cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = Uri.fromFile(photoFile)
                    val resultIntent = Intent().apply {
                        putExtra(KEY_PHOTO_URI, uri.toString())
                        putExtra(KEY_MODE, mode)
                    }
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    runOnUiThread {
                        binding.btnCapture.isEnabled = true
                        binding.progressBar.visibility = View.GONE
                        binding.tvInstruction.text = "Capture failed. Please try again."
                    }
                }
            })
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
