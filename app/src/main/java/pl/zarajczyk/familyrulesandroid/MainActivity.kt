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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.delay
import pl.zarajczyk.familyrulesandroid.ui.theme.FamilyRulesColors
import android.content.ServiceConnection
import androidx.compose.runtime.mutableLongStateOf


class MainActivity : ComponentActivity() {
    private lateinit var settingsManager: SettingsManager
    private lateinit var appDb: AppDb
    private var serviceConnection: ServiceConnection? = null

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
        // Only bind if not already bound
        if (serviceConnection == null) {
            serviceConnection = FamilyRulesCoreService.bind(this) { service ->
                setContent {
                    FamilyRulesAndroidTheme {
                        // Use the reactive state flow for device state
                        val deviceState by service.getDeviceStateFlow().collectAsState(initial = service.getCurrentDeviceState())
                        // Keep status bar color in sync with SharedAppLayout background color
                        val bgColor = when (deviceState.state) {
                            DeviceState.ACTIVE -> FamilyRulesColors.NORMAL_BACKGROUND
                            DeviceState.BLOCK_RESTRICTED_APPS -> FamilyRulesColors.BLOCKING_COLOR
                        }
                        androidx.compose.runtime.SideEffect {
                            WindowCompat.getInsetsController(window, window.decorView).apply {
                                isAppearanceLightStatusBars = true
                            }
                            window.statusBarColor = bgColor.toArgb()
                        }

                        // Track usage/screenTime and poll until calculated
                        var usageStatsListState by remember { mutableStateOf(service.getTodayPackageUsage()) }
                        var screenTimeState by remember { mutableLongStateOf(service.getTodayScreenTime()) }

                        LaunchedEffect(usageStatsListState.isEmpty() || screenTimeState == 0L) {
                            if (usageStatsListState.isEmpty() || screenTimeState == 0L) {
                                while (usageStatsListState.isEmpty() || screenTimeState == 0L) {
                                    delay(1000)
                                    usageStatsListState = service.getTodayPackageUsage()
                                    screenTimeState = service.getTodayScreenTime()
                                }
                            }
                        }
                        
                        MainScreen(
                            usageStatsList = usageStatsListState,
                            screenTime = screenTimeState,
                            settingsManager = settingsManager,
                            appDb = appDb,
                            deviceState = deviceState.state
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unbind service when activity is destroyed to prevent leaks
        serviceConnection?.let { connection ->
            try {
                unbindService(connection)
            } catch (e: IllegalArgumentException) {
                // Service might not be bound, ignore
            }
            serviceConnection = null
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
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.tab_this_device),
        stringResource(R.string.tab_all_devices)
    )

    SharedAppLayout(deviceState = deviceState) {
        Column {
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = FamilyRulesColors.SECONDARY_BACKGROUND_COLOR,
                contentColor = FamilyRulesColors.TEXT_COLOR,
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 0.dp)
                    .clip(RoundedCornerShape(16.dp))
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTabIndex) {
                0 -> ThisDeviceTab(
                    usageStatsList = usageStatsList,
                    screenTime = screenTime,
                    settingsManager = settingsManager,
                    appDb = appDb
                )
                1 -> AllDevicesTab()
            }
        }
    }
}

@Composable
fun ThisDeviceTab(
    usageStatsList: Map<String, Long>,
    screenTime: Long,
    settingsManager: SettingsManager,
    appDb: AppDb
) {
    Column {
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
fun AllDevicesTab() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val appDb = remember { AppDb(context) }
    val familyRulesClient = remember { pl.zarajczyk.familyrulesandroid.adapter.FamilyRulesClient(settingsManager, appDb) }
    
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var usageReport by remember { mutableStateOf<pl.zarajczyk.familyrulesandroid.adapter.AppGroupsUsageReportResponse?>(null) }
    
    LaunchedEffect(Unit) {
        try {
            isLoading = true
            errorMessage = null
            val response = familyRulesClient.getGroupsUsageReport()
            usageReport = response
            if (response == null) {
                errorMessage = "Failed to load data"
            }
        } catch (e: Exception) {
            errorMessage = e.message ?: "Unknown error"
        } finally {
            isLoading = false
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        when {
            isLoading -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp
                    )
                    Spacer(modifier = Modifier.padding(16.dp))
                    Text(
                        text = stringResource(R.string.loading),
                        style = MaterialTheme.typography.bodyLarge,
                        color = FamilyRulesColors.TEXT_COLOR
                    )
                }
            }
            errorMessage != null -> {
                Text(
                    text = errorMessage ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Red,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            usageReport != null -> {
                GroupsUsageReportDisplay(usageReport!!)
            }
        }
    }
}

@Composable
fun GroupsUsageReportDisplay(report: pl.zarajczyk.familyrulesandroid.adapter.AppGroupsUsageReportResponse) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = FamilyRulesColors.SECONDARY_BACKGROUND_COLOR,
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        items(report.appGroups) { group ->
            AppGroupUsageCard(group)
        }
    }
}

@Composable
fun AppGroupUsageCard(group: pl.zarajczyk.familyrulesandroid.adapter.AppGroupUsageReport) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        // Group header
        Text(
            text = "Group: ${group.appGroupId}",
            style = MaterialTheme.typography.titleMedium,
            color = FamilyRulesColors.TEXT_COLOR,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = stringResource(R.string.total_time, group.totalTimeSeconds.toHMS()),
            style = MaterialTheme.typography.bodyMedium,
            color = FamilyRulesColors.TEXT_COLOR,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        // Apps in group
        group.apps.forEach { (packageName, appUsageReport) ->
            AppUsageReportItem(packageName, appUsageReport)
        }
    }
}

@Composable
fun AppUsageReportItem(
    packageName: String,
    appUsageReport: pl.zarajczyk.familyrulesandroid.adapter.AppUsageReport
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIconFromBase64(appUsageReport.app.iconBase64Png)
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = appUsageReport.app.appName,
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 16.sp,
                color = FamilyRulesColors.TEXT_COLOR
            )
            Text(
                text = "${appUsageReport.app.deviceName} • ${appUsageReport.uptimeSeconds.toHMS()}",
                style = MaterialTheme.typography.bodySmall,
                color = FamilyRulesColors.TEXT_COLOR
            )
        }
    }
}

@Composable
fun AppIconFromBase64(iconBase64: String?) {
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
                color = FamilyRulesColors.SECONDARY_BACKGROUND_COLOR,
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Text(
            text = stringResource(R.string.screen_time, screenTime.toHMS(), settingsManager.getVersion()),
            style = MaterialTheme.typography.bodyMedium,
            color = FamilyRulesColors.TEXT_COLOR,
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
                color = FamilyRulesColors.TEXT_COLOR,
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
                color = FamilyRulesColors.SECONDARY_BACKGROUND_COLOR,
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
                        FamilyRulesColors.SECONDARY_BACKGROUND_COLOR,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = FamilyRulesColors.TEXT_COLOR,
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
                fontSize = 18.sp,
                color = FamilyRulesColors.TEXT_COLOR
            )
            Text(
                text = stringResource(R.string.total_time, totalTimeFormatted),
                style = MaterialTheme.typography.bodyMedium,
                color = FamilyRulesColors.TEXT_COLOR
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