package pl.zarajczyk.familyrulesandroid

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import pl.zarajczyk.familyrulesandroid.R
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

        if (!settingsManager.areSettingsComplete()) {
            startActivity(Intent(this, InitialSetupActivity::class.java))
            finish()
        } else {
            PermissionsChecker(this).checkPermissions()

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
            val uptime = it.getUptime()
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
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) { innerPadding ->
        val bgColor = Color(0xFFD3E8FF)
        if (isLandscape) {
            // Horizontal layout for landscape orientation
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
                    .padding(innerPadding)
            ) {
                // Left side - Icon and label
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.icon),
                        contentDescription = "Family Rules Icon",
                        modifier = Modifier.size(128.dp)
                    )
                    Text(
                        text = "FamilyRules",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.Black,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
                
                // Right side - App list and screen time card
                Column(
                    modifier = Modifier
                        .weight(2f)
                        .fillMaxSize()
                ) {
                    ScreenTimeCard(screenTime, settingsManager, mainActivity)
                    Spacer(modifier = Modifier.weight(1f))
                    UsageStatsDisplay(usageStatsList, appDb = appDb)
                }
            }
        } else {
            // Vertical layout for portrait orientation
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
                    .padding(innerPadding)
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.icon),
                            contentDescription = "Family Rules Icon",
                            modifier = Modifier
                                .size(128.dp)
                                .padding(top = 32.dp)
                        )
                        Text(
                            text = "FamilyRules",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.Black,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                        ScreenTimeCard(screenTime, settingsManager, mainActivity)
                        Spacer(modifier = Modifier.weight(1f))
                        UsageStatsDisplay(usageStatsList, appDb = appDb)
                    }
                }
            }
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