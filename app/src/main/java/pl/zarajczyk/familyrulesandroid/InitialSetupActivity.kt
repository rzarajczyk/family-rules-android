package pl.zarajczyk.familyrulesandroid

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.IconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pl.zarajczyk.familyrulesandroid.core.SettingsManager
import pl.zarajczyk.familyrulesandroid.ui.theme.FamilyRulesAndroidTheme
import java.net.HttpURLConnection
import java.net.URL

class InitialSetupActivity : ComponentActivity() {
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(this)

        setContent {
            FamilyRulesAndroidTheme {
                InitialSetupScreen(
                    settingsManager = settingsManager,
                    onRegistrationCompleted = {
                        // After registration, go to protection setup
                        finish()
                        startActivity(Intent(this, PermissionsSetupActivity::class.java))
                    }
                )
            }
        }
    }


    suspend fun registerInstance(
        context: Context,
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
                        "INSTANCE_ALREADY_EXISTS" -> Result.Error(context.getString(R.string.instance_already_exists))
                        "ILLEGAL_INSTANCE_NAME" -> Result.Error(context.getString(R.string.illegal_instance_name))
                        "INVALID_PASSWORD" -> Result.Error(context.getString(R.string.invalid_password))
                        else -> Result.Error(context.getString(R.string.unknown_error))
                    }
                } else {
                    Result.Error(context.getString(R.string.server_error, connection.responseMessage))
                }
            } catch (e: Exception) {
                Result.Error(e.message ?: context.getString(R.string.unknown_error))
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
    onRegistrationCompleted: () -> Unit
) {
    // Only handle registration - permissions are handled in ProtectionSetupActivity
    showSetupForm(
        settingsManager = settingsManager,
        onRegistrationCompleted = onRegistrationCompleted
    )
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
                        context, serverUrl, username, password, instanceName
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
            label = { Text(stringResource(R.string.server_url)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )
        TextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text(stringResource(R.string.username)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )
        var passwordVisible by remember { mutableStateOf(false) }
        
        TextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.password)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Text(
                        text = if (passwordVisible) stringResource(R.string.password_show) else stringResource(R.string.password_hide),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        )
        TextField(
            value = instanceName,
            onValueChange = onInstanceNameChange,
            label = { Text(stringResource(R.string.instance_name)) },
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
            Text(stringResource(R.string.install_button))
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
