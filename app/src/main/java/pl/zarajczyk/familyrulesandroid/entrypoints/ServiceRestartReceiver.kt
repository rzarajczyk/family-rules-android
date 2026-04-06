package pl.zarajczyk.familyrulesandroid.entrypoints

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import pl.zarajczyk.familyrulesandroid.core.FamilyRulesCoreService
import pl.zarajczyk.familyrulesandroid.core.ServiceKeepAliveAlarm
import kotlin.time.Duration.Companion.minutes

/**
 * BroadcastReceiver that restarts the FamilyRulesCoreService.
 * This receiver is triggered by AlarmManager, which is allowed to start FGS on Android 12+
 */
class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_RESTART_SERVICE) {
            Log.i(TAG, "ServiceRestartReceiver triggered - restarting service")
            
            // Always re-arm the next alarm first, so the chain continues regardless of service state.
            ServiceKeepAliveAlarm.scheduleAlarm(context, 5.minutes)
            
            // BroadcastReceiver context is allowed to start FGS on Android 12+
            // when triggered by AlarmManager
            if (!FamilyRulesCoreService.isServiceRunning(context)) {
                FamilyRulesCoreService.install(context)
                Log.i(TAG, "Service was not running - start requested")
            } else {
                Log.d(TAG, "Service is already running")
            }
        }
    }
    
    companion object {
        const val ACTION_RESTART_SERVICE = "pl.zarajczyk.familyrulesandroid.ACTION_RESTART_SERVICE"
        private const val TAG = "ServiceRestartReceiver"
    }
}
