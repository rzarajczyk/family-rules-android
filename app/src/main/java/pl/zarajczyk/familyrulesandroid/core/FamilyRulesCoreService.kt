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
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import pl.zarajczyk.familyrulesandroid.MainActivity
import pl.zarajczyk.familyrulesandroid.R
import pl.zarajczyk.familyrulesandroid.entrypoints.KeepAliveWorker
import pl.zarajczyk.familyrulesandroid.utils.toHMS
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


class FamilyRulesCoreService : Service() {
    private val binder = LocalBinder()
    private lateinit var periodicUptimeChecker: PeriodicUptimeChecker

    companion object {
        const val CHANNEL_ID = "FamilyRulesChannel"
        const val NOTIFICATION_ID = 1001

        fun install(context: Context) {
            if (!isNotificationAlive(context)) {
                val serviceIntent = Intent(context, FamilyRulesCoreService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }

        private fun isNotificationAlive(context: Context): Boolean {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            return notificationManager.activeNotifications.any { it.id == NOTIFICATION_ID }
        }

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
                        familyRulesCoreService.updateNotification()
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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        KeepAliveWorker.install(this, delayDuration = 30.minutes)
        FamilyRulesCoreServicePeriodicInstaller.install(this, delayDuration = 30.seconds)
        periodicUptimeChecker = PeriodicUptimeChecker(this, delayDuration = 60.seconds)
            .also { it.start() }
        PeriodicReportSender.install(
            this,
            settingsManager = SettingsManager(this),
            periodicUptimeChecker,
            delayMillis = 10.seconds
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Family Rules Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Keeps Family Rules running in background"
                enableLights(true)
                setShowBadge(true)
            }

            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateNotification()

        return START_STICKY
    }

    fun updateNotification() {
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Family Rules")
            .setContentText("Screen time: ${getUptime().screenTimeMillis.toHMS()}")
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