package pl.zarajczyk.familyrulesandroid.core

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

class DeviceAdminManager(private val context: Context) {
    
    private val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, FamilyRulesDeviceAdminReceiver::class.java)
    
    fun isDeviceAdminActive(): Boolean {
        return devicePolicyManager.isAdminActive(adminComponent)
    }
    
    fun requestDeviceAdminPermission(): Intent {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, 
            "This app needs device administrator rights to prevent unauthorized uninstallation and protect your child's device.")
        return intent
    }
    
    fun lockDevice() {
        if (isDeviceAdminActive()) {
            try {
                devicePolicyManager.lockNow()
                Log.i("DeviceAdmin", "Device locked successfully")
            } catch (e: Exception) {
                Log.e("DeviceAdmin", "Failed to lock device: ${e.message}", e)
            }
        }
    }
    
    fun setPasswordQuality(quality: Int) {
        if (isDeviceAdminActive()) {
            try {
                devicePolicyManager.setPasswordQuality(adminComponent, quality)
                Log.i("DeviceAdmin", "Password quality set to: $quality")
            } catch (e: Exception) {
                Log.e("DeviceAdmin", "Failed to set password quality: ${e.message}", e)
            }
        }
    }
    
    fun setPasswordMinimumLength(length: Int) {
        if (isDeviceAdminActive()) {
            try {
                devicePolicyManager.setPasswordMinimumLength(adminComponent, length)
                Log.i("DeviceAdmin", "Password minimum length set to: $length")
            } catch (e: Exception) {
                Log.e("DeviceAdmin", "Failed to set password minimum length: ${e.message}", e)
            }
        }
    }
    
    fun disableCamera(disable: Boolean) {
        if (isDeviceAdminActive()) {
            try {
                devicePolicyManager.setCameraDisabled(adminComponent, disable)
                Log.i("DeviceAdmin", "Camera disabled: $disable")
            } catch (e: Exception) {
                Log.e("DeviceAdmin", "Failed to disable camera: ${e.message}", e)
            }
        }
    }
    
    companion object {
        const val PASSWORD_QUALITY_NUMERIC = DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
        const val PASSWORD_QUALITY_ALPHABETIC = DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC
        const val PASSWORD_QUALITY_ALPHANUMERIC = DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC
    }
}
