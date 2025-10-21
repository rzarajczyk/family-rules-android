package pl.zarajczyk.familyrulesandroid.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

class StealthModeManager(private val context: Context) {
    
    private val packageManager = context.packageManager
    private val mainActivityComponent = ComponentName(context, "pl.zarajczyk.familyrulesandroid.MainActivity")
    
    fun enableStealthMode() {
        try {
            packageManager.setComponentEnabledSetting(
                mainActivityComponent,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.i("StealthMode", "Stealth mode enabled - app icon hidden")
        } catch (e: Exception) {
            Log.e("StealthMode", "Failed to enable stealth mode: ${e.message}", e)
        }
    }
    
    fun disableStealthMode() {
        try {
            packageManager.setComponentEnabledSetting(
                mainActivityComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.i("StealthMode", "Stealth mode disabled - app icon visible")
        } catch (e: Exception) {
            Log.e("StealthMode", "Failed to disable stealth mode: ${e.message}", e)
        }
    }
    
    fun isStealthModeEnabled(): Boolean {
        return packageManager.getComponentEnabledSetting(mainActivityComponent) == 
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
    
    fun toggleStealthMode(): Boolean {
        return if (isStealthModeEnabled()) {
            disableStealthMode()
            false
        } else {
            enableStealthMode()
            true
        }
    }
}
