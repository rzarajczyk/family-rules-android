package pl.zarajczyk.familyrulesandroid.adapter

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pl.zarajczyk.familyrulesandroid.scheduled.Uptime
import pl.zarajczyk.familyrulesandroid.gui.SettingsManager
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class FamilyRulesClient(
    private val context: Context,
    private val settingsManager: SettingsManager
) {
    private val isDevMode = true


    fun sendLaunchRequest() {
        val serverUrl = settingsManager.getString("serverUrl", "")
        val instanceId = settingsManager.getString("instanceId", "")
        val username = settingsManager.getString("username", "")
        val instanceToken = settingsManager.getString("instanceToken", "")
        val version = settingsManager.getVersion()

        val json = JSONObject().apply {
            put("instanceId", instanceId)
            put("version", version)
            put("availableStates", JSONArray().apply {
                put(JSONObject().apply {
                    put("deviceState", "ACTIVE")
                    put("title", "Active")
                    put(
                        "icon",
                        "<path d=\"m424-296 282-282-56-56-226 226-114-114-56 56 170 170Zm56 216q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z\"/>"
                    )
                    put("description", JSONObject.NULL)
                })
            })
        }.toString()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("$serverUrl/api/v2/launch")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Content-Type", "application/json; utf-8")
                val auth = android.util.Base64.encodeToString(
                    "$instanceId:$instanceToken".toByteArray(),
                    android.util.Base64.NO_WRAP
                )
                connection.setRequestProperty("Authorization", "Basic $auth")
                connection.doOutput = true

                connection.outputStream.use { os: OutputStream ->
                    val input = json.toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw RuntimeException("Failed to send launch request: HTTP ${connection.responseCode}")
                }
            } catch (e: Exception) {
                Log.e("FamilyRulesClient", "Failed to send launch request: ${e.message}", e)
                showToast(e)
            }
        }
    }

    fun reportUptime(uptime: Uptime) {
        val serverUrl = settingsManager.getString("serverUrl", "")
        val username = settingsManager.getString("username", "")
        val instanceId = settingsManager.getString("instanceId", "")
        val instanceToken = settingsManager.getString("instanceToken", "")

        val applications = JSONObject().apply {
            uptime.packageUsages.forEach { stat ->
                put(stat.packageName, stat.totalTimeInForegroundMillis / 1000)
            }
        }

        val json = JSONObject().apply {
            put("instanceId", instanceId)
            put("screenTime", uptime.screenTimeMillis / 1000)
            put("applications", applications)
        }.toString()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("$serverUrl/api/v2/report")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Content-Type", "application/json; utf-8")
                val auth = android.util.Base64.encodeToString(
                    "$instanceId:$instanceToken".toByteArray(),
                    android.util.Base64.NO_WRAP
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
            } catch (e: Exception) {
                Log.e("FamilyRulesClient", "Failed to send launch request: ${e.message}", e)
                showToast(e)
            }
        }
    }

    private suspend fun showToast(e: Exception) {
        if (isDevMode) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}