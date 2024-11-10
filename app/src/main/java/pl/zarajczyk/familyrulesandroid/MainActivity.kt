package pl.zarajczyk.familyrulesandroid

import ReportWorker
import android.R
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pl.zarajczyk.familyrulesandroid.ui.theme.FamilyRulesAndroidTheme
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit


class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var updateRunnable: Runnable
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        enableEdgeToEdge()
        settingsManager = SettingsManager(this)

        if (!settingsManager.areSettingsComplete()) {
            startActivity(Intent(this, InitialSetupActivity::class.java))
            finish()
        }

        if (!isUsageStatsPermissionGranted()) {
            navigateToUsageStatsPermissionSettings()
        }

        sendLaunchRequest(this)

        setupContent()
        setupPeriodicUpdate()

        val reportWorkRequest =
            OneTimeWorkRequestBuilder<ReportWorker>()
                .setInitialDelay(5, TimeUnit.SECONDS)
                .build()
        WorkManager.getInstance(this).enqueue(reportWorkRequest)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }

        val serviceIntent = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

//        val nMN = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
//        val n: Notification = Notification.Builder(this)
//            .setContentTitle("Whip And Weep")
//            .setContentText("Whip is On!")
//            .setSmallIcon(R.drawable.ic_menu_search)
//            .build()
//        nMN.notify(2, n)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
    }

    private fun setupContent() {
        setContent {
            FamilyRulesAndroidTheme {
                MainScreen(
                    usageStatsList = fetchUsageStats(this.applicationContext),
                    screenTime = getTotalScreenOnTimeSinceMidnight(this.applicationContext),
                    settingsManager = settingsManager
                )
            }
        }
    }

    private fun sendLaunchRequest(context: Context) {
        val settingsManager = SettingsManager(context)
        val serverUrl = settingsManager.getString("serverUrl", "")
        val instanceId = settingsManager.getString("instanceId", "")
        val username = settingsManager.getString("username", "")
        val instanceToken = settingsManager.getString("instanceToken", "")

        val json = JSONObject().apply {
            put("instanceId", instanceId)
            put("version", "v1")
            put("availableStates", JSONArray().apply {
                put(JSONObject().apply {
                    put("deviceState", "ACTIVE")
                    put("title", "Active")
                    put(
                        "icon",
                        "<path d=\"m424-296 282-282-56-56-226 226-114-114-56 56 170 170Zm56 216q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z\"/>"
                    )
                    put("description", JSONObject.NULL)
                })
            })
        }.toString()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("$serverUrl/api/v1/launch")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; utf-8")
                val auth = android.util.Base64.encodeToString(
                    "$username:$instanceToken".toByteArray(),
                    android.util.Base64.NO_WRAP
                )
                connection.setRequestProperty("Authorization", "Basic $auth")
                connection.doOutput = true

                connection.outputStream.use { os: OutputStream ->
                    val input = json.toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("Failed to send launch request: HTTP ${connection.responseCode}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupPeriodicUpdate() {
        updateRunnable = Runnable {
            setupContent()
            handler.postDelayed(updateRunnable, 5000)
        }
        handler.post(updateRunnable)
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
}

@Composable
fun MainScreen(
    usageStatsList: List<UsageStatistics>,
    screenTime: Long,
    settingsManager: SettingsManager
) {
    val context = LocalContext.current
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { AppTopBar() },
        bottomBar = { BottomToolbar(screenTime, settingsManager, context) }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            UsageStatsDisplay(usageStatsList)
        }
    }
}

@Composable
fun BottomToolbar(screenTime: Long, settingsManager: SettingsManager, context: Context) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Screen time: ${
                String.format(
                    "%02d:%02d:%02d",
                    screenTime / 3600,
                    (screenTime % 3600) / 60,
                    screenTime % 60
                )
            }",
            modifier = Modifier.padding(start = 16.dp)
        )
        Button(
            onClick = {
                settingsManager.clearSettings()
                context.startActivity(Intent(context, InitialSetupActivity::class.java))
            },
            modifier = Modifier
                .padding(end = 16.dp)
                .size(width = 60.dp, height = 40.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color.Black
            )
        ) {
            Text("🗑️")
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
fun UsageStatsDisplay(usageStatsList: List<UsageStatistics>, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Sort the usageStatsList by totalTimeInForeground in descending order
    val sortedUsageStatsList = usageStatsList.sortedByDescending { it.totalTimeInForeground }

    LazyColumn(modifier = modifier.padding(16.dp)) {
        items(sortedUsageStatsList) { stat ->
            val app = getAppNameAndIcon(stat.packageName, context)
            val totalTimeInSeconds = stat.totalTimeInForeground / 1000
            val hours = totalTimeInSeconds / 3600
            val minutes = (totalTimeInSeconds % 3600) / 60
            val seconds = totalTimeInSeconds % 60
            val totalTimeFormatted =
                String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)

            Row(modifier = Modifier.padding(vertical = 8.dp)) {
                app.icon?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = app.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 18.sp
                    )
                    Text(
                        text = "Total time: $totalTimeFormatted",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

private fun getAppNameAndIcon(packageName: String, context: Context): App {
    return try {
        val packageManager = context.packageManager
        val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        val appName = packageManager.getApplicationLabel(appInfo).toString()
        val appIcon = packageManager.getApplicationIcon(appInfo).toBitmap()
        App(appName, packageName, appIcon)
    } catch (e: PackageManager.NameNotFoundException) {
        App(packageName, packageName, null)
    }
}

private data class App(
    val name: String,
    val packageName: String,
    val icon: Bitmap?,
)

@Preview(showBackground = true)
@Composable
fun UsageStatsDisplayPreview() {
    FamilyRulesAndroidTheme {
        UsageStatsDisplay(emptyList())
    }
}