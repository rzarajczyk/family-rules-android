package pl.zarajczyk.familyrulesandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import pl.zarajczyk.familyrulesandroid.core.FamilyRulesCoreService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            FamilyRulesCoreService.install(context)
        }
    }
}

