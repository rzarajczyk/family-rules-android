package pl.zarajczyk.familyrulesandroid.adapter

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import pl.zarajczyk.familyrulesandroid.core.SettingsManager
import pl.zarajczyk.familyrulesandroid.database.AppDb
import pl.zarajczyk.familyrulesandroid.utils.Logger
import pl.zarajczyk.familyrulesandroid.utils.millisToHMS
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

data class Uptime(
    val screenTimeMillis: Long,
    val packageUsages: Map<String, Long>
)

data class AppDetails(
    val appName: String,
    val iconBase64Png: String?
)

class FamilyRulesClient(
    private val settingsManager: SettingsManager,
    private val appDb: AppDb
) {
    companion object {
        val AVAILABLE_STATES = DeviceState.entries.map { state ->
            val stateTitle = when (state) {
                DeviceState.ACTIVE -> "Active"
                DeviceState.BLOCK_RESTRICTED_APPS -> "Block Restricted Apps"
            }
            val stateIcon = when (state) {
                DeviceState.ACTIVE -> "<path d=\"m424-296 282-282-56-56-226 226-114-114-56 56 170 170Zm56 216q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z\"/>"
                DeviceState.BLOCK_RESTRICTED_APPS -> "<path d=\"M240-80q-33 0-56.5-23.5T160-160v-400q0-33 23.5-56.5T240-640h40v-80q0-83 58.5-141.5T480-920q83 0 141.5 58.5T680-720v80h40q33 0 56.5 23.5T800-560v400q0 33-23.5 56.5T720-80H240Zm0-80h480v-400H240v400Zm240-120q33 0 56.5-23.5T560-360q0-33-23.5-56.5T480-440q-33 0-56.5 23.5T400-360q0 33 23.5 56.5T480-280ZM360-640h240v-80q0-50-35-85t-85-35q-50 0-85 35t-35 85v80ZM240-160v-400 400Z\"/>"
            }
            val arguments = when (state) {
                DeviceState.ACTIVE -> null
                DeviceState.BLOCK_RESTRICTED_APPS -> null
            }
            AvailableState(
                deviceState = state.name,
                title = stateTitle,
                icon = stateIcon,
                description = when (state) {
                    DeviceState.ACTIVE -> "Device is active"
                    DeviceState.BLOCK_RESTRICTED_APPS -> "Blocking apps belonging to the chosen group"
                },
                arguments = arguments
            )
        }
    }

    private val apiService: FamilyRulesApiService by lazy {
        val serverUrlRaw = settingsManager.getString("serverUrl", "")
        val baseUrl = if (serverUrlRaw.endsWith("/")) serverUrlRaw else "$serverUrlRaw/"
        val instanceId = settingsManager.getString("instanceId", "")
        val instanceToken = settingsManager.getString("instanceToken", "")

        val authInterceptor = Interceptor { chain ->
            val original: Request = chain.request()
            val credentials =
                Base64.encodeToString("$instanceId:$instanceToken".toByteArray(), Base64.NO_WRAP)
            val newRequest = original.newBuilder()
                .header("Authorization", "Basic $credentials")
                .header("Content-Type", "application/json; charset=utf-8")
                .build()
            chain.proceed(newRequest)
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .followRedirects(true)
            .build()

        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        retrofit.create(FamilyRulesApiService::class.java)
    }

    suspend fun sendClientInfoRequest() {
        Logger.i("FamilyRulesClient", "Sending client info request")
        val instanceId = settingsManager.getString("instanceId", "")
        val version = settingsManager.getVersion()
        val knownApps: Map<String, AppData> = appDb
            .getAllAppInfo()
            .associate { appInfo ->
                appInfo.packageName to AppData(
                    appName = appInfo.appName,
                    iconBase64Png = appInfo.iconBase64
                )
            }

        val request = ClientInfoRequest(
            instanceId = instanceId,
            version = version,
            knownApps = knownApps,
            availableStates = AVAILABLE_STATES
        )

        withContext(Dispatchers.IO) {
            try {
                val response = apiService.sendClientInfo(request)
                val restrictedApps = response.restrictedApps ?: emptyMap()
                Log.d(
                    "FamilyRulesClient",
                    "Client-info returned restricted apps: ${restrictedApps.keys}"
                )
            } catch (e: Exception) {
                Log.e("FamilyRulesClient", "Failed to send client-info request: ${e.message}", e)
            }
        }
    }

    suspend fun reportUptime(uptime: Uptime): ActualDeviceState {
        val instanceId = settingsManager.getString("instanceId", "")

        val applications: Map<String, Long> = uptime.packageUsages.mapValues { it.value / 1000 }
        val request = ReportRequest(
            instanceId = instanceId,
            screenTime = uptime.screenTimeMillis / 1000,
            applications = applications
        )

        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.report(request)
                val state = ActualDeviceState.from(response)
                Logger.i("FamilyRulesClient", createUptimeLogMessage(uptime, state))
                state
            } catch (e: Exception) {
                Log.e("FamilyRulesClient", "Failed to send report request: ${e.message}", e)
                ActualDeviceState.ACTIVE
            }
        }
    }


    private fun createUptimeLogMessage(uptime: Uptime, response: ActualDeviceState): String {
        val topApps = uptime.packageUsages.entries.sortedByDescending { it.value }.take(3)
        return "Uptime reported [" +
                "screen time: ${uptime.screenTimeMillis.millisToHMS()}; " +
                "top 3 apps: " + topApps.joinToString(", ") { "${it.key} (${it.value.millisToHMS()})" } +
                "], received device state: ${response.state}"
    }

    suspend fun getBlockedApps(): List<String> {
        Log.d("FamilyRulesClient", "Fetching blocked apps for device")

        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.getBlockedApps()
                val appPackages = response.apps.map { it.appPath }
                Logger.i(
                    "FamilyRulesClient",
                    "Fetched ${appPackages.size} blocked apps for device: $appPackages"
                )
                appPackages
            } catch (e: Exception) {
                Log.e("FamilyRulesClient", "Failed to fetch blocked apps: ${e.message}", e)
                emptyList()
            }
        }
    }

    suspend fun getGroupsUsageReport(): AppGroupsUsageReportResponse? {
        Log.d("FamilyRulesClient", "Fetching groups usage report")

        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.getGroupsUsageReport()
                Log.d(
                    "FamilyRulesClient",
                    "Groups usage report returned ${response.appGroups.size} groups"
                )
                response
            } catch (e: Exception) {
                Log.e("FamilyRulesClient", "Failed to fetch groups usage report: ${e.message}", e)
                null
            }
        }
    }

    suspend fun ensureAllAppsAreCached(packageNames: Set<String>) {
        Logger.i("FamilyRulesClient", "Ensuring all apps are cached")
        packageNames.forEach { packageName ->
            appDb.getAppNameAndIcon(packageName)
        }
    }
}

enum class DeviceState {
    ACTIVE,
    BLOCK_RESTRICTED_APPS
}

data class ActualDeviceState(
    val state: DeviceState,
    val extra: String?
) {
    companion object {
        val ACTIVE = ActualDeviceState(DeviceState.ACTIVE, null)
        fun from(responseDto: ReportResponseDto) = when (responseDto.deviceState) {
            "ACTIVE" -> ACTIVE
            "BLOCK_RESTRICTED_APPS" -> ActualDeviceState(
                DeviceState.BLOCK_RESTRICTED_APPS,
                responseDto.extra
            )
            else -> throw IllegalArgumentException("Unknown device state: ${responseDto.deviceState}")
        }
    }
}