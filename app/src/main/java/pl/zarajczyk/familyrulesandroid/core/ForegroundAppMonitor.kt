package pl.zarajczyk.familyrulesandroid.core

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Monitors foreground apps to detect when blocked apps are opened
 */
class ForegroundAppMonitor(
    private val context: Context,
    private val appBlocker: AppBlocker
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isMonitoring = false
    private var lastForegroundApp: String? = null
    
    fun startMonitoring() {
        if (isMonitoring) {
            Log.d("ForegroundAppMonitor", "Already monitoring")
            return
        }
        
        isMonitoring = true
        Log.i("ForegroundAppMonitor", "Starting foreground app monitoring")
        
        scope.launch {
            while (isActive && isMonitoring) {
                try {
                    checkForegroundApp()
                    delay(1.seconds) // Check every second
                } catch (e: Exception) {
                    Log.e("ForegroundAppMonitor", "Error monitoring foreground app: ${e.message}", e)
                    delay(5.seconds) // Wait longer on error
                }
            }
        }
    }
    
    fun stopMonitoring() {
        isMonitoring = false
        Log.i("ForegroundAppMonitor", "Stopped foreground app monitoring")
    }
    
    private fun checkForegroundApp() {
        val currentApp = getCurrentForegroundApp()
        
        if (currentApp != null && currentApp != lastForegroundApp) {
            Log.d("ForegroundAppMonitor", "Foreground app changed to: $currentApp")
            lastForegroundApp = currentApp
            
            // Check if the current app is blocked
            if (appBlocker.isAppBlocked(currentApp)) {
                Log.i("ForegroundAppMonitor", "Blocked app detected: $currentApp")
                showBlockingOverlay(currentApp)
            } else {
                // Hide overlay if it was showing for a blocked app
                hideBlockingOverlay()
            }
        }
    }
    
    private fun getCurrentForegroundApp(): String? {
        return try {
            // Method 1: Using UsageStatsManager (requires PACKAGE_USAGE_STATS permission)
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val currentTime = System.currentTimeMillis()
            val usageEvents = usageStatsManager.queryEvents(currentTime - 1000, currentTime)
            
            var lastEvent: UsageEvents.Event? = null
            while (usageEvents.hasNextEvent()) {
                val event = UsageEvents.Event()
                if (usageEvents.getNextEvent(event)) {
                    if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        lastEvent = event
                    }
                }
            }
            
            lastEvent?.packageName
        } catch (e: Exception) {
            Log.w("ForegroundAppMonitor", "Failed to get foreground app via UsageStats: ${e.message}")
            
            // Method 2: Fallback using ActivityManager (less reliable but works without special permissions)
            try {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val runningTasks = activityManager.appTasks
                if (runningTasks.isNotEmpty()) {
                    val topTask = runningTasks[0]
                    val topActivity = topTask.taskInfo?.topActivity
                    topActivity?.packageName
                } else {
                    null
                }
            } catch (e2: Exception) {
                Log.w("ForegroundAppMonitor", "Failed to get foreground app via ActivityManager: ${e2.message}")
                null
            }
        }
    }
    
    private fun showBlockingOverlay(packageName: String) {
        Log.i("ForegroundAppMonitor", "Showing blocking overlay for: $packageName")
        AppBlockingOverlayService.showOverlay(context, packageName)
    }
    
    private fun hideBlockingOverlay() {
        Log.d("ForegroundAppMonitor", "Hiding blocking overlay")
        AppBlockingOverlayService.hideOverlay(context)
    }
    
    fun isCurrentlyMonitoring(): Boolean = isMonitoring
}
