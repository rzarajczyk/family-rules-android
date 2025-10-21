package pl.zarajczyk.familyrulesandroid.core

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class FamilyRulesDeviceAdminReceiver : DeviceAdminReceiver() {
    
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i("DeviceAdmin", "Device admin enabled - app is now protected from uninstall")
    }
    
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w("DeviceAdmin", "Device admin disabled - app can now be uninstalled")
    }
    
    override fun onPasswordChanged(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordChanged(context, intent, user)
        Log.i("DeviceAdmin", "Password changed")
    }
    
    override fun onPasswordFailed(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordFailed(context, intent, user)
        Log.w("DeviceAdmin", "Password failed - potential tampering attempt")
    }
}
