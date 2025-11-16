package pl.zarajczyk.familyrulesandroid.entrypoints

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import pl.zarajczyk.familyrulesandroid.core.FamilyRulesCoreService

/**
 * BroadcastReceiver that restarts the FamilyRulesCoreService.
 * This receiver is triggered by AlarmManager, which is allowed to start FGS on Android 12+
 */
class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_RESTART_SERVICE) {
            Log.i(TAG, "ServiceRestartReceiver triggered - restarting service")
            
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
