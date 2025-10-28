package pl.zarajczyk.familyrulesandroid.core

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
class ForegroundAppMonitor(private val coreService: FamilyRulesCoreService) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isMonitoring = false
    private var lastForegroundApp: String? = null
    private var packagesToBlock: List<String> = emptyList()
    
    fun startMonitoring(packagesToBlock: List<String>) {
        this.packagesToBlock = packagesToBlock
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
        val currentApp = coreService.getForegroundApp()
        
        if (currentApp != null && currentApp != lastForegroundApp) {
            Log.d("ForegroundAppMonitor", "Foreground app changed to: $currentApp")
            lastForegroundApp = currentApp
            
            if (currentApp in packagesToBlock) {
                Log.i("ForegroundAppMonitor", "Blocked app detected: $currentApp")
                showBlockingOverlay(currentApp)
            } else {
                hideBlockingOverlay()
            }
        }
    }

    private fun showBlockingOverlay(packageName: String) {
        Log.i("ForegroundAppMonitor", "Showing blocking overlay for: $packageName")
        AppBlockingOverlayService.showOverlay(coreService, packageName)
    }
    
    private fun hideBlockingOverlay() {
        Log.d("ForegroundAppMonitor", "Hiding blocking overlay")
        AppBlockingOverlayService.hideOverlay(coreService)
    }
}
