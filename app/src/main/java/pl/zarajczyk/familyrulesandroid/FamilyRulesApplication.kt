package pl.zarajczyk.familyrulesandroid

import android.app.Application
import pl.zarajczyk.familyrulesandroid.utils.CrashLogger

class FamilyRulesApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Set up global exception handler
        val defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Log the exception to file
            CrashLogger.logException(this, throwable, thread)
            
            // Call the default handler to let Android handle the crash normally
            defaultExceptionHandler?.uncaughtException(thread, throwable)
        }
    }
}
