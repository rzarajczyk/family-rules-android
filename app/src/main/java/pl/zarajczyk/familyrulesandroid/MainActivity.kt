package pl.zarajczyk.familyrulesandroid

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import pl.zarajczyk.familyrulesandroid.core.FamilyRulesCoreService
import pl.zarajczyk.familyrulesandroid.core.PackageUsage
import pl.zarajczyk.familyrulesandroid.core.PermissionsChecker
import pl.zarajczyk.familyrulesandroid.core.SettingsManager
import pl.zarajczyk.familyrulesandroid.database.App
import pl.zarajczyk.familyrulesandroid.database.AppDb
import pl.zarajczyk.familyrulesandroid.ui.theme.FamilyRulesAndroidTheme
import pl.zarajczyk.familyrulesandroid.utils.toHMS


class MainActivity : ComponentActivity() {
    private lateinit var settingsManager: SettingsManager
    private lateinit var appDb: AppDb

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set status bar color to match background
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
        }
        window.statusBarColor = getColor(R.color.background_color)

        settingsManager = SettingsManager(this)
        appDb = AppDb(this)

        val permissionsChecker = PermissionsChecker(this)
        if (!settingsManager.areSettingsComplete() || !permissionsChecker.isAllPermissionsGranted()) {
            startActivity(Intent(this, InitialSetupActivity::class.java))
            finish()
        } else {
            // Install service if not already running (might have been started in InitialSetupActivity)
            FamilyRulesCoreService.install(this)
            setupContent()
        }
    }

    override fun onResume() {
        super.onResume()
        setupContent()
    }

    fun setupContent() {
        FamilyRulesCoreService.bind(this) {
            var uptime = it.getUptime()
            
            // If uptime is zero, try to force an immediate update
            if (uptime.screenTimeMillis == 0L && uptime.packageUsages.isEmpty()) {
                uptime = it.forceUptimeUpdate()
            }
            
            setContent {
                FamilyRulesAndroidTheme {
                    MainScreen(
                        usageStatsList = uptime.packageUsages,
                        screenTime = uptime.screenTimeMillis,
                        settingsManager = settingsManager,
                        mainActivity = this,
                        appDb = appDb
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    usageStatsList: List<PackageUsage>,
    screenTime: Long,
    settingsManager: SettingsManager,
    mainActivity: MainActivity,
    appDb: AppDb
) {
    SharedAppLayout {
        ScreenTimeCard(screenTime, settingsManager, mainActivity)
        Spacer(modifier = Modifier.weight(1f))
        
        // Show fallback message if no app usage data is available yet
        if (usageStatsList.isEmpty() && screenTime == 0L) {
            AppUsageCalculationInProgress()
        } else {
            UsageStatsDisplay(usageStatsList, appDb = appDb)
        }
    }
}

@Composable
fun ScreenTimeCard(
    screenTime: Long,
    settingsManager: SettingsManager,
    mainActivity: MainActivity
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end=16.dp, bottom = 0.dp, top = 16.dp)
            .background(
                color = Color.White,
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Screen time: ${screenTime.toHMS()}\n(${settingsManager.getVersion()})",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Black
            )
            Button(
                onClick = {
                    mainActivity.setupContent()
                },
                modifier = Modifier.size(width = 60.dp, height = 40.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = Color.Black
                )
            ) {
                Text("🔄")
            }
        }
    }
}

@Composable
fun AppUsageCalculationInProgress() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(
                color = Color.White,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 4.dp
            )
            Spacer(modifier = Modifier.padding(16.dp))
            Text(
                text = "App usage calculation in progress, it might take a while",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Black,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
fun UsageStatsDisplay(
    usageStatsList: List<PackageUsage>,
    appDb: AppDb,
    modifier: Modifier = Modifier
) {
    // Sort the usageStatsList by totalTimeInForeground in descending order
    val sortedUsageStatsList = usageStatsList.sortedByDescending { it.totalTimeInForegroundMillis }

    LazyColumn(
        modifier = modifier
            .padding(16.dp)
            .background(
                color = Color.White,
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        items(sortedUsageStatsList) { stat ->
            AppUsageItem(stat, appDb)
        }
    }
}

@Composable
private fun AppUsageItem(stat: PackageUsage, appDb: AppDb) {
    val totalTimeFormatted = stat.totalTimeInForegroundMillis.toHMS()

    // State to hold the app info
    var appInfo by remember(stat.packageName) {
        mutableStateOf<App?>(null)
    }
    var isLoading by remember(stat.packageName) {
        mutableStateOf(true)
    }

    // Launch coroutine to fetch app info
    LaunchedEffect(stat.packageName) {
        try {
            appInfo = appDb.getAppNameAndIcon(stat.packageName)
        } catch (e: Exception) {
            // Handle error - create a fallback app info
            appInfo = App(stat.packageName, stat.packageName, null)
        } finally {
            isLoading = false
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(all = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            // Show loading indicator
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        Color.Gray.copy(alpha = 0.3f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        } else {
            AppIcon(appInfo?.icon)
        }

        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = appInfo?.name ?: stat.packageName,
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

@Composable
private fun AppIcon(iconBase64: String?) {
    val bitmap = remember(iconBase64) {
        iconBase64?.let { base64ToBitmap(it) }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(48.dp)
        )
    } else {
        // Placeholder for apps without icons
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    Color.Gray.copy(alpha = 0.3f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("📱", fontSize = 24.sp)
        }
    }
}

private fun base64ToBitmap(base64: String): android.graphics.Bitmap? {
    return try {
        val decodedBytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    } catch (e: Exception) {
        null
    }
}

@Preview(showBackground = true)
@Composable
fun UsageStatsDisplayPreview() {
    FamilyRulesAndroidTheme {
        // Preview doesn't need real AppDb
        UsageStatsDisplay(
            emptyList(),
            appDb = AppDb(androidx.compose.ui.platform.LocalContext.current)
        )
    }
}