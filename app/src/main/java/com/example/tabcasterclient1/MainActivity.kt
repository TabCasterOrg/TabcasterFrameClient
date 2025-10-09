package com.example.tabcasterclient1

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : AppCompatActivity(), UIManager.UICallbacks {

    private lateinit var uiManager: UIManager

    private var udpReceiver: UDPReceiver? = null
    private var executorService: ExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val bitmapsToRecycle = mutableListOf<Bitmap>()
    private val recycleHandler = Handler(Looper.getMainLooper())

    private var decodingExecutor: ExecutorService? = null
    private val maxPendingFrames = 2

    @Volatile
    private var pendingDecodes = 0
    @Volatile
    private var droppedFrames = 0

    private var totalDecodeTime = 0L
    private var decodedFrameCount = 0
    private var avgDecodeTime = 0f

    private var isHardwareAccelerationSupported = false
    private var hardwareDecodeCount = 0
    private var softwareDecodeCount = 0
    private var totalHardwareDecodeTime = 0L
    private var totalSoftwareDecodeTime = 0L

    private var isStreaming: Boolean = false

    // CRITICAL: Keep mutable software bitmap for delta regions
    @Volatile
    private var previousBitmap: Bitmap? = null

    private var lastReceivedFrameId = -1
    private var expectedFrameId = 0
    private var hasValidBaseFrame = false
    private var lastFullFrameId = -1

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

    private var lastFrameTime = 0L
    private var frameCount = 0
    private var fpsStartTime = 0L
    private var currentFPS = 0f

    companion object {
        private const val DEFAULT_IP = "10.1.10.105"
        private const val DEFAULT_PORT = 23532
    }

    // Data class for delta operations
    data class DeltaOperation(
        val magic: String,
        val rx: Int,
        val ry: Int,
        val rw: Int,
        val rh: Int,
        val pngBytes: ByteArray
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        uiManager = UIManager(this)
        uiManager.setCallbacks(this)
        uiManager.initializeViews()
        uiManager.setDefaultValues(DEFAULT_IP, DEFAULT_PORT)
        uiManager.setupClickListeners()
        uiManager.getScreenResolution()

        executorService = Executors.newSingleThreadExecutor()
        decodingExecutor = Executors.newSingleThreadExecutor()

        initializeHardwareAcceleration()

        uiManager.updateStatus("Ready")
        uiManager.updateFrameInfo("No frame data")
        uiManager.updateResolutionInfo()
    }

    override fun onTryConnect() {
        connectToServer()
    }

    override fun onDisconnectClicked() {
        disconnectFromServer()
    }

    override fun onFullscreenToggled() {
        if (isStreaming) {
            uiManager.toggleFullscreen()
        }
    }

    override fun onFrameClicked() {
        if (isStreaming) {
            uiManager.toggleFullscreen()
        }
    }

    private fun initializeHardwareAcceleration() {
        isHardwareAccelerationSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        if (isHardwareAccelerationSupported) {
            uiManager.updateStatus("Hardware acceleration enabled (ImageDecoder)")
        } else {
            uiManager.updateStatus("Using software decoding (BitmapFactory)")
        }
    }

    private fun connectToServer() {
        val defaultIP = "10.1.10.105"
        val defaultPort = 23532

        val serverIP = uiManager.getServerIP().ifEmpty { defaultIP }
        val portStr = defaultPort.toString()

        val port = try {
            portStr.toInt()
        } catch (e: NumberFormatException) {
            Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show()
            return
        }
        udpReceiver = UDPReceiver(serverIP, port)
        executorService?.submit(udpReceiver)

        uiManager.setConnectionState(true)
        uiManager.updateStatus("Connecting to $serverIP:$port")
    }

    private fun disconnectFromServer() {
        udpReceiver?.stop()
        udpReceiver = null
        isStreaming = false

        mainHandler.post {
            // Clear ImageView first
            uiManager.clearFrame()

            // Then schedule bitmap recycling with delay
            val bitmapToRecycle = previousBitmap
            previousBitmap = null

            if (bitmapToRecycle != null) {
                recycleBitmapSafely(bitmapToRecycle)
            }
        }

        currentFPS = 0f
        frameCount = 0
        fpsStartTime = 0L

        pendingDecodes = 0
        droppedFrames = 0
        totalDecodeTime = 0L
        decodedFrameCount = 0
        avgDecodeTime = 0f

        hardwareDecodeCount = 0
        softwareDecodeCount = 0
        totalHardwareDecodeTime = 0L
        totalSoftwareDecodeTime = 0L

        lastReceivedFrameId = -1
        expectedFrameId = 0

        hasValidBaseFrame = false
        lastFullFrameId = -1

        uiManager.setStreamingState(false)
        uiManager.setConnectionState(false)
        uiManager.resetUI()
    }


    private fun decodeImageHardware(pngData: ByteArray): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(pngData)
                ImageDecoder.decodeBitmap(source) { decoder, info, source ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
                    decoder.setUnpremultipliedRequired(false)
                }
            } catch (e: Exception) {
                decodeImageSoftware(pngData)
            }
        } else {
            decodeImageSoftware(pngData)
        }
    }

    private fun decodeImageSoftware(pngData: ByteArray): Bitmap? {
        return try {
            BitmapFactory.decodeByteArray(pngData, 0, pngData.size, softwareBitmapOptions)
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeImage(pngData: ByteArray): Bitmap? {
        return if (isHardwareAccelerationSupported) {
            decodeImageHardware(pngData)
        } else {
            decodeImageSoftware(pngData)
        }
    }

    private fun recycleBitmapSafely(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return

        // Add to recycle queue and schedule delayed recycling
        synchronized(bitmapsToRecycle) {
            bitmapsToRecycle.add(bitmap)
        }

        // Delay recycling to ensure Android rendering pipeline is done with it
        recycleHandler.postDelayed({
            synchronized(bitmapsToRecycle) {
                val toRecycle = bitmapsToRecycle.toList()
                bitmapsToRecycle.clear()

                toRecycle.forEach { bmp ->
                    try {
                        if (!bmp.isRecycled) {
                            bmp.recycle()
                        }
                    } catch (e: Exception) {
                        // Ignore errors
                    }
                }
            }
        }, 100) // 100ms delay gives rendering pipeline time to finish
    }


    private fun updateFPSCalculation() {
        val now = System.currentTimeMillis()

        if (fpsStartTime == 0L) {
            fpsStartTime = now
            frameCount = 0
        }

        frameCount++

        val elapsed = now - fpsStartTime
        if (elapsed >= 1000) {
            currentFPS = (frameCount * 1000f) / elapsed
            fpsStartTime = now
            frameCount = 0
        }
    }

    private fun displayFrame(pngData: ByteArray, frameId: Int, frameTime: Long, compressedSize: Int, onSuccess: ((Boolean) -> Unit)? = null) {
        if (pendingDecodes >= maxPendingFrames) {
            droppedFrames++
            if (droppedFrames % 5 == 0) {
                uiManager.updateStatus("Streaming ($pendingDecodes pending, dropped $droppedFrames frames)")
            }
            onSuccess?.invoke(false)
            return
        }

        pendingDecodes++

        decodingExecutor?.submit {
            val decodeStartTime = System.nanoTime()

            try {
                val bitmap = decodeImage(pngData)
                val decodeTimeMs = (System.nanoTime() - decodeStartTime) / 1_000_000

                synchronized(this) {
                    totalDecodeTime += decodeTimeMs
                    decodedFrameCount++
                    avgDecodeTime = totalDecodeTime.toFloat() / decodedFrameCount

                    if (isHardwareAccelerationSupported && bitmap != null) {
                        hardwareDecodeCount++
                        totalHardwareDecodeTime += decodeTimeMs
                    } else {
                        softwareDecodeCount++
                        totalSoftwareDecodeTime += decodeTimeMs
                    }
                }

                if (bitmap != null) {
                    mainHandler.post {
                        try {
                            if (bitmap.isRecycled) {
                                onSuccess?.invoke(false)
                                return@post
                            }

                            // Store old bitmap reference before updating ImageView
                            val oldBitmap = previousBitmap

                            // Update reference before displaying
                            previousBitmap = bitmap

                            // Display new bitmap
                            uiManager.displayFrame(bitmap)

                            // Schedule old bitmap for delayed recycling
                            if (oldBitmap != null && oldBitmap != bitmap) {
                                recycleBitmapSafely(oldBitmap)
                            }

                            updateFPSCalculation()

                            if (uiManager.shouldUpdateFrameInfo()) {
                                uiManager.updateOptimizedFrameInfo(
                                    frameId, frameTime, bitmap.width, bitmap.height, decodeTimeMs,
                                    currentFPS, avgDecodeTime, isHardwareAccelerationSupported,
                                    hardwareDecodeCount, softwareDecodeCount,
                                    totalHardwareDecodeTime, totalSoftwareDecodeTime, droppedFrames
                                )
                            }

                            onSuccess?.invoke(true)
                        } catch (e: Exception) {
                            uiManager.updateFrameInfo("Error displaying frame: ${e.message}")
                            onSuccess?.invoke(false)
                        }
                    }
                } else {
                    uiManager.updateFrameInfo("Failed to decode PNG frame $frameId")
                    onSuccess?.invoke(false)
                }
            } catch (e: Exception) {
                uiManager.updateFrameInfo("PNG decode error: ${e.message}")
                onSuccess?.invoke(false)
            } finally {
                pendingDecodes--
            }
        }
    }

    private fun processAtomicDeltaOperations(frameData: ByteArray, totalSize: Int, info: FrameInfo, currentFrameId: Int, frameTime: Long) {
        var offset = 0
        val operations = mutableListOf<DeltaOperation>()

        // Parse all operations in the frame
        while (offset + 8 <= totalSize) {
            val magic = String(frameData, offset, 4)
            offset += 4

            if (magic != "CREG" && magic != "DREG") {
                uiManager.updateFrameInfo("Invalid operation magic: $magic")
                break
            }

            // Parse region header
            if (offset + 12 > totalSize) break

            val rx = ((frameData[offset].toInt() and 0xFF) shl 8) or (frameData[offset+1].toInt() and 0xFF)
            val ry = ((frameData[offset+2].toInt() and 0xFF) shl 8) or (frameData[offset+3].toInt() and 0xFF)
            val rw = ((frameData[offset+4].toInt() and 0xFF) shl 8) or (frameData[offset+5].toInt() and 0xFF)
            val rh = ((frameData[offset+6].toInt() and 0xFF) shl 8) or (frameData[offset+7].toInt() and 0xFF)
            offset += 8 // Skip to flags
            val flags = frameData[offset++].toInt() and 0xFF
            val quality = frameData[offset++].toInt() and 0xFF
            offset += 2 // Skip reserved bytes

            val pngSize = totalSize - offset
            if (pngSize <= 0) break

            // Find PNG end (or next operation)
            var pngEnd = offset
            var foundNextOp = false

            // Look for next operation marker
            while (pngEnd + 4 < totalSize) {
                if ((frameData[pngEnd] == 'C'.code.toByte() && frameData[pngEnd+1] == 'R'.code.toByte() &&
                            frameData[pngEnd+2] == 'E'.code.toByte() && frameData[pngEnd+3] == 'G'.code.toByte()) ||
                    (frameData[pngEnd] == 'D'.code.toByte() && frameData[pngEnd+1] == 'R'.code.toByte() &&
                            frameData[pngEnd+2] == 'E'.code.toByte() && frameData[pngEnd+3] == 'G'.code.toByte())) {
                    foundNextOp = true
                    break
                }
                pngEnd++
            }

            if (!foundNextOp) pngEnd = totalSize

            val pngBytes = frameData.copyOfRange(offset, pngEnd)
            offset = pngEnd

            operations.add(DeltaOperation(magic, rx, ry, rw, rh, pngBytes))
        }

        if (operations.isEmpty()) {
            uiManager.updateFrameInfo("No valid operations found in delta frame")
            return
        }

        // Process delta operations on background thread for better performance
        decodingExecutor?.submit {
            try {
                val base = previousBitmap ?: run {
                    uiManager.updateFrameInfo("No base bitmap for delta operations")
                    return@submit
                }

                if (base.isRecycled) {
                    uiManager.updateFrameInfo("Base bitmap is recycled, cannot process delta operations")
                    return@submit
                }

                // Validate base bitmap dimensions
                if (base.width != info.width || base.height != info.height) {
                    uiManager.updateFrameInfo("Base bitmap size mismatch: ${base.width}x${base.height} vs ${info.width}x${info.height}")
                    return@submit
                }

                // Only copy if we have operations to apply
                if (operations.isEmpty()) {
                    return@submit
                }

                //create a copy to avoid recycling race conditions
                val workingBitmap = base.copy(Bitmap.Config.ARGB_8888, true)

                var operationsApplied = 0
                val decodeStartTime = System.nanoTime()

                // Batch decode all PNGs first, then apply operations
                val decodedBitmaps = mutableListOf<Bitmap?>()
                for (op in operations) {
                    val decodedBitmap = decodeImageSoftware(op.pngBytes)
                    decodedBitmaps.add(decodedBitmap)
                }

                // Apply all operations
                for (i in operations.indices) {
                    val op = operations[i]
                    val decodedBitmap = decodedBitmaps[i]

                    if (decodedBitmap != null) {
                        when (op.magic) {
                            "CREG" -> applyClearOperationOptimized(workingBitmap, op, decodedBitmap)
                            "DREG" -> applyDrawOperationOptimized(workingBitmap, op, decodedBitmap)
                        }
                        operationsApplied++
                    }
                }

                // Clean up decoded bitmaps
                decodedBitmaps.forEach { recycleBitmapSafely(it) }

                val decodeTimeMs = (System.nanoTime() - decodeStartTime) / 1_000_000

                if (operationsApplied > 0) {
                    // Update statistics
                    synchronized(this) {
                        totalDecodeTime += decodeTimeMs
                        decodedFrameCount++
                        avgDecodeTime = totalDecodeTime.toFloat() / decodedFrameCount
                        softwareDecodeCount++
                        totalSoftwareDecodeTime += decodeTimeMs
                    }

                    // Update UI on main thread
                    mainHandler.post {
                        try {
                            // Store old bitmap reference BEFORE any operations
                            val oldBitmap = previousBitmap

                            // Update reference BEFORE displaying
                            previousBitmap = workingBitmap

                            // Display the updated frame
                            uiManager.displayFrame(workingBitmap)

                            // Schedule old bitmap for delayed recycling
                            if (oldBitmap != null && oldBitmap != workingBitmap) {
                                recycleBitmapSafely(oldBitmap)
                            }

                            updateFPSCalculation()
                            if (uiManager.shouldUpdateFrameInfo()) {
                                uiManager.updateOptimizedFrameInfo(
                                    currentFrameId, frameTime, workingBitmap.width, workingBitmap.height, decodeTimeMs,
                                    currentFPS, avgDecodeTime, isHardwareAccelerationSupported,
                                    hardwareDecodeCount, softwareDecodeCount,
                                    totalHardwareDecodeTime, totalSoftwareDecodeTime, droppedFrames
                                )
                            }

                            uiManager.updateFrameInfo("Applied $operationsApplied operations (${operations.count { it.magic == "CREG" }} clear, ${operations.count { it.magic == "DREG" }} draw)")
                            hasValidBaseFrame = true
                        } catch (e: Exception) {
                            uiManager.updateFrameInfo("UI update error: ${e.message}")
                            // If UI update failed, recycle the working bitmap since it won't be displayed
                            recycleBitmapSafely(workingBitmap)
                        }
                    }
                }
            } catch (e: Exception) {
                uiManager.updateFrameInfo("Delta processing error: ${e.message}")
                e.printStackTrace()
            }
        }
    }


    private fun applyClearOperationOptimized(bitmap: Bitmap, op: DeltaOperation, clearBitmap: Bitmap) {
        try {
            if (bitmap.isRecycled || clearBitmap.isRecycled) {
                uiManager.updateFrameInfo("Cannot apply clear operation: bitmap is recycled")
                return
            }

            // Validate dimensions
            if (clearBitmap.width == op.rw && clearBitmap.height == op.rh) {
                val clearPixels = IntArray(op.rw * op.rh)
                clearBitmap.getPixels(clearPixels, 0, op.rw, 0, 0, op.rw, op.rh)

                // Validate bounds
                if (op.rx >= 0 && op.ry >= 0 &&
                    op.rx + op.rw <= bitmap.width &&
                    op.ry + op.rh <= bitmap.height) {
                    bitmap.setPixels(clearPixels, 0, op.rw, op.rx, op.ry, op.rw, op.rh)
                } else {
                    uiManager.updateFrameInfo("Clear operation bounds error: ${op.rx},${op.ry} ${op.rw}x${op.rh}")
                }
            } else {
                uiManager.updateFrameInfo("Clear bitmap size mismatch: ${clearBitmap.width}x${clearBitmap.height} vs ${op.rw}x${op.rh}")
            }
        } catch (e: Exception) {
            uiManager.updateFrameInfo("Clear operation error: ${e.message}")
        }
    }

    private fun applyDrawOperationOptimized(bitmap: Bitmap, op: DeltaOperation, drawBitmap: Bitmap) {
        try {
            // Check if bitmaps are recycled before using them
            if (bitmap.isRecycled || drawBitmap.isRecycled) {
                uiManager.updateFrameInfo("Cannot apply draw operation: bitmap is recycled")
                return
            }

            // Validate dimensions
            if (drawBitmap.width == op.rw && drawBitmap.height == op.rh) {
                val drawPixels = IntArray(op.rw * op.rh)
                drawBitmap.getPixels(drawPixels, 0, op.rw, 0, 0, op.rw, op.rh)

                // Validate bounds
                if (op.rx >= 0 && op.ry >= 0 &&
                    op.rx + op.rw <= bitmap.width &&
                    op.ry + op.rh <= bitmap.height) {
                    bitmap.setPixels(drawPixels, 0, op.rw, op.rx, op.ry, op.rw, op.rh)
                } else {
                    uiManager.updateFrameInfo("Draw operation bounds error: ${op.rx},${op.ry} ${op.rw}x${op.rh}")
                }
            } else {
                uiManager.updateFrameInfo("Draw bitmap size mismatch: ${drawBitmap.width}x${drawBitmap.height} vs ${op.rw}x${op.rh}")
            }
        } catch (e: Exception) {
            uiManager.updateFrameInfo("Draw operation error: ${e.message}")
        }
    }

    // Keep original functions for fallback
    private fun applyClearOperation(bitmap: Bitmap, op: DeltaOperation, info: FrameInfo) {
        try {
            // Clear operation: restore region from base content
            val clearBitmap = decodeImageSoftware(op.pngBytes)
            if (clearBitmap != null) {
                applyClearOperationOptimized(bitmap, op, clearBitmap)
                recycleBitmapSafely(clearBitmap)
            } else {
                uiManager.updateFrameInfo("Failed to decode clear operation PNG")
            }
        } catch (e: Exception) {
            uiManager.updateFrameInfo("Clear operation error: ${e.message}")
        }
    }

    private fun applyDrawOperation(bitmap: Bitmap, op: DeltaOperation) {
        try {
            // Draw operation: apply new content
            val drawBitmap = decodeImageSoftware(op.pngBytes)
            if (drawBitmap != null) {
                applyDrawOperationOptimized(bitmap, op, drawBitmap)
                recycleBitmapSafely(drawBitmap)
            } else {
                uiManager.updateFrameInfo("Failed to decode draw operation PNG")
            }
        } catch (e: Exception) {
            uiManager.updateFrameInfo("Draw operation error: ${e.message}")
        }
    }

    data class PacketHeader(
        val frameId: Int,
        val packetId: Int,
        val totalPackets: Int,
        val dataSize: Int
    )

    data class FrameInfo(
        val width: Int,
        val height: Int
    )

    private inner class UDPReceiver(
        private val serverIP: String,
        private val serverPort: Int
    ) : Runnable {

        @Volatile
        private var running = true
        private var socket: DatagramSocket? = null
        private var frameInfo: FrameInfo? = null
        private val framePackets = mutableMapOf<Int, ByteArray>()
        private var expectedPackets = 0
        private var currentFrameId = -1
        private var packetsReceived = 0
        private var frameStartTime = 0L
        private var framesReceived = 0
        private var handshakeComplete = false
        private var displayReady = false

        private var lastKeyframeRequestTime = 0L
        private val keyframeRequestCooldown = 1000L

        private var lastFrameDisplayTime = 0L
        private val minFrameInterval = 16L // Reduced from 33ms to 16ms for higher FPS (60 FPS max)

        fun stop() {
            running = false
            socket?.close()

            // NEW: Clear any pending packets and reset state
            framePackets.clear()
            currentFrameId = -1
            packetsReceived = 0
            handshakeComplete = false
            displayReady = false
        }

        private fun requestKeyframe() {
            val now = System.currentTimeMillis()
            if (now - lastKeyframeRequestTime < keyframeRequestCooldown) {
                return
            }

            try {
                val serverAddress = InetAddress.getByName(serverIP)
                val requestData = "REQUEST_KEYFRAME".toByteArray()
                val requestPacket = DatagramPacket(requestData, requestData.size, serverAddress, serverPort)
                socket?.send(requestPacket)
                lastKeyframeRequestTime = now
                uiManager.updateStatus("Requested keyframe from server")
            } catch (e: Exception) {
                uiManager.updateStatus("Failed to request keyframe: ${e.localizedMessage}")
            }
        }

        override fun run() {
            try {
                // NEW: Always create fresh socket
                socket?.close()
                socket = null
                socket = DatagramSocket()
                socket?.soTimeout = 10000

                val serverAddress = InetAddress.getByName(serverIP)
                uiManager.updateStatus("Socket created. Starting handshake...")

                if (!performHandshake(serverAddress)) {
                    uiManager.updateStatus("Handshake failed")
                    return
                }

                if (!requestStreaming(serverAddress)) {
                    uiManager.updateStatus("Failed to start streaming")
                    return
                }

                isStreaming = true
                uiManager.setStreamingState(true)

                val buffer = ByteArray(2048)
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket?.receive(packet)
                        processReceivedPacket(packet.data, packet.length)
                    } catch (e: SocketTimeoutException) {
                        uiManager.updateStatus("Waiting for data...")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                uiManager.updateStatus("Error: ${e.localizedMessage}")
            } finally {
                socket?.close()
                isStreaming = false
                uiManager.setStreamingState(false)
                uiManager.updateStatus("Socket closed")
            }
        }

        private fun performHandshake(serverAddress: InetAddress): Boolean {
            try {
                uiManager.updateStatus("Sending HELLO...")
                if (!sendMessage(serverAddress, "HELLO")) return false

                if (!waitForMessage("HELLO_ACK", 5000)) {
                    uiManager.updateStatus("Did not receive HELLO_ACK")
                    return false
                }
                uiManager.updateStatus("Received HELLO_ACK")

                val resolutionMsg = "RESOLUTION:${uiManager.getScreenWidth()}:${uiManager.getScreenHeight()}:${uiManager.getRefreshRate()}"
                uiManager.updateStatus("Sending resolution: ${uiManager.getScreenWidth()}x${uiManager.getScreenHeight()}@${uiManager.getRefreshRate()}Hz")
                if (!sendMessage(serverAddress, resolutionMsg)) return false

                val resolutionResponse = waitForResolutionResponse(15000)
                if (resolutionResponse == null) {
                    uiManager.updateStatus("No resolution response from server")
                    return false
                }

                if (resolutionResponse == "RESOLUTION_ACK") {
                    uiManager.updateStatus("Resolution accepted by server")
                } else if (resolutionResponse.startsWith("RESOLUTION_CHANGED:")) {
                    handleResolutionChanged(resolutionResponse)
                } else {
                    uiManager.updateStatus("Unexpected resolution response: $resolutionResponse")
                    return false
                }

                val displayReadyMsg = waitForDisplayReady(15000)
                if (displayReadyMsg == null) {
                    uiManager.updateStatus("Display setup timeout")
                    return false
                }

                uiManager.updateStatus("Display ready: $displayReadyMsg")
                handshakeComplete = true
                displayReady = true

                return true

            } catch (e: Exception) {
                uiManager.updateStatus("Handshake error: ${e.localizedMessage}")
                return false
            }
        }

        private fun handleResolutionChanged(message: String) {
            try {
                val parts = message.split(":")
                if (parts.size >= 3 && parts[0] == "RESOLUTION_CHANGED") {
                    val resolutionPart = parts[1]
                    val refreshRatePart = parts[2]

                    val resolutionParts = resolutionPart.split("x")
                    if (resolutionParts.size == 2) {
                        val width = resolutionParts[0].toInt()
                        val height = resolutionParts[1].toInt()
                        val refreshRate = refreshRatePart.toFloat()

                        uiManager.updateServerResolution(width, height, refreshRate)
                        uiManager.updateStatus("Server using fallback resolution: ${width}x${height}@${refreshRate}Hz")

                        mainHandler.post {
                            Toast.makeText(
                                this@MainActivity,
                                "Server using fallback resolution: ${width}x${height}@${refreshRate}Hz",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                uiManager.updateStatus("Error parsing resolution change: ${e.localizedMessage}")
            }
        }

        private fun waitForResolutionResponse(timeoutMs: Long): String? {
            val buffer = ByteArray(1024)
            val startTime = System.currentTimeMillis()

            try {
                socket?.soTimeout = timeoutMs.toInt()

                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)

                    val receivedMessage = String(packet.data, 0, packet.length)

                    if (receivedMessage == "RESOLUTION_ACK" ||
                        receivedMessage.startsWith("RESOLUTION_CHANGED:")) {
                        return receivedMessage
                    } else if (receivedMessage.startsWith("RESOLUTION_ERROR:")) {
                        uiManager.updateStatus("Server error: $receivedMessage")
                        return null
                    }
                }
                return null
            } catch (e: SocketTimeoutException) {
                return null
            } catch (e: Exception) {
                uiManager.updateStatus("Wait error: ${e.localizedMessage}")
                return null
            }
        }

        private fun requestStreaming(serverAddress: InetAddress): Boolean {
            try {
                uiManager.updateStatus("Requesting stream start...")
                if (!sendMessage(serverAddress, "START_STREAM")) return false

                if (!waitForMessage("STREAM_STARTED", 5000)) {
                    uiManager.updateStatus("Did not receive STREAM_STARTED")
                    return false
                }
                uiManager.updateStatus("Streaming started")
                return true

            } catch (e: Exception) {
                uiManager.updateStatus("Stream request error: ${e.localizedMessage}")
                return false
            }
        }

        private fun sendMessage(serverAddress: InetAddress, message: String): Boolean {
            return try {
                val sendData = message.toByteArray()
                val sendPacket = DatagramPacket(sendData, sendData.size, serverAddress, serverPort)
                socket?.send(sendPacket)
                true
            } catch (e: Exception) {
                uiManager.updateStatus("Send error: ${e.localizedMessage}")
                false
            }
        }

        private fun waitForMessage(expectedMessage: String, timeoutMs: Long): Boolean {
            val buffer = ByteArray(1024)
            val startTime = System.currentTimeMillis()

            try {
                socket?.soTimeout = timeoutMs.toInt()

                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)

                    val receivedMessage = String(packet.data, 0, packet.length)
                    if (receivedMessage == expectedMessage) {
                        return true
                    } else if (receivedMessage.startsWith("DISPLAY_ERROR:") ||
                        receivedMessage.startsWith("RESOLUTION_ERROR:")) {
                        uiManager.updateStatus("Server error: $receivedMessage")
                        return false
                    }
                }
                return false
            } catch (e: SocketTimeoutException) {
                return false
            } catch (e: Exception) {
                uiManager.updateStatus("Wait error: ${e.localizedMessage}")
                return false
            }
        }

        private fun waitForDisplayReady(timeoutMs: Long): String? {
            val buffer = ByteArray(1024)
            val startTime = System.currentTimeMillis()

            try {
                socket?.soTimeout = timeoutMs.toInt()

                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)

                    val receivedMessage = String(packet.data, 0, packet.length)
                    if (receivedMessage.startsWith("DISPLAY_READY:")) {
                        return receivedMessage
                    } else if (receivedMessage.startsWith("DISPLAY_ERROR:")) {
                        uiManager.updateStatus("Server error: $receivedMessage")
                        return null
                    }
                }
                return null
            } catch (e: SocketTimeoutException) {
                return null
            } catch (e: Exception) {
                uiManager.updateStatus("Display ready wait error: ${e.localizedMessage}")
                return null
            }
        }

        private fun processReceivedPacket(data: ByteArray, length: Int) {
            if (!handshakeComplete || !displayReady) return

            try {
                val dataStr = String(data, 0, length)
                if (dataStr.startsWith("INFO:")) {
                    handleFrameInfo(dataStr)
                    return
                }

                if (length >= 16) {
                    handleFramePacket(data, length)
                }
            } catch (e: Exception) {
                uiManager.updateStatus("Packet processing error: ${e.localizedMessage}")
            }
        }

        private fun handleFrameInfo(infoPacket: String) {
            val parts = infoPacket.split(":")
            if (parts.size >= 3 && parts[0] == "INFO") {
                val width = parts[1].toIntOrNull()
                val height = parts[2].toIntOrNull()

                if (width != null && height != null) {
                    frameInfo = FrameInfo(width, height)
                    val hasDelta = parts.size >= 4 && parts[3] == "DELTA"
                    if (hasDelta) {
                        uiManager.updateStatus("Frame info received: ${width}x${height} (PNG+DELTA)")
                    } else {
                        uiManager.updateStatus("Frame info received: ${width}x${height} (PNG)")
                    }
                } else {
                    uiManager.updateStatus("Invalid frame info format")
                }
            }
        }

        private fun handleFramePacket(data: ByteArray, length: Int) {
            try {
                val buffer = ByteBuffer.wrap(data)
                buffer.order(ByteOrder.BIG_ENDIAN)

                val header = PacketHeader(
                    frameId = buffer.int,
                    packetId = buffer.int,
                    totalPackets = buffer.int,
                    dataSize = buffer.int
                )

                if (header.dataSize < 0 || header.dataSize > length - 16) {
                    uiManager.updateStatus("Invalid packet header")
                    return
                }

                if (header.frameId != currentFrameId) {
                    if (currentFrameId >= 0 && packetsReceived > 0) {
                        uiManager.updateStatus("Frame ${currentFrameId}: ${packetsReceived}/${expectedPackets} packets")
                    }

                    currentFrameId = header.frameId
                    expectedPackets = header.totalPackets
                    packetsReceived = 0
                    framePackets.clear()
                    frameStartTime = System.currentTimeMillis()

                    if (currentFrameId != expectedFrameId) {
                        if (currentFrameId > expectedFrameId) {
                            uiManager.updateStatus("Frame gap detected: expected $expectedFrameId, got $currentFrameId")
                            requestKeyframe()
                            hasValidBaseFrame = false
                        }
                        expectedFrameId = currentFrameId + 1
                    } else {
                        expectedFrameId++
                    }
                }

                val packetData = ByteArray(header.dataSize)
                buffer.get(packetData, 0, header.dataSize)
                framePackets[header.packetId] = packetData
                packetsReceived++

                if (packetsReceived == expectedPackets) {
                    val frameTime = System.currentTimeMillis() - frameStartTime
                    reconstructAndDisplayFrame(frameTime)
                } else {
                    if (packetsReceived % 50 == 0) {
                        uiManager.updateStatus("Frame ${currentFrameId}: ${packetsReceived}/${expectedPackets}")
                    }
                }
            } catch (e: Exception) {
                uiManager.updateStatus("Frame packet error: ${e.localizedMessage}")
            }
        }

        private fun reconstructAndDisplayFrame(frameTime: Long) {
            val info = frameInfo ?: return

            try {
                val now = System.currentTimeMillis()

                if (now - lastFrameDisplayTime < minFrameInterval) {
                    return
                }

                val totalSize = framePackets.values.sumOf { it.size }
                val frameData = ByteArray(totalSize)

                var offset = 0
                for (packetId in 0 until expectedPackets) {
                    val packetData = framePackets[packetId]
                    if (packetData != null) {
                        System.arraycopy(packetData, 0, frameData, offset, packetData.size)
                        offset += packetData.size
                    } else {
                        uiManager.updateStatus("Missing packet $packetId in frame $currentFrameId")
                        return
                    }
                }

                framesReceived++
                lastFrameDisplayTime = now

                // Detect delta operations by magic headers (CREG or DREG)
                if (totalSize >= 4 && (
                            (frameData[0] == 'C'.code.toByte() && frameData[1] == 'R'.code.toByte() &&
                                    frameData[2] == 'E'.code.toByte() && frameData[3] == 'G'.code.toByte()) ||
                                    (frameData[0] == 'D'.code.toByte() && frameData[1] == 'R'.code.toByte() &&
                                            frameData[2] == 'E'.code.toByte() && frameData[3] == 'G'.code.toByte())
                            )) {
                    processAtomicDeltaOperations(frameData, totalSize, info, currentFrameId, frameTime)
                } else {
                    // FULL FRAME PATH
                    displayFrame(frameData, currentFrameId, frameTime, totalSize) { success ->
                        if (success) {
                            mainHandler.post {
                                // Convert hardware bitmap to mutable software bitmap
                                val displayedBitmap = previousBitmap
                                if (displayedBitmap != null &&
                                    (!displayedBitmap.isMutable || displayedBitmap.config != Bitmap.Config.ARGB_8888)) {

                                    // Convert to mutable software bitmap for delta region updates
                                    val mutableCopy = displayedBitmap.copy(Bitmap.Config.ARGB_8888, true)
                                    recycleBitmapSafely(displayedBitmap)
                                    previousBitmap = mutableCopy
                                }

                                hasValidBaseFrame = true
                                lastFullFrameId = currentFrameId
                            }
                        }
                    }
                }

                if (framesReceived % 30 == 0) {
                    uiManager.updateStatus("Received $framesReceived frames")
                }

            } catch (e: Exception) {
                uiManager.updateStatus("Frame reconstruction error: ${e.localizedMessage}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        mainHandler.post {
            // Clear ImageView first
            uiManager.clearFrame()

            // Then recycle bitmap with delay
            val bitmapToRecycle = previousBitmap
            previousBitmap = null

            if (bitmapToRecycle != null) {
                recycleBitmapSafely(bitmapToRecycle)
            }

            // Clean up any pending bitmaps
            recycleHandler.postDelayed({
                synchronized(bitmapsToRecycle) {
                    bitmapsToRecycle.forEach { bmp ->
                        try {
                            if (!bmp.isRecycled) {
                                bmp.recycle()
                            }
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                    bitmapsToRecycle.clear()
                }
            }, 200)
        }

        disconnectFromServer()

        decodingExecutor?.shutdownNow()
        executorService?.shutdownNow()
    }
    override fun onBackPressed() {
        if (uiManager.isInFullscreen()) {
            uiManager.toggleFullscreen()
        } else {
            super.onBackPressed()
        }
    }
}