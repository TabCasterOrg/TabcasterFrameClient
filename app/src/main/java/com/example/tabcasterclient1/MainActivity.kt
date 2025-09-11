package com.example.tabcasterclient1

import android.content.res.Configuration
import android.graphics.Bitmap

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.InetAddresses
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    private var portNumber = 23532;
    private lateinit var etServerIP: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button

    private lateinit var btnHide: Button

    private lateinit var tvStatus: TextView
    private lateinit var ivFrame: ImageView
    private lateinit var tvFrameInfo: TextView
    private lateinit var tvResolution: TextView
    private lateinit var bottomBar: LinearLayout

    private var udpReceiver: UDPReceiver? = null
    private var executorService: ExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Add dedicated thread pool for WebP decoding
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

    // Optimized bitmap options for WebP
    private val optimizedBitmapOptions = BitmapFactory.Options().apply {
        inMutable = true
        inPreferredConfig = Bitmap.Config.ARGB_8888 // Better for WebP decoding
        inSampleSize = 1
        inDither = false
        inPreferQualityOverSpeed = false // Prioritize speed
        inTempStorage = ByteArray(16 * 1024) // Reuse temp storage
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

        etServerIP = findViewById(R.id.serverInput)
        btnConnect = findViewById(R.id.connectButton)
        btnDisconnect = findViewById(R.id.disconnectButton)
        btnHide = findViewById(R.id.hideButton)



        initializeViews()
        // Auto-fill defaults
        etServerIP.setText(DEFAULT_IP)
        etPort.setText(DEFAULT_PORT.toString())

        setupClickListeners()

        executorService = Executors.newSingleThreadExecutor()
        // Initialize single decoding thread (one thread is better for sequential frame processing)
        decodingExecutor = Executors.newSingleThreadExecutor()

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
        ivFrame = findViewById(R.id.iv_frame) // This is the variable for the display itself
        tvFrameInfo = findViewById(R.id.tv_frame_info)
        tvResolution = findViewById(R.id.tv_resolution)

        bottomBar = findViewById(R.id.controlsLayout)

        executorService = Executors.newSingleThreadExecutor()

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

        btnHide.setOnClickListener {
            hideBar()
        }

        ivFrame.setOnClickListener {
            showBar()
        }

        updateStatus("Ready")
        updateFrameInfo("No frame data")
        updateResolutionInfo()

        // This is here to make the connection button update when a button is or isnt available. More responsive to the user.
        val serverTextWatcher : TextWatcher = object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) { return }

            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                //Toast.makeText(this, p0, Toast.LENGTH_SHORT).show()
                evaluateInput(p0)
            }

            override fun afterTextChanged(p0: Editable?) { return }
        }
        etServerIP.addTextChangedListener(serverTextWatcher)
        // Initially hide disconnect button
        // TODO: Turn The Connect Button Into The Disconnect Button When Connected
        btnDisconnect.visibility = View.GONE
        // TODO: Figure Out Why The Center Text Does Not Update
        //binding.centerText.text = introText // Change the text

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

        // Initially, server resolution matches client
        serverWidth = screenWidth
        serverHeight = screenHeight
        serverRefreshRate = refreshRate
    }

    // This is designed to grey out the connect button if the IP address is not valid. The user can still press the button to explain IP addresses.
    private fun evaluateInput(input : CharSequence?){
        // If an IP Address is valid, show 'Connect'
        if (input != null && InetAddresses.isNumericAddress(input.toString())){
            // TODO: "Update the background colour and icon when a valid or nonvalid IP is entered"
            btnConnect.setText("Connect") // Change the text to connect when we have an IP address that we an connect to.
        }
        // If it isn't, show 'Help'
        else {
            btnConnect.setText("Help") // If we do not have an IP address, show the 'help'text.
        }
    }

    private fun connectToServer() {
        val serverIP = etServerIP.text.toString().trim()

        if (serverIP.isEmpty()) {
            Toast.makeText(this, "Please enter server IP", Toast.LENGTH_SHORT).show()
            return
        }
        else if (InetAddresses.isNumericAddress(serverIP)){

            Toast.makeText(this, "Connecting to: $serverIP", Toast.LENGTH_SHORT).show()
            btnDisconnect.visibility = View.VISIBLE

            // Start Sending UDP Packets!
            udpReceiver = UDPReceiver(serverIP, portNumber)
            executorService?.submit(udpReceiver)

            btnConnect.isEnabled = false
            btnDisconnect.isEnabled = true
            etServerIP.isEnabled = false

            updateStatus("Connecting to $serverIP")
        } else {
            // This is a dialog box, to explain how to input the IP address.
            val builder: AlertDialog.Builder =
                AlertDialog.Builder(this) // Use 'this as the context.
            builder.setTitle("Invalid IP Address")
            builder.setMessage("$serverIP is not a valid IP address.\nIP Addresses usually follow the format of XXX.XXX.XX.XXX:XXXX\n\nTo retreive your IP address on Linux systems with NetworkManager installed, use `nmcli` in the Terminal to find your IP address.\nWhen a valid IP address is entered, the help button will become the connect button, and you can attempt to connect to TabCaster.")
            val dialog: AlertDialog = builder.create()
            dialog.show()
        }

        serverWidth = screenWidth
        serverHeight = screenHeight
        serverRefreshRate = refreshRate
    }



    private fun disconnectFromServer() {
        udpReceiver?.stop()
        udpReceiver = null
        isStreaming = false

        // Clean up bitmap
        previousBitmap?.recycle()
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

        btnConnect.isEnabled = true
        btnDisconnect.isEnabled = false
        etServerIP.isEnabled = true

        updateStatus("Disconnected")
        updateFrameInfo("No frame data")
        updateFullscreenButton()

        // Reset server resolution to match client
        serverWidth = screenWidth
        serverHeight = screenHeight
        serverRefreshRate = refreshRate
        updateResolutionInfo()

        // Reset server resolution to match client
        serverWidth = screenWidth
        serverHeight = screenHeight
        serverRefreshRate = refreshRate
        updateResolutionInfo()

        // Clear the image view
        mainHandler.post {
            ivFrame.setImageBitmap(null)
        }

        // Re-disable the Disconnect button
        btnDisconnect.visibility = View.GONE

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

        val info = "Frame: $frameId | ${width}x${height} | Net: ${latency}ms | Decode: ${decodeTime}ms (avg: ${avgDecodeText}ms) | ${fpsText} FPS"

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

    private fun displayFrame(bitmap: Bitmap, frameId: Int, frameTime: Long) {
        mainHandler.post {
            ivFrame.setImageBitmap(bitmap)
            updateFrameInfo("Frame $frameId (${bitmap.width}x${bitmap.height}) - ${frameTime}ms")
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

    // Optimized frame display with background decoding and frame dropping
    private fun displayFrame(webpData: ByteArray, frameId: Int, frameTime: Long, compressedSize: Int) {
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

        // Decode WebP on background thread to avoid blocking UI
        decodingExecutor?.submit {
            val decodeStartTime = System.nanoTime()

            try {
                // WebP decoding happens on background thread
                val bitmap = BitmapFactory.decodeByteArray(webpData, 0, webpData.size, optimizedBitmapOptions)

                val decodeTimeMs = (System.nanoTime() - decodeStartTime) / 1_000_000

                // Update decode time statistics
                synchronized(this) {
                    totalDecodeTime += decodeTimeMs
                    decodedFrameCount++
                    avgDecodeTime = totalDecodeTime.toFloat() / decodedFrameCount
                }

                if (bitmap != null) {
                    // Only UI updates happen on main thread
                    mainHandler.post {
                        try {
                            // Recycle previous bitmap to prevent memory leaks
                            previousBitmap?.recycle()

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
                    updateFrameInfo("Failed to decode WebP frame $frameId")
                }
            } catch (e: Exception) {
                updateFrameInfo("WebP decode error: ${e.message}")
            } finally {
                pendingDecodes--
            }
        }
    }

    // Show And Hide Bar
    private fun hideBar(){
        if (bottomBar.visibility == View.VISIBLE){
            bottomBar.visibility = View.GONE
            Toast.makeText(this, "Hiding controls bar. Tap the screen to show again", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBar(){
        if (bottomBar.visibility == View.GONE){
            bottomBar.visibility = View.VISIBLE
            Toast.makeText(this, "Showing controls bar", Toast.LENGTH_SHORT).show()
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

                // Wait for RESOLUTION_ACK or RESOLUTION_CHANGED
                val resolutionResponse = waitForResolutionResponse(15000)
                if (resolutionResponse == null) {
                    updateStatus("No resolution response from server")
                    return false
                }

                // Handle resolution response
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
            // Parse "RESOLUTION_CHANGED:1920x1080:60.0"
            try {
                val parts = message.split(":")
                if (parts.size >= 3 && parts[0] == "RESOLUTION_CHANGED") {
                    val resolutionPart = parts[1] // "1920x1080"
                    val refreshRatePart = parts[2] // "60.0"


                    val resolutionParts = resolutionPart.split("x")
                    if (resolutionParts.size == 2) {
                        val width = resolutionParts[0].toInt()
                        val height = resolutionParts[1].toInt()
                        val refreshRate = refreshRatePart.toFloat()

                        updateServerResolution(width, height, refreshRate)
                        updateStatus("Server using fallback resolution: ${width}x${height}@${refreshRate}Hz")

                        // Show a toast to notify user

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
                    // Continue waiting for the right message
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
                    updateStatus("Frame info received: ${width}x${height} (WebP)")
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

                // Send to optimized display function
                displayFrame(frameData, currentFrameId, frameTime, totalSize)

                if (framesReceived % 30 == 0) {
                    updateStatus("Received $framesReceived WebP frames")
                }

            } catch (e: Exception) {
                updateStatus("Frame reconstruction error: ${e.localizedMessage}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        previousBitmap?.recycle()
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