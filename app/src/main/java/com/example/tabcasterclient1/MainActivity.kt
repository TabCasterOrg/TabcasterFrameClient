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

    // Add dedicated thread pool for PNG decoding
    private var decodingExecutor: ExecutorService? = null
    private val maxPendingFrames = 2 // Drop frames if more than this are pending

    // Frame dropping mechanism
    @Volatile
    private var pendingDecodes = 0
    @Volatile
    private var droppedFrames = 0

    // Decode time tracking
    private var totalDecodeTime = 0L
    private var decodedFrameCount = 0
    private var avgDecodeTime = 0f

    // Hardware acceleration support
    private var isHardwareAccelerationSupported = false
    private var hardwareDecodeCount = 0
    private var softwareDecodeCount = 0
    private var totalHardwareDecodeTime = 0L
    private var totalSoftwareDecodeTime = 0L

    // Streaming state
    private var isStreaming: Boolean = false

    // Bitmap optimization - CRITICAL: Keep bitmap reference in MainActivity
    private var previousBitmap: Bitmap? = null

    // Frame synchronization tracking
    private var lastReceivedFrameId = -1
    private var expectedFrameId = 0
    private var hasValidBaseFrame = false
    private var lastFullFrameId = -1

    // Optimized bitmap options for hardware acceleration
    private val hardwareBitmapOptions = BitmapFactory.Options().apply {
        inMutable = true
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inSampleSize = 1
        inDither = false
        inPreferQualityOverSpeed = false
        inTempStorage = ByteArray(64 * 1024) // Larger temp buffer for hardware acceleration
        inJustDecodeBounds = false
        inScaled = false // Let hardware handle scaling
    }

    // Fallback bitmap options for software decoding
    private val softwareBitmapOptions = BitmapFactory.Options().apply {
        inMutable = true
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inSampleSize = 1
        inDither = false
        inPreferQualityOverSpeed = false
        inTempStorage = ByteArray(32 * 1024)
    }

    // FPS and latency tracking
    private var lastFrameTime = 0L
    private var frameCount = 0
    private var fpsStartTime = 0L
    private var currentFPS = 0f

    companion object {
        private const val DEFAULT_IP = "10.1.10.105"
        private const val DEFAULT_PORT = 23532
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
        // Initialize single decoding thread (one thread is better for sequential frame processing)
        decodingExecutor = Executors.newSingleThreadExecutor()

        // Initialize hardware acceleration support
        initializeHardwareAcceleration()

        uiManager.updateStatus("Ready")
        uiManager.updateFrameInfo("No frame data")
        uiManager.updateResolutionInfo()
    }

    // UIManager.UICallbacks implementation
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
            portStr.toInt() // TODO: Refactor
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

        // Clean up bitmap - CRITICAL: Do this on main thread
        mainHandler.post {
            recycleBitmapSafely(previousBitmap)
            previousBitmap = null
            uiManager.clearFrame()
        }

        // Reset FPS tracking
        currentFPS = 0f
        frameCount = 0
        fpsStartTime = 0L

        // Reset all frame processing counters
        pendingDecodes = 0
        droppedFrames = 0
        totalDecodeTime = 0L
        decodedFrameCount = 0
        avgDecodeTime = 0f

        // Reset hardware acceleration statistics
        hardwareDecodeCount = 0
        softwareDecodeCount = 0
        totalHardwareDecodeTime = 0L
        totalSoftwareDecodeTime = 0L

        // Reset frame synchronization state
        hasValidBaseFrame = false
        lastFullFrameId = -1
        expectedFrameId = 0

        uiManager.setStreamingState(false)
        uiManager.setConnectionState(false)
        uiManager.resetUI()
    }

    // Hardware-accelerated image decoding using ImageDecoder (API 28+)
    private fun decodeImageHardware(pngData: ByteArray): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(pngData)

                // Use ImageDecoder.decodeBitmap with configuration
                ImageDecoder.decodeBitmap(source) { decoder, info, source ->
                    // Configure for hardware acceleration
                    decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
                    decoder.setUnpremultipliedRequired(false)
                }
            } catch (e: Exception) {
                // Fallback to software decoding if hardware fails
                decodeImageSoftware(pngData)
            }
        } else {
            // Fallback to software decoding for older API levels
            decodeImageSoftware(pngData)
        }
    }

    // Software image decoding using BitmapFactory (fallback)
    private fun decodeImageSoftware(pngData: ByteArray): Bitmap? {
        return try {
            BitmapFactory.decodeByteArray(pngData, 0, pngData.size, softwareBitmapOptions)
        } catch (e: Exception) {
            null
        }
    }

    // Unified image decoding method that chooses the best approach
    private fun decodeImage(pngData: ByteArray): Bitmap? {
        return if (isHardwareAccelerationSupported) {
            decodeImageHardware(pngData)
        } else {
            decodeImageSoftware(pngData)
        }
    }

    // Memory-optimized bitmap recycling
    private fun recycleBitmapSafely(bitmap: Bitmap?) {
        try {
            bitmap?.recycle()
        } catch (e: Exception) {
            // Ignore recycling errors
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

        // Calculate FPS every second
        val elapsed = now - fpsStartTime
        if (elapsed >= 1000) {
            currentFPS = (frameCount * 1000f) / elapsed
            fpsStartTime = now
            frameCount = 0
        }
    }

    // CRITICAL FIX: Ensure all bitmap operations happen on main thread
    private fun displayFrame(pngData: ByteArray, frameId: Int, frameTime: Long, compressedSize: Int, onSuccess: ((Boolean) -> Unit)? = null) {
        // Frame dropping: Skip if too many frames are pending decode
        if (pendingDecodes >= maxPendingFrames) {
            droppedFrames++
            if (droppedFrames % 5 == 0) {
                // Update status with frame dropping info
                uiManager.updateStatus("Streaming ($pendingDecodes pending, dropped $droppedFrames frames)")
            }
            onSuccess?.invoke(false)
            return
        }

        pendingDecodes++

        // Decode PNG on background thread to avoid blocking UI
        decodingExecutor?.submit {
            val decodeStartTime = System.nanoTime()

            try {
                // Use hardware-accelerated decoding when available
                val bitmap = decodeImage(pngData)

                val decodeTimeMs = (System.nanoTime() - decodeStartTime) / 1_000_000

                // Update decode time statistics with hardware/software tracking
                synchronized(this) {
                    totalDecodeTime += decodeTimeMs
                    decodedFrameCount++
                    avgDecodeTime = totalDecodeTime.toFloat() / decodedFrameCount

                    // Track hardware vs software performance
                    if (isHardwareAccelerationSupported && bitmap != null) {
                        hardwareDecodeCount++
                        totalHardwareDecodeTime += decodeTimeMs
                    } else {
                        softwareDecodeCount++
                        totalSoftwareDecodeTime += decodeTimeMs
                    }
                }

                if (bitmap != null) {
                    // CRITICAL: All bitmap operations must happen on main thread
                    mainHandler.post {
                        try {
                            // Recycle previous bitmap to prevent memory leaks
                            recycleBitmapSafely(previousBitmap)

                            // Display new frame
                            uiManager.displayFrame(bitmap)
                            previousBitmap = bitmap

                            // Update FPS calculation
                            updateFPSCalculation()

                            // Throttled UI updates (max 60 FPS for UI)
                            if (uiManager.shouldUpdateFrameInfo()) {
                                uiManager.updateOptimizedFrameInfo(
                                    frameId, frameTime, bitmap.width, bitmap.height, decodeTimeMs,
                                    currentFPS, avgDecodeTime, isHardwareAccelerationSupported,
                                    hardwareDecodeCount, softwareDecodeCount,
                                    totalHardwareDecodeTime, totalSoftwareDecodeTime, droppedFrames
                                )
                            }

                            // Notify success callback
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

        // Keyframe request mechanism
        private var lastKeyframeRequestTime = 0L
        private val keyframeRequestCooldown = 1000L // 1 second cooldown

        // Modified UDPReceiver with frame skipping logic
        private var lastFrameDisplayTime = 0L
        private val minFrameInterval = 33L // ~30 FPS max display rate

        fun stop() {
            running = false
            socket?.close()
        }

        private fun requestKeyframe() {
            val now = System.currentTimeMillis()
            if (now - lastKeyframeRequestTime < keyframeRequestCooldown) {
                return // Rate limit keyframe requests
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

                // Streaming started successfully
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
                    // Backward and forward compatible status: detect optional DELTA flag
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

                    // Check for frame sequence issues
                    if (currentFrameId != expectedFrameId) {
                        if (currentFrameId > expectedFrameId) {
                            uiManager.updateStatus("Frame gap detected: expected $expectedFrameId, got $currentFrameId")
                            // Request keyframe when frame gap is detected to prevent delta application to wrong base
                            requestKeyframe()
                            // Reset base frame validity since we may have missed frames
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
                    // Skip this frame - displaying too fast
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

                // CRITICAL: Validate base frame before processing any frame
                // This prevents case #4: delta applied to wrong base frame
                if (!hasValidBaseFrame && currentFrameId != 0) {
                    uiManager.updateStatus("Rejecting frame $currentFrameId - no valid base frame, requesting keyframe")
                    requestKeyframe()
                    return
                }

                // Detect delta region payload by magic 'DREG' prefix
                if (totalSize >= 4 &&
                    frameData[0] == 'D'.code.toByte() &&
                    frameData[1] == 'R'.code.toByte() &&
                    frameData[2] == 'E'.code.toByte() &&
                    frameData[3] == 'G'.code.toByte()) {
                    // Parse RegionHeader: x,y,w,h (uint16 BE), flags (u8), quality (u8), reserved (u16 BE)
                    if (totalSize < 4 + 2 + 2 + 2 + 2 + 1 + 1 + 2) {
                        uiManager.updateStatus("Delta header too small")
                        return
                    }
                    var p = 4
                    fun readU16BE(): Int {
                        val v = ((frameData[p].toInt() and 0xFF) shl 8) or (frameData[p+1].toInt() and 0xFF)
                        p += 2
                        return v
                    }
                    val rx = readU16BE()
                    val ry = readU16BE()
                    val rw = readU16BE()
                    val rh = readU16BE()
                    val flags = frameData[p++].toInt() and 0xFF
                    val quality = frameData[p++].toInt() and 0xFF
                    p += 2 // reserved
                    val pngBytes = frameData.copyOfRange(p, totalSize)

                    // CRITICAL: All delta processing must happen on main thread
                    mainHandler.post {
                        try {
                            // Validate region bounds
                            if (rx < 0 || ry < 0 || rw <= 0 || rh <= 0 ||
                                rx + rw > info.width || ry + rh > info.height) {
                                uiManager.updateFrameInfo("Invalid delta region bounds: $rx,$ry $rw x $rh")
                                return@post
                            }

                            // CRITICAL FIX: Reject delta regions if we don't have a valid base frame
                            if (!hasValidBaseFrame) {
                                uiManager.updateFrameInfo("Rejecting delta frame $currentFrameId - no valid base frame")
                                requestKeyframe()
                                return@post
                            }

                            // Ensure base bitmap exists and has server size (mutable software bitmap)
                            if (previousBitmap == null ||
                                previousBitmap!!.width != info.width ||
                                previousBitmap!!.height != info.height ||
                                previousBitmap!!.config != Bitmap.Config.ARGB_8888 ||
                                !previousBitmap!!.isMutable) {
                                recycleBitmapSafely(previousBitmap)
                                previousBitmap = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
                                uiManager.updateFrameInfo("Created new base bitmap for delta regions - waiting for full frame")
                                return@post
                            }

                            val base = previousBitmap!!

                            // Decode region on background thread, then apply on main thread
                            decodingExecutor?.submit {
                                val regionBitmap = decodeImage(pngBytes)
                                if (regionBitmap != null) {
                                    mainHandler.post {
                                        try {
                                            // Ensure region bitmap is software-backed
                                            val safeRegion = if (regionBitmap.config != Bitmap.Config.ARGB_8888 || !regionBitmap.isMutable) {
                                                regionBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                            } else regionBitmap

                                            // CRITICAL FIX: Use pixel-level replacement instead of canvas compositing
                                            val basePixels = IntArray(base.width * base.height)
                                            base.getPixels(basePixels, 0, base.width, 0, 0, base.width, base.height)

                                            val regionPixels = IntArray(safeRegion.width * safeRegion.height)
                                            safeRegion.getPixels(regionPixels, 0, safeRegion.width, 0, 0, safeRegion.width, safeRegion.height)

                                            // Replace pixels in the delta region
                                            for (y in 0 until safeRegion.height) {
                                                for (x in 0 until safeRegion.width) {
                                                    val baseX = rx + x
                                                    val baseY = ry + y
                                                    if (baseX < base.width && baseY < base.height) {
                                                        val regionIndex = y * safeRegion.width + x
                                                        val baseIndex = baseY * base.width + baseX
                                                        basePixels[baseIndex] = regionPixels[regionIndex]
                                                    }
                                                }
                                            }

                                            base.setPixels(basePixels, 0, base.width, 0, 0, base.width, base.height)
                                            uiManager.displayFrame(base)

                                            updateFPSCalculation()
                                            if (uiManager.shouldUpdateFrameInfo()) {
                                                uiManager.updateOptimizedFrameInfo(
                                                    currentFrameId, frameTime, base.width, base.height, 0,
                                                    currentFPS, avgDecodeTime, isHardwareAccelerationSupported,
                                                    hardwareDecodeCount, softwareDecodeCount,
                                                    totalHardwareDecodeTime, totalSoftwareDecodeTime, droppedFrames
                                                )
                                            }
                                        } catch (e: Exception) {
                                            uiManager.updateFrameInfo("Delta apply error: ${e.message}")
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            uiManager.updateFrameInfo("Delta processing error: ${e.message}")
                        }
                    }
                } else {
                    // Full frame PNG path - mark as valid base frame only after successful display
                    displayFrame(frameData, currentFrameId, frameTime, totalSize) { success ->
                        if (success) {
                            hasValidBaseFrame = true
                            lastFullFrameId = currentFrameId
                        }
                    }
                }

                if (framesReceived % 30 == 0) {
                    uiManager.updateStatus("Received $framesReceived PNG frames")
                }

            } catch (e: Exception) {
                uiManager.updateStatus("Frame reconstruction error: ${e.localizedMessage}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Clean up on main thread
        mainHandler.post {
            recycleBitmapSafely(previousBitmap)
            previousBitmap = null
        }

        disconnectFromServer()

        // Shutdown decoding executor
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