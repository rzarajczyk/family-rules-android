package pl.zarajczyk.familyrulesandroid

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.zarajczyk.familyrulesandroid.ui.theme.FamilyRulesAndroidTheme

class InitialSetupActivity : ComponentActivity() {
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(this)

        setContent {
            FamilyRulesAndroidTheme {
                InitialSetupScreen(settingsManager) {
                    finish()
                    startActivity(Intent(this, MainActivity::class.java))
                }
            }
        }
    }
}

@Composable
fun InitialSetupScreen(settingsManager: SettingsManager, onSetupComplete: () -> Unit) {
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var instanceId by remember { mutableStateOf("") }
    var instanceName by remember { mutableStateOf("") }
    var instanceToken by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(16.dp)) {
        TextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        TextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        TextField(
            value = instanceId,
            onValueChange = { instanceId = it },
            label = { Text("Instance ID") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        TextField(
            value = instanceName,
            onValueChange = { instanceName = it },
            label = { Text("Instance Name") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        TextField(
            value = instanceId,
            onValueChange = { instanceId = it },
            label = { Text("Instance Token") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        Button(
            onClick = {
                settingsManager.setString("serverUrl", serverUrl)
                settingsManager.setString("username", username)
                settingsManager.setString("instanceId", instanceId)
                settingsManager.setString("instanceName", instanceName)
                settingsManager.setString("instanceToken", instanceToken)
                onSetupComplete()
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
        ) {
            Text("Save Settings")
        }
    }
}