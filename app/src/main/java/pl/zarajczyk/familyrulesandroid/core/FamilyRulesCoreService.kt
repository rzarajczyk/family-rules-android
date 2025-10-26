package pl.zarajczyk.familyrulesandroid.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import pl.zarajczyk.familyrulesandroid.MainActivity
import pl.zarajczyk.familyrulesandroid.R
import pl.zarajczyk.familyrulesandroid.adapter.DeviceState
import pl.zarajczyk.familyrulesandroid.database.AppDb
import pl.zarajczyk.familyrulesandroid.entrypoints.KeepAliveWorker
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


class FamilyRulesCoreService : Service() {
    private val binder = LocalBinder()
    private lateinit var periodicUptimeChecker: PeriodicUptimeChecker
    private val deviceStateManager = DeviceStateManager()

    companion object {
        const val CHANNEL_ID = "FamilyRulesChannel"
        const val NOTIFICATION_ID = 1001
        
        private lateinit var notificationManager: NotificationManager

        fun install(context: Context) {
            if (!this::notificationManager.isInitialized) {
                notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            }
            if (!isNotificationAlive()) {
                val serviceIntent = Intent(context, FamilyRulesCoreService::class.java)
                context.startForegroundService(serviceIntent)
            }
        }

        private fun isNotificationAlive() =
            notificationManager.activeNotifications.any { it.id == NOTIFICATION_ID }

        fun bind(
            context: Context,
            callback: (FamilyRulesCoreService) -> Unit
        ) {
            context.bindService(
                Intent(context, FamilyRulesCoreService::class.java),
                object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        val binder = service as LocalBinder
                        val familyRulesCoreService = binder.getService()
                        callback(familyRulesCoreService)
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                    }
                },
                Context.BIND_AUTO_CREATE
            )
        }
    }

    fun getUptime() = periodicUptimeChecker.getUptime()
    
    fun forceUptimeUpdate() = periodicUptimeChecker.forceUpdate()
    
    fun getCurrentDeviceState() = deviceStateManager.getCurrentState()
    
    fun getDeviceStateFlow() = deviceStateManager.currentState
    
    fun updateDeviceState(newState: DeviceState) {
        val currentState = deviceStateManager.getCurrentState()
        if (currentState != newState) {
            deviceStateManager.updateState(newState)
            updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        KeepAliveWorker.install(this, delayDuration = 30.minutes)
        KeepAliveBackgroundLoop.install(this, delayDuration = 30.seconds)
        periodicUptimeChecker = PeriodicUptimeChecker.install(this, delayDuration = 60.seconds)
        val periodicReportSender = PeriodicReportSender(
            this,
            settingsManager = SettingsManager(this),
            periodicUptimeChecker,
            delayDuration = 30.seconds,
            appDb = AppDb(this)
        )
        periodicReportSender.setCoreService(this)
        periodicReportSender.start()
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
            DeviceState.BLOCK_LIMITTED_APPS -> "Monitoring active - apps blocked"
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

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    inner class LocalBinder : Binder() {
        fun getService(): FamilyRulesCoreService = this@FamilyRulesCoreService
    }
}