package com.example.camerax.ui.analyzer

import android.content.Context
import android.graphics.*
import android.graphics.drawable.ShapeDrawable
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.utils.ContextUtil.getApplicationContext
import androidx.camera.core.impl.utils.ContextUtil.getBaseContext
import androidx.core.content.ContentProviderCompat.requireContext
import com.example.camerax.databinding.ActivityMainBinding
import com.example.camerax.ui.MainActivity
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.lang.Double.min
import java.nio.ByteBuffer

class MLKitBarCodeAnalyzer(binding: ActivityMainBinding): ImageAnalysis.Analyzer {
    private var isScanning: Boolean = false
    private var binding = binding // This is passed for show toast

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val planeProxy = image.planes[0]
        val buffer: ByteBuffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        val orientation = imageProxy.imageInfo.rotationDegrees
        if (mediaImage != null && !isScanning) {

            val tori = imageProxy.toBitmap()!!
            val matrix = Matrix()
            val rr = imageProxy.cropRect
            val tempImage = Bitmap.createBitmap(tori, rr.left, rr.top, rr.width(), rr.height())
            val originalImage: Bitmap = when(orientation) {
                90 -> {
                    matrix.postRotate(90.0F)
                    Bitmap.createBitmap(tempImage,0,0, tempImage.width, tempImage.height, matrix,true)
                }
                180 -> {
                    matrix.postRotate(180.0F)
                    Bitmap.createBitmap(tempImage,0,0, tempImage.height, tempImage.width, matrix,true) /// 180 problem here
                }
                270 -> {
                    matrix.postRotate(270.0F)
                    Bitmap.createBitmap(tempImage,0,0, tempImage.width, tempImage.height, matrix,true)
                }
                else -> {
                    tempImage
                }
            }
            val width = originalImage.width.toFloat()
            val height = originalImage.height.toFloat()
            val boxWidth = width * 75 / 100
            val boxHeight = boxWidth - (boxWidth / 4.0).toFloat()
            val cx = width / 2
            val cy = height / 2
            val rect = RectF(cx - boxWidth / 2, cy - boxHeight / 2, cx + boxWidth / 2, cy + boxHeight / 2)
            val boxImage = Bitmap.createBitmap(originalImage, rect.left.toInt(), rect.top.toInt(), rect.width().toInt(), rect.height().toInt())
            val image = InputImage.fromBitmap(boxImage, 0)
            val scanner = BarcodeScanning.getClient()
            isScanning = true
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
                    isScanning = false
                    imageProxy.close()
                }
                .addOnFailureListener {
                    isScanning = false
                    imageProxy.close()
                }
        }
    }
}
