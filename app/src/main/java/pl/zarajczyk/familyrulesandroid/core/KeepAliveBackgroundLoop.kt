package pl.zarajczyk.familyrulesandroid.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

class KeepAliveBackgroundLoop {
    companion object {
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        fun install(context: Context, delayDuration: Duration) {
            scope.launch {
                while (isActive) {
                    FamilyRulesCoreService.install(context)
                    delay(delayDuration)
                }
            }
        }
    }
}