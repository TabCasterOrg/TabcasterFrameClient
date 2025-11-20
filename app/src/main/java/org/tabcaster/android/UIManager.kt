package org.tabcaster.android

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.InetAddresses
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.DisplayMetrics
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
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.widget.addTextChangedListener
import com.google.android.material.textfield.TextInputEditText

class UIManager(private val activity: AppCompatActivity) {

    // UI Views
    private lateinit var etServerIP: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnFullscreen: Button
    private lateinit var btnHelp: Button
    private lateinit var tvStatus: TextView
    private lateinit var ivFrame: ImageView
    private lateinit var tvFrameInfo: TextView
    private lateinit var tvResolution: TextView
    // Layout References
    private lateinit var controlsLayout: LinearLayout
    private lateinit var root: LinearLayout

    // UI State
    private var isFullscreen: Boolean = false
    private var isStreaming: Boolean = false

    // Screen resolution info
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var refreshRate: Float = 60.0f
    private var unfullscreenPaddingDP = 16

    // Server resolution (might be different from client if fallback used)
    private var serverWidth: Int = 0
    private var serverHeight: Int = 0
    private var serverRefreshRate: Float = 60.0f

    // UI update throttling
    private var lastFrameInfoUpdate = 0L
    private var lastStatusUpdate = 0L

    // Main thread handler for UI updates
    private val mainHandler = Handler(Looper.getMainLooper())

    // Callbacks for user interactions
    interface UICallbacks {
        fun onTryConnect()
        fun onDisconnectClicked()
        fun onFullscreenToggled()
        fun onFrameClicked()
    }

    private var callbacks: UICallbacks? = null

    fun setCallbacks(callbacks: UICallbacks) {
        this.callbacks = callbacks
    }

    fun initializeViews() {
        // UI Elements
        etServerIP = activity.findViewById(R.id.et_server_ip)
        btnConnect = activity.findViewById(R.id.btn_connect)
        btnHelp = activity.findViewById(R.id.btn_help)
        btnDisconnect = activity.findViewById(R.id.btn_disconnect)
        btnFullscreen = activity.findViewById(R.id.btn_fullscreen)
        tvStatus = activity.findViewById(R.id.tv_status)
        ivFrame = activity.findViewById(R.id.iv_frame)
        tvFrameInfo = activity.findViewById(R.id.tv_frame_info)
        tvResolution = activity.findViewById(R.id.tv_resolution)
        // Layouts
        controlsLayout = activity.findViewById(R.id.controlsLayout)
        root = activity.findViewById(R.id.root)
        // Button State
        setConnectionState(false) // Removes the disconnect button
    }

    fun setupClickListeners() {
        btnConnect.setOnClickListener { onConnectClicked() }
        btnDisconnect.setOnClickListener { callbacks?.onDisconnectClicked() }
        btnFullscreen.setOnClickListener { callbacks?.onFullscreenToggled() }
        btnHelp.setOnClickListener { onHelpClicked() } // Since this is done just in the UI handler, this shouldn't be a callback.

        // This is for the dynamic button text on the connect button.
        etServerIP.addTextChangedListener( object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    checkIP()
                }

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                    checkIP()
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    checkIP()
                }
            }
        )

        // Allow clicking on image to toggle fullscreen when streaming
        ivFrame.setOnClickListener {
            if (isStreaming) {
                callbacks?.onFrameClicked()
            }
        }

        // Load IP after views are initialised
        val lastIP = PrefsManager.getInstance(activity).getLastIP()
        etServerIP.setText(lastIP)

        // Set the buttons up correctly
        checkIP()
    }

    fun getScreenResolution() {
        val windowManager = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val displayMetrics = DisplayMetrics()

        display.getRealMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        refreshRate = display.refreshRate

        if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
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

    fun getScreenWidth(): Int = screenWidth
    fun getScreenHeight(): Int = screenHeight
    fun getRefreshRate(): Float = refreshRate

    fun setStreamingState(streaming: Boolean) {
        isStreaming = streaming
        updateFullscreenButton()
    }

    fun setConnectionState(connected: Boolean) {
        // Ensure UI updates happen on main thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            btnConnect.isVisible = !connected
            btnDisconnect.isVisible = connected
            etServerIP.isEnabled = !connected
        } else {
            mainHandler.post {
                btnConnect.isVisible = !connected
                btnDisconnect.isVisible = connected
                etServerIP.isEnabled = !connected
            }
        }
    }

    fun checkIP(){
        var validIP = serverIPIsValid()
        // Next check the buttons. Also we have the !isFullscreen just in case.
        btnConnect.isVisible = validIP && !isFullscreen
        btnHelp.isVisible = !validIP && !isFullscreen
    }

    fun getServerIP(): String = etServerIP.text.toString().trim()

    fun serverIPIsValid(): Boolean = InetAddresses.isNumericAddress(etServerIP.text.toString())

    fun setDefaultValues(defaultIP: String, defaultPort: Int) {
        etServerIP.setText(defaultIP)
    }

    fun toggleFullscreen() {
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
        val windowInsetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        // Hide all UI elements except the image
        controlsLayout.visibility = View.GONE
        etServerIP.visibility = View.GONE
        btnConnect.visibility = View.GONE
        btnDisconnect.visibility = View.GONE
        btnFullscreen.visibility = View.GONE
        tvStatus.visibility = View.GONE
        tvFrameInfo.visibility = View.GONE
        tvResolution.visibility = View.GONE

        // Make image fill screen
        ivFrame.scaleType = ImageView.ScaleType.FIT_CENTER
        root.setPadding(0)

        updateFullscreenButton()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun exitFullscreen() {
        isFullscreen = false

        // Show system UI
        val windowInsetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        windowInsetsController.show(WindowInsetsCompat.Type.systemBars())


        // Show all UI elements
        controlsLayout.visibility = View.VISIBLE
        etServerIP.visibility = View.VISIBLE
        btnFullscreen.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE
        tvFrameInfo.visibility = View.VISIBLE
        tvResolution.visibility = View.VISIBLE

        // Connect/Disconnect buttons are handled here
        setConnectionState(isStreaming)

        // Reset image scaling
        ivFrame.scaleType = ImageView.ScaleType.FIT_CENTER
        root.setPadding(unfullscreenPaddingDP)

        updateFullscreenButton()
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }


    private fun updateFullscreenButton() {
        // Ensure UI updates happen on main thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            btnFullscreen.isEnabled = isStreaming
            btnFullscreen.text = if (isFullscreen) "Exit Fullscreen" else "Fullscreen"
        } else {
            mainHandler.post {
                btnFullscreen.isEnabled = isStreaming
                btnFullscreen.text = if (isFullscreen) "Exit Fullscreen" else "Fullscreen"
            }
        }
    }

    private fun onConnectClicked(){
        if (serverIPIsValid()){
            callbacks?.onTryConnect() // Tell the main activity to connect
        }
    }

    private fun onHelpClicked(){
        // This is a dialog box, to explain how to input the IP address.
        val builder: AlertDialog.Builder = AlertDialog.Builder(activity)
        builder.setTitle("Invalid IP Address")
        val serverIP = etServerIP.text.toString().trim()
        builder.setMessage("$serverIP is not a valid IP address.\nIP Addresses usually follow the format of XXX.XXX.XX.XXX:XXXX\n\nTo retreive your IP address on Linux systems with NetworkManager installed, use `nmcli` in the Terminal to find your IP address.\nWhen a valid IP address is entered, the help button will become the connect button, and you can attempt to connect to TabCaster.")
        val dialog: AlertDialog = builder.create()
        dialog.show()
    }

    fun isInFullscreen(): Boolean = isFullscreen

    // Throttled status updates
    fun updateStatus(status: String) {
        val now = System.currentTimeMillis()
        if (now - lastStatusUpdate > 100) { // 10fps max for status updates
            lastStatusUpdate = now
            if (Looper.myLooper() == Looper.getMainLooper()) {
                tvStatus.text = "Status: $status"
            } else {
                mainHandler.post {
                    tvStatus.text = "Status: $status"
                }
            }
        }
    }

    // Regular frame info update (for non-optimized calls)
    fun updateFrameInfo(info: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            tvFrameInfo.text = info
        } else {
            mainHandler.post {
                tvFrameInfo.text = info
            }
        }
    }

    // Enhanced frame info with decode timing and average decode time
    fun updateOptimizedFrameInfo(
        frameId: Int,
        latency: Long,
        width: Int,
        height: Int,
        decodeTime: Long,
        currentFPS: Float,
        avgDecodeTime: Float,
        isHardwareAccelerationSupported: Boolean,
        hardwareDecodeCount: Int,
        softwareDecodeCount: Int,
        totalHardwareDecodeTime: Long,
        totalSoftwareDecodeTime: Long,
        droppedFrames: Int
    ) {
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

        val finalInfo = if (droppedFrames > 0) {
            "$info | Dropped: $droppedFrames"
        } else {
            info
        }

        // Ensure UI updates happen on main thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            tvFrameInfo.text = finalInfo
        } else {
            mainHandler.post {
                tvFrameInfo.text = finalInfo
            }
        }
    }

    fun updateResolutionInfo() {
        val resolutionText = if (serverWidth == screenWidth && serverHeight == screenHeight) {
            "Resolution: ${screenWidth}x${screenHeight} @ ${refreshRate}Hz"
        } else {
            "Client: ${screenWidth}x${screenHeight} @ ${refreshRate}Hz\n" +
                    "Server: ${serverWidth}x${serverHeight} @ ${serverRefreshRate}Hz (fallback)"
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            tvResolution.text = resolutionText
        } else {
            mainHandler.post {
                tvResolution.text = resolutionText
            }
        }
    }
    // Get current drawable to check if bitmap is displayed
    fun getCurrentDrawable(): android.graphics.drawable.Drawable? {
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            ivFrame.drawable
        } else {
            null
        }
    }

    // Force ImageView to rebuild its display list
    fun invalidateImageView() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ivFrame.invalidate()
            ivFrame.requestLayout()
        } else {
            mainHandler.post {
                ivFrame.invalidate()
                ivFrame.requestLayout()
            }
        }
    }
    fun updateServerResolution(width: Int, height: Int, refreshRate: Float) {
        serverWidth = width
        serverHeight = height
        serverRefreshRate = refreshRate
        updateResolutionInfo()
    }

    // This method must ONLY be called from the main thread
    // Bitmap operations are not thread-safe
    fun displayFrame(bitmap: Bitmap) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw IllegalStateException("displayFrame() must be called from the main thread!")
        }

        // Additional safety check
        if (!bitmap.isRecycled) {
            try {
                ivFrame.setImageBitmap(bitmap)
            } catch (e: Exception) {
                // Log but don't crash
                android.util.Log.e("UIManager", "Error setting bitmap: ${e.message}")
            }
        }
    }
    fun clearFrame() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ivFrame.setImageBitmap(null)
        } else {
            mainHandler.post {
                ivFrame.setImageBitmap(null)
            }
        }
    }

    fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(activity, message, duration).show()
        } else {
            mainHandler.post {
                Toast.makeText(activity, message, duration).show()
            }
        }
    }

    fun shouldUpdateFrameInfo(): Boolean {
        val now = System.currentTimeMillis()
        return if (now - lastFrameInfoUpdate > 16) {
            lastFrameInfoUpdate = now
            true
        } else {
            false
        }
    }

    fun resetUI() {
        val resetTask = {
            isStreaming = false

            setConnectionState(false)
            updateStatus("Disconnected")
            updateFrameInfo("No frame data")
            updateFullscreenButton()

            // Reset server resolution to match client
            serverWidth = screenWidth
            serverHeight = screenHeight
            serverRefreshRate = refreshRate
            updateResolutionInfo()

            clearFrame()

            // Exit fullscreen if active
            if (isFullscreen) {
                exitFullscreen()
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            resetTask()
        } else {
            mainHandler.post { resetTask() }
        }
    }
    
    /**
     * Clean up resources when activity is being destroyed.
     * Removes all callbacks and clears references to prevent memory leaks.
     */
    fun cleanup() {
        // Remove callbacks to prevent memory leaks
        callbacks = null
        
        // Clear any pending UI updates
        mainHandler.removeCallbacksAndMessages(null)
        
        // Reset all state
        isStreaming = false
        isFullscreen = false
        
        // Clear frame to free bitmap memory
        clearFrame()
    }
}
