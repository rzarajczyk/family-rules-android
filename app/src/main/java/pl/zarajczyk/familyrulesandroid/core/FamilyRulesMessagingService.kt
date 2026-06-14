package pl.zarajczyk.familyrulesandroid.core

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import pl.zarajczyk.familyrulesandroid.utils.Logger

class FamilyRulesMessagingService : FirebaseMessagingService() {
    companion object {
        private const val TAG = "FamilyRulesMessagingService"
        const val ACTION_FORCE_REPORT = "FORCE_REPORT"
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["action"] == ACTION_FORCE_REPORT) {
            Logger.i(TAG, "Received force-report push")
            FamilyRulesCoreService.requestForceReport(applicationContext)
        }
    }

    override fun onNewToken(token: String) {
        Logger.i(TAG, "FCM token updated")
        SettingsManager(this).setString(FcmTokenRegistrar.PUSH_TOKEN_KEY, token)
        FamilyRulesCoreService.install(applicationContext)
        FamilyRulesCoreService.requestPushTokenSync(applicationContext)
    }
}
