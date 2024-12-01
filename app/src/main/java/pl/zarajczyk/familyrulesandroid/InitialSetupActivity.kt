package pl.zarajczyk.familyrulesandroid

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pl.zarajczyk.familyrulesandroid.gui.SettingsManager
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
                InitialSetupScreen(settingsManager) {
                    finish()
                    startActivity(Intent(this, MainActivity::class.java))
                }
            }
        }
    }

    public suspend fun registerInstance(
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
@OptIn(ExperimentalMaterial3Api::class)
fun InitialSetupScreen(settingsManager: SettingsManager, onSetupComplete: () -> Unit) {
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var instanceName by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "FamilyRules Initial Setup") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF000080),
                    titleContentColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(16.dp)) {
            TextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("Server URL") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )
            TextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )
            TextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                visualTransformation = PasswordVisualTransformation()
            )
            TextField(
                value = instanceName,
                onValueChange = { instanceName = it },
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
                onClick = {
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
                                onSetupComplete()
                            }
                            is InitialSetupActivity.Result.Error -> {
                                errorMessage = result.message
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                Text("Save Settings")
            }
        }
    }
}