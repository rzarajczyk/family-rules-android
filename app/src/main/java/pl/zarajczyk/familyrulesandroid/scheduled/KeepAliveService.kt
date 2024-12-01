package pl.zarajczyk.familyrulesandroid.scheduled

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import pl.zarajczyk.familyrulesandroid.MainActivity
import pl.zarajczyk.familyrulesandroid.gui.SettingsManager

class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {

        val settingsManager = SettingsManager(this)

        if (settingsManager.areSettingsComplete()) {
            Log.d("KeepAliveService", "Settings are complete")
//            val intent = Intent(this, MainActivity::class.java)
//            startActivity(intent)
        }

        super.onCreate()
    }

}