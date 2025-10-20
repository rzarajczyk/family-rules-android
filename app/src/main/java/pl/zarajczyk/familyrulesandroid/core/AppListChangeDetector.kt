package pl.zarajczyk.familyrulesandroid.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.zarajczyk.familyrulesandroid.database.AppDb
import pl.zarajczyk.familyrulesandroid.database.AppInfo

class AppListChangeDetector(private val appDb: AppDb) {
    private var lastSentAppPackages: Set<String> = emptySet()

    suspend fun hasAppListChanged(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val currentAppInfo = appDb.getAllAppInfo()
                val currentPackages = currentAppInfo.map { it.packageName }.toSet()
                
                val hasChanged = currentPackages != lastSentAppPackages
                
                if (hasChanged) {
                    Log.d("AppListChangeDetector", "App list changed. Previous: ${lastSentAppPackages.size}, Current: ${currentPackages.size}")
                }
                
                hasChanged
            } catch (e: Exception) {
                Log.e("AppListChangeDetector", "Failed to check app list changes: ${e.message}", e)
                false
            }
        }
    }

    suspend fun updateLastSentApps() {
        withContext(Dispatchers.IO) {
            try {
                val currentAppInfo = appDb.getAllAppInfo()
                lastSentAppPackages = currentAppInfo.map { it.packageName }.toSet()
                Log.d("AppListChangeDetector", "Updated last sent apps: ${lastSentAppPackages.size} packages")
            } catch (e: Exception) {
                Log.e("AppListChangeDetector", "Failed to update last sent apps: ${e.message}", e)
            }
        }
    }
}

