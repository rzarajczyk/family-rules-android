package pl.zarajczyk.familyrulesandroid.core

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import pl.zarajczyk.familyrulesandroid.MainActivity
import pl.zarajczyk.familyrulesandroid.R
import pl.zarajczyk.familyrulesandroid.adapter.ActualDeviceState
import pl.zarajczyk.familyrulesandroid.adapter.DeviceState
import pl.zarajczyk.familyrulesandroid.entrypoints.KeepAliveWorker
import pl.zarajczyk.familyrulesandroid.entrypoints.ScreenOffReceiver
import pl.zarajczyk.familyrulesandroid.utils.Logger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


class FamilyRulesCoreService : Service() {
    private val binder = LocalBinder()

    private lateinit var periodicUsageEventsMonitor: PeriodicUsageEventsMonitor
    private lateinit var screenTimeCalculator: ScreenTimeCalculator
    private lateinit var packageUsageCalculator: PackageUsageCalculator
    private lateinit var screenOffReceiver: ScreenOffReceiver

    private lateinit var periodicReportSender: PeriodicReportSender
    private lateinit var notificationRestorer: NotificationRestorer

    private val deviceStateManager = DeviceStateManager()
    
    @Volatile
    private var foregroundStarted = false

    companion object {
        const val CHANNEL_ID = "FamilyRulesChannel"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "FamilyRulesCoreService"

        private lateinit var notificationManager: NotificationManager
        
        @Volatile
        private var serviceStarted = false

        fun install(context: Context) {
            if (!this::notificationManager.isInitialized) {
                notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            }
            
            // Check if service is already running - prevent repeated FGS starts
            if (serviceStarted || isNotificationAlive()) {
                Logger.d(TAG, "Service already running, skipping start")
                return
            }
            
            try {
                val serviceIntent = Intent(context, FamilyRulesCoreService::class.java)
                context.startForegroundService(serviceIntent)
                Logger.i(TAG, "Foreground service start requested")
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to start service: ${e.message} - this might be fine in Android 12+", e)
                // If startForegroundService fails, the service should auto-restart via START_STICKY
                // when the system allows it
            }
        }

        /**
         * Checks if the service is actually running (not just if notification exists)
         */
        fun isServiceRunning(context: Context): Boolean {
            // First check notification (fast check)
            if (!this::notificationManager.isInitialized) {
                notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            }
            if (isNotificationAlive()) {
                return true
            }
            
            // Then check if service process is actually running
            // Note: getRunningServices is deprecated but still functional for this use case
            // Alternative would be to use ServiceConnection binding, but that's more complex
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            val serviceClassName = FamilyRulesCoreService::class.java.name
            
            return runningServices.any { serviceInfo ->
                serviceInfo.service.className == serviceClassName
            }
        }

        private fun isNotificationAlive() =
            notificationManager.activeNotifications.any { it.id == NOTIFICATION_ID }

        fun bind(
            context: Context,
            callback: (FamilyRulesCoreService) -> Unit
        ): ServiceConnection {
            val serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    val binder = service as LocalBinder
                    val familyRulesCoreService = binder.getService()
                    callback(familyRulesCoreService)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                }
            }
            context.bindService(
                Intent(context, FamilyRulesCoreService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
            return serviceConnection
        }
    }

    fun getForegroundApp() = packageUsageCalculator.getForegroundApp()

    fun getTodayScreenTime() = screenTimeCalculator.getTodayScreenTime()

    fun getTodayPackageUsage() = packageUsageCalculator.getTodayPackageUsage()

    fun getCurrentDeviceState() = deviceStateManager.getCurrentState()

    fun getDeviceStateFlow() = deviceStateManager.currentState

    fun updateDeviceState(newState: ActualDeviceState) {
        val currentState = deviceStateManager.getCurrentState()
        if (currentState != newState) {
            deviceStateManager.updateState(newState)
            updateNotification()
            Logger.i(TAG, "Device state changed: ${currentState.state} -> ${newState.state}")
        }
    }

    fun resetPeriodicUsageEventsMonitor() {
        periodicUsageEventsMonitor.reset()
        periodicReportSender.reportUptimeAsync()
        periodicReportSender.sendClientInfoAsync()
    }
    
    /**
     * Ensures the notification is visible. Called by NotificationRestorer.
     */
    internal fun ensureNotificationVisible() {
        updateNotification()
    }

    override fun onCreate() {
        super.onCreate()
        serviceStarted = true
        Logger.i(TAG, "FamilyRulesCoreService onCreate() - service starting")
        
        createNotificationChannel()
        KeepAliveWorker.install(this, delayDuration = 30.minutes)
        // Removed: KeepAliveBackgroundLoop - it was causing excessive FGS starts every 60 seconds

        screenTimeCalculator = ScreenTimeCalculator()
        packageUsageCalculator = PackageUsageCalculator()

        periodicUsageEventsMonitor = PeriodicUsageEventsMonitor.install(this,
            delayDuration = 5.seconds,
            processors = listOf(
                screenTimeCalculator,
                packageUsageCalculator,
            )
        )

        // Register screen off receiver
        screenOffReceiver = ScreenOffReceiver {
            resetPeriodicUsageEventsMonitor()
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenOffReceiver, filter)

        val appBlocker = AppBlocker(this)
        periodicReportSender = PeriodicReportSender.install(this, appBlocker,
            reportDuration = 30.seconds,
            clientInfoDuration = 10.minutes)
        
        // Install notification restorer to prevent dismissal
        notificationRestorer = NotificationRestorer.install(this, checkInterval = 5.seconds)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Family Rules Service",
            NotificationManager.IMPORTANCE_HIGH  // Changed from DEFAULT to HIGH
        ).apply {
            description = "Keeps Family Rules running in background"
            enableLights(false)
            setShowBadge(false)
            // Prevent user from disabling notifications for this channel
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }

        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateNotification()
        return START_STICKY
    }

    private fun updateNotification() {
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val currentState = deviceStateManager.getCurrentState()
        val notificationText = when (currentState.state) {
            DeviceState.ACTIVE -> "Monitoring active"
            DeviceState.BLOCK_RESTRICTED_APPS -> "Monitoring active - apps blocked"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Family Rules")
            .setContentText(notificationText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)  // Changed from DEFAULT to HIGH
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)  // Makes notification non-dismissible
            .setAutoCancel(false)  // Prevents auto-dismissal
            .setShowWhen(false)  // Removes timestamp to look more permanent
            .setSilent(true)
            .setOnlyAlertOnce(true)  // No repeated alerts
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)  // API 31+
            .build()

        try {
            if (!foregroundStarted) {
                // Only call startForeground() once to avoid exhausting FGS budget
                startForeground(NOTIFICATION_ID, notification)
                foregroundStarted = true
                Logger.i(TAG, "Service started in foreground with notification")
            } else {
                // Update existing notification without re-entering foreground
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, notification)
                Logger.d(TAG, "Notification content updated")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to update notification", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceStarted = false
        foregroundStarted = false
        Logger.w(TAG, "FamilyRulesCoreService onDestroy() - service stopping")
        
        if (::screenOffReceiver.isInitialized) {
            unregisterReceiver(screenOffReceiver)
        }
        
        if (::notificationRestorer.isInitialized) {
            notificationRestorer.stop()
        }
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // For parental control: if task is removed, schedule immediate service restart
        // This helps ensure the monitoring continues even if the child swipes the app
        Logger.w(TAG, "Task removed from recents - scheduling immediate restart check")
        KeepAliveWorker.scheduleImmediateWork(this)
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    inner class LocalBinder : Binder() {
        fun getService(): FamilyRulesCoreService = this@FamilyRulesCoreService
    }
}