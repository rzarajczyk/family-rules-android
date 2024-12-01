package pl.zarajczyk.familyrulesandroid.scheduled

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import pl.zarajczyk.familyrulesandroid.gui.PermanentNotification

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "Boot completed")
            // Start your service here
            Intent(context, PermanentNotification::class.java).also {
                context.startService(it)
            }
        }
    }
}