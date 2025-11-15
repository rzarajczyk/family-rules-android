package pl.zarajczyk.familyrulesandroid.core

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.zarajczyk.familyrulesandroid.utils.Logger
import kotlin.time.Duration

/**
 * Monitors the foreground service notification and restores it if dismissed.
 * This is critical for parental control apps where the notification must remain visible.
 */
class NotificationRestorer private constructor(
    private val coreService: FamilyRulesCoreService,
    private val checkInterval: Duration
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitoringJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    companion object {
        private const val TAG = "NotificationRestorer"

        fun install(
            coreService: FamilyRulesCoreService,
            checkInterval: Duration
        ): NotificationRestorer {
            val instance = NotificationRestorer(coreService, checkInterval)
            instance.start()
            return instance
        }
    }

    private fun start() {
        notificationManager = coreService.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        monitoringJob = scope.launch {
            Log.d(TAG, "Notification monitoring started (checking every ${checkInterval.inWholeSeconds}s)")
            
            while (isActive) {
                delay(checkInterval)
                
                try {
                    checkAndRestoreNotification()
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking notification: ${e.message}", e)
                }
            }
        }
    }

    private fun checkAndRestoreNotification() {
        val isNotificationVisible = notificationManager.activeNotifications.any { 
            it.id == FamilyRulesCoreService.NOTIFICATION_ID 
        }

        if (!isNotificationVisible) {
            Logger.w(TAG, "SECURITY ALERT: Parental control notification was dismissed by user - restoring")
            coreService.ensureNotificationVisible()
        }
    }

    fun stop() {
        monitoringJob?.cancel()
        Log.d(TAG, "Notification monitoring stopped")
    }
}
