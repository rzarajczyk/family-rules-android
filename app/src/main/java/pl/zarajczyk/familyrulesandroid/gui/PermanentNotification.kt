package pl.zarajczyk.familyrulesandroid.gui

import android.content.Context
import android.content.Intent
import android.os.Build

class PermanentNotification(private val context: Context) {

    fun install() {
        val serviceIntent = Intent(context, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

}