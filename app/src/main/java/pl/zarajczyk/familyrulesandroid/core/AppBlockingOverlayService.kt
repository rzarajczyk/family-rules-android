package pl.zarajczyk.familyrulesandroid.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Service that shows a blocking overlay when a blocked app is opened
 */
class AppBlockingOverlayService : Service() {
    
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isOverlayShowing = false
    
    companion object {
        private const val TAG = "AppBlockingOverlay"
        
        fun showOverlay(context: Context, blockedPackageName: String) {
            val intent = Intent(context, AppBlockingOverlayService::class.java).apply {
                putExtra("blocked_package", blockedPackageName)
                putExtra("action", "show")
            }
            context.startService(intent)
        }
        
        fun hideOverlay(context: Context) {
            val intent = Intent(context, AppBlockingOverlayService::class.java).apply {
                putExtra("action", "hide")
            }
            context.startService(intent)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Log.d(TAG, "AppBlockingOverlayService created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("action")
        val blockedPackage = intent?.getStringExtra("blocked_package")
        
        when (action) {
            "show" -> {
                if (blockedPackage != null && !isOverlayShowing) {
                    showBlockingOverlay(blockedPackage)
                }
            }
            "hide" -> {
                hideBlockingOverlay()
            }
        }
        
        return START_NOT_STICKY
    }
    
    private fun showBlockingOverlay(blockedPackageName: String) {
        if (isOverlayShowing) {
            Log.d(TAG, "Overlay already showing")
            return
        }
        
        // Check if we have permission to draw overlays
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW permission not granted")
            return
        }
        
        try {
            val layoutParams = WindowManager.LayoutParams().apply {
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                format = PixelFormat.TRANSLUCENT
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                gravity = Gravity.CENTER
            }
            
            // Create the overlay view
            overlayView = createOverlayView(blockedPackageName)
            
            windowManager?.addView(overlayView, layoutParams)
            isOverlayShowing = true
            
            Log.i(TAG, "Blocking overlay shown for: $blockedPackageName")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show blocking overlay: ${e.message}", e)
        }
    }
    
    private fun createOverlayView(@Suppress("UNUSED_PARAMETER") blockedPackageName: String): View {
        // Create main container
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(
                dpToPx(32),
                dpToPx(32),
                dpToPx(32),
                dpToPx(32)
            )
        }
        
        // Add emoji
        val emojiText = TextView(this).apply {
            text = "🚫"
            textSize = 48f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        container.addView(emojiText)
        
        // Add spacing
        val spacer1 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(24)
            )
        }
        container.addView(spacer1)
        
        // Add main message
        val mainMessage = TextView(this).apply {
            text = "This app is blocked"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        container.addView(mainMessage)
        
        // Add spacing
        val spacer2 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(16)
            )
        }
        container.addView(spacer2)
        
        // Add description
        val description = TextView(this).apply {
            text = "Please close this app and return to allowed applications"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#CCFFFFFF")) // White with 80% alpha
        }
        container.addView(description)
        
        // Add spacing
        val spacer3 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(32)
            )
        }
        container.addView(spacer3)
        
        // Add decorative line
        val line = View(this).apply {
            setBackgroundColor(Color.parseColor("#4DFFFFFF")) // White with 30% alpha
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(100),
                dpToPx(2)
            ).apply {
                gravity = Gravity.CENTER
            }
        }
        container.addView(line)
        
        // Add click listener to prevent interaction
        container.setOnClickListener {
            Log.d(TAG, "User tried to interact with blocked app")
        }
        
        return container
    }
    
    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
    
    private fun hideBlockingOverlay() {
        if (!isOverlayShowing) {
            Log.d(TAG, "Overlay not showing")
            return
        }
        
        try {
            overlayView?.let { view ->
                windowManager?.removeView(view)
            }
            overlayView = null
            isOverlayShowing = false
            
            Log.i(TAG, "Blocking overlay hidden")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide blocking overlay: ${e.message}", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        hideBlockingOverlay()
        Log.d(TAG, "AppBlockingOverlayService destroyed")
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
