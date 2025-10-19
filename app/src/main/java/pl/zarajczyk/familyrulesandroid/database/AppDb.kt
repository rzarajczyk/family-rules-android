package pl.zarajczyk.familyrulesandroid.database

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import android.util.Base64

class AppDb(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val appInfoDao = database.appInfoDao()

    suspend fun getAppNameAndIcon(packageName: String): App {
        return withContext(Dispatchers.IO) {
            // First, try to get from cache
            val cachedAppInfo = appInfoDao.getAppInfo(packageName)
            
            if (cachedAppInfo != null) {
                return@withContext App(
                    name = cachedAppInfo.appName,
                    packageName = packageName,
                    icon = cachedAppInfo.iconBase64
                )
            }

            // Cache miss, fetch from system
            val appInfo = fetchFromSystem(packageName)
            
            // Cache the result
            cacheAppInfo(appInfo)
            
            appInfo
        }
    }

    private suspend fun fetchFromSystem(packageName: String): App {
        return try {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val appName = packageManager.getApplicationLabel(appInfo).toString()
            val appIcon = packageManager.getApplicationIcon(appInfo)
            val bitmap = appIcon.toBitmap(64, 64)
            val iconBase64 = bitmapToBase64(bitmap)
            
            App(appName, packageName, iconBase64)
        } catch (e: PackageManager.NameNotFoundException) {
            App(packageName, packageName, null)
        }
    }

    private suspend fun cacheAppInfo(app: App) {
        val appInfo = AppInfo(
            packageName = app.packageName,
            appName = app.name,
            iconBase64 = app.icon
        )

        appInfoDao.insertAppInfo(appInfo)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }


    private fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        setBounds(0, 0, width, height)
        draw(canvas)
        return bitmap
    }

    suspend fun clearCache() {
        withContext(Dispatchers.IO) {
            // Clear all app info from database
            appInfoDao.getAllAppInfo().forEach { appInfo ->
                appInfoDao.deleteAppInfo(appInfo.packageName)
            }
        }
    }
}

data class App(
    val name: String,
    val packageName: String,
    val icon: String? // base64 encoded icon
)
