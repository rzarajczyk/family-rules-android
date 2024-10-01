import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pl.zarajczyk.familyrulesandroid.SettingsManager
import pl.zarajczyk.familyrulesandroid.fetchUsageStats
import pl.zarajczyk.familyrulesandroid.getTotalScreenOnTimeSinceMidnight
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.TimeUnit

class ReportWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val settingsManager = SettingsManager(applicationContext)
        val serverUrl = settingsManager.getString("serverUrl", "")
        val username = settingsManager.getString("username", "")
        val instanceId = settingsManager.getString("instanceId", "")
        val instanceToken = settingsManager.getString("instanceToken", "")

        val usageStatsList = fetchUsageStats(applicationContext)
        val totalScreenTime = getTotalScreenOnTimeSinceMidnight(applicationContext)

        val applications = JSONObject().apply {
            usageStatsList.forEach { stat ->
                put(stat.packageName, stat.totalTimeInForeground / 1000)
            }
        }

        val json = JSONObject().apply {
            put("instanceId", instanceId)
            put("screenTime", totalScreenTime)
            put("applications", applications)
        }.toString()

        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$serverUrl/api/v1/report")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; utf-8")
                val auth = android.util.Base64.encodeToString("$username:$instanceToken".toByteArray(), android.util.Base64.NO_WRAP)
                connection.setRequestProperty("Authorization", "Basic $auth")
                connection.doOutput = true

                connection.outputStream.use { os: OutputStream ->
                    val input = json.toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    // Reschedule the worker
                    Result.success()
                } else {
                    Result.retry()
                }
            } catch (e: Exception) {
                Result.retry()
            } finally {
                val nextRequest = OneTimeWorkRequestBuilder<ReportWorker>()
                    .setInitialDelay(5, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(applicationContext).enqueue(nextRequest)
            }
        }
    }

}