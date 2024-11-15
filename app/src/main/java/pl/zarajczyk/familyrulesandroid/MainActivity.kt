package pl.zarajczyk.familyrulesandroid

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
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
import pl.zarajczyk.familyrulesandroid.adapter.FamilyRulesClient
import pl.zarajczyk.familyrulesandroid.domain.ReportService
import pl.zarajczyk.familyrulesandroid.domain.UptimeService
import pl.zarajczyk.familyrulesandroid.domain.UsageStatistics
import pl.zarajczyk.familyrulesandroid.gui.PermanentNotification
import pl.zarajczyk.familyrulesandroid.gui.PermissionsChecker
import pl.zarajczyk.familyrulesandroid.gui.SettingsManager
import pl.zarajczyk.familyrulesandroid.ui.theme.FamilyRulesAndroidTheme
import java.util.Locale


class MainActivity : ComponentActivity() {
    private lateinit var settingsManager: SettingsManager
    private lateinit var uptimeService: UptimeService
    private lateinit var reportService: ReportService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsManager = SettingsManager(this)

        if (!settingsManager.areSettingsComplete()) {
            startActivity(Intent(this, InitialSetupActivity::class.java))
            finish()
        } else {
            PermissionsChecker(this).checkPermissions()

            uptimeService = UptimeService(this, delayMillis = 5_000)
            uptimeService.start { setupContent() }

            reportService = ReportService(this, settingsManager, uptimeService, delayMillis = 5_000)
            reportService.start()

            val notification = PermanentNotification(this)
            notification.install()

            setupContent()
        }
    }

    fun setupContent() {
        val uptime = uptimeService.getUptime()
        setContent {
            FamilyRulesAndroidTheme {
                MainScreen(
                    usageStatsList = uptime.usageStatistics,
                    screenTime = uptime.screenTimeSec,
                    settingsManager = settingsManager,
                    mainActivity = this
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    usageStatsList: List<UsageStatistics>,
    screenTime: Long,
    settingsManager: SettingsManager,
    mainActivity: MainActivity
) {
    val context = LocalContext.current
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { AppTopBar() },
        bottomBar = { BottomToolbar(screenTime, settingsManager, context, mainActivity) }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            UsageStatsDisplay(usageStatsList)
        }
    }
}

@Composable
fun BottomToolbar(
    screenTime: Long,
    settingsManager: SettingsManager,
    context: Context,
    mainActivity: MainActivity
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Screen time: ${
                String.format(
                    Locale.getDefault(),
                    "%02d:%02d:%02d",
                    screenTime / 3600,
                    (screenTime % 3600) / 60,
                    screenTime % 60
                )
            }\n(${settingsManager.getVersion()})",
            modifier = Modifier.padding(start = 16.dp)
        )
        Button(
            onClick = {
                settingsManager.clearSettings()
                context.startActivity(Intent(context, InitialSetupActivity::class.java))
            },
            modifier = Modifier
                .size(width = 60.dp, height = 40.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color.Black
            )
        ) {
            Text("🗑️")
        }
        Button(
            onClick = {
                mainActivity.setupContent()
            },
            modifier = Modifier
                .padding(end = 16.dp)
                .size(width = 60.dp, height = 40.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color.Black
            )
        ) {
            Text("🔄")
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