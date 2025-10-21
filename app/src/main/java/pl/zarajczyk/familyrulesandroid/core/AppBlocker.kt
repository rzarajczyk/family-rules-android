package pl.zarajczyk.familyrulesandroid.core

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

class AppBlocker(private val context: Context) {
    
    private val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, FamilyRulesDeviceAdminReceiver::class.java)
    
    // Hardcoded list of apps to block when BLOCK_LIMITTED_APPS state is active
    private val blockedApps = listOf(
        "com.android.chrome"
    )
    
    /**
     * Block all apps in the blockedApps list
     */
    fun blockLimitedApps() {
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            Log.w("AppBlocker", "Device admin not active, cannot block apps")
            return
        }
        
        Log.i("AppBlocker", "Starting to block limited apps: $blockedApps")
        blockedApps.forEach { packageName ->
            blockApp(packageName)
        }
        
        Log.i("AppBlocker", "Completed blocking limited apps: $blockedApps")
    }
    
    /**
     * Unblock all apps in the blockedApps list
     */
    fun unblockLimitedApps() {
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            Log.w("AppBlocker", "Device admin not active, cannot unblock apps")
            return
        }
        
        Log.i("AppBlocker", "Starting to unblock limited apps: $blockedApps")
        blockedApps.forEach { packageName ->
            unblockApp(packageName)
        }
        
        Log.i("AppBlocker", "Completed unblocking limited apps: $blockedApps")
    }
    
    /**
     * Block a specific app using monitoring and user guidance approach
     */
    private fun blockApp(packageName: String) {
        try {
            // Check if the app is installed
            if (!isAppInstalled(packageName)) {
                Log.d("AppBlocker", "App $packageName is not installed, skipping")
                return
            }
            
            // Since we can't use device policy manager methods due to permission restrictions,
            // we'll implement a monitoring-based approach:
            // 1. Log the blocking attempt for monitoring purposes
            // 2. The app will show visual feedback (red background) to indicate blocking mode
            // 3. In a real implementation, you could integrate with accessibility services
            //    or other monitoring solutions to detect and redirect app usage
            
            // Track the blocked state internally
            blockedAppsState.add(packageName)
            
            Log.i("AppBlocker", "App blocking mode activated for: $packageName")
            Log.i("AppBlocker", "Note: Due to Android security restrictions, direct app blocking requires additional permissions.")
            Log.i("AppBlocker", "Consider implementing accessibility service or parental control integration for full blocking capability.")
            
            // For now, we'll rely on the visual feedback (red background) to indicate blocking mode
            // and the notification text to inform users about the blocking state
            
        } catch (e: Exception) {
            Log.e("AppBlocker", "Failed to block app $packageName: ${e.message}", e)
        }
    }
    
    /**
     * Unblock a specific app by removing monitoring restrictions
     */
    private fun unblockApp(packageName: String) {
        try {
            // Check if the app is installed
            if (!isAppInstalled(packageName)) {
                Log.d("AppBlocker", "App $packageName is not installed, skipping")
                return
            }
            
            // Remove from blocked state
            blockedAppsState.remove(packageName)
            
            // Since we're using a monitoring-based approach, we'll log the unblocking
            Log.i("AppBlocker", "App unblocking mode activated for: $packageName")
            Log.i("AppBlocker", "App is now allowed to be used normally.")
            
        } catch (e: Exception) {
            Log.e("AppBlocker", "Failed to unblock app $packageName: ${e.message}", e)
        }
    }
    
    /**
     * Check if an app is currently blocked
     * Since we're using a monitoring approach, we'll track the blocking state internally
     */
    private var blockedAppsState = mutableSetOf<String>()
    
    fun isAppBlocked(packageName: String): Boolean {
        return blockedAppsState.contains(packageName)
    }
    
    /**
     * Get the list of currently blocked apps
     */
    fun getBlockedApps(): List<String> {
        return blockedAppsState.toList()
    }
    
    /**
     * Check if an app is installed on the device
     */
    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    
    /**
     * Get the list of apps that should be blocked (for reference)
     */
    fun getBlockedAppsList(): List<String> {
        return blockedApps.toList()
    }
    
    /**
     * Test method to verify app blocking functionality
     * This can be called for testing purposes
     */
    fun testAppBlocking() {
        Log.i("AppBlocker", "Testing app blocking functionality...")
        Log.i("AppBlocker", "Device admin active: ${devicePolicyManager.isAdminActive(adminComponent)}")
        Log.i("AppBlocker", "Apps to block: $blockedApps")
        
        blockedApps.forEach { packageName ->
            val isInstalled = isAppInstalled(packageName)
            val isCurrentlyBlocked = isAppBlocked(packageName)
            Log.i("AppBlocker", "App $packageName - Installed: $isInstalled, Currently blocked: $isCurrentlyBlocked")
        }
    }
}
