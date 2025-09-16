package com.example.tabcasterclient1

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    // Persistent bitmap state
    private var backingBitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    // UI throttling
    @Volatile private var uiDirty = false
    @Volatile private var uiTickerRunning = false
    private var lastInfoUpdateMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupWindowInsets()
        executorService = Executors.newSingleThreadExecutor()

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

        ivFrame.scaleType = ImageView.ScaleType.MATRIX
        ivFrame.adjustViewBounds = true

        btnFullscreen.text = "Fullscreen"
        btnFullscreen.isEnabled = false

        // Defaults
        etServerIP.setText("10.1.10.105")
        etPort.setText("23532")
    }

    private fun setupWindowInsets() {
        windowInsetsController = ViewCompat.getWindowInsetsController(window.decorView)
        windowInsetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupClickListeners() {
        btnConnect.setOnClickListener { connectToServer() }
        btnDisconnect.setOnClickListener { disconnectFromServer() }
        btnFullscreen.setOnClickListener { toggleFullscreen() }
        ivFrame.setOnClickListener { if (btnFullscreen.isEnabled) toggleFullscreen() }
    }

    private fun toggleFullscreen() {
        if (isFullscreen) exitFullscreen() else enterFullscreen()
    }

    private fun enterFullscreen() {
        isFullscreen = true
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        controlsLayout.visibility = View.GONE
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
        windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
        controlsLayout.visibility = View.VISIBLE
        val layoutParams = ivFrame.layoutParams
        layoutParams.width = LinearLayout.LayoutParams.MATCH_PARENT
        layoutParams.height = 0
        ivFrame.layoutParams = layoutParams
        ivFrame.scaleType = ImageView.ScaleType.FIT_CENTER
        btnFullscreen.text = "Fullscreen"
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
        val serverIP = etServerIP.text.toString().trim()
        val portStr = etPort.text.toString().trim()
        if (serverIP.isEmpty() || portStr.isEmpty()) {
            Toast.makeText(this, "Please enter server IP and port", Toast.LENGTH_SHORT).show()
            return
        }
        val port = try { portStr.toInt() } catch (_: NumberFormatException) {
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

        if (isFullscreen) exitFullscreen()

        btnConnect.isEnabled = true
        btnDisconnect.isEnabled = false
        btnFullscreen.isEnabled = false
        etServerIP.isEnabled = true
        etPort.isEnabled = true

        updateStatus("Disconnected")
        updateFrameInfo("No frame data")

        serverWidth = screenWidth
        serverHeight = screenHeight
        serverRefreshRate = refreshRate
        updateResolutionInfo()

        stopUiTicker()
        mainHandler.post { ivFrame.setImageBitmap(null) }
        backingBitmap = null
    }

    private fun updateStatus(status: String) {
        mainHandler.post { tvStatus.text = "Status: $status" }
    }

    private fun updateFrameInfo(info: String) {
        mainHandler.post { tvFrameInfo.text = info }
    }

    private fun updateResolutionInfo() {
        mainHandler.post {
            val resolutionText =
                if (serverWidth == screenWidth && serverHeight == screenHeight) {
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

    private fun ensureBackingBitmap(width: Int, height: Int) {
        val bmp = backingBitmap
        if (bmp == null || bmp.width != width || bmp.height != height) {
            backingBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            mainHandler.post { ivFrame.setImageBitmap(backingBitmap) }
        }
    }

    private fun startUiTicker() {
        if (uiTickerRunning) return
        uiTickerRunning = true
        val choreographer = android.view.Choreographer.getInstance()
        val callback = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!uiTickerRunning) return
                if (uiDirty) {
                    ivFrame.invalidate()
                    uiDirty = false
                }
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)
    }

    private fun stopUiTicker() {
        uiTickerRunning = false
    }

    data class DeltaHeader(
        val frameId: Int,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val isKeyframe: Boolean,
        val dataSize: Int
    )

    data class FrameInfo(val width: Int, val height: Int)

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

        @Volatile private var running = true
        private var socket: DatagramSocket? = null
        private var frameInfo: FrameInfo? = null
        private var xImageInfo: XImageInfo? = null
        private var handshakeComplete = false
        private var displayReady = false

        // Optional line buffer reuse
        private var lineBuffer: IntArray? = null

        // Streaming metrics
        private var currentFrameId: Int = -1
        private var frameFirstSeenMs: Long = 0L
        private var prevFrameFirstSeenMs: Long = 0L
        private var estimatedFrameGapMs: Long = 0L

        // Remove decode time (we are not decoding)
        private var currentFrameDecodeAccumulatedMs: Long = 0L // unused, kept to avoid logic churn
        private var previousFrameDecodeMs: Long = 0L // unused

        private var fpsWindowStartMs: Long = 0L
        private var framesInWindow: Int = 0
        private var fps: Double = 0.0

        private var bitrateWindowStartMs: Long = 0L
        private var bytesInWindow: Long = 0
        private var kbps: Double = 0.0

        fun stop() {
            running = false
            socket?.close()
        }

        override fun run() {
            try {
                // Prefer high priority for rendering pipeline
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Exception) {}
                socket = DatagramSocket()
                socket?.soTimeout = 10000
                // Larger OS receive buffer to reduce drops
                try { socket?.receiveBufferSize = 1 shl 20 } catch (_: Exception) {}

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

                // Start UI ticker once streaming begins
                mainHandler.post { startUiTicker() }

                val buffer = ByteArray(2048) // packets <= ~1400 bytes
                val packet = DatagramPacket(buffer, buffer.size)
                while (running) {
                    try {
                        socket?.receive(packet)
                        processReceivedPacket(packet.data, packet.length)
                    } catch (_: SocketTimeoutException) {
                        // no status spam here
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

                val resolutionResponse = waitForResolutionResponse(15000) ?: return false

                if (resolutionResponse == "RESOLUTION_ACK") {
                    updateStatus("Resolution accepted by server")
                } else if (resolutionResponse.startsWith("RESOLUTION_CHANGED:")) {
                    handleResolutionChanged(resolutionResponse)
                } else {
                    updateStatus("Unexpected resolution response: $resolutionResponse")
                    return false
                }

                val displayReadyMsg = waitForDisplayReady(15000) ?: run {
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
                    val resPart = parts[1]
                    val rrPart = parts[2]
                    val r = resPart.split("x")
                    if (r.size == 2) {
                        val width = r[0].toInt()
                        val height = r[1].toInt()
                        val rr = rrPart.toFloat()
                        updateServerResolution(width, height, rr)
                        ensureBackingBitmap(width, height)
                        // Avoid Toast spam during streaming; comment out:
                        // mainHandler.post { Toast.makeText(this@MainActivity, "...", Toast.LENGTH_LONG).show() }
                    }
                }
            } catch (e: Exception) {
                updateStatus("Error parsing resolution change: ${e.localizedMessage}")
            }
        }

        private fun waitForResolutionResponse(timeoutMs: Long): String? {
            val buffer = ByteArray(1024)
            val start = System.currentTimeMillis()
            return try {
                socket?.soTimeout = timeoutMs.toInt()
                while (System.currentTimeMillis() - start < timeoutMs) {
                    val p = DatagramPacket(buffer, buffer.size)
                    socket?.receive(p)
                    val msg = String(p.data, 0, p.length)
                    if (msg == "RESOLUTION_ACK" || msg.startsWith("RESOLUTION_CHANGED:")) return msg
                    if (msg.startsWith("RESOLUTION_ERROR:")) {
                        updateStatus("Server error: $msg")
                        return null
                    }
                }
                null
            } catch (_: SocketTimeoutException) {
                null
            } catch (e: Exception) {
                updateStatus("Wait error: ${e.localizedMessage}")
                null
            }
        }

        private fun requestStreaming(serverAddress: InetAddress): Boolean {
            return try {
                updateStatus("Requesting stream start...")
                if (!sendMessage(serverAddress, "START_STREAM")) return false
                if (!waitForMessage("STREAM_STARTED", 5000)) {
                    updateStatus("Did not receive STREAM_STARTED")
                    false
                } else {
                    updateStatus("Streaming started")
                    true
                }
            } catch (e: Exception) {
                updateStatus("Stream request error: ${e.localizedMessage}")
                false
            }
        }

        private fun sendMessage(serverAddress: InetAddress, message: String): Boolean {
            return try {
                val data = message.toByteArray()
                val p = DatagramPacket(data, data.size, serverAddress, serverPort)
                socket?.send(p)
                true
            } catch (e: Exception) {
                updateStatus("Send error: ${e.localizedMessage}")
                false
            }
        }

        private fun waitForMessage(expected: String, timeoutMs: Long): Boolean {
            val buffer = ByteArray(1024)
            val start = System.currentTimeMillis()
            return try {
                socket?.soTimeout = timeoutMs.toInt()
                while (System.currentTimeMillis() - start < timeoutMs) {
                    val p = DatagramPacket(buffer, buffer.size)
                    socket?.receive(p)
                    val msg = String(p.data, 0, p.length)
                    if (msg == expected) return true
                    if (msg.startsWith("DISPLAY_ERROR:") || msg.startsWith("RESOLUTION_ERROR:")) {
                        updateStatus("Server error: $msg")
                        return false
                    }
                }
                false
            } catch (_: SocketTimeoutException) {
                false
            } catch (e: Exception) {
                updateStatus("Wait error: ${e.localizedMessage}")
                false
            }
        }

        private fun waitForDisplayReady(timeoutMs: Long): String? {
            val buffer = ByteArray(1024)
            val start = System.currentTimeMillis()
            return try {
                socket?.soTimeout = timeoutMs.toInt()
                while (System.currentTimeMillis() - start < timeoutMs) {
                    val p = DatagramPacket(buffer, buffer.size)
                    socket?.receive(p)
                    val msg = String(p.data, 0, p.length)
                    if (msg.startsWith("DISPLAY_READY:")) {
                        val parts = msg.split(":")
                        if (parts.size >= 3) {
                            val r = parts[2].split("x")
                            if (r.size == 2) {
                                val w = r[0].toIntOrNull()
                                val h = r[1].toIntOrNull()
                                if (w != null && h != null) {
                                    frameInfo = FrameInfo(w, h)
                                    ensureBackingBitmap(w, h)
                                }
                            }
                        }
                        return msg
                    }
                    if (msg.startsWith("DISPLAY_ERROR:")) {
                        updateStatus("Server error: $msg")
                        return null
                    }
                }
                null
            } catch (_: SocketTimeoutException) {
                null
            } catch (e: Exception) {
                updateStatus("Display ready wait error: ${e.localizedMessage}")
                null
            }
        }

        private fun processReceivedPacket(data: ByteArray, length: Int) {
            if (!handshakeComplete || !displayReady) return
            try {
                val asString = runCatching { String(data, 0, length) }.getOrNull()
                if (asString != null &&
                    (asString.startsWith("XIMAGE_INFO:") ||
                            asString.startsWith("INFO:") ||
                            asString.startsWith("DISPLAY_ERROR:"))) {
                    if (asString.startsWith("XIMAGE_INFO:")) { handleXImageInfo(asString); return }
                    if (asString.startsWith("INFO:")) { handleFrameInfo(asString); return }
                    return
                }
                if (length >= 20) handleRectPacket(data, length)
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
                    ensureBackingBitmap(xImageInfo!!.width, xImageInfo!!.height)
                    updateStatus("XImage info received: ${xImageInfo!!.width}x${xImageInfo!!.height}, ${xImageInfo!!.bitsPerPixel} bpp")
                }
            } catch (e: Exception) {
                updateStatus("XImage info parse error: ${e.localizedMessage}")
            }
        }

        private fun handleFrameInfo(infoPacket: String) {
            val parts = infoPacket.split(":")
            if (parts.size == 3 && parts[0] == "INFO") {
                val w = parts[1].toIntOrNull()
                val h = parts[2].toIntOrNull()
                if (w != null && h != null) {
                    frameInfo = FrameInfo(w, h)
                    xImageInfo = null
                    ensureBackingBitmap(w, h)
                    updateStatus("RGB frame info received: ${w}x${h}")
                }
            }
        }

        private fun handleRectPacket(data: ByteArray, length: Int) {
            val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
            val frameId = buffer.int
            val x = buffer.short.toInt() and 0xFFFF
            val y = buffer.short.toInt() and 0xFFFF
            val w = buffer.short.toInt() and 0xFFFF
            val h = buffer.short.toInt() and 0xFFFF
            val isKeyframe = (buffer.get().toInt() and 0xFF) == 1
            buffer.position(buffer.position() + 3)
            val dataSize = buffer.int
            if (dataSize < 0 || dataSize > buffer.remaining()) {
                updateStatus("Invalid rect payload")
                return
            }
            val rgb = ByteArray(dataSize)
            buffer.get(rgb)

            val fi = frameInfo ?: return
            ensureBackingBitmap(fi.width, fi.height)
            val bmp = backingBitmap ?: return

            if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > fi.width || y + h > fi.height) {
                updateStatus("Rect out of bounds: $x,$y $w x $h")
                return
            }

            // Metrics: detect frame boundary
            val nowMs = System.currentTimeMillis()
            if (currentFrameId == -1) {
                currentFrameId = frameId
                frameFirstSeenMs = nowMs
                fpsWindowStartMs = nowMs
                bitrateWindowStartMs = nowMs
            } else if (frameId != currentFrameId) {
                // Finalize previous frame metrics
                previousFrameDecodeMs = currentFrameDecodeAccumulatedMs
                currentFrameDecodeAccumulatedMs = 0L
                prevFrameFirstSeenMs = frameFirstSeenMs
                frameFirstSeenMs = nowMs
                estimatedFrameGapMs = frameFirstSeenMs - prevFrameFirstSeenMs

                currentFrameId = frameId

                // FPS update (based on first-rect arrivals)
                framesInWindow += 1
                val dtFps = nowMs - fpsWindowStartMs
                if (dtFps >= 1000L) {
                    fps = framesInWindow.toDouble() * 1000.0 / dtFps.toDouble()
                    framesInWindow = 0
                    fpsWindowStartMs = nowMs
                }
            }

            // Apply rect (no decode timing)
            applyRgbRect(bmp, x, y, w, h, rgb)

            // Bitrate (payload only)
            bytesInWindow += dataSize.toLong()
            val dtBr = nowMs - bitrateWindowStartMs
            if (dtBr >= 1000L) {
                // kbps = kilobits per second
                kbps = (bytesInWindow.toDouble() * 8.0) / (dtBr.toDouble() / 1000.0) / 1000.0
                bytesInWindow = 0
                bitrateWindowStartMs = nowMs
            }

            // Mark UI dirty and throttle label updates
            uiDirty = true
            val now = System.currentTimeMillis()
            if (now - lastInfoUpdateMs >= 250) {
                lastInfoUpdateMs = now
                mainHandler.post {
                    val fpsStr = if (fps > 0) String.format("%.1f", fps) else "-"
                    val kbpsStr = if (kbps > 0) String.format("%.1f", kbps) else "-"
                    val info = StringBuilder()
                    info.append("Frame ").append(frameId)
                        .append(" (").append(fi.width).append("x").append(fi.height).append(") ")
                        .append("rect=").append(x).append(",").append(y).append(" ")
                        .append(w).append("x").append(h)
                        .append(" key=").append(isKeyframe)
                        .append("\n")
                        .append("FPS: ").append(fpsStr)
                        .append("    Bitrate: ").append(kbpsStr).append(" kbps")
                        .append("\n")
                        .append("Inter-frame gap: ").append(estimatedFrameGapMs).append("ms")
                    tvFrameInfo.text = info.toString()
                }
            }
        }

        private fun applyRgbRect(bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int, rgb: ByteArray) {
            if (rgb.size < w * h * 3) return
            var line = lineBuffer
            if (line == null || line!!.size < w) {
                line = IntArray(w)
                lineBuffer = line
            }
            var src = 0
            for (row in 0 until h) {
                var i = 0
                var s = src
                while (i < w) {
                    val r = rgb[s].toInt() and 0xFF
                    val g = rgb[s + 1].toInt() and 0xFF
                    val b = rgb[s + 2].toInt() and 0xFF
                    line!![i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    s += 3
                    i++
                }
                bitmap.setPixels(line!!, 0, w, x, y + row, w, 1)
                src += w * 3
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectFromServer()
        executorService?.shutdownNow()
    }
}