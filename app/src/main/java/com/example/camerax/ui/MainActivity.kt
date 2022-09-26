package com.example.camerax.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.LayoutInflater
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.internal.compat.workaround.TargetAspectRatio.RATIO_4_3
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.camerax.R
import com.example.camerax.databinding.ActivityMainBinding
import com.example.camerax.ui.analyzer.MLKitBarCodeAnalyzer
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.schedule


@ExperimentalGetImage
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var fileName: String
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)
        supportActionBar?.hide()
        binding.overlay.post {
            binding.overlay.setViewFinder()
        }
        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()
        if(allPermissionGranted()) {
            startCamera()
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post(object : Runnable {
                override fun run() {
                    fileName = SimpleDateFormat(Constants.FILE_NAME_FORMAT, Locale.getDefault()).format(System.currentTimeMillis())
                    takePhoto()
                    mainHandler.postDelayed(this, 50)
                }
            })
        } else {
            ActivityCompat.requestPermissions(this, Constants.REQUIRED_PERMISSION, Constants.REQUEST_CODE_PERMISSION)
        }
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let{ mFile ->
            File(mFile, resources.getString(R.string.app_name)).apply {
                mkdirs()
            }
        }
        return if(mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        imageCapture.takePicture(cameraExecutor, object :
            ImageCapture.OnImageCapturedCallback() {
            @SuppressLint("UnsafeOptInUsageError")
            override fun onCaptureSuccess(imageProxy: ImageProxy) {
                super.onCaptureSuccess(imageProxy)
                val orientation = imageProxy.imageInfo.rotationDegrees
                val mediaImage = imageProxy.image
                if(mediaImage != null) {
                    val tori = imageProxyToBitmap(imageProxy)
                    val matrix = Matrix()
                    val rr = imageProxy.cropRect
                    val tempImage = Bitmap.createBitmap(tori, rr.left, rr.top, rr.width(), rr.height())
                    val originalImage: Bitmap = when(orientation) {
                        90 -> {
                            matrix.postRotate(90.0F)
                            Bitmap.createBitmap(tempImage,0,0, tempImage.width, tempImage.height,matrix,true)
                        }
                        180 -> {
                            matrix.postRotate(180.0F)
                            Bitmap.createBitmap(tempImage,0,0, tempImage.width, tempImage.height,matrix,true)
                        }
                        270 -> {
                            matrix.postRotate(270.0F)
                            Bitmap.createBitmap(tempImage,0,0, tempImage.width, tempImage.height,matrix,true)
                        }
                        else -> {
                            Bitmap.createBitmap(tempImage,0,0, tempImage.width, tempImage.height)
                        }
                    }
                    val width = originalImage.width
                    val height = originalImage.height
                    val boxWidth = (width * 80 / 100)
                    val boxHeight = (boxWidth - (boxWidth / 4.0))
                    val cx = (width / 2)
                    val cy = (height / 2)
                    val rect = Rect((cx - boxWidth / 2).toInt(), (cy - boxHeight / 2).toInt(), (cx + boxWidth / 2).toInt(), (cy + boxHeight / 2).toInt())
                    val boxImage = Bitmap.createBitmap(originalImage, rect.left.toInt(), rect.top.toInt(), rect.width().toInt(), rect.height().toInt())
                    val filename = "$fileName.jpeg"
                    val image = InputImage.fromBitmap(boxImage, 0)
                    val scanner = BarcodeScanning.getClient()
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            barcodes.firstOrNull().let { barcode ->
                                val rawValue = barcode?.rawValue
                                val boundingBox = barcode?.boundingBox
                                if(rawValue == null) {
                                    "Place a barcode".also { binding.btnTakePhoto.text = it }
                                    binding.btnTakePhoto.setTextColor(Color.WHITE)
                                }
                                rawValue?.let {
                                    it.also { binding.btnTakePhoto.text = it }
                                    binding.btnTakePhoto.setTextColor(Color.WHITE)
                                }
                            }
                            imageProxy.close()
                        }
                        .addOnFailureListener {
                            imageProxy.close()
                        }
                }
            }
            override fun onError(exception: ImageCaptureException) {
                super.onError(exception)
            }
        })
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val planeProxy = image.planes[0]
        val buffer: ByteBuffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    @SuppressLint("RestrictedApi")
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this@MainActivity)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            cameraProvider.unbindAll()

            val preview: Preview = Preview.Builder()
                .build()

            imageCapture = ImageCapture.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(binding.cameraPreview.width, binding.cameraPreview.height))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val viewPort = ViewPort.Builder(Rational(binding.overlay.findOverlayWidth().toInt(),binding.overlay.findOverlayHeight().toInt()), Surface.ROTATION_0).build()
            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(imageAnalysis)
                .addUseCase(imageCapture!!)
                .setViewPort(viewPort)
                .build()

//            val orientationEventListener = object : OrientationEventListener(this as Context) {
//                override fun onOrientationChanged(orientation : Int) {
//                    // Monitors orientation values to determine the target rotation value
//                    val rotation : Int = when (orientation) {
//                        in 45..134 -> Surface.ROTATION_270
//                        in 135..224 -> Surface.ROTATION_180
//                        in 225..314 -> Surface.ROTATION_90
//                        else -> Surface.ROTATION_0
//                    }
//
//                    imageAnalysis.targetRotation = rotation
//                }
//            }
//            orientationEventListener.enable()

//            imageAnalysis.setAnalyzer(cameraExecutor, MLKitBarCodeAnalyzer(binding))
            preview.setSurfaceProvider(binding.cameraPreview.surfaceProvider)

            try {
                cameraProvider.bindToLifecycle(this, cameraSelector, useCaseGroup)
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, imageAnalysis)
            } catch(e: Exception) {
                Log.d(Constants.TAG, "Start Camera Failed")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if(requestCode == Constants.REQUEST_CODE_PERMISSION) {
            if(allPermissionGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,"Permission Denied", Toast.LENGTH_LONG).show()
                finish() // If permission denied then close app
            }
        }
    }

    private fun allPermissionGranted() =
        Constants.REQUIRED_PERMISSION.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    override fun onDestroy() {
        super.onDestroy()
        // Shut down our background executor
        cameraExecutor.shutdown()
    }
}