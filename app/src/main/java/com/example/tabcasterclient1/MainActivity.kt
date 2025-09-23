package com.example.tabcasterclient1

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : AppCompatActivity() {

    private lateinit var etServerIP: EditText
    private lateinit var etPort: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnFullscreen: Button
    private lateinit var tvStatus: TextView
    private lateinit var ivFrame: ImageView
    private lateinit var tvFrameInfo: TextView
    private lateinit var tvResolution: TextView

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

    // Screen resolution info
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var refreshRate: Float = 60.0f

    // Server resolution (might be different from client if fallback used)
    private var serverWidth: Int = 0
    private var serverHeight: Int = 0
    private var serverRefreshRate: Float = 60.0f

    // Fullscreen state
    private var isFullscreen: Boolean = false
    private var isStreaming: Boolean = false

    // Bitmap optimization
    private var previousBitmap: Bitmap? = null

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

    // UI update throttling
    private var lastFrameInfoUpdate = 0L
    private var lastStatusUpdate = 0L

    companion object {
        private const val DEFAULT_IP = "10.1.10.105"
        private const val DEFAULT_PORT = 23532
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        // Auto-fill defaults
        etServerIP.setText(DEFAULT_IP)
        etPort.setText(DEFAULT_PORT.toString())

        setupClickListeners()

        executorService = Executors.newSingleThreadExecutor()
        // Initialize single decoding thread (one thread is better for sequential frame processing)
        decodingExecutor = Executors.newSingleThreadExecutor()

        // Initialize hardware acceleration support
        initializeHardwareAcceleration()

        getScreenResolution()

        updateStatus("Ready")
        updateFrameInfo("No frame data")
        updateResolutionInfo()
        updateFullscreenButton()
    }

    private fun initializeViews() {
        etServerIP = findViewById(R.id.et_server_ip)
        etPort = findViewById(R.id.et_port)
        btnConnect = findViewById(R.id.btn_connect)
        btnDisconnect = findViewById(R.id.btn_disconnect)
        btnFullscreen = findViewById(R.id.btn_fullscreen)
        tvStatus = findViewById(R.id.tv_status)
        ivFrame = findViewById(R.id.iv_frame)
        tvFrameInfo = findViewById(R.id.tv_frame_info)
        tvResolution = findViewById(R.id.tv_resolution)
    }

    private fun setupClickListeners() {
        btnConnect.setOnClickListener { connectToServer() }
        btnDisconnect.setOnClickListener { disconnectFromServer() }
        btnFullscreen.setOnClickListener { toggleFullscreen() }

        // Allow clicking on image to toggle fullscreen when streaming
        ivFrame.setOnClickListener {
            if (isStreaming) {
                toggleFullscreen()
            }
        }
    }

    private fun initializeHardwareAcceleration() {
        isHardwareAccelerationSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        if (isHardwareAccelerationSupported) {
            updateStatus("Hardware acceleration enabled (ImageDecoder)")
        } else {
            updateStatus("Using software decoding (BitmapFactory)")
        }
    }

    private fun getScreenResolution() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val displayMetrics = DisplayMetrics()

        display.getRealMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        refreshRate = display.refreshRate

        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (screenWidth < screenHeight) {
                val temp = screenWidth
                screenWidth = screenHeight
                screenHeight = temp
            }
        }

        serverWidth = screenWidth
        serverHeight = screenHeight
        serverRefreshRate = refreshRate
    }

    private fun connectToServer() {
        val defaultIP = "10.1.10.105"
        val defaultPort = 23532

        val serverIP = etServerIP.text.toString().trim().ifEmpty { defaultIP }
        val portStr = etPort.text.toString().trim().ifEmpty { defaultPort.toString() }

        val port = try {
            portStr.toInt()
        } catch (e: NumberFormatException) {
            Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show()
            return
        }
        udpReceiver = UDPReceiver(serverIP, port)
        executorService?.submit(udpReceiver)

        btnConnect.isEnabled = false
        btnDisconnect.isEnabled = true
        etServerIP.isEnabled = false
        etPort.isEnabled = false

        updateStatus("Connecting to $serverIP:$port")
    }

    private fun disconnectFromServer() {
        udpReceiver?.stop()
        udpReceiver = null
        isStreaming = false

        // Clean up bitmap
        recycleBitmapSafely(previousBitmap)
        previousBitmap = null

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

        btnConnect.isEnabled = true
        btnDisconnect.isEnabled = false
        etServerIP.isEnabled = true
        etPort.isEnabled = true

        updateStatus("Disconnected")
        updateFrameInfo("No frame data")
        updateFullscreenButton()

        // Reset server resolution to match client
        serverWidth = screenWidth
        serverHeight = screenHeight
        serverRefreshRate = refreshRate
        updateResolutionInfo()

        // Clear the image view
        mainHandler.post {
            ivFrame.setImageBitmap(null)
        }

        // Exit fullscreen if active
        if (isFullscreen) {
            exitFullscreen()
        }
    }

    private fun toggleFullscreen() {
        if (!isStreaming) return

        if (isFullscreen) {
            exitFullscreen()
        } else {
            enterFullscreen()
        }
    }

    private fun enterFullscreen() {
        isFullscreen = true

        // Hide system UI
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        // Hide all UI elements except the image
        etServerIP.visibility = View.GONE
        etPort.visibility = View.GONE
        btnConnect.visibility = View.GONE
        btnDisconnect.visibility = View.GONE
        btnFullscreen.visibility = View.GONE
        tvStatus.visibility = View.GONE
        tvFrameInfo.visibility = View.GONE
        tvResolution.visibility = View.GONE

        // Make image fill screen
        ivFrame.scaleType = ImageView.ScaleType.MATRIX

        updateFullscreenButton()
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private fun exitFullscreen() {
        isFullscreen = false

        // Show system UI
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.show(WindowInsetsCompat.Type.systemBars())

        // Show all UI elements
        etServerIP.visibility = View.VISIBLE
        etPort.visibility = View.VISIBLE
        btnConnect.visibility = View.VISIBLE
        btnDisconnect.visibility = View.VISIBLE
        btnFullscreen.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE
        tvFrameInfo.visibility = View.VISIBLE
        tvResolution.visibility = View.VISIBLE

        // Reset image scaling
        ivFrame.scaleType = ImageView.ScaleType.FIT_CENTER

        updateFullscreenButton()
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private fun updateFullscreenButton() {
        mainHandler.post {
            btnFullscreen.isEnabled = isStreaming
            btnFullscreen.text = if (isFullscreen) "Exit Fullscreen" else "Fullscreen"
        }
    }

    // Throttled status updates
    private fun updateStatus(status: String) {
        val now = System.currentTimeMillis()
        if (now - lastStatusUpdate > 100) { // 10fps max for status updates
            lastStatusUpdate = now
            mainHandler.post {
                tvStatus.text = "Status: $status"
            }
        }
    }

    // Regular frame info update (for non-optimized calls)
    private fun updateFrameInfo(info: String) {
        mainHandler.post {
            tvFrameInfo.text = info
        }
    }

    // Enhanced frame info with decode timing and average decode time
    private fun updateOptimizedFrameInfo(frameId: Int, latency: Long, width: Int, height: Int, decodeTime: Long) {
        val fpsText = if (currentFPS > 0) String.format("%.1f", currentFPS) else "---"
        val avgDecodeText = if (avgDecodeTime > 0) String.format("%.1f", avgDecodeTime) else "---"

        // Add hardware acceleration info
        val hwAccelText = if (isHardwareAccelerationSupported) {
            val hwAvg = if (hardwareDecodeCount > 0) totalHardwareDecodeTime / hardwareDecodeCount else 0L
            val swAvg = if (softwareDecodeCount > 0) totalSoftwareDecodeTime / softwareDecodeCount else 0L
            " | HW: ${hwAvg}ms (${hardwareDecodeCount}) | SW: ${swAvg}ms (${softwareDecodeCount})"
        } else {
            " | SW: ${avgDecodeText}ms"
        }

        val info = "Frame: $frameId | ${width}x${height} | Net: ${latency}ms | Decode: ${decodeTime}ms (avg: ${avgDecodeText}ms) | ${fpsText} FPS$hwAccelText"

        if (droppedFrames > 0) {
            tvFrameInfo.text = "$info | Dropped: $droppedFrames"
        } else {
            tvFrameInfo.text = info
        }
    }

    private fun updateResolutionInfo() {
        mainHandler.post {
            val resolutionText = if (serverWidth == screenWidth && serverHeight == screenHeight) {
                "Resolution: ${screenWidth}x${screenHeight} @ ${refreshRate}Hz"
            } else {
                "Client: ${screenWidth}x${screenHeight} @ ${refreshRate}Hz\n" +
                        "Server: ${serverWidth}x${serverHeight} @ ${serverRefreshRate}Hz (fallback)"
            }
            tvResolution.text = resolutionText
        }
    }

    private fun updateServerResolution(width: Int, height: Int, refreshRate: Float) {
        serverWidth = width
        serverHeight = height
        serverRefreshRate = refreshRate
        updateResolutionInfo()
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

    // Optimized frame display with background decoding and frame dropping (now PNG instead of WebP)
    private fun displayFrame(pngData: ByteArray, frameId: Int, frameTime: Long, compressedSize: Int) {
        // Frame dropping: Skip if too many frames are pending decode
        if (pendingDecodes >= maxPendingFrames) {
            droppedFrames++
            if (droppedFrames % 5 == 0) {
                // Update status with frame dropping info
                updateStatus("Streaming ($pendingDecodes pending, dropped $droppedFrames frames)")
            }
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
                    // Only UI updates happen on main thread
                    mainHandler.post {
                        try {
                            // Recycle previous bitmap to prevent memory leaks
                            recycleBitmapSafely(previousBitmap)

                            // Display new frame
                            ivFrame.setImageBitmap(bitmap)
                            previousBitmap = bitmap

                            // Update FPS calculation
                            updateFPSCalculation()

                            // Throttled UI updates (max 60 FPS for UI)
                            val now = System.currentTimeMillis()
                            if (now - lastFrameInfoUpdate > 16) {
                                lastFrameInfoUpdate = now
                                updateOptimizedFrameInfo(frameId, frameTime, bitmap.width, bitmap.height, decodeTimeMs)
                            }
                        } catch (e: Exception) {
                            updateFrameInfo("Error displaying frame: ${e.message}")
                        }
                    }
                } else {
                    updateFrameInfo("Failed to decode PNG frame $frameId")
                }
            } catch (e: Exception) {
                updateFrameInfo("PNG decode error: ${e.message}")
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

        // Modified UDPReceiver with frame skipping logic
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
                updateStatus("Socket created. Starting handshake...")

                if (!performHandshake(serverAddress)) {
                    updateStatus("Handshake failed")
                    return
                }

                if (!requestStreaming(serverAddress)) {
                    updateStatus("Failed to start streaming")
                    return
                }

                // Streaming started successfully
                isStreaming = true
                updateFullscreenButton()

                val buffer = ByteArray(2048)
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket?.receive(packet)
                        processReceivedPacket(packet.data, packet.length)
                    } catch (e: SocketTimeoutException) {
                        updateStatus("Waiting for data...")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                updateStatus("Error: ${e.localizedMessage}")
            } finally {
                socket?.close()
                isStreaming = false
                updateFullscreenButton()
                updateStatus("Socket closed")
            }
        }

        private fun performHandshake(serverAddress: InetAddress): Boolean {
            try {
                updateStatus("Sending HELLO...")
                if (!sendMessage(serverAddress, "HELLO")) return false

                if (!waitForMessage("HELLO_ACK", 5000)) {
                    updateStatus("Did not receive HELLO_ACK")
                    return false
                }
                updateStatus("Received HELLO_ACK")

                val resolutionMsg = "RESOLUTION:$screenWidth:$screenHeight:$refreshRate"
                updateStatus("Sending resolution: ${screenWidth}x${screenHeight}@${refreshRate}Hz")
                if (!sendMessage(serverAddress, resolutionMsg)) return false

                val resolutionResponse = waitForResolutionResponse(15000)
                if (resolutionResponse == null) {
                    updateStatus("No resolution response from server")
                    return false
                }

                if (resolutionResponse == "RESOLUTION_ACK") {
                    updateStatus("Resolution accepted by server")
                } else if (resolutionResponse.startsWith("RESOLUTION_CHANGED:")) {
                    handleResolutionChanged(resolutionResponse)
                } else {
                    updateStatus("Unexpected resolution response: $resolutionResponse")
                    return false
                }

                val displayReadyMsg = waitForDisplayReady(15000)
                if (displayReadyMsg == null) {
                    updateStatus("Display setup timeout")
                    return false
                }

                updateStatus("Display ready: $displayReadyMsg")
                handshakeComplete = true
                displayReady = true

                return true

            } catch (e: Exception) {
                updateStatus("Handshake error: ${e.localizedMessage}")
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

                        updateServerResolution(width, height, refreshRate)
                        updateStatus("Server using fallback resolution: ${width}x${height}@${refreshRate}Hz")

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
                updateStatus("Error parsing resolution change: ${e.localizedMessage}")
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
                        updateStatus("Server error: $receivedMessage")
                        return null
                    }
                }
                return null
            } catch (e: SocketTimeoutException) {
                return null
            } catch (e: Exception) {
                updateStatus("Wait error: ${e.localizedMessage}")
                return null
            }
        }

        private fun requestStreaming(serverAddress: InetAddress): Boolean {
            try {
                updateStatus("Requesting stream start...")
                if (!sendMessage(serverAddress, "START_STREAM")) return false

                if (!waitForMessage("STREAM_STARTED", 5000)) {
                    updateStatus("Did not receive STREAM_STARTED")
                    return false
                }
                updateStatus("Streaming started")
                return true

            } catch (e: Exception) {
                updateStatus("Stream request error: ${e.localizedMessage}")
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
                updateStatus("Send error: ${e.localizedMessage}")
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
                        updateStatus("Server error: $receivedMessage")
                        return false
                    }
                }
                return false
            } catch (e: SocketTimeoutException) {
                return false
            } catch (e: Exception) {
                updateStatus("Wait error: ${e.localizedMessage}")
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
                        updateStatus("Server error: $receivedMessage")
                        return null
                    }
                }
                return null
            } catch (e: SocketTimeoutException) {
                return null
            } catch (e: Exception) {
                updateStatus("Display ready wait error: ${e.localizedMessage}")
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
                updateStatus("Packet processing error: ${e.localizedMessage}")
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
                        updateStatus("Frame info received: ${width}x${height} (PNG+DELTA)")
                    } else {
                        updateStatus("Frame info received: ${width}x${height} (PNG)")
                    }
                } else {
                    updateStatus("Invalid frame info format")
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
                    updateStatus("Invalid packet header")
                    return
                }

                if (header.frameId != currentFrameId) {
                    if (currentFrameId >= 0 && packetsReceived > 0) {
                        updateStatus("Frame ${currentFrameId}: ${packetsReceived}/${expectedPackets} packets")
                    }

                    currentFrameId = header.frameId
                    expectedPackets = header.totalPackets
                    packetsReceived = 0
                    framePackets.clear()
                    frameStartTime = System.currentTimeMillis()
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
                        updateStatus("Frame ${currentFrameId}: ${packetsReceived}/${expectedPackets}")
                    }
                }
            } catch (e: Exception) {
                updateStatus("Frame packet error: ${e.localizedMessage}")
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
                        updateStatus("Missing packet $packetId in frame $currentFrameId")
                        return
                    }
                }

                framesReceived++
                lastFrameDisplayTime = now

                // Detect delta region payload by magic 'DREG' prefix
                if (totalSize >= 4 &&
                    frameData[0] == 'D'.code.toByte() &&
                    frameData[1] == 'R'.code.toByte() &&
                    frameData[2] == 'E'.code.toByte() &&
                    frameData[3] == 'G'.code.toByte()) {
                    // Parse RegionHeader: x,y,w,h (uint16 BE), flags (u8), quality (u8), reserved (u16 BE)
                    if (totalSize < 4 + 2 + 2 + 2 + 2 + 1 + 1 + 2) {
                        updateStatus("Delta header too small")
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

                    // Decode region PNG and apply onto persistent bitmap
                    decodingExecutor?.submit {
                        val regionBitmap = decodeImage(pngBytes)
                        if (regionBitmap != null) {
                            mainHandler.post {
                                try {
                                    // Ensure base bitmap exists and has server size (mutable software bitmap)
                                    if (previousBitmap == null || previousBitmap!!.width != info.width || previousBitmap!!.height != info.height || previousBitmap!!.config != Bitmap.Config.ARGB_8888 || previousBitmap!!.isMutable == false) {
                                        recycleBitmapSafely(previousBitmap)
                                        previousBitmap = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
                                    }
                                    val base = previousBitmap!!

                                    // Ensure region bitmap is software-backed; if hardware, copy
                                    val safeRegion = if (regionBitmap.config != Bitmap.Config.ARGB_8888 || !regionBitmap.isMutable) {
                                        regionBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    } else regionBitmap

                                    val canvas = android.graphics.Canvas(base)
                                    canvas.drawBitmap(safeRegion, rx.toFloat(), ry.toFloat(), null)
                                    ivFrame.setImageBitmap(base)

                                    updateFPSCalculation()
                                    val now2 = System.currentTimeMillis()
                                    if (now2 - lastFrameInfoUpdate > 16) {
                                        lastFrameInfoUpdate = now2
                                        updateOptimizedFrameInfo(currentFrameId, frameTime, base.width, base.height, 0)
                                    }
                                } catch (e: Exception) {
                                    updateFrameInfo("Delta apply error: ${e.message}")
                                }
                            }
                        }
                    }
                } else {
                    // Full frame PNG path
                    displayFrame(frameData, currentFrameId, frameTime, totalSize)
                }

                if (framesReceived % 30 == 0) {
                    updateStatus("Received $framesReceived PNG frames")
                }

            } catch (e: Exception) {
                updateStatus("Frame reconstruction error: ${e.localizedMessage}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recycleBitmapSafely(previousBitmap)
        previousBitmap = null
        disconnectFromServer()

        // Shutdown decoding executor
        decodingExecutor?.shutdownNow()
        executorService?.shutdownNow()
    }

    override fun onBackPressed() {
        if (isFullscreen) {
            exitFullscreen()
        } else {
            super.onBackPressed()
        }
    }
}