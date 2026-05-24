package pl.zarajczyk.familyrulesandroid.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import pl.zarajczyk.familyrulesandroid.R
import pl.zarajczyk.familyrulesandroid.utils.Logger

/**
 * Shows a small, non-blocking toast-style banner when media playback is blocked.
 * Auto-dismisses after [DISMISS_DELAY_MS]. Calling [show] while already visible
 * resets the dismiss timer (idempotent — no stacking).
 *
 * The overlay steals window focus momentarily, which is the mechanism by which
 * apps that ignore audio focus (e.g. WhatsApp inline video) can be forced to pause.
 */
class MediaPlaybackBlockingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { hideOverlay() }

    companion object {
        private const val TAG = "MediaPlaybackOverlay"
        private const val DISMISS_DELAY_MS = 5_000L

        private const val ACTION_SHOW = "show"
        private const val ACTION_HIDE = "hide"

        fun show(context: Context) {
            context.startService(Intent(context, MediaPlaybackBlockingOverlayService::class.java).apply {
                action = ACTION_SHOW
            })
        }

        fun hide(context: Context) {
            context.startService(Intent(context, MediaPlaybackBlockingOverlayService::class.java).apply {
                action = ACTION_HIDE
            })
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> hideOverlay()
        }
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Logger.w(TAG, "SYSTEM_ALERT_WINDOW not granted — cannot show media blocked overlay")
            return
        }

        // Reset dismiss timer regardless of whether overlay is already showing.
        handler.removeCallbacks(dismissRunnable)
        handler.postDelayed(dismissRunnable, DISMISS_DELAY_MS)

        if (overlayView != null) {
            // Already showing — timer reset above is sufficient.
            Logger.d(TAG, "Overlay already visible — dismiss timer reset")
            return
        }

        try {
            val params = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                format = PixelFormat.TRANSLUCENT
                // Focusable so it steals window focus from the playing app.
                flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                gravity = Gravity.TOP
                y = 0
            }

            val view = buildView()
            windowManager?.addView(view, params)
            overlayView = view
            Logger.i(TAG, "Media blocked overlay shown")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to show media blocked overlay", e)
        }
    }

    private fun hideOverlay() {
        handler.removeCallbacks(dismissRunnable)
        val view = overlayView ?: return
        try {
            windowManager?.removeView(view)
            Logger.i(TAG, "Media blocked overlay hidden")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to remove media blocked overlay", e)
        } finally {
            overlayView = null
        }
    }

    private fun buildView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#E6B71C1C")) // 90% dark red
            setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(16))
        }

        // App icon
        val iconView = ImageView(this).apply {
            val bmp = BitmapFactory.decodeResource(resources, R.drawable.icon)
            setImageBitmap(bmp)
            layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48)).apply {
                marginEnd = dpToPx(16)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        container.addView(iconView)

        // Text column
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(this).apply {
            text = getString(R.string.family_rules)
            textSize = 13f
            setTextColor(Color.parseColor("#FFCCCC"))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        val message = TextView(this).apply {
            text = getString(R.string.media_playback_blocked)
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        textColumn.addView(title)
        textColumn.addView(message)
        container.addView(textColumn)

        // Dismiss on tap
        container.setOnClickListener { hideOverlay() }

        return container
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
