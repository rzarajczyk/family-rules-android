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
 * Shows a full-width top-of-screen banner when media playback is blocked.
 *
 * Two modes:
 *
 * - **Plain** ([show]): auto-dismisses after [DISMISS_DELAY_MS]. No countdown visible.
 *   Used for MediaSession-based apps (e.g. YouTube) where [transportControls.pause()] already
 *   stops playback — the overlay is purely informational.
 *
 * - **Countdown** ([showWithCountdown]): displays a live N→0 countdown badge. Every second
 *   [isStillPlaying] is invoked; if it returns false the countdown is cancelled and the overlay
 *   dismisses. If it reaches zero [onExpired] is invoked on the main thread then the overlay hides.
 *   Used for AudioManager-only apps (e.g. WhatsApp) so the user has a chance to pause before
 *   being sent to the home screen.
 *
 * Calling [show] or [showWithCountdown] while a countdown is already running is a no-op — the
 * running countdown is not restarted. Calling [hide] cancels any active timer/countdown.
 */
class MediaPlaybackBlockingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var countdownTextView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private var countdownActive = false
    private var countdownValue = 0

    companion object {
        private const val TAG = "MediaPlaybackOverlay"
        private const val DISMISS_DELAY_MS = 5_000L
        private const val COUNTDOWN_SECONDS = 5

        private const val ACTION_SHOW = "show"
        private const val ACTION_SHOW_COUNTDOWN = "show_countdown"
        private const val ACTION_HIDE = "hide"

        /** Set by [showWithCountdown] before starting the service; cleared after use. */
        @Volatile var isStillPlayingCallback: (() -> Boolean)? = null
        @Volatile var onExpiredCallback: (() -> Unit)? = null

        /** Show a plain auto-dismiss overlay (no countdown). No-op if countdown is running. */
        fun show(context: Context) {
            context.startService(Intent(context, MediaPlaybackBlockingOverlayService::class.java).apply {
                action = ACTION_SHOW
            })
        }

        /**
         * Show a countdown overlay.
         * Every second [isStillPlaying] is checked; if false the overlay dismisses silently.
         * If the countdown reaches zero [onExpired] is called then the overlay hides.
         * No-op if a countdown is already running.
         */
        fun showWithCountdown(context: Context, isStillPlaying: () -> Boolean, onExpired: () -> Unit) {
            isStillPlayingCallback = isStillPlaying
            onExpiredCallback = onExpired
            context.startService(Intent(context, MediaPlaybackBlockingOverlayService::class.java).apply {
                action = ACTION_SHOW_COUNTDOWN
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
            ACTION_SHOW -> showPlainOverlay()
            ACTION_SHOW_COUNTDOWN -> showCountdownOverlay()
            ACTION_HIDE -> hideOverlay(invokeExpired = false)
        }
        return START_NOT_STICKY
    }

    // -------------------------------------------------------------------------
    // Plain overlay
    // -------------------------------------------------------------------------

    private val dismissRunnable = Runnable { hideOverlay(invokeExpired = false) }

    private fun showPlainOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Logger.w(TAG, "SYSTEM_ALERT_WINDOW not granted — cannot show media blocked overlay")
            return
        }
        // Don't interrupt a running countdown.
        if (countdownActive) {
            Logger.d(TAG, "Countdown active — ignoring plain show request")
            return
        }

        handler.removeCallbacks(dismissRunnable)
        handler.postDelayed(dismissRunnable, DISMISS_DELAY_MS)

        if (overlayView != null) {
            Logger.d(TAG, "Plain overlay already visible — dismiss timer reset")
            return
        }

        try {
            addOverlayView(buildView(countdownSeconds = null))
            Logger.i(TAG, "Plain media blocked overlay shown")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to show plain media blocked overlay", e)
        }
    }

    // -------------------------------------------------------------------------
    // Countdown overlay
    // -------------------------------------------------------------------------

    private val countdownTickRunnable = object : Runnable {
        override fun run() {
            // Check if playback has stopped — if so, dismiss silently.
            val stillPlaying = isStillPlayingCallback?.invoke() ?: false
            if (!stillPlaying) {
                Logger.i(TAG, "Playback stopped during countdown — dismissing overlay")
                hideOverlay(invokeExpired = false)
                return
            }

            countdownValue--
            countdownTextView?.text = countdownValue.toString()

            if (countdownValue <= 0) {
                Logger.i(TAG, "Countdown reached zero — invoking onExpired")
                hideOverlay(invokeExpired = true)
            } else {
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    private fun showCountdownOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Logger.w(TAG, "SYSTEM_ALERT_WINDOW not granted — cannot show countdown overlay")
            return
        }
        // Already counting down — do not restart.
        if (countdownActive) {
            Logger.d(TAG, "Countdown already running (${countdownValue}s remaining) — ignoring")
            return
        }

        // Dismiss any plain overlay that might be showing.
        handler.removeCallbacks(dismissRunnable)
        overlayView?.let { v ->
            try { windowManager?.removeView(v) } catch (_: Exception) {}
        }
        overlayView = null
        countdownTextView = null

        try {
            countdownValue = COUNTDOWN_SECONDS
            countdownActive = true
            addOverlayView(buildView(countdownSeconds = countdownValue))
            handler.postDelayed(countdownTickRunnable, 1_000L)
            Logger.i(TAG, "Countdown media blocked overlay shown ($COUNTDOWN_SECONDS s)")
        } catch (e: Exception) {
            countdownActive = false
            Logger.e(TAG, "Failed to show countdown media blocked overlay", e)
        }
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private fun hideOverlay(invokeExpired: Boolean) {
        handler.removeCallbacks(dismissRunnable)
        handler.removeCallbacks(countdownTickRunnable)
        countdownActive = false

        val expired = if (invokeExpired) {
            val cb = onExpiredCallback
            onExpiredCallback = null
            isStillPlayingCallback = null
            cb
        } else {
            onExpiredCallback = null
            isStillPlayingCallback = null
            null
        }

        val view = overlayView
        if (view != null) {
            try {
                windowManager?.removeView(view)
                Logger.i(TAG, "Media blocked overlay hidden")
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to remove media blocked overlay", e)
            } finally {
                overlayView = null
                countdownTextView = null
            }
        }

        // Invoke after removing the view so pressHome fires with overlay already gone.
        expired?.invoke()
    }

    private fun addOverlayView(view: View) {
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP
            y = 0
        }
        windowManager?.addView(view, params)
        overlayView = view
    }

    private fun buildView(countdownSeconds: Int?): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#E6B71C1C")) // 90% dark red
            setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(16))
        }

        val iconView = ImageView(this).apply {
            val bmp = BitmapFactory.decodeResource(resources, R.drawable.icon)
            setImageBitmap(bmp)
            layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48)).apply {
                marginEnd = dpToPx(16)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        container.addView(iconView)

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

        if (countdownSeconds != null) {
            val countdownView = TextView(this).apply {
                text = countdownSeconds.toString()
                textSize = 32f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dpToPx(56), dpToPx(56)).apply {
                    marginStart = dpToPx(16)
                }
            }
            container.addView(countdownView)
            countdownTextView = countdownView
        }

        container.setOnClickListener { hideOverlay(invokeExpired = false) }

        return container
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay(invokeExpired = false)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
