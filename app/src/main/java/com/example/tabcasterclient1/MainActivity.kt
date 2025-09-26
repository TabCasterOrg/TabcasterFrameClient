package com.example.tabcasterclient1

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    // Simplified thread pool for decoding - single thread is better for sequential processing
    private var decodingExecutor: ExecutorService? = null
    private val maxPendingFrames = 2

    // Frame dropping mechanism
    @Volatile
    private var pendingDecodes = 0
    @Volatile
    private var droppedFrames = 0

    // Performance tracking
    private var totalDecodeTime = 0L
    private var decodedFrameCount = 0
    private var avgDecodeTime = 0f

    // CRITICAL FIX: Force software-only processing for reliable delta handling
    private var useSoftwareDecoding = true // Always use software for deltas

    // Decode time tracking (software only now)
    private var softwareDecodeCount = 0
    private var totalSoftwareDecodeTime = 0L

    // Streaming state
    private var isStreaming: Boolean = false

    // CRITICAL: Base frame must be mutable software bitmap
    private var baseBitmap: Bitmap? = null

    // Frame synchronization tracking
    private var lastReceivedFrameId = -1
    private var expectedFrameId = 0
    private var hasValidBaseFrame = false
    private var lastFullFrameId = -1

    // CRITICAL FIX: Software-only bitmap options for reliable delta processing
    private val softwareBitmapOptions = BitmapFactory.Options().apply {
        inMutable = true
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inSampleSize = 1
        inDither = false
        inPreferQualityOverSpeed = false
        inTempStorage = ByteArray(64 * 1024)
        inJustDecodeBounds = false
        inScaled = false
    }

    // FPS and latency tracking
    private var lastFrameTime = 0L
    private var frameCount = 0
    private var fpsStartTime = 0L
    private var currentFPS = 0f

    // Delta frame tracking for debugging
    private var consecutiveDeltaFrames = 0
    private val maxConsecutiveDeltas = 60 // Force keyframe after 60 deltas
    private var lastKeyframeRequestTime = 0L
    private val keyframeRequestCooldown = 2000L // 2 second cooldown

    companion object {
        private const val DEFAULT_IP = "10.1.10.105"
        private const val DEFAULT_PORT = 23532
        private const val TAG = "TabCaster"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI Manager
        uiManager = UIManager(this)
        uiManager.setCallbacks(this)
        uiManager.initializeViews()
        uiManager.setDefaultValues(DEFAULT_IP, DEFAULT_PORT)
        uiManager.setupClickListeners()
        uiManager.getScreenResolution()

        executorService = Executors.newSingleThreadExecutor()
        // Single thread for sequential frame processing
        decodingExecutor = Executors.newSingleThreadExecutor()

        // CRITICAL: Always use software decoding for delta reliability
        useSoftwareDecoding = true
        Log.i(TAG, "Initialized with software-only decoding for delta reliability")

        uiManager.updateStatus("Ready - Software decoding mode")
        uiManager.updateFrameInfo("No frame data")
        uiManager.updateResolutionInfo()
    }

    // UIManager.UICallbacks implementation
    override fun onConnectClicked() {
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

    private fun connectToServer() {
        val defaultIP = "10.1.10.105"
        val defaultPort = 23532

        val serverIP = uiManager.getServerIP().ifEmpty { defaultIP }
        val portStr = uiManager.getPort().ifEmpty { defaultPort.toString() }

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

        // Clean up base bitmap on main thread
        mainHandler.post {
            recycleBitmapSafely(baseBitmap)
            baseBitmap = null
            uiManager.clearFrame()
        }

        // Reset all tracking variables
        currentFPS = 0f
        frameCount = 0
        fpsStartTime = 0L
        pendingDecodes = 0
        droppedFrames = 0
        totalDecodeTime = 0L
        decodedFrameCount = 0
        avgDecodeTime = 0f
        softwareDecodeCount = 0
        totalSoftwareDecodeTime = 0L
        hasValidBaseFrame = false
        lastFullFrameId = -1
        expectedFrameId = 0
        consecutiveDeltaFrames = 0

        uiManager.setStreamingState(false)
        uiManager.setConnectionState(false)
        uiManager.resetUI()
    }

    // CRITICAL FIX: Software-only decoding for consistent results
    private fun decodeImageSoftware(pngData: ByteArray): Bitmap? {
        return try {
            BitmapFactory.decodeByteArray(pngData, 0, pngData.size, softwareBitmapOptions)
        } catch (e: Exception) {
            Log.e(TAG, "Software decode failed: ${e.message}")
            null
        }
    }

    // Memory-optimized bitmap recycling
    private fun recycleBitmapSafely(bitmap: Bitmap?) {
        try {
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bitmap recycle warning: ${e.message}")
        }
    }

    // FPS calculation
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

    // CRITICAL FIX: Proper full frame handling with software bitmap creation
    private fun displayFullFrame(pngData: ByteArray, frameId: Int, frameTime: Long, compressedSize: Int, onSuccess: ((Boolean) -> Unit)? = null) {
        if (pendingDecodes >= maxPendingFrames) {
            droppedFrames++
            onSuccess?.invoke(false)
            return
        }

        pendingDecodes++

        decodingExecutor?.submit {
            val decodeStartTime = System.nanoTime()

            try {
                val bitmap = decodeImageSoftware(pngData)
                val decodeTimeMs = (System.nanoTime() - decodeStartTime) / 1_000_000

                synchronized(this) {
                    totalDecodeTime += decodeTimeMs
                    decodedFrameCount++
                    avgDecodeTime = totalDecodeTime.toFloat() / decodedFrameCount
                    softwareDecodeCount++
                    totalSoftwareDecodeTime += decodeTimeMs
                }

                if (bitmap != null) {
                    // CRITICAL: Ensure bitmap is mutable and software-based
                    val finalBitmap = if (!bitmap.isMutable || bitmap.config == Bitmap.Config.HARDWARE) {
                        bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    } else {
                        bitmap
                    }

                    mainHandler.post {
                        try {
                            // Clean up previous base bitmap
                            recycleBitmapSafely(baseBitmap)

                            // Set new base frame
                            baseBitmap = finalBitmap
                            uiManager.displayFrame(finalBitmap)

                            // Mark as valid base frame
                            hasValidBaseFrame = true
                            lastFullFrameId = frameId
                            consecutiveDeltaFrames = 0

                            updateFPSCalculation()

                            if (uiManager.shouldUpdateFrameInfo()) {
                                uiManager.updateOptimizedFrameInfo(
                                    frameId, frameTime, finalBitmap.width, finalBitmap.height, decodeTimeMs,
                                    currentFPS, avgDecodeTime, false, // Never hardware
                                    0, softwareDecodeCount, 0, totalSoftwareDecodeTime, droppedFrames
                                )
                            }

                            Log.d(TAG, "Full frame $frameId applied successfully (${finalBitmap.width}x${finalBitmap.height})")
                            onSuccess?.invoke(true)

                        } catch (e: Exception) {
                            Log.e(TAG, "Error displaying full frame: ${e.message}")
                            onSuccess?.invoke(false)
                        }
                    }
                } else {
                    Log.e(TAG, "Failed to decode full frame $frameId")
                    onSuccess?.invoke(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Full frame processing error: ${e.message}")
                onSuccess?.invoke(false)
            } finally {
                pendingDecodes--
            }
        }
    }

    // CRITICAL FIX: Proper delta region handling with pixel-perfect replacement
    private fun applyDeltaRegion(deltaData: ByteArray, frameId: Int, frameTime: Long, onSuccess: ((Boolean) -> Unit)? = null) {
        if (deltaData.size < 16) {
            Log.e(TAG, "Delta data too small: ${deltaData.size} bytes")
            onSuccess?.invoke(false)
            return
        }

        try {
            // Parse delta header
            var offset = 4 // Skip 'DREG' magic
            fun readU16BE(): Int {
                val value = ((deltaData[offset].toInt() and 0xFF) shl 8) or (deltaData[offset + 1].toInt() and 0xFF)
                offset += 2
                return value
            }

            val rx = readU16BE()
            val ry = readU16BE()
            val rw = readU16BE()
            val rh = readU16BE()
            val flags = deltaData[offset++].toInt() and 0xFF
            val quality = deltaData[offset++].toInt() and 0xFF
            offset += 2 // skip reserved

            val regionPngData = deltaData.copyOfRange(offset, deltaData.size)

            Log.d(TAG, "Delta region: ($rx,$ry) ${rw}x${rh}, PNG size: ${regionPngData.size}")

            mainHandler.post {
                // CRITICAL: Validate base frame before processing delta
                if (!hasValidBaseFrame || baseBitmap == null || baseBitmap!!.isRecycled) {
                    Log.w(TAG, "No valid base frame for delta $frameId - requesting keyframe")
                    requestKeyframe()
                    onSuccess?.invoke(false)
                    return@post
                }

                val base = baseBitmap!!

                // Validate region bounds
                if (rx < 0 || ry < 0 || rw <= 0 || rh <= 0 ||
                    rx + rw > base.width || ry + rh > base.height) {
                    Log.e(TAG, "Invalid delta region bounds: ($rx,$ry) ${rw}x${rh} for base ${base.width}x${base.height}")
                    onSuccess?.invoke(false)
                    return@post
                }

                // Ensure base is mutable
                if (!base.isMutable) {
                    Log.e(TAG, "Base bitmap is not mutable - requesting keyframe")
                    requestKeyframe()
                    onSuccess?.invoke(false)
                    return@post
                }

                // Decode region on background thread
                decodingExecutor?.submit {
                    val regionBitmap = decodeImageSoftware(regionPngData)
                    if (regionBitmap == null) {
                        Log.e(TAG, "Failed to decode delta region")
                        onSuccess?.invoke(false)
                        return@submit
                    }

                    // Validate region size matches header
                    if (regionBitmap.width != rw || regionBitmap.height != rh) {
                        Log.e(TAG, "Region size mismatch: expected ${rw}x${rh}, got ${regionBitmap.width}x${regionBitmap.height}")
                        recycleBitmapSafely(regionBitmap)
                        onSuccess?.invoke(false)
                        return@submit
                    }

                    mainHandler.post {
                        try {
                            // CRITICAL FIX: Direct pixel replacement with proper array handling
                            val basePixels = IntArray(base.width * base.height)
                            base.getPixels(basePixels, 0, base.width, 0, 0, base.width, base.height)

                            val regionPixels = IntArray(regionBitmap.width * regionBitmap.height)
                            regionBitmap.getPixels(regionPixels, 0, regionBitmap.width, 0, 0, regionBitmap.width, regionBitmap.height)

                            // Replace pixels in delta region (this should prevent ghosting)
                            var replacedPixels = 0
                            for (y in 0 until regionBitmap.height) {
                                for (x in 0 until regionBitmap.width) {
                                    val baseX = rx + x
                                    val baseY = ry + y

                                    if (baseX >= 0 && baseX < base.width && baseY >= 0 && baseY < base.height) {
                                        val regionIndex = y * regionBitmap.width + x
                                        val baseIndex = baseY * base.width + baseX
                                        basePixels[baseIndex] = regionPixels[regionIndex]
                                        replacedPixels++
                                    }
                                }
                            }

                            // Apply pixels back to base bitmap
                            base.setPixels(basePixels, 0, base.width, 0, 0, base.width, base.height)
                            uiManager.displayFrame(base)

                            consecutiveDeltaFrames++
                            updateFPSCalculation()

                            if (uiManager.shouldUpdateFrameInfo()) {
                                uiManager.updateOptimizedFrameInfo(
                                    frameId, frameTime, base.width, base.height, 0,
                                    currentFPS, avgDecodeTime, false,
                                    0, softwareDecodeCount, 0, totalSoftwareDecodeTime, droppedFrames
                                )
                            }

                            Log.d(TAG, "Delta $frameId applied: replaced $replacedPixels pixels in region ($rx,$ry) ${rw}x${rh}")
                            onSuccess?.invoke(true)

                        } catch (e: Exception) {
                            Log.e(TAG, "Delta application error: ${e.message}")
                            onSuccess?.invoke(false)
                        } finally {
                            recycleBitmapSafely(regionBitmap)
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Delta parsing error: ${e.message}")
            onSuccess?.invoke(false)
        }
    }

    // Request keyframe with rate limiting
    private fun requestKeyframe() {
        val now = System.currentTimeMillis()
        if (now - lastKeyframeRequestTime < keyframeRequestCooldown) {
            return
        }

        try {
            val udpReceiver = this.udpReceiver ?: return
            val requestData = "REQUEST_KEYFRAME".toByteArray()
            val serverAddress = InetAddress.getByName(udpReceiver.serverIP)
            val requestPacket = DatagramPacket(requestData, requestData.size, serverAddress, udpReceiver.serverPort)
            udpReceiver.socket?.send(requestPacket)
            lastKeyframeRequestTime = now
            consecutiveDeltaFrames = 0
            Log.i(TAG, "Keyframe requested from server")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request keyframe: ${e.message}")
        }
    }

    // Data classes for frame handling
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
        val serverIP: String,
        val serverPort: Int
    ) : Runnable {

        @Volatile
        private var running = true
        var socket: DatagramSocket? = null
        private var frameInfo: FrameInfo? = null
        private val framePackets = mutableMapOf<Int, ByteArray>()
        private var expectedPackets = 0
        private var currentFrameId = -1
        private var packetsReceived = 0
        private var frameStartTime = 0L
        private var framesReceived = 0
        private var handshakeComplete = false
        private var displayReady = false

        // Frame rate limiting
        private var lastFrameDisplayTime = 0L
        private val minFrameInterval = 33L // ~30 FPS max display rate

        fun stop() {
            running = false
            socket?.close()
        }

        override fun run() {
            try {
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
                    val hasDelta = parts.size >= 4 && parts[3] == "PNG" && parts.size >= 5 && parts[4] == "DELTA"
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

                // Frame rate limiting on client side
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

                // CRITICAL FIX: Proper delta vs full frame detection
                if (totalSize >= 4 &&
                    frameData[0] == 'D'.code.toByte() &&
                    frameData[1] == 'R'.code.toByte() &&
                    frameData[2] == 'E'.code.toByte() &&
                    frameData[3] == 'G'.code.toByte()) {

                    // This is a delta region frame
                    Log.d(TAG, "Processing delta frame $currentFrameId")

                    // Force keyframe if too many consecutive deltas (prevents drift)
                    if (consecutiveDeltaFrames >= maxConsecutiveDeltas) {
                        Log.w(TAG, "Too many consecutive deltas (${consecutiveDeltaFrames}) - requesting keyframe")
                        requestKeyframe()
                        return
                    }

                    applyDeltaRegion(frameData, currentFrameId, frameTime) { success ->
                        if (!success) {
                            Log.w(TAG, "Delta application failed for frame $currentFrameId - requesting keyframe")
                            requestKeyframe()
                        }
                    }

                } else {
                    // This is a full frame (keyframe)
                    Log.d(TAG, "Processing full frame $currentFrameId")

                    displayFullFrame(frameData, currentFrameId, frameTime, totalSize) { success ->
                        if (!success) {
                            Log.e(TAG, "Full frame processing failed for frame $currentFrameId")
                        }
                    }
                }

                if (framesReceived % 60 == 0) {
                    uiManager.updateStatus("Received $framesReceived frames (deltas: $consecutiveDeltaFrames)")
                }

            } catch (e: Exception) {
                uiManager.updateStatus("Frame reconstruction error: ${e.localizedMessage}")
                Log.e(TAG, "Frame reconstruction error", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Clean up on main thread
        mainHandler.post {
            recycleBitmapSafely(baseBitmap)
            baseBitmap = null
        }

        disconnectFromServer()

        // Shutdown executors
        decodingExecutor?.shutdownNow()
        executorService?.shutdownNow()
    }

    override fun onBackPressed() {
        if (uiManager.isInFullscreen()) {
            uiManager.toggleFullscreen()
        } else {
            super.onBackPressed()
        }
    }}