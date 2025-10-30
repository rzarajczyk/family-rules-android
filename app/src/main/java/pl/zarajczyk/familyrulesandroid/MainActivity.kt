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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import pl.zarajczyk.familyrulesandroid.adapter.DeviceState
import pl.zarajczyk.familyrulesandroid.core.*
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

        val deviceAdminManager = DeviceAdminManager(this)
        val permissionsChecker = PermissionsChecker(this)

        when {
            !settingsManager.areSettingsComplete() -> switchActivity(InitialSetupActivity::class.java)
            !permissionsChecker.isAllPermissionsGranted() -> switchActivity(PermissionsSetupActivity::class.java)
            !deviceAdminManager.isDeviceAdminActive() -> switchActivity(PermissionsSetupActivity::class.java)
            else -> {
                FamilyRulesCoreService.install(this)
                setupContent()
            }
        }
    }

    private fun switchActivity(activityClass: Class<*>) {
        startActivity(Intent(this, activityClass))
        finish()
    }

    override fun onResume() {
        super.onResume()
        setupContent()
    }

    fun setupContent() {
        FamilyRulesCoreService.bind(this) { service ->
            setContent {
                FamilyRulesAndroidTheme {
                    // Use the reactive state flow for device state
                    val deviceState by service.getDeviceStateFlow().collectAsState(initial = service.getCurrentDeviceState())
                    // Keep status bar color in sync with SharedAppLayout background color
                    val bgColor = when (deviceState) {
                        DeviceState.ACTIVE -> Color(0xFFEEEEEE)
                        DeviceState.BLOCK_LIMITTED_APPS -> Color(0xFFFFDEDE)
                    }
                    androidx.compose.runtime.SideEffect {
                        WindowCompat.getInsetsController(window, window.decorView).apply {
                            isAppearanceLightStatusBars = true
                        }
                        window.statusBarColor = bgColor.toArgb()
                    }
                    
                    MainScreen(
                        usageStatsList = service.getTodayPackageUsage(),
                        screenTime = service.getTodayScreenTime(),
                        settingsManager = settingsManager,
                        appDb = appDb,
                        deviceState = deviceState
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    usageStatsList: Map<String, Long>,
    screenTime: Long,
    settingsManager: SettingsManager,
    appDb: AppDb,
    deviceState: DeviceState = DeviceState.ACTIVE
) {
    SharedAppLayout(deviceState = deviceState) {
        ScreenTimeCard(screenTime, settingsManager)
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
    settingsManager: SettingsManager
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
        Text(
            text = stringResource(R.string.screen_time, screenTime.toHMS(), settingsManager.getVersion()),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Black,
            modifier = Modifier.padding(8.dp)
        )
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
                text = stringResource(R.string.app_usage_calculation),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Black,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

data class PackageUsage(
    val packageName: String,
    val totalTimeInForegroundMillis: Long
)

@Composable
fun UsageStatsDisplay(
    usageStatsList: Map<String, Long>,
    appDb: AppDb,
    modifier: Modifier = Modifier
) {
    // Sort the usageStatsList by totalTimeInForeground in descending order
    val sortedUsageStatsList = usageStatsList
        .map { (packageName, totalTime) -> PackageUsage(packageName, totalTime) }
        .sortedByDescending { it.totalTimeInForegroundMillis }

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
                text = stringResource(R.string.total_time, totalTimeFormatted),
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
            Text(stringResource(R.string.app_icon_placeholder), fontSize = 24.sp)
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
            emptyMap(),
            appDb = AppDb(androidx.compose.ui.platform.LocalContext.current)
        )
    }
}