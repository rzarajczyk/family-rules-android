package pl.zarajczyk.familyrulesandroid

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import org.json.JSONObject
import pl.zarajczyk.familyrulesandroid.ui.theme.FamilyRulesAndroidTheme
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.text.*

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var updateRunnable: Runnable
    private lateinit var settingsManager: SettingsManager
    private val logger = Logger.getLogger("MainActivity")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settingsManager = SettingsManager(this)

        if (!settingsManager.areSettingsComplete()) {
            startActivity(Intent(this, InitialSetupActivity::class.java))
            finish()
//            return
        }

        if (!isUsageStatsPermissionGranted()) {
            navigateToUsageStatsPermissionSettings()
        }
        setupContent()
        setupPeriodicUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
    }

    private fun setupContent() {
        setContent {
            FamilyRulesAndroidTheme {
                MainScreen(fetchUsageStats(), settingsManager)
            }
        }
    }

    private fun setupPeriodicUpdate() {
        updateRunnable = Runnable {
            val usageStatsList = fetchUsageStats()
            val totalScreenTime = getTotalScreenOnTimeSinceMidnight()
            sendUsageStatsToServer(usageStatsList, totalScreenTime)
            setupContent()
            handler.postDelayed(updateRunnable, 5000)
        }
        handler.post(updateRunnable)
    }

    private fun showError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun isUsageStatsPermissionGranted(): Boolean {
        val appOpsManager = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOpsManager.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun navigateToUsageStatsPermissionSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        startActivity(intent)
    }

    private fun fetchUsageStats(): List<UsageStats> {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis
        val usageStatsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )
        return usageStatsList.filterNot { isSystemApp(it.packageName) || it.totalTimeInForeground == 0L }
    }

    private fun isSystemApp(packageName: String): Boolean {
        return try {
            val packageManager = applicationContext.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun getTotalScreenOnTimeSinceMidnight(): Long {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis
        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        var totalScreenOnTime = 0L
        var screenOnTime = 0L
        var screenOffTime = 0L

        while (usageEvents.hasNextEvent()) {
            val event = UsageEvents.Event()
            usageEvents.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> screenOnTime = event.timeStamp
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    screenOffTime = event.timeStamp
                    if (screenOnTime != 0L) {
                        totalScreenOnTime += screenOffTime - screenOnTime
                        screenOnTime = 0L
                    }
                }
            }
        }
        return totalScreenOnTime / 1000 // Convert to seconds
    }

    private fun sendUsageStatsToServer(usageStatsList: List<UsageStats>, totalScreenTime: Long) {
        val serverUrl = settingsManager.getString("serverUrl", "")
        val username = settingsManager.getString("username", "")
        val instanceId = settingsManager.getString("instanceId", "")
        val instanceToken = settingsManager.getString("instanceToken", "")

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

        CoroutineScope(Dispatchers.IO).launch {
            val result = try {
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
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Result.Success
                } else {
                    Result.Error("Failed to send usage stats: HTTP $responseCode")
                }
            } catch (e: Exception) {
                Result.Error("Failed to send usage stats: ${e.message}")
            }

            withContext(Dispatchers.Main) {
                if (result is Result.Error) {
                    showError(result.message)
                }
            }
        }
    }

    sealed class Result {
        object Success : Result()
        data class Error(val message: String) : Result()
    }}

@Composable
fun MainScreen(usageStatsList: List<UsageStats>, settingsManager: SettingsManager) {
    val context = LocalContext.current
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { AppTopBar() }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            UsageStatsDisplay(usageStatsList)
            Button(
                onClick = {
                    settingsManager.clearSettings()
                    context.startActivity(Intent(context, InitialSetupActivity::class.java))
                },
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Clear Settings")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar() {
    TopAppBar(
        title = { Text(text = "Family Rules") },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFF000080),
            titleContentColor = Color.White
        )
    )
}

@Composable
fun UsageStatsDisplay(usageStatsList: List<UsageStats>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    LazyColumn(modifier = modifier.padding(16.dp)) {
        items(usageStatsList) { stat ->
            val (appName, appIcon) = getAppNameAndIcon(stat.packageName, context)
            val lastUsed = dateFormat.format(Date(stat.lastTimeUsed))
            val totalTimeInSeconds = stat.totalTimeInForeground / 1000
            val hours = totalTimeInSeconds / 3600
            val minutes = (totalTimeInSeconds % 3600) / 60
            val seconds = totalTimeInSeconds % 60
            val totalTimeFormatted = String.format(
                "%02d:%02d:%02d",
                hours,
                minutes,
                seconds
            )

            Row(modifier = Modifier.padding(vertical = 8.dp)) {
                appIcon?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = appName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 18.sp
                    )
                    Text(text = "Last Used: $lastUsed", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Total Time: $totalTimeFormatted",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

private fun getAppNameAndIcon(packageName: String, context: Context): Pair<String, Bitmap?> {
    return try {
        val packageManager = context.packageManager
        val appInfo = packageManager.getApplicationInfo(packageName, 0)
        val appName = packageManager.getApplicationLabel(appInfo).toString()
        val appIcon = packageManager.getApplicationIcon(appInfo).toBitmap()
        Pair(appName, appIcon)
    } catch (e: PackageManager.NameNotFoundException) {
        Pair(packageName, null)
    }
}

@Preview(showBackground = true)
@Composable
fun UsageStatsDisplayPreview() {
    FamilyRulesAndroidTheme {
        UsageStatsDisplay(emptyList())
    }
}