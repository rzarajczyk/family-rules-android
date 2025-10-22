package pl.zarajczyk.familyrulesandroid

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.zarajczyk.familyrulesandroid.core.DeviceAdminManager
import pl.zarajczyk.familyrulesandroid.core.PermissionsChecker
import pl.zarajczyk.familyrulesandroid.core.TamperDetector
import pl.zarajczyk.familyrulesandroid.ui.theme.FamilyRulesAndroidTheme

class ProtectionSetupActivity : ComponentActivity() {
    
    private lateinit var deviceAdminManager: DeviceAdminManager
    private lateinit var tamperDetector: TamperDetector
    private lateinit var permissionChecker: PermissionsChecker
    
    // Permission request launchers
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            // Permission was denied, check if we should show rationale or direct to settings
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (!shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                    // User selected "Don't ask again" or this is the first denial
                    // Direct them to app settings
                    openAppSettings()
                }
            }
        }
    }

    private val usageStatsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Permission check will be handled in the UI
    }

    private val systemAlertWindowPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Permission check will be handled in the UI
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        deviceAdminManager = DeviceAdminManager(this)
        tamperDetector = TamperDetector(this)
        permissionChecker = PermissionsChecker(this)
        
        setContent {
            FamilyRulesAndroidTheme {
                SharedAppLayout {
                    ProtectionSetupContent(
                        deviceAdminManager = deviceAdminManager,
                        tamperDetector = tamperDetector,
                        permissionChecker = permissionChecker,
                        onNotificationPermissionRequest = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                // Check if permission was previously denied
                                if (!shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS) &&
                                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                    // Permission was denied and "Don't ask again" was selected, or first denial
                                    // Direct user to app settings
                                    openAppSettings()
                                } else {
                                    // Permission can be requested normally
                                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        },
                        onUsageStatsPermissionRequest = {
                            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            usageStatsPermissionLauncher.launch(intent)
                        },
                        onSystemAlertWindowPermissionRequest = {
                            permissionChecker.navigateToSystemAlertWindowPermissionSettings()
                        },
                        onSetupComplete = { 
                            finish()
                            startActivity(Intent(this@ProtectionSetupActivity, MainActivity::class.java))
                        }
                    )
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh the UI when returning from settings
    }
    
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtectionSetupContent(
    deviceAdminManager: DeviceAdminManager,
    tamperDetector: TamperDetector,
    permissionChecker: PermissionsChecker,
    onNotificationPermissionRequest: () -> Unit,
    onUsageStatsPermissionRequest: () -> Unit,
    onSystemAlertWindowPermissionRequest: () -> Unit,
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    var deviceAdminEnabled by remember { mutableStateOf(deviceAdminManager.isDeviceAdminActive()) }
    var batteryOptimizationDisabled by remember { mutableStateOf(false) }
    var notificationPermissionGranted by remember { mutableStateOf(permissionChecker.isNotificationPermissionGranted()) }
    var usageStatsPermissionGranted by remember { mutableStateOf(permissionChecker.isUsageStatsPermissionGranted()) }
    var systemAlertWindowPermissionGranted by remember { mutableStateOf(permissionChecker.isSystemAlertWindowPermissionGranted()) }
    
    LaunchedEffect(Unit) {
        batteryOptimizationDisabled = !isBatteryOptimizationEnabled(context)
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "App Protection Setup",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = "To complete the app setup, please grant the following permissions and enable protection features:",
            style = MaterialTheme.typography.bodyLarge
        )
        
        // Notification Permission
        ProtectionCard(
            title = "Notification Permission",
            description = "Required for app notifications and background operation",
            isEnabled = notificationPermissionGranted,
            onEnableClick = onNotificationPermissionRequest,
            onRefresh = { notificationPermissionGranted = permissionChecker.isNotificationPermissionGranted() }
        )
        
        // App Usage Permission
        ProtectionCard(
            title = "App Usage Permission",
            description = "Required to monitor app usage and screen time",
            isEnabled = usageStatsPermissionGranted,
            onEnableClick = onUsageStatsPermissionRequest,
            onRefresh = { usageStatsPermissionGranted = permissionChecker.isUsageStatsPermissionGranted() }
        )
        
        // System Alert Window Permission
        ProtectionCard(
            title = "Display Over Other Apps",
            description = "Required to show blocking overlays when apps are restricted",
            isEnabled = systemAlertWindowPermissionGranted,
            onEnableClick = onSystemAlertWindowPermissionRequest,
            onRefresh = { systemAlertWindowPermissionGranted = permissionChecker.isSystemAlertWindowPermissionGranted() }
        )
        
        // Device Admin Protection
        ProtectionCard(
            title = "Device Administrator Rights",
            description = "Prevents app uninstallation and provides device control",
            isEnabled = deviceAdminEnabled,
            onEnableClick = {
                val intent = deviceAdminManager.requestDeviceAdminPermission()
                context.startActivity(intent)
            },
            onRefresh = { deviceAdminEnabled = deviceAdminManager.isDeviceAdminActive() }
        )
        
        // Battery Optimization
        ProtectionCard(
            title = "Battery Optimization",
            description = "Prevents system from killing the app to save battery",
            isEnabled = batteryOptimizationDisabled,
            onEnableClick = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            },
            onRefresh = { batteryOptimizationDisabled = !isBatteryOptimizationEnabled(context) }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Setup Complete Button
        val allPermissionsGranted = notificationPermissionGranted && usageStatsPermissionGranted && systemAlertWindowPermissionGranted && deviceAdminEnabled
        
        Button(
            onClick = {
                tamperDetector.startMonitoring()
                onSetupComplete()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = allPermissionsGranted
        ) {
            Text("Complete Setup")
        }
        
        if (!allPermissionsGranted) {
            val missingPermissions = mutableListOf<String>()
            if (!notificationPermissionGranted) missingPermissions.add("Notification Permission")
            if (!usageStatsPermissionGranted) missingPermissions.add("App Usage Permission")
            if (!systemAlertWindowPermissionGranted) missingPermissions.add("Display Over Other Apps")
            if (!deviceAdminEnabled) missingPermissions.add("Device Administrator Rights")
            
            Text(
                text = "Please grant all permissions to complete setup: ${missingPermissions.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun ProtectionCard(
    title: String,
    description: String,
    isEnabled: Boolean,
    onEnableClick: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = if (isEnabled) "✓ Enabled" else "✗ Disabled",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isEnabled) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.error
                )
            }
            
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isEnabled) {
                    Button(
                        onClick = onEnableClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Enable")
                    }
                }
                
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Refresh")
                }
            }
        }
    }
}

private fun isBatteryOptimizationEnabled(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    } else {
        true
    }
}
