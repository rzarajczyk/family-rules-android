package pl.zarajczyk.familyrulesandroid.entrypoints

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import pl.zarajczyk.familyrulesandroid.utils.Logger

class ScreenOnReceiver(
    private val onScreenOn: () -> Unit
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_SCREEN_ON) {
            Logger.d("ScreenOnReceiver", "Screen turned on, notifying report loop")
            onScreenOn()
        }
    }
}
