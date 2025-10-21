package pl.zarajczyk.familyrulesandroid

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.zarajczyk.familyrulesandroid.core.*
import pl.zarajczyk.familyrulesandroid.ui.theme.FamilyRulesAndroidTheme

class ProtectionSetupActivity : ComponentActivity() {
    
    private lateinit var deviceAdminManager: DeviceAdminManager
    private lateinit var stealthModeManager: StealthModeManager
    private lateinit var tamperDetector: TamperDetector
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        deviceAdminManager = DeviceAdminManager(this)
        stealthModeManager = StealthModeManager(this)
        tamperDetector = TamperDetector(this)
        
        setContent {
            FamilyRulesAndroidTheme {
                ProtectionSetupScreen(
                    deviceAdminManager = deviceAdminManager,
                    stealthModeManager = stealthModeManager,
                    tamperDetector = tamperDetector,
                    onSetupComplete = { finish() }
                )
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh the UI when returning from settings
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtectionSetupScreen(
    deviceAdminManager: DeviceAdminManager,
    stealthModeManager: StealthModeManager,
    tamperDetector: TamperDetector,
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    var deviceAdminEnabled by remember { mutableStateOf(deviceAdminManager.isDeviceAdminActive()) }
    var stealthModeEnabled by remember { mutableStateOf(stealthModeManager.isStealthModeEnabled()) }
    var batteryOptimizationDisabled by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        batteryOptimizationDisabled = !isBatteryOptimizationEnabled(context)
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
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
            text = "To prevent your child from uninstalling this app, please enable the following protection features:",
            style = MaterialTheme.typography.bodyLarge
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
        
        // Stealth Mode
        ProtectionCard(
            title = "Stealth Mode",
            description = "Hides app icon from launcher to prevent discovery",
            isEnabled = stealthModeEnabled,
            onEnableClick = {
                stealthModeManager.enableStealthMode()
                stealthModeEnabled = stealthModeManager.isStealthModeEnabled()
            },
            onRefresh = { stealthModeEnabled = stealthModeManager.isStealthModeEnabled() }
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
        Button(
            onClick = {
                tamperDetector.startMonitoring()
                onSetupComplete()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = deviceAdminEnabled
        ) {
            Text("Complete Setup")
        }
        
        if (!deviceAdminEnabled) {
            Text(
                text = "Device Administrator rights are required to complete setup",
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
