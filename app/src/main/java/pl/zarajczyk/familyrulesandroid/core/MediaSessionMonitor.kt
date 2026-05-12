package pl.zarajczyk.familyrulesandroid.core

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import pl.zarajczyk.familyrulesandroid.utils.Logger
import java.util.concurrent.ConcurrentHashMap

object MediaSessionMonitor {
    private const val TAG = "MediaSessionMonitor"

    @Volatile
    private var initialized = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var sessionManager: MediaSessionManager? = null

    @Volatile
    private var listenerComponent: ComponentName? = null

    @Volatile
    private var listenerRegistered = false

    /** Package names whose playback should be paused when [playbackBlockingActive] is true. */
    @Volatile
    private var blockedPlaybackPackages: Set<String> = emptySet()

    /** Whether playback blocking is currently active. */
    @Volatile
    private var playbackBlockingActive: Boolean = false

    private val callbackHandler = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<String, MediaController.Callback>()

    private val activeSessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        logSessionsSnapshot("active-sessions-changed", controllers.orEmpty())
        registerCallbacks(controllers.orEmpty())
    }

    /**
     * Update the set of packages whose playback must be blocked and whether blocking is active.
     * Called by PeriodicReportSender whenever the device state or blocked-playback-apps list changes.
     */
    fun updatePlaybackBlocking(active: Boolean, packages: Set<String>) {
        playbackBlockingActive = active
        blockedPlaybackPackages = packages
        Logger.i(TAG, "Playback blocking updated: active=$active, packages=$packages")
    }

    /**
     * Pause all active media sessions belonging to [blockedPlaybackPackages] when
     * [playbackBlockingActive] is true.  Called once per report tick from PeriodicReportSender.
     */
    fun enforcePlaybackBlocking() {
        if (!playbackBlockingActive) return
        if (!initialized) return

        val manager = sessionManager ?: return
        val component = listenerComponent ?: return
        val blocked = blockedPlaybackPackages
        if (blocked.isEmpty()) return

        try {
            val controllers = manager.getActiveSessions(component).orEmpty()
            for (controller in controllers) {
                if (controller.packageName !in blocked) continue
                val state = controller.playbackState?.state
                if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) {
                    Logger.i(TAG, "Pausing playback for ${controller.packageName}")
                    controller.transportControls?.pause()
                }
            }
        } catch (e: SecurityException) {
            Logger.w(TAG, "Cannot enforce playback blocking - notification access missing", e)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to enforce playback blocking", e)
        }
    }

    fun install(context: Context) {
        appContext = context.applicationContext
        sessionManager = context.getSystemService(MediaSessionManager::class.java)
        listenerComponent = ComponentName(context, FamilyRulesNotificationListenerService::class.java)
        initialized = true
        Logger.i(TAG, "Installed media session monitor")
        refreshActiveSessions("install")
    }

    fun start() {
        if (!initialized) {
            return
        }

        val manager = sessionManager ?: return
        val component = listenerComponent ?: return

        try {
            if (listenerRegistered) {
                manager.removeOnActiveSessionsChangedListener(activeSessionsChangedListener)
            }
            manager.addOnActiveSessionsChangedListener(
                activeSessionsChangedListener,
                component,
                callbackHandler
            )
            listenerRegistered = true
            Logger.i(TAG, "Registered active sessions listener")
            refreshActiveSessions("start")
        } catch (e: SecurityException) {
            Logger.w(TAG, "Notification access missing, media session monitor inactive", e)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to register media session listener", e)
        }
    }

    fun stop() {
        val manager = sessionManager
        if (manager != null) {
            try {
                manager.removeOnActiveSessionsChangedListener(activeSessionsChangedListener)
                listenerRegistered = false
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to unregister active sessions listener", e)
            }
        }
        callbacks.entries.forEach { entry ->
            unregisterCallback(entry.key, entry.value)
        }
        callbacks.clear()
    }

    fun onNotificationListenerConnected(service: FamilyRulesNotificationListenerService) {
        if (!initialized) {
            install(service)
        }
        start()
    }

    fun onNotificationListenerDisconnected(service: FamilyRulesNotificationListenerService) {
        if (appContext === service.applicationContext) {
            stop()
        }
    }

    fun refreshActiveSessions(reason: String) {
        if (!initialized) {
            return
        }

        val manager = sessionManager ?: return
        val component = listenerComponent ?: return

        try {
            val controllers = manager.getActiveSessions(component).orEmpty()
            logSessionsSnapshot(reason, controllers)
            registerCallbacks(controllers)
        } catch (e: SecurityException) {
            Logger.w(TAG, "Cannot query active sessions for $reason - notification access missing", e)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to query active sessions for $reason", e)
        }
    }

    private fun registerCallbacks(controllers: List<MediaController>) {
        val activeTokens = controllers.map { it.sessionToken.toString() }.toSet()

        callbacks.entries.removeIf { (token, callback) ->
            if (token !in activeTokens) {
                unregisterCallback(token, callback)
                true
            } else {
                false
            }
        }

        controllers.forEach { controller ->
            val token = controller.sessionToken.toString()
            if (callbacks.containsKey(token)) {
                return@forEach
            }

            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    logControllerEvent("playback-state-changed", controller, state = state)
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    logControllerEvent("metadata-changed", controller, metadata = metadata)
                }

                override fun onExtrasChanged(extras: Bundle?) {
                    logControllerEvent("extras-changed", controller, extras = extras)
                }

                override fun onQueueTitleChanged(title: CharSequence?) {
                    logControllerEvent("queue-title-changed", controller, queueTitle = title)
                }

                override fun onSessionDestroyed() {
                    Logger.i(TAG, "Media session destroyed: ${describeController(controller)}")
                    unregisterCallback(token, this)
                    callbacks.remove(token)
                }
            }

            try {
                controller.registerCallback(callback, callbackHandler)
                callbacks[token] = callback
                logControllerEvent("callback-registered", controller)
            } catch (e: SecurityException) {
                Logger.w(TAG, "Failed to register callback for ${controller.packageName}", e)
            } catch (e: Exception) {
                Logger.w(TAG, "Unexpected failure registering callback for ${controller.packageName}", e)
            }
        }
    }

    private fun unregisterCallback(token: String, callback: MediaController.Callback) {
        val controller = try {
            sessionManager?.getActiveSessions(listenerComponent)?.orEmpty()?.firstOrNull {
                it.sessionToken.toString() == token
            }
        } catch (_: Exception) {
            null
        }

        try {
            controller?.unregisterCallback(callback)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to unregister callback for token=$token", e)
        }
    }

    private fun logSessionsSnapshot(reason: String, controllers: List<MediaController>) {
        Logger.i(TAG, "Active sessions snapshot [$reason]: count=${controllers.size}")
        if (controllers.isEmpty()) {
            return
        }
        controllers.forEach { controller ->
            logControllerEvent("snapshot-$reason", controller)
        }
    }

    private fun logControllerEvent(
        reason: String,
        controller: MediaController,
        state: PlaybackState? = controller.playbackState,
        metadata: MediaMetadata? = controller.metadata,
        extras: Bundle? = controller.extras,
        queueTitle: CharSequence? = controller.queueTitle,
    ) {
        Logger.i(
            TAG,
            buildString {
                append("Media session [$reason]: ")
                append(describeController(controller))
                append("; playbackState=")
                append(describePlaybackState(state))
                append("; queueTitle=")
                append(queueTitle ?: "<null>")
                append("; metadata=")
                append(describeMetadata(metadata))
                append("; extras=")
                append(describeBundle(extras))
            }
        )
    }

    private fun describeController(controller: MediaController): String {
        return buildString {
            append("package=")
            append(controller.packageName)
            append(", token=")
            append(controller.sessionToken)
        }
    }

    private fun describePlaybackState(state: PlaybackState?): String {
        if (state == null) {
            return "<null>"
        }
        return buildString {
            append(playbackStateName(state.state))
            append("(")
            append(state.state)
            append(")")
            append(", position=")
            append(state.position)
            append(", speed=")
            append(state.playbackSpeed)
            append(", actions=")
            append(state.actions)
            append(", buffered=")
            append(state.bufferedPosition)
            append(", updated=")
            append(state.lastPositionUpdateTime)
            append(", error=")
            append(state.errorMessage ?: "<null>")
            append(", extras=")
            append(describeBundle(state.extras))
        }
    }

    private fun describeMetadata(metadata: MediaMetadata?): String {
        if (metadata == null) {
            return "<null>"
        }

        val keys = metadata.keySet().sorted()
        if (keys.isEmpty()) {
            return "{}"
        }
        return keys.joinToString(prefix = "{", postfix = "}") { key ->
            val textValue = metadata.getText(key)
            val longValue = metadata.getLong(key)
            val ratingValue = metadata.getRating(key)
            val bitmapValue = metadata.getBitmap(key)
            val value = when {
                textValue != null -> textValue.toString()
                ratingValue != null -> ratingValue.toString()
                bitmapValue != null -> "Bitmap(${bitmapValue.width}x${bitmapValue.height})"
                else -> longValue.toString()
            }
            "$key=$value"
        }
    }

    private fun describeBundle(bundle: Bundle?): String {
        if (bundle == null || bundle.isEmpty) {
            return "{}"
        }
        val keys = bundle.keySet().sorted()
        return keys.joinToString(prefix = "{", postfix = "}") { key ->
            "$key=${describeBundleValue(bundle, key)}"
        }
    }

    @Suppress("DEPRECATION")
    private fun describeBundleValue(bundle: Bundle, key: String): String {
        return describeValue(bundle.get(key))
    }

    private fun describeValue(value: Any?): String {
        return when (value) {
            null -> "<null>"
            is Bundle -> describeBundle(value)
            is CharSequence -> value.toString()
            is Parcelable -> value.javaClass.simpleName
            is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { describeValue(it) }
            is LongArray -> value.joinToString(prefix = "[", postfix = "]")
            is IntArray -> value.joinToString(prefix = "[", postfix = "]")
            is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
            is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
            is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
            else -> value.toString()
        }
    }

    private fun playbackStateName(state: Int): String {
        return when (state) {
            PlaybackState.STATE_NONE -> "NONE"
            PlaybackState.STATE_STOPPED -> "STOPPED"
            PlaybackState.STATE_PAUSED -> "PAUSED"
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
            PlaybackState.STATE_REWINDING -> "REWINDING"
            PlaybackState.STATE_BUFFERING -> "BUFFERING"
            PlaybackState.STATE_ERROR -> "ERROR"
            PlaybackState.STATE_CONNECTING -> "CONNECTING"
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIP_PREVIOUS"
            PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIP_NEXT"
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIP_QUEUE_ITEM"
            else -> "UNKNOWN"
        }
    }
}
