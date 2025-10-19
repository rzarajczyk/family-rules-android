package pl.zarajczyk.familyrulesandroid

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import android.util.Base64
import android.graphics.BitmapFactory
import pl.zarajczyk.familyrulesandroid.core.FamilyRulesCoreService
import pl.zarajczyk.familyrulesandroid.core.PackageUsage
import pl.zarajczyk.familyrulesandroid.core.PermissionsChecker
import pl.zarajczyk.familyrulesandroid.core.SettingsManager
import pl.zarajczyk.familyrulesandroid.database.AppDb
import pl.zarajczyk.familyrulesandroid.database.App
import pl.zarajczyk.familyrulesandroid.ui.theme.FamilyRulesAndroidTheme
import pl.zarajczyk.familyrulesandroid.utils.toHMS
import java.util.Locale


class MainActivity : ComponentActivity() {
    private lateinit var settingsManager: SettingsManager
    private lateinit var appDb: AppDb

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
    val context = LocalContext.current
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { AppTopBar() },
        bottomBar = { BottomToolbar(screenTime, settingsManager, context, mainActivity) }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            UsageStatsDisplay(usageStatsList, appDb = appDb)
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
    // State to count clicks on the text field
    var clickCount by remember { mutableStateOf(0) }
    val clickTimeoutMillis = 500L // Time window to count clicks
    var lastClickTime by remember { mutableLongStateOf(0L) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Screen time: ${screenTime.toHMS()}\n(${settingsManager.getVersion()})",
            modifier = Modifier
                .padding(start = 16.dp)
                .clickable {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastClickTime > clickTimeoutMillis) {
                        clickCount = 1 // Reset the count if timeout exceeded
                    } else {
                        clickCount++
                    }
                    lastClickTime = currentTime

                    if (clickCount >= 5) {
                        // Toggle dev mode
                        settingsManager.toggleDevMode()
                        Toast.makeText(context, "Dev mode: ${settingsManager.isDevMode()}", Toast.LENGTH_LONG).show()
                        clickCount = 0 // Reset the click count
                    }
                }
        )
        if (settingsManager.isDevMode()) {
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
fun UsageStatsDisplay(usageStatsList: List<PackageUsage>, appDb: AppDb, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Sort the usageStatsList by totalTimeInForeground in descending order
    val sortedUsageStatsList = usageStatsList.sortedByDescending { it.totalTimeInForegroundMillis }

    LazyColumn(modifier = modifier.padding(16.dp)) {
        items(sortedUsageStatsList) { stat ->
            var app by remember { mutableStateOf<App?>(null) }
            val totalTimeFormatted = stat.totalTimeInForegroundMillis.toHMS()

            LaunchedEffect(stat.packageName) {
                app = appDb.getAppNameAndIcon(stat.packageName)
            }

            app?.let { appInfo ->
                Row(modifier = Modifier.padding(vertical = 8.dp)) {
                    appInfo.icon?.let { base64Icon ->
                        val bitmap = base64ToBitmap(base64Icon)
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = appInfo.name,
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
        UsageStatsDisplay(emptyList(), appDb = AppDb(androidx.compose.ui.platform.LocalContext.current))
    }
}