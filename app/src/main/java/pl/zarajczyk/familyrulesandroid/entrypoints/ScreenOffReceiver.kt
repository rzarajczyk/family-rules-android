package pl.zarajczyk.familyrulesandroid.entrypoints

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ScreenOffReceiver(
    private val onScreenOff: () -> Unit
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_SCREEN_OFF) {
            Log.d("ScreenOffReceiver", "Screen turned off, resetting PeriodicUsageEventsMonitor")
            onScreenOff()
        }
    }
}
