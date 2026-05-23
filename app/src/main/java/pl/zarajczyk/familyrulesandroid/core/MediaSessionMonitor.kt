package pl.zarajczyk.familyrulesandroid.core

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    /** True while the NotificationListenerService is connected. Media queries are only reliable then. */
    @Volatile
    private var notificationListenerConnected = false

    /** Package names whose playback should be paused when [playbackBlockingActive] is true. */
    @Volatile
    private var blockedPlaybackPackages: Set<String> = emptySet()

    /** Whether playback blocking is currently active. */
    @Volatile
    private var playbackBlockingActive: Boolean = false

    private val callbackHandler = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<String, Pair<MediaController, MediaController.Callback>>()

    private val enforcementScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var enforcementLoopJob: Job? = null

    @Volatile
    private var audioFocusHeld = false

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private const val ENFORCEMENT_POLL_INTERVAL_MS = 1_000L

    private val activeSessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        logSessionsSnapshot("active-sessions-changed", controllers.orEmpty())
        registerCallbacks(controllers.orEmpty())
    }

    /**
     * Update the set of packages whose playback must be blocked and whether blocking is active.
     * Called by PeriodicReportSender whenever the device state or blocked-playback-apps list changes.
     */
    fun updatePlaybackBlocking(enabled: Boolean, blockedPackages: Set<String>) {
        val effectiveEnabled = enabled && blockedPackages.isNotEmpty()
        playbackBlockingActive = effectiveEnabled
        blockedPlaybackPackages = blockedPackages
        Logger.i(
            TAG,
            "Playback blocking updated: enabled=$enabled, effectiveEnabled=$effectiveEnabled, blockedPackages=$blockedPackages"
        )
        if (effectiveEnabled) {
            startEnforcementLoop()
        } else {
            stopEnforcementLoop()
        }
    }

    private fun startEnforcementLoop() {
        if (enforcementLoopJob?.isActive == true) return
        enforcementLoopJob = enforcementScope.launch {
            Logger.i(TAG, "Enforcement loop started (interval=${ENFORCEMENT_POLL_INTERVAL_MS}ms)")
            while (isActive && playbackBlockingActive) {
                enforcePlaybackBlocking()
                delay(ENFORCEMENT_POLL_INTERVAL_MS)
            }
            Logger.i(TAG, "Enforcement loop stopped")
        }
    }

    private fun stopEnforcementLoop() {
        enforcementLoopJob?.cancel()
        enforcementLoopJob = null
        abandonAudioFocus()
    }

    private fun requestAudioFocusForBlocking() {
        if (audioFocusHeld) return
        val am = audioManager ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener {}
            .build()
        val result = am.requestAudioFocus(req)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = req
            audioFocusHeld = true
            Logger.i(TAG, "Audio focus acquired for playback blocking")
        } else {
            Logger.w(TAG, "Audio focus request denied (result=$result)")
        }
    }

    private fun abandonAudioFocus() {
        if (!audioFocusHeld) return
        val am = audioManager ?: return
        val req = audioFocusRequest ?: return
        am.abandonAudioFocusRequest(req)
        audioFocusHeld = false
        audioFocusRequest = null
        Logger.i(TAG, "Audio focus abandoned")
    }

    /**
     * Pause all active media sessions belonging to [blockedPlaybackPackages] when
     * [playbackBlockingActive] is true. Called once per report tick from PeriodicReportSender
     * and on every playback-state callback. Only packages explicitly present in the blocked
     * list are paused; all other apps are left untouched.
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
            val blockedControllers = controllers.filter { controller ->
                controller.packageName in blocked && isPlaybackActive(controller.playbackState?.state)
            }
            for (controller in blockedControllers) {
                Logger.i(TAG, "Pausing playback for ${controller.packageName}")
                requestAudioFocusForBlocking()
                controller.transportControls?.pause()
            }
        } catch (e: SecurityException) {
            Logger.w(TAG, "Cannot enforce playback blocking - notification access missing", e)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to enforce playback blocking", e)
        }
    }

    fun getCurrentlyPlayingPackages(): Set<String> {
        if (!initialized) return emptySet()
        if (!notificationListenerConnected) {
            Logger.w(TAG, "getCurrentlyPlayingPackages called while NotificationListenerService is disconnected — result may be empty/stale")
        }

        val manager = sessionManager ?: return emptySet()
        val component = listenerComponent ?: return emptySet()

        return try {
            manager.getActiveSessions(component)
                .orEmpty()
                .filter { controller ->
                    val state = controller.playbackState?.state
                    state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                }
                .map { it.packageName }
                .toSet()
        } catch (e: SecurityException) {
            Logger.w(TAG, "Cannot query media playing packages - notification access missing", e)
            emptySet()
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to query media playing packages", e)
            emptySet()
        }
    }

    fun install(context: Context) {
        appContext = context.applicationContext
        sessionManager = context.getSystemService(MediaSessionManager::class.java)
        audioManager = context.getSystemService(AudioManager::class.java)
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
        stopEnforcementLoop()
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
            unregisterCallback(entry.key, entry.value.first, entry.value.second)
        }
        callbacks.clear()
    }

    fun onNotificationListenerConnected(service: FamilyRulesNotificationListenerService) {
        notificationListenerConnected = true
        if (!initialized) {
            install(service)
        }
        start()
        // Re-arm the enforcement loop if playback blocking was active before the disconnect.
        // stop() cancels the loop on disconnect; start() does not restart it.
        if (playbackBlockingActive) {
            Logger.i(TAG, "NotificationListener reconnected while playback blocking is active - restarting enforcement loop")
            startEnforcementLoop()
        }
    }

    fun onNotificationListenerDisconnected(service: FamilyRulesNotificationListenerService) {
        notificationListenerConnected = false
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

    private fun pauseIfBlocked(controller: MediaController) {
        if (!playbackBlockingActive) return
        if (controller.packageName !in blockedPlaybackPackages) return
        val state = controller.playbackState?.state
        if (isPlaybackActive(state)) {
            Logger.i(TAG, "Callback: pausing playback for ${controller.packageName}")
            try {
                requestAudioFocusForBlocking()
                controller.transportControls?.pause()
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to pause ${controller.packageName} from callback", e)
            }
        }
    }

    private fun registerCallbacks(controllers: List<MediaController>) {
        val activeTokens = controllers.map { it.sessionToken.toString() }.toSet()

        callbacks.entries.removeIf { (token, pair) ->
            if (token !in activeTokens) {
                unregisterCallback(token, pair.first, pair.second)
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
                    pauseIfBlocked(controller)
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    logControllerEvent("metadata-changed", controller, metadata = metadata)
                }

                override fun onExtrasChanged(extras: Bundle?) {
                    logControllerEvent("extras-changed", controller, extras = extras)
                }

                override fun onQueueTitleChanged(title: CharSequence?) {
                    logControllerEvent("queue-title-changed", controller)
                }

                override fun onSessionDestroyed() {
                    Logger.i(TAG, "Media session destroyed: ${controller.packageName}")
                    unregisterCallback(token, controller, this)
                    callbacks.remove(token)
                }
            }

            try {
                controller.registerCallback(callback, callbackHandler)
                callbacks[token] = Pair(controller, callback)
                logControllerEvent("callback-registered", controller)
            } catch (e: SecurityException) {
                Logger.w(TAG, "Failed to register callback for ${controller.packageName}", e)
            } catch (e: Exception) {
                Logger.w(TAG, "Unexpected failure registering callback for ${controller.packageName}", e)
            }
        }
    }

    private fun isPlaybackActive(state: Int?): Boolean {
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
    }

    private fun unregisterCallback(token: String, controller: MediaController, callback: MediaController.Callback) {
        try {
            controller.unregisterCallback(callback)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to unregister callback for token=$token", e)
        }
    }

    private fun logSessionsSnapshot(reason: String, controllers: List<MediaController>) {
        Logger.d(TAG, "Active sessions snapshot [$reason]: count=${controllers.size}")
        controllers.forEach { controller ->
            logControllerEvent("snapshot-$reason", controller)
        }
    }

    private fun logControllerEvent(
        reason: String,
        controller: MediaController,
        state: PlaybackState? = controller.playbackState,
        metadata: MediaMetadata? = controller.metadata,
        extras: Bundle? = controller.extras
    ) {
        Logger.i(
            TAG,
            buildString {
                append("Media session [$reason]: ")
                append(controller.packageName)
                append(" is ")
                append(describePlaybackState(state))
                append(describeMetadata(metadata))
            }
        )
    }

    private fun describePlaybackState(state: PlaybackState?): String {
        if (state == null) {
            return "<null>"
        }
        return playbackStateName(state.state)
    }

     private fun describeMetadata(metadata: MediaMetadata?): String {
         return buildString {
             append(" [title: ")
             append(metadata?.getText(MediaMetadata.METADATA_KEY_TITLE) ?: "<unknown>")
             append("][artist: ")
             append(metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST) ?: "<unknown>")
             append("]")
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
            else -> "UNKNOWN ($state)"
        }
    }
}
