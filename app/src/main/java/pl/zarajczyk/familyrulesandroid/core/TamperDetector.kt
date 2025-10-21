package pl.zarajczyk.familyrulesandroid.core

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.*

class TamperDetector(private val context: Context) {
    
    private val packageManager = context.packageManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isMonitoring = false
    
    fun startMonitoring() {
        if (isMonitoring) return
        
        isMonitoring = true
        Log.i("TamperDetector", "Started tamper monitoring")
        
        scope.launch {
            while (isActive && isMonitoring) {
                checkForTampering()
                delay(30_000) // Check every 30 seconds
            }
        }
    }
    
    fun stopMonitoring() {
        isMonitoring = false
        scope.cancel()
        Log.i("TamperDetector", "Stopped tamper monitoring")
    }
    
    private suspend fun checkForTampering() {
        try {
            // Check if app is still installed
            val packageInfo = packageManager.getPackageInfo(context.packageName, 0)
            if (packageInfo == null) {
                Log.w("TamperDetector", "App package not found - possible uninstall attempt")
                notifyTamperDetected("App package not found")
                return
            }
            
            // Check if device admin is still active
            val deviceAdminManager = DeviceAdminManager(context)
            if (!deviceAdminManager.isDeviceAdminActive()) {
                Log.w("TamperDetector", "Device admin privileges removed")
                notifyTamperDetected("Device admin privileges removed")
            }
            
            // Note: Stealth mode removed - child should be aware of monitoring
            
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w("TamperDetector", "App not found - possible uninstall attempt")
            notifyTamperDetected("App not found - possible uninstall")
        } catch (e: Exception) {
            Log.e("TamperDetector", "Error during tamper check: ${e.message}", e)
        }
    }
    
    private fun notifyTamperDetected(reason: String) {
        Log.w("TamperDetector", "TAMPER DETECTED: $reason")
        
        // Here you would typically:
        // 1. Send notification to parent
        // 2. Lock the device
        // 3. Send alert to server
        // 4. Take screenshot
        
        // For now, just log the detection
        // In a real implementation, you'd integrate with your existing notification system
    }
    
    fun checkPermissionsStatus(): Map<String, Boolean> {
        val permissions = mapOf(
            "PACKAGE_USAGE_STATS" to checkUsageStatsPermission(),
            "DEVICE_ADMIN" to DeviceAdminManager(context).isDeviceAdminActive(),
            "NOTIFICATION_ACCESS" to checkNotificationAccess(),
            "ACCESSIBILITY_SERVICE" to checkAccessibilityService()
        )
        
        return permissions
    }
    
    private fun checkUsageStatsPermission(): Boolean {
        return try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val time = System.currentTimeMillis()
            usageStatsManager.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                time - 1000 * 60 * 60 * 24,
                time
            )
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun checkNotificationAccess(): Boolean {
        return try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.isNotificationPolicyAccessGranted
        } catch (e: Exception) {
            false
        }
    }
    
    private fun checkAccessibilityService(): Boolean {
        return try {
            val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            accessibilityManager.isEnabled
        } catch (e: Exception) {
            false
        }
    }
}
