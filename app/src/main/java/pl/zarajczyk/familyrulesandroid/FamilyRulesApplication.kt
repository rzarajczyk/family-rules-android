package pl.zarajczyk.familyrulesandroid

import android.app.Application
import pl.zarajczyk.familyrulesandroid.utils.CrashLogger
import pl.zarajczyk.familyrulesandroid.utils.FileLogger

class FamilyRulesApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Initialize file logger for all app logs (debug, info, warnings, errors)
        FileLogger.init(this)
        FileLogger.i("Application", "FamilyRules application starting...")
        
        // Set up global exception handler
        val defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Log the exception to both file logger and crash logger
            FileLogger.e("UncaughtException", "Uncaught exception in thread: ${thread.name}", throwable)
            CrashLogger.logException(this, throwable, thread)
            
            // Call the default handler to let Android handle the crash normally
            defaultExceptionHandler?.uncaughtException(thread, throwable)
        }
    }
}