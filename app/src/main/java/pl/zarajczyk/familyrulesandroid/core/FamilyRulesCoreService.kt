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
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import pl.zarajczyk.familyrulesandroid.MainActivity
import pl.zarajczyk.familyrulesandroid.R
import pl.zarajczyk.familyrulesandroid.adapter.DeviceState
import pl.zarajczyk.familyrulesandroid.entrypoints.KeepAliveWorker
import pl.zarajczyk.familyrulesandroid.entrypoints.ScreenOffReceiver
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


class FamilyRulesCoreService : Service() {
    private val binder = LocalBinder()

    private lateinit var periodicUsageEventsMonitor: PeriodicUsageEventsMonitor
    private lateinit var screenTimeCalculator: ScreenTimeCalculator
    private lateinit var packageUsageCalculator: PackageUsageCalculator
    private lateinit var screenOffReceiver: ScreenOffReceiver

    private lateinit var periodicReportSender: PeriodicReportSender

    private val deviceStateManager = DeviceStateManager()

    companion object {
        const val CHANNEL_ID = "FamilyRulesChannel"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "FamilyRulesCoreService"

        private lateinit var notificationManager: NotificationManager

        fun install(context: Context) {
            if (!this::notificationManager.isInitialized) {
                notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            }
            
            try {
                val serviceIntent = Intent(context, FamilyRulesCoreService::class.java)
                context.startForegroundService(serviceIntent)
                Log.d(TAG, "Service start requested")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service: ${e.message}", e)
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

    fun updateDeviceState(newState: DeviceState) {
        val currentState = deviceStateManager.getCurrentState()
        if (currentState != newState) {
            deviceStateManager.updateState(newState)
            updateNotification()
        }
    }

    fun resetPeriodicUsageEventsMonitor() {
        periodicUsageEventsMonitor.reset()
        periodicReportSender.reportUptimeAsync()
        periodicReportSender.sendClientInfoAsync()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        KeepAliveWorker.install(this, delayDuration = 30.minutes)
        KeepAliveBackgroundLoop.install(this, delayDuration = 60.seconds)

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
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Family Rules Service",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Keeps Family Rules running in background"
            enableLights(false)
            setShowBadge(false)
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
        val notificationText = when (currentState) {
            DeviceState.ACTIVE -> "Monitoring active"
            DeviceState.BLOCK_RESTRICTED_APPS -> "Monitoring active - apps blocked"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Family Rules")
            .setContentText(notificationText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::screenOffReceiver.isInitialized) {
            unregisterReceiver(screenOffReceiver)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    inner class LocalBinder : Binder() {
        fun getService(): FamilyRulesCoreService = this@FamilyRulesCoreService
    }
}