package pl.zarajczyk.familyrulesandroid.scheduled

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.zarajczyk.familyrulesandroid.gui.PermanentNotification

class KeepAliveService {
    companion object {
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        fun install(context: Context, delayMillis: Long = 5000) {
            scope.launch {
                while (isActive) {
                    if (!PermanentNotification.isNotificationAlive(context)) {
                        PermanentNotification.install(context)
                    }
                    delay(delayMillis)
                }
            }
        }
    }
}