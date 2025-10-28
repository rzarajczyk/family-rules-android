package pl.zarajczyk.familyrulesandroid.core

import android.content.Context
import android.util.Log

class AppBlocker(coreService: FamilyRulesCoreService) {

    private var foregroundAppMonitor: ForegroundAppMonitor = ForegroundAppMonitor(coreService)
    
    // Hardcoded list of apps to block when BLOCK_LIMITTED_APPS state is active
    private val blockedApps = listOf(
        "com.android.chrome"
    )
    
    /**
     * Block all apps in the blockedApps list
     */
    fun blockLimitedApps() {
        Log.i("AppBlocker", "Starting to block limited apps: $blockedApps")
        blockedApps.forEach { packageName ->
            blockedAppsState.add(packageName)
        }

        // Start monitoring foreground apps
        startForegroundAppMonitoring()
        
        Log.i("AppBlocker", "Completed blocking limited apps: $blockedApps")
    }
    
    /**
     * Unblock all apps in the blockedApps list
     */
    fun unblockLimitedApps() {
        Log.i("AppBlocker", "Starting to unblock limited apps: $blockedApps")
        blockedApps.forEach { packageName ->
            blockedAppsState.remove(packageName)
        }

        // Stop monitoring foreground apps
        stopForegroundAppMonitoring()
        
        Log.i("AppBlocker", "Completed unblocking limited apps: $blockedApps")
    }

    /**
     * Check if an app is currently blocked
     * Since we're using a monitoring approach, we'll track the blocking state internally
     */
    private var blockedAppsState = mutableSetOf<String>()
    
    /**
     * Start monitoring foreground apps for blocking
     */
    private fun startForegroundAppMonitoring() {
        foregroundAppMonitor.startMonitoring(blockedApps)
        Log.i("AppBlocker", "Started foreground app monitoring")
    }
    
    /**
     * Stop monitoring foreground apps
     */
    private fun stopForegroundAppMonitoring() {
        foregroundAppMonitor.stopMonitoring()
        Log.i("AppBlocker", "Stopped foreground app monitoring")
    }
}
