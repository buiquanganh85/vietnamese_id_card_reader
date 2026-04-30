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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
        private const val CARD_ASPECT = 300f / 190f // ≈ 1.5789

        // Configurable thresholds
        const val MOTION_MAD_THRESHOLD: Double = 3.0      // Lower = more strict stillness
        const val STABILITY_DURATION_MS: Long  = 700L    // Time to hold still before auto-capture
    }

    private lateinit var binding: ActivityCameraBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null

    // Auto-capture state
    private var mode: String = MODE_FRONT
    private var lastDownsampled: IntArray? = null
    private var lastW: Int = 0
    private var lastH: Int = 0
    private var stableSince: Long = 0L
    private var isStable: Boolean = false
    private var isFramed: Boolean = false
    @Volatile private var captureRequested: Boolean = false

    // Review state
    private var inReview: Boolean = false
    private var pendingPhotoFile: File? = null
    private var pendingPhotoUri: Uri? = null
    private var confirmed: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra(KEY_MODE) ?: MODE_FRONT

        val titleRes = if (mode == MODE_FRONT) "Chụp mặt trước / Front" else "Chụp mặt sau / Back"
        supportActionBar?.title = titleRes
        val holdSecs = STABILITY_DURATION_MS / 1000.0
        val secsStr = if (STABILITY_DURATION_MS % 1000L == 0L)
            String.format(Locale.getDefault(), "%.0f", holdSecs)
        else
            String.format(Locale.getDefault(), "%.1f", holdSecs)
        binding.tvInstruction.text = if (mode == MODE_FRONT)
            "Đặt mặt trước thẻ CCCD vào khung\nPlace the FRONT of the CCCD card in the frame\nGiữ máy ổn định ${secsStr}s để chụp tự động / Hold still ${secsStr}s for auto-capture"
        else
            "Đặt mặt sau thẻ CCCD vào khung\nPlace the BACK of the CCCD card in the frame\nGiữ máy ổn định ${secsStr}s để chụp tự động / Hold still ${secsStr}s for auto-capture"

        cameraExecutor = Executors.newSingleThreadExecutor()

        startCamera()

        binding.btnCancel.setOnClickListener  {
            // If user cancels during review, cleanup the temporary file
            if (inReview) {
                pendingPhotoFile?.let { f ->
                    try { f.delete() } catch (_: Throwable) {}
                }
                pendingPhotoFile = null
                pendingPhotoUri = null
            }
            finish()
        }

        // Review actions
        binding.btnConfirm.setOnClickListener {
            val uri = pendingPhotoUri
            if (uri != null) {
                val resultIntent = Intent().apply {
                    putExtra(KEY_PHOTO_URI, uri.toString())
                    putExtra(KEY_MODE, mode)
                }
                setResult(RESULT_OK, resultIntent)
                confirmed = true
            }
            finish()
        }
        binding.btnRetake.setOnClickListener {
            // Delete pending file if any
            pendingPhotoFile?.let { f ->
                try { f.delete() } catch (_: Throwable) {}
            }
            pendingPhotoFile = null
            pendingPhotoUri = null
            // Hide review and reset analyzer state
            binding.reviewContainer.visibility = View.GONE
            binding.ivPreview.setImageDrawable(null)
            binding.cardFrameGuide.visibility = View.VISIBLE
            inReview = false
            captureRequested = false
            lastDownsampled = null
            stableSince = 0L
            isStable = false
            isFramed = false
            // Restore instruction text
            val holdSecs = STABILITY_DURATION_MS / 1000.0
            val secsStr = if (STABILITY_DURATION_MS % 1000L == 0L)
                String.format(Locale.getDefault(), "%.0f", holdSecs)
            else
                String.format(Locale.getDefault(), "%.1f", holdSecs)
            binding.tvInstruction.text = if (mode == MODE_FRONT)
                "Đặt mặt trước thẻ CCCD vào khung\nPlace the FRONT of the CCCD card in the frame\nGiữ máy ổn định ${secsStr}s để chụp tự động / Hold still ${secsStr}s for auto-capture"
            else
                "Đặt mặt sau thẻ CCCD vào khung\nPlace the BACK of the CCCD card in the frame\nGiữ máy ổn định ${secsStr}s để chụp tự động / Hold still ${secsStr}s for auto-capture"
        }
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

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { image ->
                        try {
                            analyzeFrame(image)
                        } catch (t: Throwable) {
                            Log.w(TAG, "Analyzer error: ${t.message}")
                        } finally {
                            image.close()
                        }
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        if (inReview) return
        val plane = image.planes.firstOrNull() ?: return
        val buffer = plane.buffer
        if (!buffer.hasArray()) {
            // Fallback: copy buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            processLuma(bytes, image.width, image.height)
        } else {
            val arr = buffer.array()
            val offset = buffer.arrayOffset() + buffer.position()
            val len = buffer.remaining()
            val y = arr.copyOfRange(offset, offset + len)
            processLuma(y, image.width, image.height)
        }
    }

    private fun processLuma(yBytes: ByteArray, width: Int, height: Int) {
        // Downsample grid to reduce noise and workload
        val step = 8 // every 8th pixel
        val dsW = width / step
        val dsH = height / step
        if (dsW <= 2 || dsH <= 2) return
        val down = IntArray(dsW * dsH)
        var idx = 0
        var sum = 0L
        for (j in 0 until dsH) {
            val row = j * step
            val base = row * width
            for (i in 0 until dsW) {
                val col = i * step
                val v = yBytes[base + col].toInt() and 0xFF
                down[idx++] = v
                sum += v
            }
        }
        val mean = sum.toDouble() / down.size

        // Motion estimation: mean absolute difference to previous downsample
        val last = lastDownsampled
        var mad = 9999.0
        if (last != null && last.size == down.size) {
            var acc = 0L
            for (k in down.indices) acc += abs(down[k] - last[k])
            mad = acc.toDouble() / down.size
        }
        lastDownsampled = down
        lastW = dsW
        lastH = dsH

        val now = System.currentTimeMillis()
        val motionThreshold = MOTION_MAD_THRESHOLD
        if (mad < motionThreshold) {
            if (!isStable) {
                if (stableSince == 0L) stableSince = now
                if (now - stableSince >= STABILITY_DURATION_MS) {
                    isStable = true
                    runOnUiThread {
                        binding.tvInstruction.text = "Đã giữ ổn định ✓\nHold still ✓\nĐang kiểm tra khung..."
                    }
                } else {
                    val remaining = ((STABILITY_DURATION_MS - (now - stableSince)) / 1000.0)
                    runOnUiThread {
                        binding.tvInstruction.text = String.format(Locale.getDefault(),
                            "Giữ máy ổn định ~%.1fs nữa để chụp tự động\nHold still ~%.1fs to auto-capture",
                            remaining, remaining)
                    }
                }
            }
        } else {
            // motion detected -> reset stability
            if (isStable || stableSince != 0L) {
                runOnUiThread {
                    binding.tvInstruction.text = "Di chuyển ít hơn / Hold still to auto-capture"
                }
            }
            isStable = false
            stableSince = 0L
        }

        // Framing heuristic on the downsample grid
        isFramed = checkFraming(down, dsW, dsH, mean)
        if (!isFramed) {
            runOnUiThread {
                binding.tvInstruction.text = "Căn thẻ vào khung / Fit the card within the frame"
            }
        }

        if (isStable && isFramed && !captureRequested) {
            captureRequested = true
            runOnUiThread {
                binding.tvInstruction.text = "Điều kiện đạt ✓\nCapturing..."
            }
            takePhoto()
        }
    }

    private fun checkFraming(pixels: IntArray, w: Int, h: Int, mean: Double): Boolean {
        // Define ROI: centered rectangle with target aspect. Size approx 70% of min dimension
        val targetAspect = CARD_ASPECT
        val scale = 0.72
        val base = min(w, (h * targetAspect).toInt())
        var roiW = (base * scale).toInt()
        var roiH = (roiW / targetAspect).toInt()
        if (roiH > h) {
            roiH = (h * scale).toInt()
            roiW = (roiH * targetAspect).toInt()
        }
        val cx = w / 2
        val cy = h / 2
        val x0 = max(1, cx - roiW / 2)
        val y0 = max(1, cy - roiH / 2)
        val x1 = min(w - 2, x0 + roiW)
        val y1 = min(h - 2, y0 + roiH)

        // Compute inside variance and border edge strength
        var insideSum = 0L
        var insideSq = 0L
        var count = 0
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) {
                val v = pixels[row + x]
                insideSum += v
                insideSq += v * v
                count++
            }
        }
        val insideMean = insideSum.toDouble() / max(1, count)
        val insideVar = insideSq.toDouble() / max(1, count) - insideMean * insideMean

        // Simple edge density along the 4 borders using absolute differences
        fun edgeLine(ax0: Int, ay0: Int, ax1: Int, ay1: Int): Double {
            var acc = 0L
            var n = 0
            if (ay0 == ay1) {
                val y = ay0
                val row = y * w
                for (x in ax0 until ax1) {
                    val g = abs(pixels[row + x + 1] - pixels[row + x])
                    acc += g
                    n++
                }
            } else if (ax0 == ax1) {
                val x = ax0
                for (y in ay0 until ay1) {
                    val g = abs(pixels[(y + 1) * w + x] - pixels[y * w + x])
                    acc += g
                    n++
                }
            }
            return if (n == 0) 0.0 else acc.toDouble() / n
        }
        val top = edgeLine(x0, y0, x1, y0)
        val bottom = edgeLine(x0, y1, x1, y1)
        val left = edgeLine(x0, y0, x0, y1)
        val right = edgeLine(x1, y0, x1, y1)
        val borderEdge = (top + bottom + left + right) / 4.0

        // Outside ring mean difference
        var ringSum = 0L
        var ringCount = 0
        val pad = (min(roiW, roiH) * 0.08).toInt().coerceAtLeast(1)
        val rx0 = max(1, x0 - pad)
        val ry0 = max(1, y0 - pad)
        val rx1 = min(w - 2, x1 + pad)
        val ry1 = min(h - 2, y1 + pad)
        for (y in ry0 until ry1) {
            val row = y * w
            for (x in rx0 until rx1) {
                val inside = x in x0 until x1 && y in y0 until y1
                if (!inside) {
                    ringSum += pixels[row + x]
                    ringCount++
                }
            }
        }
        val ringMean = if (ringCount == 0) mean else ringSum.toDouble() / ringCount

        // Heuristics thresholds
        val varianceOk = insideVar > 150.0 // card text/features cause variance
        val contrastOk = abs(insideMean - ringMean) > 8.0
        val edgesOk = borderEdge > 6.0

        return varianceOk && contrastOk && edgesOk
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        runOnUiThread {
            binding.progressBar.visibility = View.VISIBLE
            binding.tvInstruction.text = "Đang chụp... / Capturing..."
        }

        val photoFile = File(
            externalCacheDir ?: cacheDir,
            "CCCD_${mode}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        )
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(outputOptions, cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = Uri.fromFile(photoFile)
                    pendingPhotoFile = photoFile
                    pendingPhotoUri  = uri
                    inReview = true
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        // Hide card frame guide for an unobstructed preview
                        binding.cardFrameGuide.visibility = View.GONE
                        binding.reviewContainer.visibility = View.VISIBLE
                        try {
                            binding.ivPreview.setImageURI(uri)
                        } catch (_: Throwable) {
                            // Fallback – clear image on error
                            binding.ivPreview.setImageDrawable(null)
                        }
                        binding.tvInstruction.text = if (mode == MODE_FRONT)
                            "Xem ảnh mặt trước. Xác nhận nếu rõ nét / Review FRONT photo. Confirm if clear."
                        else
                            "Xem ảnh mặt sau. Xác nhận nếu rõ nét / Review BACK photo. Confirm if clear."
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        binding.tvInstruction.text = "Chụp thất bại, vui lòng thử lại. / Capture failed. Please try again."
                        captureRequested = false
                    }
                }
            })
    }

    override fun onDestroy() {
        super.onDestroy()
        // If a temporary photo exists and user didn't confirm, delete it
        if (!confirmed) {
            pendingPhotoFile?.let { f ->
                try { f.delete() } catch (_: Throwable) {}
            }
        }
        cameraExecutor.shutdown()
    }
}
