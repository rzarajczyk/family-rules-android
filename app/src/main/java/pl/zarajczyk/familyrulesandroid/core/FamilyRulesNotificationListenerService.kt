package pl.zarajczyk.familyrulesandroid.core

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import pl.zarajczyk.familyrulesandroid.utils.Logger

class FamilyRulesNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "FamilyRulesNotifListener"

        @Volatile
        private var currentInstance: FamilyRulesNotificationListenerService? = null

        fun getInstance(): FamilyRulesNotificationListenerService? = currentInstance
    }

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        Logger.i(TAG, "Notification listener created")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        currentInstance = this
        Logger.i(TAG, "Notification listener connected")
        MediaSessionMonitor.onNotificationListenerConnected(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Logger.w(TAG, "Notification listener disconnected")
        MediaSessionMonitor.onNotificationListenerDisconnected(this)
        if (currentInstance === this) {
            currentInstance = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.w(TAG, "Notification listener destroyed")
        MediaSessionMonitor.onNotificationListenerDisconnected(this)
        if (currentInstance === this) {
            currentInstance = null
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        MediaSessionMonitor.refreshActiveSessions("notification-posted")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        MediaSessionMonitor.refreshActiveSessions("notification-removed")
    }
}
