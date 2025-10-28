package pl.zarajczyk.familyrulesandroid.adapter

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pl.zarajczyk.familyrulesandroid.core.SettingsManager
import pl.zarajczyk.familyrulesandroid.database.AppDb
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

data class Uptime(
    val screenTimeMillis: Long,
    val packageUsages: Map<String, Long>
)

class FamilyRulesClient(
    private val settingsManager: SettingsManager,
    private val appDb: AppDb
) {

    suspend fun sendClientInfoRequest() {
        val serverUrl = settingsManager.getString("serverUrl", "")
        val instanceId = settingsManager.getString("instanceId", "")
        val instanceToken = settingsManager.getString("instanceToken", "")
        val version = settingsManager.getVersion()

        // Get known apps from AppDb
        val knownApps = JSONObject()
        try {
            val allAppInfo = appDb.getAllAppInfo()
            allAppInfo.forEach { appInfo ->
                val appData = JSONObject().apply {
                    put("appName", appInfo.appName)
                    put("iconBase64Png", appInfo.iconBase64 ?: JSONObject.NULL)
                }
                knownApps.put(appInfo.packageName, appData)
            }
        } catch (e: Exception) {
            Log.e("FamilyRulesClient", "Failed to get known apps: ${e.message}", e)
        }

        val json = JSONObject().apply {
            put("instanceId", instanceId)
            put("version", version)
            put("knownApps", knownApps)
            put("availableStates", JSONArray().apply {
                DeviceState.entries.forEach { state ->
                    val stateName = state.name
                    val stateTitle = when (state) {
                        DeviceState.ACTIVE -> "Active"
                        DeviceState.BLOCK_LIMITTED_APPS -> "Block Limited Apps"
                    }
                    val stateIcon = when (state) {
                        DeviceState.ACTIVE -> "<path d=\"m424-296 282-282-56-56-226 226-114-114-56 56 170 170Zm56 216q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z\"/>"
                        DeviceState.BLOCK_LIMITTED_APPS -> "<path d=\"M240-80q-33 0-56.5-23.5T160-160v-400q0-33 23.5-56.5T240-640h40v-80q0-83 58.5-141.5T480-920q83 0 141.5 58.5T680-720v80h40q33 0 56.5 23.5T800-560v400q0 33-23.5 56.5T720-80H240Zm0-80h480v-400H240v400Zm240-120q33 0 56.5-23.5T560-360q0-33-23.5-56.5T480-440q-33 0-56.5 23.5T400-360q0 33 23.5 56.5T480-280ZM360-640h240v-80q0-50-35-85t-85-35q-50 0-85 35t-35 85v80ZM240-160v-400 400Z\"/>"
                    }
                    val stateDescription = when (state) {
                        DeviceState.ACTIVE -> "Device is active"
                        DeviceState.BLOCK_LIMITTED_APPS -> "Blocking limited apps like Chrome"
                    }
                    val arguments = when (state) {
                        DeviceState.ACTIVE -> null
                        DeviceState.BLOCK_LIMITTED_APPS -> listOf(mapOf("type" to "SET_OF_APP_GROUPS"))
                    }

                    put(JSONObject().apply {
                        put("deviceState", stateName)
                        put("title", stateTitle)
                        put("icon", stateIcon)
                        put("description", stateDescription)
                        put("arguments", arguments)
                    })

                }
            })
        }.toString()

        withContext(Dispatchers.IO) {
            try {
                val url = URL("$serverUrl/api/v2/launch")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Content-Type", "application/json; utf-8")
                val auth = Base64.encodeToString(
                    "$instanceId:$instanceToken".toByteArray(),
                    Base64.NO_WRAP
                )
                connection.setRequestProperty("Authorization", "Basic $auth")
                connection.doOutput = true

                connection.outputStream.use { os: OutputStream ->
                    val input = json.toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw RuntimeException("Server returned HTTP ${connection.responseCode}")
                }
                "ok"
            } catch (e: Exception) {
                Log.e("FamilyRulesClient", "Failed to send client-info request: ${e.message}", e)
            }
        }
    }

    suspend fun reportUptime(uptime: Uptime): DeviceState {
        val serverUrl = settingsManager.getString("serverUrl", "")
        val instanceId = settingsManager.getString("instanceId", "")
        val instanceToken = settingsManager.getString("instanceToken", "")

        val applications = JSONObject().apply {
            uptime.packageUsages.forEach { stat ->
                val packageName = stat.key
                val totalTimeInForegroundMillis = stat.value
                put(packageName, totalTimeInForegroundMillis / 1000)
            }
        }

        val json = JSONObject().apply {
            put("instanceId", instanceId)
            put("screenTime", uptime.screenTimeMillis / 1000)
            put("applications", applications)
        }.toString()

        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$serverUrl/api/v2/report")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Content-Type", "application/json; utf-8")
                val auth = Base64.encodeToString(
                    "$instanceId:$instanceToken".toByteArray(),
                    Base64.NO_WRAP
                )
                connection.setRequestProperty("Authorization", "Basic $auth")
                connection.doOutput = true

                connection.outputStream.use { os: OutputStream ->
                    val input = json.toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw RuntimeException("Failed to send report request: HTTP ${connection.responseCode}")
                }

                // Read the response
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d("FamilyRulesClient", "Report response: $response")
                val jsonResponse = JSONObject(response)
                val deviceStateString = jsonResponse.getString("deviceState")

                DeviceState.valueOf(deviceStateString)
            } catch (e: Exception) {
                Log.e("FamilyRulesClient", "Failed to send report request: ${e.message}", e)
                DeviceState.ACTIVE
            }
        }
    }
}

enum class DeviceState {
    ACTIVE,
    BLOCK_LIMITTED_APPS
}