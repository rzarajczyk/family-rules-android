package pl.zarajczyk.familyrulesandroid.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import pl.zarajczyk.familyrulesandroid.entrypoints.ServiceRestartReceiver
import kotlin.time.Duration

/**
 * Manages periodic exact alarms to keep the service alive.
 * This works on Android 12+ because AlarmManager broadcasts are allowed to start FGS.
 */
object ServiceKeepAliveAlarm {
    private const val TAG = "ServiceKeepAliveAlarm"
    private const val REQUEST_CODE = 10001
    
    /**
     * Schedules a recurring exact alarm to check and restart the service.
     * On Android 12+, this is one of the few ways to reliably start FGS from background.
     */
    fun scheduleAlarm(context: Context, interval: Duration) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        // Check if we have permission on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                Log.w(TAG, "Cannot schedule exact alarms - permission not granted")
                return
            }
        }
        
        val intent = Intent(context, ServiceRestartReceiver::class.java).apply {
            action = ServiceRestartReceiver.ACTION_RESTART_SERVICE
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val triggerAtMillis = System.currentTimeMillis() + interval.inWholeMilliseconds
        
        try {
            // Use setExactAndAllowWhileIdle for best reliability
            // This works even in Doze mode
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            Log.i(TAG, "Exact alarm scheduled for ${interval.inWholeSeconds} seconds from now")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule exact alarm: ${e.message}", e)
        }
    }
    
    /**
     * Cancels the keep-alive alarm.
     */
    fun cancelAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        val intent = Intent(context, ServiceRestartReceiver::class.java).apply {
            action = ServiceRestartReceiver.ACTION_RESTART_SERVICE
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
        Log.i(TAG, "Keep-alive alarm cancelled")
    }
    
    /**
     * Checks if exact alarm permission is granted (Android 12+)
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            return alarmManager.canScheduleExactAlarms()
        }
        return true // Always available on Android 11 and below
    }
}
