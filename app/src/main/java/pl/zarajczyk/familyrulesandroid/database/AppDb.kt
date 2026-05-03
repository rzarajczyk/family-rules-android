package pl.zarajczyk.familyrulesandroid.database

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import android.util.Base64
import android.util.Log
import pl.zarajczyk.familyrulesandroid.utils.Logger
import androidx.core.graphics.createBitmap

class AppDb(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val appInfoDao = database.appInfoDao()
    private val serverCommandDao = database.serverCommandDao()

    suspend fun getAppNameAndIcon(packageName: String): App {
        return withContext(Dispatchers.IO) {
            // First, try to get from cache
            val cachedAppInfo = appInfoDao.getAppInfo(packageName)

            val isPoisoned = cachedAppInfo != null
                && cachedAppInfo.appName == packageName
                && cachedAppInfo.iconBase64 == null
            val poisonExpired = isPoisoned
                && System.currentTimeMillis() - cachedAppInfo!!.lastUpdated > 3_600_000L

            if (cachedAppInfo != null && (!isPoisoned || !poisonExpired)) {
                return@withContext App(
                    name = cachedAppInfo.appName,
                    packageName = packageName,
                    icon = cachedAppInfo.iconBase64
                )
            }

            // Cache miss, fetch from system
            val start = System.currentTimeMillis()
            val appInfo = fetchFromSystem(packageName)
            val end = System.currentTimeMillis()
            Logger.i("AppDb", "Fetching app info from system took ${end - start}ms for package $packageName")
            
            // Cache the result
            cacheAppInfo(appInfo)
            
            appInfo
        }
    }

    private fun fetchFromSystem(packageName: String): App {
        return try {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val appName = packageManager.getApplicationLabel(appInfo).toString()
            val appIcon = packageManager.getApplicationIcon(appInfo)
            val bitmap = appIcon.toBitmap(64, 64)
            val iconBase64 = bitmapToBase64(bitmap)
            
            App(appName, packageName, iconBase64)
        } catch (e: Exception) {
            Logger.w("AppDb", "Unable to fetch app info for package $packageName", e)
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
        val bitmap = createBitmap(width, height)
        val canvas = android.graphics.Canvas(bitmap)
        setBounds(0, 0, width, height)
        draw(canvas)
        return bitmap
    }

    suspend fun getAllAppInfo(): List<AppInfo> {
        return withContext(Dispatchers.IO) {
            appInfoDao.getAllAppInfo()
        }
    }

    suspend fun insertServerCommandIfAbsent(command: ServerCommand): Boolean {
        return withContext(Dispatchers.IO) {
            serverCommandDao.insertIfAbsent(command) != -1L
        }
    }

    suspend fun getPendingCommandAcks(): List<ServerCommand> = withContext(Dispatchers.IO) {
        serverCommandDao.getPendingAcks()
    }

    suspend fun markCommandAcksConfirmed(commandIds: List<String>, ackConfirmedAtMillis: Long) {
        withContext(Dispatchers.IO) {
            serverCommandDao.markAckConfirmed(commandIds, ackConfirmedAtMillis)
        }
    }

    suspend fun getCommandsByExecutionState(executionState: ServerCommandExecutionState): List<ServerCommand> = withContext(Dispatchers.IO) {
        serverCommandDao.getByExecutionState(executionState.name)
    }

    suspend fun markCommandExecuting(commandId: String) {
        withContext(Dispatchers.IO) {
            serverCommandDao.updateExecutionState(commandId, ServerCommandExecutionState.EXECUTING.name)
        }
    }

    suspend fun storeCommandResult(
        commandId: String,
        resultStatus: String,
        responseType: String,
        responsePayloadJson: String,
        completedAtIso: String,
    ) {
        withContext(Dispatchers.IO) {
            serverCommandDao.storeResult(
                commandId = commandId,
                executionState = ServerCommandExecutionState.EXECUTING.name,
                resultStatus = resultStatus,
                responseType = responseType,
                responsePayloadJson = responsePayloadJson,
                completedAtIso = completedAtIso,
            )
        }
    }

    suspend fun getPendingCommandResultUploads(): List<ServerCommand> = withContext(Dispatchers.IO) {
        serverCommandDao.getPendingResultUploads()
    }

    suspend fun markCommandResultUploaded(commandId: String, uploadedAtMillis: Long) {
        withContext(Dispatchers.IO) {
            serverCommandDao.markResultUploaded(commandId, uploadedAtMillis, ServerCommandExecutionState.COMPLETED.name)
        }
    }
}

data class App(
    val name: String,
    val packageName: String,
    val icon: String? // base64 encoded icon
)
