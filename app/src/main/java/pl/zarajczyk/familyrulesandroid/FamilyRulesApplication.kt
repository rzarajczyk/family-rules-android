package pl.zarajczyk.familyrulesandroid

import android.app.Application
import pl.zarajczyk.familyrulesandroid.utils.Logger

class FamilyRulesApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Initialize unified logger for all app logs and crashes
        Logger.init(this)
        Logger.i("Application", "FamilyRules application starting...")
        
        // Set up global exception handler
        val defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Log the crash with full details
            Logger.e("UncaughtException", "Uncaught exception in thread: ${thread.name}", throwable)
            Logger.logCrash(throwable, thread)
            
            // Call the default handler to let Android handle the crash normally
            defaultExceptionHandler?.uncaughtException(thread, throwable)
        }
    }
}