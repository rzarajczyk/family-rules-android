package pl.zarajczyk.familyrulesandroid.core

import com.google.firebase.messaging.FirebaseMessaging
import pl.zarajczyk.familyrulesandroid.utils.Logger

object FcmTokenRegistrar {
    private const val TAG = "FcmTokenRegistrar"
    const val PUSH_TOKEN_KEY = "fcmPushToken"

    fun refreshToken(settingsManager: SettingsManager, onComplete: () -> Unit = {}) {
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Logger.e(TAG, "Failed to obtain FCM token", task.exception)
                    onComplete()
                    return@addOnCompleteListener
                }
                val token = task.result
                if (token.isNullOrBlank()) {
                    Logger.w(TAG, "FCM token was blank")
                    onComplete()
                    return@addOnCompleteListener
                }
                settingsManager.setString(PUSH_TOKEN_KEY, token)
                Logger.i(TAG, "FCM token refreshed")
                onComplete()
            }
    }
}
