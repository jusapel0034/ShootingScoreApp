package com.shootingscore

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.documentfile.provider.DocumentFile
import org.opencv.android.OpenCVLoader
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.shootingscore.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.atan2

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import android.graphics.SurfaceTexture
import android.view.TextureView

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    
    private var exoPlayer: ExoPlayer? = null
    private var isRtspMode = false
    
    private lateinit var yoloDetector: YoloDetector
    private lateinit var imageProcessor: ImageProcessor

    private var totalScore = 0
    private var shotCount = 0
    private var currentRound = 0
    private val allHits = mutableListOf<Hit>()
    private var lastReferencePath: String? = null
    private var isCameraMode = false
    private var isAutoScoring = false
    private var autoScoreJob: kotlinx.coroutines.Job? = null
    private var currentReferenceBitmap: Bitmap? = null

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            if (isCameraMode) startCamera() // If we were trying to start it
        } else {
            Toast.makeText(this, "Camera permission required!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Request permissions at startup
        checkAndRequestPermissions()

        // Initialize OpenCV
        if (!OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "Internal OpenCV library not found.")
        } else {
            Log.d("OpenCV", "OpenCV library found inside package. Using it!")
        }

        // Initialize Detector and Processor
        yoloDetector = YoloDetector(this)
        imageProcessor = ImageProcessor(yoloDetector)
        
        Toast.makeText(this, "App Logic Updated: v1.6 (Stable Align)", Toast.LENGTH_SHORT).show()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Load reference path
        lastReferencePath = getSharedPreferences("shooting_prefs", MODE_PRIVATE)
            .getString("last_ref_path", null)

        // Show default target placeholder
        showDefaultTarget()

        // Button listeners
        binding.btnCapture.setOnClickListener { captureAndScore() }
        binding.btnCaptureReference.setOnClickListener { captureReference() }
        binding.btnAutoScore.setOnClickListener { toggleAutoScoring() }
        binding.btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
        binding.btnReset.setOnClickListener { resetScores() }
        binding.btnBrowse.setOnClickListener { browseImages() }
        binding.btnCameraToggle.setOnClickListener { toggleCameraMode() }
        binding.btnBackArrow.setOnClickListener { if (isCameraMode) toggleCameraMode() }

        // Control buttons (non-functional UI for now)
        binding.btnTargetUp.setOnClickListener { Toast.makeText(this, "Target Up", Toast.LENGTH_SHORT).show() }
        binding.btnTargetDown.setOnClickListener { Toast.makeText(this, "Target Down", Toast.LENGTH_SHORT).show() }
        binding.btnNextRound.setOnClickListener { nextRound() }

        updateScoreDisplay()
        updateRoundDisplay()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.S_V2) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (toRequest.isNotEmpty()) {
            requestPermission.launch(toRequest.toTypedArray())
        }
    }

    private fun showDefaultTarget() {
        // Create a placeholder target image
        val size = 600
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background
        canvas.drawColor(Color.parseColor("#e8eef4"))

        val centerX = size / 2f
        val centerY = size / 2f
        val maxRadius = size * 0.42f

        // Draw target rings
        val ringPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
            color = Color.parseColor("#2c3e50")
        }

        val fillPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val ringRatios = listOf(1.0f, 0.82f, 0.65f, 0.50f, 0.35f, 0.22f, 0.10f)
        val ringNumbers = listOf(6, 7, 8, 9, 10)

        // Draw alternating dark/light rings
        for (i in ringRatios.indices) {
            fillPaint.color = if (i % 2 == 0) "#34495e".toColorInt() else "#5d6d7e".toColorInt()
            canvas.drawCircle(centerX, centerY, maxRadius * ringRatios[i], fillPaint)
        }

        // Draw ring outlines
        for (ratio in ringRatios) {
            canvas.drawCircle(centerX, centerY, maxRadius * ratio, ringPaint)
        }

        // Draw ring numbers
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = maxRadius * 0.08f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        ringNumbers.forEachIndexed { index, number ->
            val ratio = if (index < ringRatios.size - 1) {
                (ringRatios[index] + ringRatios[index + 1]) / 2
            } else {
                ringRatios[index] * 0.5f
            }
            val y = centerY - maxRadius * ratio + textPaint.textSize / 3
            canvas.drawText(number.toString(), centerX, y, textPaint)
            canvas.drawText(number.toString(), centerX, centerY + maxRadius * ratio + textPaint.textSize / 3, textPaint)
            canvas.drawText(number.toString(), centerX - maxRadius * ratio, centerY + textPaint.textSize / 3, textPaint)
            canvas.drawText(number.toString(), centerX + maxRadius * ratio, centerY + textPaint.textSize / 3, textPaint)
        }

        binding.targetImageView.setImageBitmap(bitmap)
    }

    private fun toggleCameraMode() {
        isCameraMode = !isCameraMode
        val prefs = getSharedPreferences("shooting_prefs", MODE_PRIVATE)
        isRtspMode = prefs.getInt("camera_source", 0) == 1

        if (isCameraMode) {
            if (isRtspMode) {
                startRtspPlayer()
                binding.rtspTextureView.visibility = View.VISIBLE
                binding.viewFinder.visibility = View.GONE
            } else {
                // Check permission first for local camera
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                    requestPermission.launch(arrayOf(Manifest.permission.CAMERA))
                    isCameraMode = false
                    return
                }
                startCamera()
                binding.viewFinder.visibility = View.VISIBLE
                binding.rtspTextureView.visibility = View.GONE
            }
            binding.targetImageView.visibility = View.GONE
            binding.targetGuide.visibility = View.VISIBLE
            binding.btnCaptureReference.visibility = View.VISIBLE
            binding.btnCapture.text = "CAPTURE"
        } else {
            if (isRtspMode) stopRtspPlayer() else stopCamera()
            binding.viewFinder.visibility = View.GONE
            binding.rtspTextureView.visibility = View.GONE
            binding.targetImageView.visibility = View.VISIBLE
            binding.targetGuide.visibility = View.GONE
            binding.btnCaptureReference.visibility = View.GONE
            binding.btnCapture.text = "CAPTURE"
        }
    }

    private fun startRtspPlayer() {
        val prefs = getSharedPreferences("shooting_prefs", MODE_PRIVATE)
        val rtspUrl = prefs.getString("rtsp_url", "rtsp://admin:646335@192.168.1.148:554/live/profile.0/video") ?: return

        stopRtspPlayer() // Ensure previous instance is cleaned up

        exoPlayer = ExoPlayer.Builder(this).build().apply {
            val mediaItem = MediaItem.fromUri(rtspUrl)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
            
            // Set the surface to our TextureView
            setVideoTextureView(binding.rtspTextureView)
            
            addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e("ShootingScore", "RTSP Player Error: ${error.message}", error)
                    Toast.makeText(this@MainActivity, "RTSP Error: ${error.message}", Toast.LENGTH_LONG).show()
                }
            })
        }
    }

    private fun stopRtspPlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (exc: Exception) {
                Log.e("ShootingScore", "Camera binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        } catch (e: Exception) {
            Log.e("ShootingScore", "Error stopping camera", e)
        }
    }

    private fun getUriFromPath(path: String): Uri {
        return if (path.startsWith("content://") || path.startsWith("file://")) {
            Uri.parse(path)
        } else {
            val file = File(path)
            if (file.exists()) {
                Uri.fromFile(file)
            } else {
                Uri.parse(path)
            }
        }
    }

    private fun decodeBitmapFromUri(uri: Uri, options: BitmapFactory.Options): Bitmap? {
        return try {
            val bitmap = if (uri.scheme == "file") {
                val path = uri.path
                if (path == null) {
                    Log.e("ShootingScore", "Uri path is null for file: $uri")
                    null
                } else {
                    BitmapFactory.decodeFile(path, options)
                }
            } else {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream, null, options)
                }
            }
            if (bitmap == null) {
                Log.e("ShootingScore", "BitmapFactory returned null for $uri")
            }
            bitmap
        } catch (e: Exception) {
            Log.e("ShootingScore", "Error decoding bitmap from Uri: $uri", e)
            null
        }
    }

    private fun captureAndScore() {
        if (!isCameraMode) {
            toggleCameraMode()
            Toast.makeText(this, "Camera activated. Tap CAPTURE again.", Toast.LENGTH_SHORT).show()
            return
        }

        if (isRtspMode) {
            captureRtspAndScore()
            return
        }

        val capture = imageCapture ?: run {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnCapture.isEnabled = false

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val fileName = "TEMP_RAW_$timeStamp.jpg"
        val tempFile = File(cacheDir, fileName)
        
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                            val bitmap = decodeBitmapFromUri(Uri.fromFile(tempFile), options)
                            if (tempFile.exists()) tempFile.delete()
                            
                            if (bitmap != null) {
                                if (isBitmapBlack(bitmap)) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@MainActivity, "Capture failed: Image was black. Try again.", Toast.LENGTH_LONG).show()
                                        binding.progressBar.visibility = View.GONE
                                        binding.btnCapture.isEnabled = true
                                    }
                                } else {
                                    handleCapturedBitmap(bitmap)
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "Failed to decode image", Toast.LENGTH_SHORT).show()
                                    binding.progressBar.visibility = View.GONE
                                    binding.btnCapture.isEnabled = true
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("ShootingScore", "Exception in processing thread", e)
                            if (tempFile.exists()) tempFile.delete()
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Processing failed: ${e.message}", Toast.LENGTH_LONG).show()
                                binding.progressBar.visibility = View.GONE
                                binding.btnCapture.isEnabled = true
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnCapture.isEnabled = true
                    Toast.makeText(this@MainActivity, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun captureRtspAndScore() {
        val bitmap = binding.rtspTextureView.bitmap
        
        if (bitmap != null) {
            // Check if bitmap is black (common RTSP capture issue)
            val isBlack = isBitmapBlack(bitmap)
            if (isBlack) {
                Toast.makeText(this, "RTSP stream is not ready or black. Retrying...", Toast.LENGTH_SHORT).show()
                // Try once more after a short delay
                binding.rtspTextureView.postDelayed({
                    val retryBitmap = binding.rtspTextureView.bitmap
                    if (retryBitmap != null && !isBitmapBlack(retryBitmap)) {
                        processRtspBitmap(retryBitmap)
                    } else {
                        Toast.makeText(this, "Capture failed: Stream returned no image.", Toast.LENGTH_LONG).show()
                    }
                }, 500)
                return
            }
            processRtspBitmap(bitmap)
        } else {
            Toast.makeText(this, "Failed to capture frame from RTSP stream", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processRtspBitmap(bitmap: Bitmap) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            binding.btnCapture.isEnabled = false
            handleCapturedBitmap(bitmap)
        }
    }

    private fun isBitmapBlack(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        
        // Sample multiple points to ensure it's not just a dark background
        var blackCount = 0
        val samplePoints = listOf(
            PointF(w*0.25f, h*0.25f), PointF(w*0.5f, h*0.25f), PointF(w*0.75f, h*0.25f),
            PointF(w*0.25f, h*0.5f),  PointF(w*0.5f, h*0.5f),  PointF(w*0.75f, h*0.5f),
            PointF(w*0.25f, h*0.75f), PointF(w*0.5f, h*0.75f), PointF(w*0.75f, h*0.75f)
        )
        
        samplePoints.forEach { pt ->
            val pixel = bitmap.getPixel(pt.x.toInt(), pt.y.toInt())
            if (Color.red(pixel) < 15 && Color.green(pixel) < 15 && Color.blue(pixel) < 15) {
                blackCount++
            }
        }
        
        // If 8 out of 9 sample points are pitch black, it's a failed capture
        return blackCount >= 8
    }

    private suspend fun handleCapturedBitmap(bitmap: Bitmap) {
        val prefs = getSharedPreferences("shooting_prefs", MODE_PRIVATE)
        val useComparison = prefs.getBoolean("use_comparison_mode", false)
        
        withContext(Dispatchers.Main) {
            if (useComparison && lastReferencePath != null) {
                val refUri = getUriFromPath(lastReferencePath!!)
                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                val refBitmap = decodeBitmapFromUri(refUri, options)
                
                if (refBitmap != null) {
                    processImageWithRef(bitmap, refBitmap)
                } else {
                    processImage(bitmap)
                }
            } else {
                processImage(bitmap)
            }
        }
    }

    private fun captureReference() {
        if (!isCameraMode) return

        if (isRtspMode) {
            captureRtspReference()
            return
        }

        val capture = imageCapture ?: run {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnCaptureReference.isEnabled = false

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val fileName = "REF_TARGET_$timeStamp.jpg"
        val tempFile = File(cacheDir, fileName)
        
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath)
                        if (bitmap != null) {
                            val warped = imageProcessor.getWarpedTarget(bitmap)
                            if (warped != null) {
                                // Save only the warped target
                                FileOutputStream(tempFile).use { out ->
                                    warped.compress(Bitmap.CompressFormat.JPEG, 95, out)
                                }
                                saveRefFile(tempFile, fileName)
                            } else {
                                withContext(Dispatchers.Main) {
                                    if (tempFile.exists()) tempFile.delete()
                                    binding.progressBar.visibility = View.GONE
                                    binding.btnCaptureReference.isEnabled = true
                                    Toast.makeText(this@MainActivity, "No shooting target detected in view!", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    if (tempFile.exists()) tempFile.delete()
                    binding.progressBar.visibility = View.GONE
                    binding.btnCaptureReference.isEnabled = true
                    Toast.makeText(this@MainActivity, "Ref capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun captureRtspReference() {
        val bitmap = binding.rtspTextureView.bitmap
        if (bitmap != null) {
            binding.progressBar.visibility = View.VISIBLE
            binding.btnCaptureReference.isEnabled = false
            
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            val fileName = "REF_TARGET_$timeStamp.jpg"
            val tempFile = File(cacheDir, fileName)
            
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val warped = imageProcessor.getWarpedTarget(bitmap)
                    if (warped != null) {
                        FileOutputStream(tempFile).use { out ->
                            warped.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        }
                        saveRefFile(tempFile, fileName)
                    } else {
                        withContext(Dispatchers.Main) {
                            if (tempFile.exists()) tempFile.delete()
                            binding.progressBar.visibility = View.GONE
                            binding.btnCaptureReference.isEnabled = true
                            Toast.makeText(this@MainActivity, "No shooting target detected in view!", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ShootingScore", "Failed to save RTSP ref", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to save reference", Toast.LENGTH_SHORT).show()
                        binding.progressBar.visibility = View.GONE
                        binding.btnCaptureReference.isEnabled = true
                    }
                }
            }
        }
    }

    private suspend fun saveRefFile(tempFile: File, fileName: String) {
        val prefs = getSharedPreferences("shooting_prefs", MODE_PRIVATE)
        val customUriStr = prefs.getString("custom_save_uri", null)
        
        val finalUri = if (customUriStr != null) {
            saveFileToCustomLocation(tempFile, customUriStr, fileName) ?: Uri.fromFile(tempFile)
        } else {
            val permFile = File(getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), fileName)
            tempFile.renameTo(permFile)
            Uri.fromFile(permFile)
        }

        if (tempFile.exists()) tempFile.delete()

        withContext(Dispatchers.Main) {
            lastReferencePath = finalUri.toString()
            prefs.edit { putString("last_ref_path", lastReferencePath) }
                
            binding.progressBar.visibility = View.GONE
            binding.btnCaptureReference.isEnabled = true
            Toast.makeText(this@MainActivity, "Reference target saved", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveFileToCustomLocation(sourceFile: File, treeUriStr: String, fileName: String): Uri? {
        return try {
            val treeUri = Uri.parse(treeUriStr)
            val docFile = DocumentFile.fromTreeUri(this, treeUri)
            val newFile = docFile?.createFile("image/jpeg", fileName) ?: return null
            
            contentResolver.openOutputStream(newFile.uri)?.use { outputStream ->
                sourceFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            newFile.uri
        } catch (e: Exception) {
            Log.e("ShootingScore", "Failed to save to custom location", e)
            null
        }
    }

    private fun toggleAutoScoring() {
        if (!isCameraMode) {
            Toast.makeText(this, "Enable camera first", Toast.LENGTH_SHORT).show()
            return
        }

        isAutoScoring = !isAutoScoring
        if (isAutoScoring) {
            binding.btnAutoScore.text = "STOP"
            binding.btnAutoScore.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.RED)
            startAutoScoreLoop()
        } else {
            binding.btnAutoScore.text = "START"
            binding.btnAutoScore.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#5dade2"))
            stopAutoScoreLoop()
        }
    }

    private fun startAutoScoreLoop() {
        autoScoreJob = lifecycleScope.launch(Dispatchers.Default) {
            // First, establish a fresh reference if we don't have one
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.VISIBLE
            }
            
            delay(1000) // Wait for camera to stabilize
            
            while (isActive && isAutoScoring) {
                try {
                    val currentFrame = withContext(Dispatchers.Main) {
                        if (isRtspMode) binding.rtspTextureView.bitmap else binding.viewFinder.bitmap
                    }

                    if (currentFrame != null) {
                        // 1. Warp the current frame (this handles sway/tilt)
                        val processed = imageProcessor.processWithAutoAlign(currentFrame, currentReferenceBitmap)
                        
                        if (processed.targetFound) {
                            if (currentReferenceBitmap == null) {
                                // Just established initial target reference
                                currentReferenceBitmap = processed.warpedBitmap
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "Target Locked - Auto scoring active", Toast.LENGTH_SHORT).show()
                                    binding.progressBar.visibility = View.GONE
                                }
                            } else if (processed.hits.isNotEmpty()) {
                                // Detected new hits!
                                withContext(Dispatchers.Main) {
                                    handleProcessingResult(processed, isAuto = true)
                                    // Update our memory reference to include these hits
                                    currentReferenceBitmap = processed.warpedBitmap
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ShootingScore", "AutoScore Error", e)
                }
                
                delay(1500) // Check every 1.5 seconds to balance responsiveness and battery
            }
        }
    }

    private fun stopAutoScoreLoop() {
        autoScoreJob?.cancel()
        autoScoreJob = null
        currentReferenceBitmap = null
        binding.progressBar.visibility = View.GONE
    }

    private fun processImage(bitmap: Bitmap) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                imageProcessor.process(bitmap)
            }
            handleProcessingResult(result)
        }
    }

    private fun processImageWithRef(bitmap: Bitmap, refBitmap: Bitmap) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                imageProcessor.processWithReference(bitmap, refBitmap)
            }
            handleProcessingResult(result)
        }
    }

    private fun handleProcessingResult(result: ProcessingResult, isAuto: Boolean = false) {
        if (!result.targetFound) {
            if (!isAuto) {
                binding.progressBar.visibility = View.GONE
                binding.btnCapture.isEnabled = true
                Toast.makeText(this, "No shooting target detected in view!", Toast.LENGTH_LONG).show()
            }
            return
        }

        // 1. Filter out hits that are too close to existing ones (duplicates)
        // increased distance to 20 pixels
        val minDistance = 20f
        val newUniqueHits = result.hits.filter { newHit ->
            allHits.none { existingHit ->
                val dx = newHit.x - existingHit.x
                val dy = newHit.y - existingHit.y
                kotlin.math.sqrt(dx * dx + dy * dy) < minDistance
            }
        }

        if (newUniqueHits.isEmpty()) {
            if (!isAuto) {
                binding.progressBar.visibility = View.GONE
                binding.btnCapture.isEnabled = true
                Toast.makeText(this, "No new hits detected.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // 2. Add new unique hits to the session list
        newUniqueHits.forEach { hit ->
            shotCount++
            allHits.add(hit)
        }
        totalScore += newUniqueHits.sumOf { it.score }

        // 3. Annotate the image
        // We draw OLD hits in a dimmer color and NEW hits in bright red
        val annotatedBitmap = annotateBitmapWithHits(result.warpedBitmap, allHits, newUniqueHits)

        // 4. Show and Save
        binding.targetImageView.setImageBitmap(annotatedBitmap)
        
        if (!isAuto) {
            binding.viewFinder.visibility = View.GONE
            binding.rtspTextureView.visibility = View.GONE
            binding.targetImageView.visibility = View.VISIBLE
            binding.targetGuide.visibility = View.GONE
            isCameraMode = false
            if (isRtspMode) stopRtspPlayer() else stopCamera()
            binding.btnCapture.isEnabled = true
        }

        val prefs = getSharedPreferences("shooting_prefs", MODE_PRIVATE)
        val useComparison = prefs.getBoolean("use_comparison_mode", false)
        val prefix = if (useComparison) (if (isAuto) "AUTO_SCORED" else "SCORED_SESSION") 
                     else (if (isAuto) "AI_AUTO" else "AI_SCORED")

        lifecycleScope.launch(Dispatchers.IO) {
            saveBitmapToStorage(annotatedBitmap, prefix)
        }

        // 5. Update UI Table
        updateShotTable(newUniqueHits)
        updateScoreDisplay()

        binding.progressBar.visibility = View.GONE
        Toast.makeText(this, "Detected ${newUniqueHits.size} new hit(s)!", Toast.LENGTH_SHORT).show()
    }

    private fun annotateBitmapWithHits(bitmap: Bitmap, allHits: List<Hit>, newHits: List<Hit>): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        
        // Paint for NEW hits (Bright Red)
        val newPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 5f
            isAntiAlias = true
        }
        val newFill = Paint().apply {
            color = Color.argb(150, 255, 0, 0)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        // Paint for OLD hits (Dimmer Greyish-Red)
        val oldPaint = Paint().apply {
            color = Color.argb(180, 150, 50, 50)
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
        val oldFill = Paint().apply {
            color = Color.argb(80, 100, 0, 0)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        val textPaint = Paint().apply {
            color = Color.YELLOW
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        allHits.forEachIndexed { index, hit ->
            val isNew = newHits.contains(hit)
            val radius = if (isNew) 18f else 14f
            
            val p = if (isNew) newPaint else oldPaint
            val f = if (isNew) newFill else oldFill
            
            canvas.drawCircle(hit.x, hit.y, radius, f)
            canvas.drawCircle(hit.x, hit.y, radius, p)
            
            // Draw shot number (1, 2, 3...)
            val label = (index + 1).toString()
            canvas.drawText(label, hit.x + radius + 3, hit.y - radius, textPaint)
        }
        return mutableBitmap
    }

    private fun updateShotTable(newHits: List<Hit>) {
        val container = binding.shotDataContainer
        binding.emptyHintText.visibility = View.GONE

        val startIndex = allHits.size - newHits.size

        newHits.forEachIndexed { i, hit ->
            val shotNum = startIndex + i + 1
            val row = createShotRow(shotNum, hit)
            container.addView(row)
        }
    }

    private fun createShotRow(shotNum: Int, hit: Hit): LinearLayout {
        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 10, 8, 10)
            if (shotNum % 2 == 0) {
                setBackgroundColor("#f0f4f8".toColorInt())
            }
        }

        // Calculate sector (clock direction)
        val sector = calculateSector(hit.x, hit.y)

        // No. column
        row.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
            text = shotNum.toString()
            textSize = 14f
            setTextColor("#333333".toColorInt())
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })

        // Rings column
        row.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = hit.score.toString()
            textSize = 14f
            setTextColor("#3498db".toColorInt())
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })

        // Sector column
        row.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = sector
            textSize = 14f
            setTextColor("#666666".toColorInt())
            gravity = Gravity.CENTER
        })

        // Score column
        row.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = hit.score.toString()
            textSize = 14f
            setTextColor("#27ae60".toColorInt())
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })

        return row
    }

    private fun calculateSector(x: Float, y: Float): String {
        // Center of the 640x640 warped target
        val centerX = 320f
        val centerY = 320f

        val dx = x - centerX
        val dy = y - centerY

        if (kotlin.math.sqrt(dx * dx + dy * dy) < 15f) return "Center"

        // Calculate angle in degrees (0 = top, clockwise)
        var angle = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble()))
        if (angle < 0) angle += 360

        // Convert to clock hours (1-12)
        val hour = ((angle / 30) + 0.5).toInt() // Rounded to nearest hour
        val clockHour = when {
            hour <= 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }

        return "${clockHour}h"
    }

    private fun simulateShot() {
        val centerX = binding.targetImageView.width / 2f
        val centerY = binding.targetImageView.height / 2f

        if (centerX == 0f || centerY == 0f) {
            // View not ready yet, use default
            simulateShotDelayed()
            return
        }

        val radius = minOf(centerX, centerY) * 0.85f
        val accuracy = 0.4f + Math.random().toFloat() * 0.5f
        val hit = imageProcessor.simulateShot(PointF(centerX, centerY), radius, accuracy)

        totalScore += hit.score
        shotCount++
        allHits.add(hit)

        updateShotTable(listOf(hit))
        updateScoreDisplay()

        // Redraw target with ONLY the current hit and save
        val bitmap = redrawTargetWithHits(listOf(hit))
        lifecycleScope.launch(Dispatchers.IO) {
            saveBitmapToStorage(bitmap, "SIMULATION")
        }

        Toast.makeText(this, "Simulated: ${hit.score} pts (Ring ${hit.score})", Toast.LENGTH_SHORT).show()
    }

    private fun simulateShotDelayed() {
        binding.targetImageView.post {
            simulateShot()
        }
    }

    private fun redrawTargetWithHits(hitsToDraw: List<Hit>): Bitmap {
        // Create a base target and draw specific hits on it
        val size = 600
        val bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // ... (existing drawing code)

        canvas.drawColor("#e8eef4".toColorInt())

        val centerX = size / 2f
        val centerY = size / 2f
        val maxRadius = size * 0.42f

        // Draw rings
        val ringPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
            color = Color.parseColor("#2c3e50")
        }

        val fillPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val ringRatios = listOf(1.0f, 0.82f, 0.65f, 0.50f, 0.35f, 0.22f, 0.10f)
        val ringNumbers = listOf(6, 7, 8, 9, 10)

        for (i in ringRatios.indices) {
            fillPaint.color = if (i % 2 == 0) "#34495e".toColorInt() else "#5d6d7e".toColorInt()
            canvas.drawCircle(centerX, centerY, maxRadius * ringRatios[i], fillPaint)
        }

        for (ratio in ringRatios) {
            canvas.drawCircle(centerX, centerY, maxRadius * ratio, ringPaint)
        }

        // Draw ring numbers
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = maxRadius * 0.08f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        ringNumbers.forEachIndexed { index, number ->
            val ratio = if (index < ringRatios.size - 1) {
                (ringRatios[index] + ringRatios[index + 1]) / 2
            } else {
                ringRatios[index] * 0.5f
            }
            val y = centerY - maxRadius * ratio + textPaint.textSize / 3
            canvas.drawText(number.toString(), centerX, y, textPaint)
            canvas.drawText(number.toString(), centerX, centerY + maxRadius * ratio + textPaint.textSize / 3, textPaint)
            canvas.drawText(number.toString(), centerX - maxRadius * ratio, centerY + textPaint.textSize / 3, textPaint)
            canvas.drawText(number.toString(), centerX + maxRadius * ratio, centerY + textPaint.textSize / 3, textPaint)
        }

        // Draw all hits
        val hitCirclePaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }

        val hitFillPaint = Paint().apply {
            color = Color.argb(120, 255, 0, 0)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val scoreTextPaint = Paint().apply {
            color = Color.YELLOW
            textSize = maxRadius * 0.07f
            isAntiAlias = true
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }

        // Scale hit coordinates from view size to bitmap size
        val viewCenterX = binding.targetImageView.width / 2f
        val viewCenterY = binding.targetImageView.height / 2f
        val scaleX = if (viewCenterX > 0) (centerX / viewCenterX) else 1f
        val scaleY = if (viewCenterY > 0) (centerY / viewCenterY) else 1f
        val scale = minOf(scaleX, scaleY) * 1.7f

        hitsToDraw.forEach { hit ->
            // Map from view coordinates to bitmap coordinates
            val bx = centerX + (hit.x - viewCenterX) * scale
            val by = centerY + (hit.y - viewCenterY) * scale

            val markerSize = maxRadius * 0.04f
            canvas.drawCircle(bx, by, markerSize, hitFillPaint)
            canvas.drawCircle(bx, by, markerSize, hitCirclePaint)
            canvas.drawText(hit.score.toString(), bx + markerSize + 2, by - markerSize, scoreTextPaint)
        }

        binding.targetImageView.setImageBitmap(bitmap)
        return bitmap
    }

    private suspend fun saveBitmapToStorage(bitmap: Bitmap, prefix: String) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val fileName = "${prefix}_$timeStamp.jpg"
        
        val prefs = getSharedPreferences("shooting_prefs", MODE_PRIVATE)
        val customUriStr = prefs.getString("custom_save_uri", null)
        
        try {
            var savedName = fileName
            val outputStream = if (customUriStr != null) {
                val treeUri = Uri.parse(customUriStr)
                val docFile = DocumentFile.fromTreeUri(this, treeUri)
                val file = docFile?.createFile("image/jpeg", fileName)
                if (file != null) {
                    contentResolver.openOutputStream(file.uri)
                } else {
                    null
                }
            } else {
                val directory = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                if (directory != null) {
                    val file = File(directory, fileName)
                    FileOutputStream(file)
                } else {
                    null
                }
            }

            if (outputStream != null) {
                outputStream.use { out ->
                    var bitmapToSave = bitmap
                    // Downscale only if extremely large (not applicable to 640x640 warped)
                    if (bitmap.width * bitmap.height > 4000 * 3000) { 
                        val scale = 0.5f
                        val matrix = Matrix().apply { postScale(scale, scale) }
                        bitmapToSave = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    }
                    bitmapToSave.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                }
                Log.d("ShootingScore", "Saved image to: $savedName")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Image saved: $savedName", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.e("ShootingScore", "Failed to create output stream for $fileName")
            }
        } catch (e: Exception) {
            Log.e("ShootingScore", "Failed to save image", e)
        }
    }

    private fun nextRound() {
        currentRound++
        shotCount = 0
        allHits.clear()
        updateRoundDisplay()
        updateScoreDisplay()

        // Clear shot table
        binding.shotDataContainer.removeAllViews()
        binding.emptyHintText.visibility = View.VISIBLE
        binding.shotDataContainer.addView(binding.emptyHintText)

        showDefaultTarget()
        Toast.makeText(this, "Round $currentRound started!", Toast.LENGTH_SHORT).show()
    }

    private fun updateRoundDisplay() {
        binding.deviceRoundText.text = "Device 1  Round $currentRound"
    }

    private fun updateScoreDisplay() {
        binding.totalScoreText.text = totalScore.toString()
        binding.totalShotsText.text = shotCount.toString()
    }

    private fun resetScores() {
        totalScore = 0
        shotCount = 0
        currentRound = 0
        allHits.clear()

        // Clear shot table and UI
        binding.shotDataContainer.removeAllViews()
        binding.emptyHintText.visibility = View.VISIBLE
        binding.shotDataContainer.addView(binding.emptyHintText)
        updateScoreDisplay()
        updateRoundDisplay()
        showDefaultTarget()

        if (isCameraMode) {
            toggleCameraMode()
        }

        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Clear internal app directory
                val directory = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                directory?.listFiles()?.forEach { it.delete() }

                // 2. Clear custom save directory (if configured)
                val prefs = getSharedPreferences("shooting_prefs", MODE_PRIVATE)
                val customUriStr = prefs.getString("custom_save_uri", null)
                if (customUriStr != null) {
                    val treeUri = Uri.parse(customUriStr)
                    val docFile = DocumentFile.fromTreeUri(this@MainActivity, treeUri)
                    docFile?.listFiles()?.forEach { file ->
                        if (file.isFile && file.name?.endsWith(".jpg", ignoreCase = true) == true) {
                            file.delete()
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "All scores and saved images cleared!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("ShootingScore", "Error during reset", e)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Partial clear: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun browseImages() {
        val prefs = getSharedPreferences("shooting_prefs", MODE_PRIVATE)
        val customUriStr = prefs.getString("custom_save_uri", null)
        
        val fileList = mutableListOf<Triple<String, Uri, Long>>() // Name, Uri, Date

        // 1. Get files from internal app folder
        val directory = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
        directory?.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg", ignoreCase = true) }?.forEach {
            // For internal files, we MUST use FileProvider to share with other apps
            val authority = "com.shootingscore.fileprovider"
            val contentUri = FileProvider.getUriForFile(this, authority, it)
            fileList.add(Triple(it.name, contentUri, it.lastModified()))
        }

        // 2. Get files from custom folder (if set)
        if (customUriStr != null) {
            try {
                val treeUri = Uri.parse(customUriStr)
                val docFile = DocumentFile.fromTreeUri(this, treeUri)
                docFile?.listFiles()?.filter { it.isFile && it.name?.endsWith(".jpg", ignoreCase = true) == true }?.forEach {
                    fileList.add(Triple(it.name ?: "Unknown", it.uri, it.lastModified()))
                }
            } catch (e: Exception) {
                Log.e("ShootingScore", "Failed to list custom folder", e)
            }
        }
        
        if (fileList.isEmpty()) {
            Toast.makeText(this, "No saved images found.", Toast.LENGTH_SHORT).show()
            return
        }

        // Sort by date descending
        val sortedList = fileList.sortedByDescending { it.third }
        val displayNames = sortedList.map { 
            if (it.first.startsWith("SCORED")) "⭐ " + it.first else it.first 
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Saved Images (${sortedList.size})")
            .setItems(displayNames) { _, which ->
                openImage(sortedList[which].second)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openImage(uri: Uri) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/jpeg")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRtspPlayer()
        cameraExecutor.shutdown()
    }
}
