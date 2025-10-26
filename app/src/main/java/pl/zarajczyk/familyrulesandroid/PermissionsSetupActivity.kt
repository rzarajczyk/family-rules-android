package pl.zarajczyk.familyrulesandroid

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.zarajczyk.familyrulesandroid.core.DeviceAdminManager
import pl.zarajczyk.familyrulesandroid.core.PermissionsChecker
import pl.zarajczyk.familyrulesandroid.ui.theme.FamilyRulesAndroidTheme

class PermissionsSetupActivity : ComponentActivity() {
    
    private lateinit var deviceAdminManager: DeviceAdminManager
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
        permissionChecker = PermissionsChecker(this)
        
        setContent {
            FamilyRulesAndroidTheme {
                SharedAppLayout {
                    ProtectionSetupContent(
                        deviceAdminManager = deviceAdminManager,
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
                            startActivity(Intent(this@PermissionsSetupActivity, MainActivity::class.java))
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
            text = stringResource(R.string.app_protection_setup),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = stringResource(R.string.protection_setup_description),
            style = MaterialTheme.typography.bodyLarge
        )
        
        // Notification Permission
        ProtectionCard(
            title = stringResource(R.string.notification_permission),
            description = stringResource(R.string.notification_permission_description),
            isEnabled = notificationPermissionGranted,
            onEnableClick = onNotificationPermissionRequest,
            onRefresh = { notificationPermissionGranted = permissionChecker.isNotificationPermissionGranted() }
        )
        
        // App Usage Permission
        ProtectionCard(
            title = stringResource(R.string.app_usage_permission),
            description = stringResource(R.string.app_usage_permission_description),
            isEnabled = usageStatsPermissionGranted,
            onEnableClick = onUsageStatsPermissionRequest,
            onRefresh = { usageStatsPermissionGranted = permissionChecker.isUsageStatsPermissionGranted() }
        )
        
        // System Alert Window Permission
        ProtectionCard(
            title = stringResource(R.string.display_over_apps),
            description = stringResource(R.string.display_over_apps_description),
            isEnabled = systemAlertWindowPermissionGranted,
            onEnableClick = onSystemAlertWindowPermissionRequest,
            onRefresh = { systemAlertWindowPermissionGranted = permissionChecker.isSystemAlertWindowPermissionGranted() }
        )
        
        // Device Admin Protection
        ProtectionCard(
            title = stringResource(R.string.device_admin_rights),
            description = stringResource(R.string.device_admin_rights_description),
            isEnabled = deviceAdminEnabled,
            onEnableClick = {
                val intent = deviceAdminManager.requestDeviceAdminPermission()
                context.startActivity(intent)
            },
            onRefresh = { deviceAdminEnabled = deviceAdminManager.isDeviceAdminActive() }
        )
        
        // Battery Optimization
        ProtectionCard(
            title = stringResource(R.string.battery_optimization),
            description = stringResource(R.string.battery_optimization_description),
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
                onSetupComplete()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = allPermissionsGranted
        ) {
            Text(stringResource(R.string.complete_setup))
        }
        
        if (!allPermissionsGranted) {
            val missingPermissions = mutableListOf<String>()
            if (!notificationPermissionGranted) missingPermissions.add(stringResource(R.string.notification_permission))
            if (!usageStatsPermissionGranted) missingPermissions.add(stringResource(R.string.app_usage_permission))
            if (!systemAlertWindowPermissionGranted) missingPermissions.add(stringResource(R.string.display_over_apps))
            if (!deviceAdminEnabled) missingPermissions.add(stringResource(R.string.device_admin_rights))
            
            Text(
                text = stringResource(R.string.missing_permissions, missingPermissions.joinToString(", ")),
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
                    text = if (isEnabled) stringResource(R.string.enabled) else stringResource(R.string.disabled),
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
                        Text(stringResource(R.string.enable))
                    }
                }
                
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.refresh))
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
