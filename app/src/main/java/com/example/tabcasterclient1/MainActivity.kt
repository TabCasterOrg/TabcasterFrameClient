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
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
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

class MainActivity : AppCompatActivity() {

    private lateinit var etServerIP: EditText
    private lateinit var etPort: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var tvStatus: TextView
    private lateinit var ivFrame: ImageView
    private lateinit var tvFrameInfo: TextView
    private lateinit var tvResolution: TextView

    private var udpReceiver: UDPReceiver? = null
    private var executorService: ExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Screen resolution info
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var refreshRate: Float = 60.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etServerIP = findViewById(R.id.et_server_ip)
        etPort = findViewById(R.id.et_port)
        btnConnect = findViewById(R.id.btn_connect)
        btnDisconnect = findViewById(R.id.btn_disconnect)
        tvStatus = findViewById(R.id.tv_status)
        ivFrame = findViewById(R.id.iv_frame)
        tvFrameInfo = findViewById(R.id.tv_frame_info)
        tvResolution = findViewById(R.id.tv_resolution) // Add this TextView to your layout

        executorService = Executors.newSingleThreadExecutor()

        // Get screen resolution
        getScreenResolution()

        btnConnect.setOnClickListener {
            connectToServer()
        }

        btnDisconnect.setOnClickListener {
            disconnectFromServer()
        }

        updateStatus("Ready")
        updateFrameInfo("No frame data")
        updateResolutionInfo()
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
        etServerIP.isEnabled = false
        etPort.isEnabled = false

        updateStatus("Connecting to $serverIP:$port")
    }

    private fun disconnectFromServer() {
        udpReceiver?.stop()
        udpReceiver = null

        btnConnect.isEnabled = true
        btnDisconnect.isEnabled = false
        etServerIP.isEnabled = true
        etPort.isEnabled = true

        updateStatus("Disconnected")
        updateFrameInfo("No frame data")

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
            tvResolution.text = "Device Resolution: ${screenWidth}x${screenHeight} @ ${refreshRate}Hz"
        }
    }

    private fun displayFrame(bitmap: Bitmap, frameId: Int, frameTime: Long) {
        mainHandler.post {
            ivFrame.setImageBitmap(bitmap)
            updateFrameInfo("Frame $frameId (${bitmap.width}x${bitmap.height}) - ${frameTime}ms")
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

        private fun performHandshake(serverAddress: InetAddress): Boolean {
            try {
                // Step 1: Send HELLO
                updateStatus("Sending HELLO...")
                if (!sendMessage(serverAddress, "HELLO")) return false

                // Wait for HELLO_ACK
                if (!waitForMessage("HELLO_ACK", 5000)) {
                    updateStatus("Did not receive HELLO_ACK")
                    return false
                }
                updateStatus("Received HELLO_ACK")

                // Step 2: Send resolution information
                val resolutionMsg = "RESOLUTION:$screenWidth:$screenHeight:$refreshRate"
                updateStatus("Sending resolution: ${screenWidth}x${screenHeight}@${refreshRate}Hz")
                if (!sendMessage(serverAddress, resolutionMsg)) return false

                // Wait for RESOLUTION_ACK
                if (!waitForMessage("RESOLUTION_ACK", 10000)) {
                    updateStatus("Did not receive RESOLUTION_ACK")
                    return false
                }
                updateStatus("Resolution acknowledged by server")

                // Wait for DISPLAY_READY message
                val displayReadyMsg = waitForDisplayReady(15000)
                if (displayReadyMsg == null) {
                    updateStatus("Display setup timeout")
                    return false
                }

                // Parse display ready message: "DISPLAY_READY:output:widthxheight:x:y"
                updateStatus("Display ready: $displayReadyMsg")
                handshakeComplete = true
                displayReady = true

                return true

            } catch (e: Exception) {
                updateStatus("Handshake error: ${e.localizedMessage}")
                return false
            }
        }

        private fun requestStreaming(serverAddress: InetAddress): Boolean {
            try {
                updateStatus("Requesting stream start...")
                if (!sendMessage(serverAddress, "START_STREAM")) return false

                // Wait for STREAM_STARTED
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
                // Check if this is a frame info packet (starts with "INFO:")
                val dataStr = String(data, 0, length)
                if (dataStr.startsWith("INFO:")) {
                    handleFrameInfo(dataStr)
                    return
                }

                // Check if this is a frame data packet (has PacketHeader)
                if (length >= 16) { // Minimum size for PacketHeader (4 * 4 bytes)
                    handleFramePacket(data, length)
                }
            } catch (e: Exception) {
                updateStatus("Packet processing error: ${e.localizedMessage}")
            }
        }

        private fun handleFrameInfo(infoPacket: String) {
            // Parse "INFO:width:height"
            val parts = infoPacket.split(":")
            if (parts.size == 3 && parts[0] == "INFO") {
                val width = parts[1].toIntOrNull()
                val height = parts[2].toIntOrNull()
                if (width != null && height != null) {
                    frameInfo = FrameInfo(width, height)
                    updateStatus("Frame info received: ${width}x${height}")
                } else {
                    updateStatus("Invalid frame info format")
                }
            }
        }

        private fun handleFramePacket(data: ByteArray, length: Int) {
            try {
                // Parse PacketHeader (assuming network byte order - big-endian)
                val buffer = ByteBuffer.wrap(data)
                buffer.order(ByteOrder.BIG_ENDIAN) // Server uses network byte order

                val header = PacketHeader(
                    frameId = buffer.int,
                    packetId = buffer.int,
                    totalPackets = buffer.int,
                    dataSize = buffer.int
                )

                // Validate header
                if (header.dataSize < 0 || header.dataSize > length - 16) {
                    updateStatus("Invalid packet header")
                    return
                }

                // Handle new frame
                if (header.frameId != currentFrameId) {
                    // New frame started
                    if (currentFrameId >= 0 && packetsReceived > 0) {
                        updateStatus("Frame ${currentFrameId}: ${packetsReceived}/${expectedPackets} packets")
                    }

                    currentFrameId = header.frameId
                    expectedPackets = header.totalPackets
                    packetsReceived = 0
                    framePackets.clear()
                    frameStartTime = System.currentTimeMillis()
                }

                // Store packet data
                val packetData = ByteArray(header.dataSize)
                buffer.get(packetData, 0, header.dataSize)
                framePackets[header.packetId] = packetData
                packetsReceived++

                // Check if frame is complete
                if (packetsReceived == expectedPackets) {
                    val frameTime = System.currentTimeMillis() - frameStartTime
                    reconstructAndDisplayFrame(frameTime)
                } else {
                    // Periodic update for large frames
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
                // Calculate total frame size
                val totalSize = framePackets.values.sumOf { it.size }
                val rgbData = ByteArray(totalSize)

                // Reconstruct frame by concatenating packets in order
                var offset = 0
                for (packetId in 0 until expectedPackets) {
                    val packetData = framePackets[packetId]
                    if (packetData != null) {
                        System.arraycopy(packetData, 0, rgbData, offset, packetData.size)
                        offset += packetData.size
                    } else {
                        updateStatus("Missing packet $packetId in frame $currentFrameId")
                        return
                    }
                }

                // Convert RGB data to Bitmap
                val bitmap = createBitmapFromRGB(rgbData, info.width, info.height)
                if (bitmap != null) {
                    framesReceived++
                    displayFrame(bitmap, currentFrameId, frameTime)

                    // Update status every 10 frames
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

        private fun createBitmapFromRGB(rgbData: ByteArray, width: Int, height: Int): Bitmap? {
            try {
                // Verify data size
                val expectedSize = width * height * 3 // 3 bytes per pixel (RGB)
                if (rgbData.size != expectedSize) {
                    updateStatus("RGB data size mismatch: got ${rgbData.size}, expected $expectedSize")
                    return null
                }

                // Create bitmap
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                // Convert RGB to ARGB pixels
                val pixels = IntArray(width * height)
                for (i in pixels.indices) {
                    val rgbIndex = i * 3
                    val r = rgbData[rgbIndex].toInt() and 0xFF
                    val g = rgbData[rgbIndex + 1].toInt() and 0xFF
                    val b = rgbData[rgbIndex + 2].toInt() and 0xFF
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b // ARGB format
                }

                // Set pixels to bitmap
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

                return bitmap

            } catch (e: Exception) {
                updateStatus("Bitmap creation error: ${e.localizedMessage}")
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