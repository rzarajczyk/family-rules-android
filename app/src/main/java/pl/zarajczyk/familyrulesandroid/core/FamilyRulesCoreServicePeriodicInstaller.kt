package pl.zarajczyk.familyrulesandroid.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FamilyRulesCoreServicePeriodicInstaller {
    companion object {
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        fun install(context: Context, delayMillis: Long = 5000) {
            scope.launch {
                while (isActive) {
                    FamilyRulesCoreService.install(context)
                    delay(delayMillis)
                }
            }
        }
    }
}