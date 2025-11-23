package pl.zarajczyk.familyrulesandroid.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.zarajczyk.familyrulesandroid.R

/**
 * Service that shows a countdown overlay (60 seconds to 0) before blocking apps
 */
class CountdownOverlayService : Service() {
    
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isOverlayShowing = false
    private var countdownTextView: TextView? = null
    
    private val scope = CoroutineScope(Dispatchers.Main)
    private var countdownJob: Job? = null
    
    companion object {
        private const val TAG = "CountdownOverlay"
        private const val COUNTDOWN_SECONDS = 60
        
        var onCountdownComplete: (() -> Unit)? = null
        
        fun showCountdown(context: Context, onComplete: () -> Unit) {
            onCountdownComplete = onComplete
            val intent = Intent(context, CountdownOverlayService::class.java).apply {
                putExtra("action", "show")
            }
            context.startService(intent)
        }
        
        fun hideCountdown(context: Context) {
            val intent = Intent(context, CountdownOverlayService::class.java).apply {
                putExtra("action", "hide")
            }
            context.startService(intent)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Log.d(TAG, "CountdownOverlayService created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("action")
        
        when (action) {
            "show" -> {
                if (!isOverlayShowing) {
                    showCountdownOverlay()
                }
            }
            "hide" -> {
                hideCountdownOverlay()
            }
        }
        
        return START_NOT_STICKY
    }
    
    private fun showCountdownOverlay() {
        if (isOverlayShowing) {
            Log.d(TAG, "Countdown overlay already showing")
            return
        }
        
        // Check if we have permission to draw overlays
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW permission not granted")
            return
        }
        
        try {
            val layoutParams = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                format = PixelFormat.TRANSLUCENT
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dpToPx(16) // 16dp from top
            }
            
            // Create the overlay view
            overlayView = createCountdownOverlayView()
            
            windowManager?.addView(overlayView, layoutParams)
            isOverlayShowing = true
            
            Log.i(TAG, "Countdown overlay shown")
            
            // Start countdown
            startCountdown()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show countdown overlay: ${e.message}", e)
        }
    }
    
    private fun createCountdownOverlayView(): View {
        // Create main container with rounded corners and shadow
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            
            // Create rounded background drawable
            val drawable = android.graphics.drawable.GradientDrawable().apply {
                setColor("#E0000000".toColorInt()) // Black with 88% alpha
                cornerRadius = dpToPx(16).toFloat()
            }
            background = drawable
            
            setPadding(
                dpToPx(16),
                dpToPx(12),
                dpToPx(16),
                dpToPx(12)
            )
            elevation = dpToPx(8).toFloat()
        }
        
        // Add small icon
        val iconView = ImageView(this).apply {
            setImageResource(R.drawable.icon)
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(32),
                dpToPx(32)
            ).apply {
                marginEnd = dpToPx(12)
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        container.addView(iconView)
        
        // Add countdown timer display
        countdownTextView = TextView(this).apply {
            text = formatTime(COUNTDOWN_SECONDS)
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFEB3B")) // Yellow/amber color
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dpToPx(12)
            }
        }
        container.addView(countdownTextView)
        
        // Add compact message
        val messageText = TextView(this).apply {
            text = getString(R.string.countdown_compact_message)
            textSize = 10f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            maxLines = 1
        }
        container.addView(messageText)
        
        return container
    }
    
    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            var secondsLeft = COUNTDOWN_SECONDS
            
            while (isActive && secondsLeft > 0) {
                countdownTextView?.text = formatTime(secondsLeft)
                delay(1000)
                secondsLeft--
            }
            
            if (isActive && secondsLeft == 0) {
                countdownTextView?.text = formatTime(0)
                Log.i(TAG, "Countdown complete")
                
                // Hide the overlay
                hideCountdownOverlay()
                
                // Notify completion
                onCountdownComplete?.invoke()
                onCountdownComplete = null
            }
        }
    }
    
    private fun formatTime(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", mins, secs)
    }
    
    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
    
    private fun hideCountdownOverlay() {
        if (!isOverlayShowing) {
            Log.d(TAG, "Countdown overlay not showing")
            return
        }
        
        try {
            countdownJob?.cancel()
            countdownJob = null
            
            overlayView?.let { view ->
                windowManager?.removeView(view)
            }
            overlayView = null
            countdownTextView = null
            isOverlayShowing = false
            
            Log.i(TAG, "Countdown overlay hidden")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide countdown overlay: ${e.message}", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        hideCountdownOverlay()
        Log.d(TAG, "CountdownOverlayService destroyed")
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
