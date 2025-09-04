package com.example.tabcasterclient1

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
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
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
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
    private lateinit var controlsLayout: LinearLayout

    private var udpReceiver: UDPReceiver? = null
    private var executorService: ExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Screen resolution info
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var refreshRate: Float = 60.0f

    // Server resolution (might be different from client if fallback used)
    private var serverWidth: Int = 0
    private var serverHeight: Int = 0
    private var serverRefreshRate: Float = 60.0f

    // Fullscreen state
    private var isFullscreen = false
    private var windowInsetsController: WindowInsetsControllerCompat? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupWindowInsets()
        executorService = Executors.newSingleThreadExecutor()

        // Get screen resolution
        getScreenResolution()

        setupClickListeners()
        updateStatus("Ready")
        updateFrameInfo("No frame data")
        updateResolutionInfo()
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
        controlsLayout = findViewById(R.id.controls_layout)

        // Configure ImageView for better scaling
        ivFrame.scaleType = ImageView.ScaleType.MATRIX
        ivFrame.adjustViewBounds = true

        // Set initial fullscreen button text
        btnFullscreen.text = "Fullscreen"
        btnFullscreen.isEnabled = false // Enable only when streaming
    }

    private fun setupWindowInsets() {
        windowInsetsController = ViewCompat.getWindowInsetsController(window.decorView)
        windowInsetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupClickListeners() {
        btnConnect.setOnClickListener {
            connectToServer()
        }

        btnDisconnect.setOnClickListener {
            disconnectFromServer()
        }

        btnFullscreen.setOnClickListener {
            toggleFullscreen()
        }

        // Double-tap ImageView to toggle fullscreen
        ivFrame.setOnClickListener {
            if (btnFullscreen.isEnabled) {
                toggleFullscreen()
            }
        }
    }

    private fun toggleFullscreen() {
        if (isFullscreen) {
            exitFullscreen()
        } else {
            enterFullscreen()
        }
    }

    private fun enterFullscreen() {
        isFullscreen = true

        // Hide system bars
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())

        // Hide controls
        controlsLayout.visibility = View.GONE

        // Make ImageView fill the screen and center the image
        val layoutParams = ivFrame.layoutParams
        layoutParams.width = LinearLayout.LayoutParams.MATCH_PARENT
        layoutParams.height = LinearLayout.LayoutParams.MATCH_PARENT
        ivFrame.layoutParams = layoutParams
        ivFrame.scaleType = ImageView.ScaleType.FIT_CENTER

        btnFullscreen.text = "Exit Fullscreen"

        Toast.makeText(this, "Fullscreen mode - tap to exit", Toast.LENGTH_SHORT).show()
    }

    private fun exitFullscreen() {
        isFullscreen = false

        // Show system bars
        windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())

        // Show controls
        controlsLayout.visibility = View.VISIBLE

        // Reset ImageView size and scaling
        val layoutParams = ivFrame.layoutParams
        layoutParams.width = LinearLayout.LayoutParams.MATCH_PARENT
        layoutParams.height = 0 // Use layout_weight instead
        ivFrame.layoutParams = layoutParams
        ivFrame.scaleType = ImageView.ScaleType.FIT_CENTER

        btnFullscreen.text = "Fullscreen"
    }

    private fun getScreenResolution() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val displayMetrics = DisplayMetrics()

        // Get real display metrics (including navigation bar, status bar, etc.)
        display.getRealMetrics(displayMetrics)

        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels

        // Get refresh rate
        refreshRate = display.refreshRate

        // For landscape orientation, ensure width > height
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (screenWidth < screenHeight) {
                val temp = screenWidth
                screenWidth = screenHeight
                screenHeight = temp
            }
        }

        // Initially, server resolution matches client
        serverWidth = screenWidth
        serverHeight = screenHeight
        serverRefreshRate = refreshRate
    }

    private fun connectToServer() {
        val serverIP = etServerIP.text.toString().trim()
        val portStr = etPort.text.toString().trim()

        if (serverIP.isEmpty() || portStr.isEmpty()) {
            Toast.makeText(this, "Please enter server IP and port", Toast.LENGTH_SHORT).show()
            return
        }

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
        btnFullscreen.isEnabled = true
        etServerIP.isEnabled = false
        etPort.isEnabled = false

        updateStatus("Connecting to $serverIP:$port")
    }

    private fun disconnectFromServer() {
        udpReceiver?.stop()
        udpReceiver = null

        // Exit fullscreen if active
        if (isFullscreen) {
            exitFullscreen()
        }

        btnConnect.isEnabled = true
        btnDisconnect.isEnabled = false
        btnFullscreen.isEnabled = false
        etServerIP.isEnabled = true
        etPort.isEnabled = true

        updateStatus("Disconnected")
        updateFrameInfo("No frame data")

        // Reset server resolution to match client
        serverWidth = screenWidth
        serverHeight = screenHeight
        serverRefreshRate = refreshRate
        updateResolutionInfo()

        // Clear the image view
        mainHandler.post {
            ivFrame.setImageBitmap(null)
        }
    }

    private fun updateStatus(status: String) {
        mainHandler.post {
            tvStatus.text = "Status: $status"
        }
    }

    private fun updateFrameInfo(info: String) {
        mainHandler.post {
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

    private fun displayFrame(bitmap: Bitmap, frameId: Int, frameTime: Long) {
        mainHandler.post {
            // Scale the bitmap to fit the display properly
            val scaledBitmap = scaleBitmapToFit(bitmap)
            ivFrame.setImageBitmap(scaledBitmap)

            updateFrameInfo("Frame $frameId (${bitmap.width}x${bitmap.height}) - ${frameTime}ms")
        }
    }

    private fun scaleBitmapToFit(originalBitmap: Bitmap): Bitmap {
        // Get ImageView dimensions
        val viewWidth = ivFrame.width
        val viewHeight = ivFrame.height

        // If ImageView not measured yet, return original
        if (viewWidth <= 0 || viewHeight <= 0) {
            return originalBitmap
        }

        val originalWidth = originalBitmap.width.toFloat()
        val originalHeight = originalBitmap.height.toFloat()

        // Calculate scale factors
        val scaleX = viewWidth / originalWidth
        val scaleY = viewHeight / originalHeight
        val scaleFactor = minOf(scaleX, scaleY) // Use smaller scale to maintain aspect ratio

        // Calculate new dimensions
        val newWidth = (originalWidth * scaleFactor).toInt()
        val newHeight = (originalHeight * scaleFactor).toInt()

        // Only scale if necessary (avoid unnecessary scaling)
        return if (scaleFactor != 1.0f && newWidth > 0 && newHeight > 0) {
            Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)
        } else {
            originalBitmap
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

    data class XImageInfo(
        val width: Int,
        val height: Int,
        val depth: Int,
        val bitsPerPixel: Int,
        val bytesPerLine: Int,
        val bitmapUnit: Int,
        val bitmapBitOrder: Int,
        val redMask: Long,
        val greenMask: Long,
        val blueMask: Long
    )

    private inner class UDPReceiver(
        private val serverIP: String,
        private val serverPort: Int
    ) : Runnable {

        @Volatile
        private var running = true
        private var socket: DatagramSocket? = null
        private var frameInfo: FrameInfo? = null
        private var xImageInfo: XImageInfo? = null
        private val framePackets = mutableMapOf<Int, ByteArray>()
        private var expectedPackets = 0
        private var currentFrameId = -1
        private var packetsReceived = 0
        private var frameStartTime = 0L
        private var framesReceived = 0
        private var handshakeComplete = false
        private var displayReady = false

        fun stop() {
            running = false
            socket?.close()
        }

        override fun run() {
            try {
                // Create socket
                socket = DatagramSocket()
                socket?.soTimeout = 10000 // 10 seconds timeout

                val serverAddress = InetAddress.getByName(serverIP)
                updateStatus("Socket created. Starting handshake...")

                // Handshake Phase
                if (!performHandshake(serverAddress)) {
                    updateStatus("Handshake failed")
                    return
                }

                // Start streaming
                if (!requestStreaming(serverAddress)) {
                    updateStatus("Failed to start streaming")
                    return
                }

                // Main receiving loop
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
                updateStatus("Socket closed")
            }
        }

        // ... Keep all existing handshake methods unchanged ...
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

        // ... Keep other existing methods (handleResolutionChanged, waitForResolutionResponse, etc.) ...

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

                // Check for XImage info packet
                if (dataStr.startsWith("XIMAGE_INFO:")) {
                    handleXImageInfo(dataStr)
                    return
                }

                // Check for legacy INFO packet (fallback)
                if (dataStr.startsWith("INFO:")) {
                    handleFrameInfo(dataStr)
                    return
                }

                // Handle frame data packets
                if (length >= 16) {
                    handleFramePacket(data, length)
                }
            } catch (e: Exception) {
                updateStatus("Packet processing error: ${e.localizedMessage}")
            }
        }

        private fun handleXImageInfo(infoPacket: String) {
            try {
                val parts = infoPacket.split(":")
                if (parts.size >= 11 && parts[0] == "XIMAGE_INFO") {
                    xImageInfo = XImageInfo(
                        width = parts[1].toInt(),
                        height = parts[2].toInt(),
                        depth = parts[3].toInt(),
                        bitsPerPixel = parts[4].toInt(),
                        bytesPerLine = parts[5].toInt(),
                        bitmapUnit = parts[6].toInt(),
                        bitmapBitOrder = parts[7].toInt(),
                        redMask = parts[8].toLong(),
                        greenMask = parts[9].toLong(),
                        blueMask = parts[10].toLong()
                    )

                    frameInfo = FrameInfo(xImageInfo!!.width, xImageInfo!!.height)
                    updateStatus("XImage info received: ${xImageInfo!!.width}x${xImageInfo!!.height}, ${xImageInfo!!.bitsPerPixel} bpp")
                } else {
                    updateStatus("Invalid XImage info format")
                }
            } catch (e: Exception) {
                updateStatus("XImage info parse error: ${e.localizedMessage}")
            }
        }

        private fun handleFrameInfo(infoPacket: String) {
            val parts = infoPacket.split(":")
            if (parts.size == 3 && parts[0] == "INFO") {
                val width = parts[1].toIntOrNull()
                val height = parts[2].toIntOrNull()
                if (width != null && height != null) {
                    frameInfo = FrameInfo(width, height)
                    xImageInfo = null
                    updateStatus("RGB frame info received: ${width}x${height}")
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
                } else if (packetsReceived % 50 == 0) {
                    updateStatus("Frame ${currentFrameId}: ${packetsReceived}/${expectedPackets}")
                }
            } catch (e: Exception) {
                updateStatus("Frame packet error: ${e.localizedMessage}")
            }
        }

        private fun reconstructAndDisplayFrame(frameTime: Long) {
            try {
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

                val bitmap = if (xImageInfo != null) {
                    createBitmapFromXImage(frameData, xImageInfo!!)
                } else if (frameInfo != null) {
                    createBitmapFromRGB(frameData, frameInfo!!.width, frameInfo!!.height)
                } else {
                    updateStatus("No format info available for frame reconstruction")
                    return
                }

                if (bitmap != null) {
                    framesReceived++
                    displayFrame(bitmap, currentFrameId, frameTime)

                    if (framesReceived % 10 == 0) {
                        updateStatus("Received $framesReceived frames")
                    }
                } else {
                    updateStatus("Failed to create bitmap for frame $currentFrameId")
                }

            } catch (e: Exception) {
                updateStatus("Frame reconstruction error: ${e.localizedMessage}")
            }
        }

        // ... Keep all existing bitmap creation methods (createBitmapFromXImage, createBitmapFromRGB, etc.) ...

        private fun createBitmapFromXImage(xImageData: ByteArray, xImg: XImageInfo): Bitmap? {
            try {
                val expectedSize = xImg.bytesPerLine * xImg.height
                if (xImageData.size != expectedSize) {
                    updateStatus("XImage data size mismatch: got ${xImageData.size}, expected $expectedSize")
                    return null
                }

                val bitmap = Bitmap.createBitmap(xImg.width, xImg.height, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(xImg.width * xImg.height)

                when (xImg.bitsPerPixel) {
                    32 -> convertXImage32bpp(xImageData, pixels, xImg)
                    24 -> convertXImage24bpp(xImageData, pixels, xImg)
                    16 -> convertXImage16bpp(xImageData, pixels, xImg)
                    else -> {
                        updateStatus("Unsupported XImage format: ${xImg.bitsPerPixel} bpp")
                        return null
                    }
                }

                bitmap.setPixels(pixels, 0, xImg.width, 0, 0, xImg.width, xImg.height)
                return bitmap

            } catch (e: Exception) {
                updateStatus("XImage bitmap creation error: ${e.localizedMessage}")
                return null
            }
        }

        private fun convertXImage32bpp(data: ByteArray, pixels: IntArray, xImg: XImageInfo) {
            val bytesPerPixel = 4
            var pixelIndex = 0

            val redShift = getShiftFromMask(xImg.redMask)
            val greenShift = getShiftFromMask(xImg.greenMask)
            val blueShift = getShiftFromMask(xImg.blueMask)

            for (y in 0 until xImg.height) {
                var lineOffset = y * xImg.bytesPerLine

                for (x in 0 until xImg.width) {
                    val pixel = ((data[lineOffset + 3].toInt() and 0xFF) shl 24) or
                            ((data[lineOffset + 2].toInt() and 0xFF) shl 16) or
                            ((data[lineOffset + 1].toInt() and 0xFF) shl 8) or
                            (data[lineOffset].toInt() and 0xFF)

                    val r = ((pixel and xImg.redMask.toInt()) ushr redShift) and 0xFF
                    val g = ((pixel and xImg.greenMask.toInt()) ushr greenShift) and 0xFF
                    val b = ((pixel and xImg.blueMask.toInt()) ushr blueShift) and 0xFF

                    pixels[pixelIndex++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    lineOffset += bytesPerPixel
                }
            }
        }

        private fun convertXImage24bpp(data: ByteArray, pixels: IntArray, xImg: XImageInfo) {
            val bytesPerPixel = 3
            var pixelIndex = 0

            for (y in 0 until xImg.height) {
                var lineOffset = y * xImg.bytesPerLine

                for (x in 0 until xImg.width) {
                    val r = data[lineOffset].toInt() and 0xFF
                    val g = data[lineOffset + 1].toInt() and 0xFF
                    val b = data[lineOffset + 2].toInt() and 0xFF

                    pixels[pixelIndex++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    lineOffset += bytesPerPixel
                }
            }
        }

        private fun convertXImage16bpp(data: ByteArray, pixels: IntArray, xImg: XImageInfo) {
            val bytesPerPixel = 2
            var pixelIndex = 0

            for (y in 0 until xImg.height) {
                var lineOffset = y * xImg.bytesPerLine

                for (x in 0 until xImg.width) {
                    // Read 16-bit pixel (little-endian)
                    val pixel = ((data[lineOffset + 1].toInt() and 0xFF) shl 8) or
                            (data[lineOffset].toInt() and 0xFF)

                    // Common 16-bit formats: 565 RGB or 555 RGB
                    val r: Int
                    val g: Int
                    val b: Int

                    if (xImg.redMask == 0xF800L && xImg.greenMask == 0x07E0L && xImg.blueMask == 0x001FL) {
                        // 565 RGB format
                        r = ((pixel and 0xF800) shr 8) or ((pixel and 0xF800) shr 13)
                        g = ((pixel and 0x07E0) shr 3) or ((pixel and 0x07E0) shr 9)
                        b = ((pixel and 0x001F) shl 3) or ((pixel and 0x001F) shr 2)
                    } else {
                        // 555 RGB format or other - use generic mask extraction
                        val redShift = getShiftFromMask(xImg.redMask)
                        val greenShift = getShiftFromMask(xImg.greenMask)
                        val blueShift = getShiftFromMask(xImg.blueMask)

                        r = ((pixel and xImg.redMask.toInt()) ushr redShift) shl 3
                        g = ((pixel and xImg.greenMask.toInt()) ushr greenShift) shl 3
                        b = ((pixel and xImg.blueMask.toInt()) ushr blueShift) shl 3
                    }

                    pixels[pixelIndex++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

                    lineOffset += bytesPerPixel
                }
            }
        }

        private fun getShiftFromMask(mask: Long): Int {
            if (mask == 0L) return 0
            var shift = 0
            var tempMask = mask
            while ((tempMask and 1L) == 0L) {
                tempMask = tempMask ushr 1
                shift++
            }
            return shift
        }

        // Keep existing createBitmapFromRGB method for fallback RGB support
        private fun createBitmapFromRGB(rgbData: ByteArray, width: Int, height: Int): Bitmap? {
            try {
                val expectedSize = width * height * 3
                if (rgbData.size != expectedSize) {
                    updateStatus("RGB data size mismatch: got ${rgbData.size}, expected $expectedSize")
                    return null
                }

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(width * height)

                for (i in pixels.indices) {
                    val rgbIndex = i * 3
                    val r = rgbData[rgbIndex].toInt() and 0xFF
                    val g = rgbData[rgbIndex + 1].toInt() and 0xFF
                    val b = rgbData[rgbIndex + 2].toInt() and 0xFF
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }

                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                return bitmap

            } catch (e: Exception) {
                updateStatus("RGB bitmap creation error: ${e.localizedMessage}")
                return null
            }
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        disconnectFromServer()
        executorService?.shutdownNow()
    }
}