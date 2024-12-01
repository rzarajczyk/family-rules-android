package pl.zarajczyk.familyrulesandroid.scheduled

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Start your service here
            Intent(context, KeepAliveService::class.java).also {
                context.startService(it)
            }
        }
    }
}

