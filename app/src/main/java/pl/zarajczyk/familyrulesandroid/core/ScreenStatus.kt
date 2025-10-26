package pl.zarajczyk.familyrulesandroid.core

import android.content.Context
import android.os.PowerManager

object ScreenStatus {
    private lateinit var powerManager: PowerManager


    fun isScreenOn(context: Context): Boolean {
        if (!this::powerManager.isInitialized) {
            powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        }
        return powerManager.isInteractive
    }

}