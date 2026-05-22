package pl.zarajczyk.familyrulesandroid.core

import pl.zarajczyk.familyrulesandroid.utils.Logger

class AppBlocker(coreService: FamilyRulesCoreService) {

    private var foregroundAppMonitor: ForegroundAppMonitor = ForegroundAppMonitor(coreService)
    
    // Dynamic list of packages to block, received from server
    private var packagesToBlock: List<String> = emptyList()
    
    /**
     * Block all apps in the blockedApps list
     */
    fun block(packages: List<String>) {
        packagesToBlock = packages
        Logger.i("AppBlocker", "Starting to block apps: $packagesToBlock")
        packagesToBlock.forEach { packageName ->
            blockedAppsState.add(packageName)
        }

        // Start monitoring foreground apps
        startForegroundAppMonitoring()
        
        Logger.i("AppBlocker", "Completed blocking apps: $packagesToBlock")
    }
    
    /**
     * Unblock all apps in the blockedApps list
     */
    fun unblock() {
        Logger.i("AppBlocker", "Starting to unblock apps: $packagesToBlock")
        packagesToBlock.forEach { packageName ->
            blockedAppsState.remove(packageName)
        }

        // Stop monitoring foreground apps
        stopForegroundAppMonitoring()
        
        Logger.i("AppBlocker", "Completed unblocking apps: $packagesToBlock")
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
        foregroundAppMonitor.startMonitoring(packagesToBlock)
        Logger.i("AppBlocker", "Started foreground app monitoring")
    }
    
    /**
     * Stop monitoring foreground apps
     */
    private fun stopForegroundAppMonitoring() {
        foregroundAppMonitor.stopMonitoring()
        Logger.i("AppBlocker", "Stopped foreground app monitoring")
    }
}
