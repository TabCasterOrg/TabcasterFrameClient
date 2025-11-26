package org.tabcaster.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import java.nio.ByteBuffer

class RenderingManager(private val activity: AppCompatActivity) {

    interface RenderingCallbacks{
        fun initializeHardwareAcceleration(): String
        fun decodeImage(pngData: ByteArray): Bitmap?
        fun decodeImageSoftware(pngData: ByteArray): Bitmap?
    }
    private var isHardwareAccelerationSupported = false

    private val hardwareBitmapOptions = BitmapFactory.Options().apply {
        inMutable = true
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inSampleSize = 1
        inDither = false
        inPreferQualityOverSpeed = false
        inTempStorage = ByteArray(64 * 1024)
        inJustDecodeBounds = false
        inScaled = false
    }

    private val softwareBitmapOptions = BitmapFactory.Options().apply {
        inMutable = true
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inSampleSize = 1
        inDither = false
        inPreferQualityOverSpeed = false
        inTempStorage = ByteArray(32 * 1024)
    }

    fun initializeHardwareAcceleration() : String {
        isHardwareAccelerationSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        var status = if (isHardwareAccelerationSupported) "Hardware acceleration enabled (ImageDecoder)" else "Using software decoding (BitmapFactory)" // Decide the status message
        return status
        // Use uiManager.updateStatus(initializeHardwareAcceleration) in areas where it is needed.
    }

    fun decodeImageHardware(pngData: ByteArray): Bitmap? {
        android.util.Log.d("MainActivity", "decodeImageHardware: attempting ImageDecoder")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(pngData))
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, source ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                    decoder.setUnpremultipliedRequired(false)
                }
                android.util.Log.d("MainActivity", "decodeImageHardware SUCCESS: ${bitmap.width}x${bitmap.height}")
                bitmap
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "decodeImageHardware FAILED, falling back to software", e)
                decodeImageSoftware(pngData)
            }
        } else {
            android.util.Log.d("MainActivity", "decodeImageHardware: API < P, using software")
            decodeImageSoftware(pngData)
        }
    }

    fun decodeImageSoftware(pngData: ByteArray): Bitmap? {
        return try {
            BitmapFactory.decodeByteArray(pngData, 0, pngData.size, softwareBitmapOptions)
        } catch (e: Exception) {
            null
        }
    }

    fun decodeImage(pngData: ByteArray): Bitmap? {
        return if (isHardwareAccelerationSupported) {
            decodeImageHardware(pngData)
        } else {
            decodeImageSoftware(pngData)
        }
    }

    fun useHardwareRendering(): Boolean {
        return isHardwareAccelerationSupported
    }


}