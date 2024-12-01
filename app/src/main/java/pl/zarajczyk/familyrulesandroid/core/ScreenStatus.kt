package pl.zarajczyk.familyrulesandroid.core

import android.content.Context
import android.os.PowerManager

object ScreenStatus {

    fun isScreenOn(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isInteractive
    }

}