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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pl.zarajczyk.familyrulesandroid.SharedAppLayout
import pl.zarajczyk.familyrulesandroid.core.SettingsManager
import pl.zarajczyk.familyrulesandroid.core.PermissionsChecker
import pl.zarajczyk.familyrulesandroid.core.FamilyRulesCoreService
import pl.zarajczyk.familyrulesandroid.ui.theme.FamilyRulesAndroidTheme
import java.net.HttpURLConnection
import java.net.URL

class InitialSetupActivity : ComponentActivity() {
    private lateinit var settingsManager: SettingsManager
    private lateinit var permissionChecker: PermissionsChecker

    // Permission request launcher
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
        checkPermissionsAndUpdateUI()
    }

    private val usageStatsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkPermissionsAndUpdateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(this)
        permissionChecker = PermissionsChecker(this)

        setContent {
            FamilyRulesAndroidTheme {
                InitialSetupScreen(
                    settingsManager = settingsManager,
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
                    onAllPermissionsGranted = {
                        // Start the core service early to begin uptime calculation
                        FamilyRulesCoreService.install(this)
                        finish()
                        startActivity(Intent(this, MainActivity::class.java))
                    },
                    onRegistrationCompleted = {
                        checkPermissionsAndUpdateUI()
                    }
                )
            }
        }
    }

    private fun checkPermissionsAndUpdateUI() {
        finish()
        startActivity(Intent(this, InitialSetupActivity::class.java))
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    suspend fun registerInstance(
        serverUrl: String,
        username: String,
        password: String,
        instanceName: String
    ): Result {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$serverUrl/api/v2/register-instance")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; utf-8")
                val auth = android.util.Base64.encodeToString("$username:$password".toByteArray(), android.util.Base64.NO_WRAP)
                connection.setRequestProperty("Authorization", "Basic $auth")
                connection.doOutput = true

                val jsonInputString = JSONObject()
                    .put("instanceName", instanceName)
                    .put("clientType", "ANDROID")
                    .toString()

                connection.outputStream.use { os ->
                    val input = jsonInputString.toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(response)
                    val status = jsonResponse.getString("status")
                    return@withContext when (status) {
                        "SUCCESS" -> {
                            val instanceId = jsonResponse.getString("instanceId")
                            val instanceToken = jsonResponse.getString("token")
                            Result.Success(instanceId, instanceToken)
                        }
                        "INSTANCE_ALREADY_EXISTS" -> Result.Error("Instance already exists.")
                        "ILLEGAL_INSTANCE_NAME" -> Result.Error("Illegal instance name.")
                        "INVALID_PASSWORD" -> Result.Error("Invalid password.")
                        else -> Result.Error("Unknown error.")
                    }
                } else {
                    Result.Error("Server returned: ${connection.responseMessage}")
                }
            } catch (e: Exception) {
                Result.Error(e.message ?: "Unknown error")
            }
        }
    }

    sealed class Result {
        data class Success(val instanceId: String, val instanceToken: String) : Result()
        data class Error(val message: String) : Result()
    }
}

@Composable
fun InitialSetupScreen(
    settingsManager: SettingsManager,
    permissionChecker: PermissionsChecker,
    onNotificationPermissionRequest: () -> Unit,
    onUsageStatsPermissionRequest: () -> Unit,
    onAllPermissionsGranted: () -> Unit,
    onRegistrationCompleted: () -> Unit
) {
    // Determine current screen state using the specified conditional flow
    when {
        !settingsManager.areSettingsComplete() -> showSetupForm(
            settingsManager = settingsManager,
            onRegistrationCompleted = onRegistrationCompleted
        )
        !permissionChecker.isNotificationPermissionGranted() || !permissionChecker.isUsageStatsPermissionGranted() -> showGrantPermissionsForm(
            permissionChecker = permissionChecker,
            onNotificationPermissionRequest = onNotificationPermissionRequest,
            onUsageStatsPermissionRequest = onUsageStatsPermissionRequest
        )
        else -> setupCompleted(onAllPermissionsGranted = onAllPermissionsGranted)
    }

}

// Generic layout wrapper that uses the shared layout component
@Composable
fun GenericSetupLayout(
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    SharedAppLayout(content = content)
}

// Setup form screen
@Composable
fun showSetupForm(
    settingsManager: SettingsManager,
    onRegistrationCompleted: () -> Unit
) {
    var serverUrl by remember { mutableStateOf("https://familyrules.org") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var instanceName by remember { 
        mutableStateOf(
            "${Build.MANUFACTURER} ${Build.MODEL}".replace(" ", "_")
                .replace("[^a-zA-Z0-9_]".toRegex(), "")
        )
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    GenericSetupLayout {
        SetupForm(
            serverUrl = serverUrl,
            onServerUrlChange = { serverUrl = it },
            username = username,
            onUsernameChange = { username = it },
            password = password,
            onPasswordChange = { password = it },
            instanceName = instanceName,
            onInstanceNameChange = { instanceName = it },
            errorMessage = errorMessage,
            onInstallClick = {
                scope.launch {
                    val result = (context as InitialSetupActivity).registerInstance(
                        serverUrl, username, password, instanceName
                    )
                    when (result) {
                        is InitialSetupActivity.Result.Success -> {
                            settingsManager.setString("serverUrl", serverUrl)
                            settingsManager.setString("username", username)
                            settingsManager.setString("instanceId", result.instanceId)
                            settingsManager.setString("instanceName", instanceName)
                            settingsManager.setString("instanceToken", result.instanceToken)
                            // Trigger recomposition to restart the flow
                            onRegistrationCompleted()
                        }
                        is InitialSetupActivity.Result.Error -> {
                            errorMessage = result.message
                        }
                    }
                }
            }
        )
    }
}

// Unified permission screen
@Composable
fun showGrantPermissionsForm(
    permissionChecker: PermissionsChecker,
    onNotificationPermissionRequest: () -> Unit,
    onUsageStatsPermissionRequest: () -> Unit
) {
    GenericSetupLayout {
        UnifiedPermissionScreen(
            permissionChecker = permissionChecker,
            onNotificationPermissionRequest = onNotificationPermissionRequest,
            onUsageStatsPermissionRequest = onUsageStatsPermissionRequest
        )
    }
}

// Setup completed screen
@Composable
fun setupCompleted(onAllPermissionsGranted: () -> Unit) {
    // Call the completion callback immediately
    LaunchedEffect(Unit) {
        onAllPermissionsGranted()
    }
}

@Composable
fun SetupForm(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    instanceName: String,
    onInstanceNameChange: (String) -> Unit,
    errorMessage: String?,
    onInstallClick: () -> Unit
) {
    val scrollState = rememberScrollState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .background(
                color = Color.White,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        TextField(
            value = serverUrl,
            onValueChange = onServerUrlChange,
            label = { Text("Server URL") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )
        TextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("Username") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )
        var passwordVisible by remember { mutableStateOf(false) }
        
        TextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Text(
                        text = if (passwordVisible) "👁️" else "🙈",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        )
        TextField(
            value = instanceName,
            onValueChange = onInstanceNameChange,
            label = { Text("Instance Name") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )
        errorMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        Button(
            onClick = onInstallClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Text("Install!")
        }
    }
}

@Composable
fun PermissionScreen(
    title: String,
    description: String,
    buttonText: String,
    onButtonClick: () -> Unit
) {
    val scrollState = rememberScrollState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .background(
                color = Color.White,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.Black,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Black,
            modifier = Modifier.padding(bottom = 32.dp)
        )
        
        Button(
            onClick = onButtonClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Text(buttonText)
        }
    }
}

@Composable
fun UnifiedPermissionScreen(
    permissionChecker: PermissionsChecker,
    onNotificationPermissionRequest: () -> Unit,
    onUsageStatsPermissionRequest: () -> Unit
) {
    val scrollState = rememberScrollState()
    val isNotificationPermissionGranted = permissionChecker.isNotificationPermissionGranted()
    val isUsageStatsPermissionGranted = permissionChecker.isUsageStatsPermissionGranted()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .background(
                color = Color.White,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "FamilyRules needs the following permissions to function properly:",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Black,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        if (!isNotificationPermissionGranted) {
            Text(
                text = "If you previously denied notification permission, you may need to grant it manually in app settings.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF666666),
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
        
        // Notification Permission Button
        Button(
            onClick = onNotificationPermissionRequest,
            enabled = !isNotificationPermissionGranted,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Text(
                if (isNotificationPermissionGranted) "Notification Permission Granted ✓" 
                else "Grant Notification Permission"
            )
        }
        
        // App Usage Permission Button
        Button(
            onClick = onUsageStatsPermissionRequest,
            enabled = !isUsageStatsPermissionGranted,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Text(
                if (isUsageStatsPermissionGranted) "App Usage Permission Granted ✓" 
                else "Grant App Usage Permission"
            )
        }
        
        if (isNotificationPermissionGranted && isUsageStatsPermissionGranted) {
            Text(
                text = "All permissions granted! You can now proceed.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF4CAF50),
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}